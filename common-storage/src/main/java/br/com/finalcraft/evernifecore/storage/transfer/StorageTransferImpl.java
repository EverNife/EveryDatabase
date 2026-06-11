package br.com.finalcraft.evernifecore.storage.transfer;

import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.log.StorageLog;
import br.com.finalcraft.evernifecore.storage.log.StorageLogLevel;
import br.com.finalcraft.evernifecore.storage.log.StorageOp;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Internal implementation of {@link StorageTransfer}.
 *
 * <p>Package-private: callers always interact through the {@link StorageTransfer} interface
 * obtained from {@link StorageTransfer#builder()}.
 *
 * <h3>Execution flow</h3>
 * <ol>
 *   <li>Pre-flight health checks on source and target.</li>
 *   <li>Apply pending target migrations if {@code applyTargetMigrations=true} and
 *       target is {@link SchemaAwareStorage}.</li>
 *   <li>For each registered {@link DescriptorPair}: read all from source, write to target
 *       in batches of {@code batchSize}, fire progress events.</li>
 *   <li>Count verification across all collections.</li>
 * </ol>
 *
 * <p>The returned {@link CompletableFuture} <em>never</em> completes exceptionally for expected
 * failures (health DOWN, non-empty target, write errors, count mismatch). Those are captured in
 * {@link TransferReport#errors()} and {@link TransferReport#success()} is {@code false}.
 * Only truly unexpected JVM-level failures (OOM, etc.) propagate as exceptional completions.
 */
final class StorageTransferImpl implements StorageTransfer {

    final Storage source;
    final Storage target;

    @SuppressWarnings("rawtypes")
    final List<DescriptorPair> descriptors;

    final int batchSize;
    final ErrorPolicy errorPolicy;
    final boolean applyTargetMigrations;
    final boolean failIfTargetCollectionNotEmpty;
    final boolean verifyCounts;
    final Consumer<TransferProgress> progressListener;

    /**
     * Mirrors transfer milestones to the log sink (TRANSFER topic, spec 8.7) without touching
     * {@link TransferReport} or the {@code progressListener}. Bound to the <b>target</b>
     * storage's live log config - the target is where the writes land, so its operator is the
     * one who needs the visibility. Enable with e.g.
     * {@code target.getStorageLogConfig().level(StorageLogTopic.TRANSFER, StorageLogLevel.INFO)}.
     */
    private final StorageLog log;

    @SuppressWarnings("rawtypes")
    StorageTransferImpl(
            Storage source,
            Storage target,
            List<DescriptorPair> descriptors,
            int batchSize,
            ErrorPolicy errorPolicy,
            boolean applyTargetMigrations,
            boolean failIfTargetCollectionNotEmpty,
            boolean verifyCounts,
            Consumer<TransferProgress> progressListener) {
        this.source                         = source;
        this.target                         = target;
        this.descriptors                    = descriptors;
        this.batchSize                      = batchSize;
        this.errorPolicy                    = errorPolicy;
        this.applyTargetMigrations          = applyTargetMigrations;
        this.failIfTargetCollectionNotEmpty = failIfTargetCollectionNotEmpty;
        this.verifyCounts                   = verifyCounts;
        this.progressListener               = progressListener;
        this.log                            = new StorageLog("transfer", target::getStorageLogConfig);
    }

    // ------------------------------------------------------------------
    //  execute()
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<TransferReport> execute() {
        return CompletableFuture.supplyAsync(() -> {
            long transferStartMs = System.currentTimeMillis();
            TransferReport.Builder report = TransferReport.builder(transferStartMs);

            log.emit(StorageOp.TRANSFER_BEGIN, StorageLogLevel.INFO, b -> b
                .detail("source=" + source.getClass().getSimpleName()
                    + " target=" + target.getClass().getSimpleName()
                    + " collections=" + descriptors.size()
                    + " policy=" + errorPolicy));

            try {
                // 1. Pre-flight: health check both storages
                if (!preFlight(report)) {
                    return report.build();
                }

                // 2. Apply pending migrations on target (if SchemaAware)
                if (applyTargetMigrations && target instanceof SchemaAwareStorage) {
                    try {
                        ((SchemaAwareStorage) target).migrate().join();
                    } catch (Exception e) {
                        report.addError(new TransferError("(pre-flight/migrations)", null, e));
                        return report.build();
                    }
                }

                // 3. Transfer each collection pair in registration order
                for (DescriptorPair pair : descriptors) {
                    boolean abort = transferCollectionRaw(pair, report);
                    if (abort) break;
                }

                // 4. Verify counts across all collections
                if (verifyCounts) {
                    verifyAllCounts(report);
                }

            } catch (Exception e) {
                // Truly unexpected failure - still captured in the report
                report.addError(new TransferError("(unexpected)", null, e));
            }

            TransferReport built = report.build();
            log.emit(StorageOp.TRANSFER_COMPLETE,
                built.success() ? StorageLogLevel.INFO : StorageLogLevel.WARN, b -> b
                    .affected(built.totalEntities())
                    .durationMs(System.currentTimeMillis() - transferStartMs)
                    .detail("success=" + built.success()
                        + " collections=" + built.collections().size()
                        + " errors=" + built.errors().size()));
            return built;
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  Pre-flight
    // ------------------------------------------------------------------

    private boolean preFlight(TransferReport.Builder report) {
        HealthStatus srcHealth;
        try {
            srcHealth = source.health().join();
        } catch (Exception e) {
            report.addError(new TransferError("(pre-flight)", null,
                new RuntimeException("Source storage health check threw an exception", e)));
            return false;
        }
        if (!srcHealth.isConnected()) {
            report.addError(new TransferError("(pre-flight)", null,
                new RuntimeException("Source storage is DOWN: " + srcHealth.details())));
            return false;
        }

        HealthStatus tgtHealth;
        try {
            tgtHealth = target.health().join();
        } catch (Exception e) {
            report.addError(new TransferError("(pre-flight)", null,
                new RuntimeException("Target storage health check threw an exception", e)));
            return false;
        }
        if (!tgtHealth.isConnected()) {
            report.addError(new TransferError("(pre-flight)", null,
                new RuntimeException("Target storage is DOWN: " + tgtHealth.details())));
            return false;
        }

        return true;
    }

    // ------------------------------------------------------------------
    //  Collection transfer (raw -> typed bridge)
    // ------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean transferCollectionRaw(DescriptorPair pair, TransferReport.Builder report) {
        return transferCollection(pair, report);
    }

    private <K, V> boolean transferCollection(DescriptorPair<K, V> pair,
                                              TransferReport.Builder report) {
        String srcCollection = pair.source.collection();
        String tgtCollection = pair.target.collection();

        Repository<K, V> sourceRepo = source.repository(pair.source);
        Repository<K, V> targetRepo = target.repository(pair.target);

        // Snapshot counts before touching anything
        long sourceCount       = sourceRepo.count().join();
        long targetCountBefore = targetRepo.count().join();
        long startMs           = System.currentTimeMillis();
        long entitiesWritten   = 0L;

        // Safety: refuse to overwrite non-empty target collection
        if (failIfTargetCollectionNotEmpty && targetCountBefore > 0) {
            String msg = "Target collection '" + tgtCollection + "' already has "
                + targetCountBefore + " entities. Refusing to overwrite."
                + " Use failIfTargetCollectionNotEmpty(false) to allow.";
            report.addError(new TransferError(srcCollection, null, new RuntimeException(msg)));

            // Record zero-write stats for this collection
            report.addCollectionStats(new CollectionStats(
                srcCollection, tgtCollection,
                sourceCount, targetCountBefore, targetCountBefore,
                0L, System.currentTimeMillis() - startMs
            ));

            // FAIL_FAST: abort the whole transfer; CONTINUE: skip this collection but keep going
            return errorPolicy == ErrorPolicy.FAIL_FAST;
        }

        // Read all entities from source into memory (materialised, not streamed, so batching is simple)
        List<V> all = sourceRepo.all().join().collect(Collectors.toList());

        boolean aborted = false;

        for (int offset = 0; offset < all.size() && !aborted; offset += batchSize) {
            List<V> chunk = all.subList(offset, Math.min(offset + batchSize, all.size()));

            try {
                switch (errorPolicy) {
                    case SKIP_EXISTING:
                        // Write entity-by-entity; skip keys already present in target
                        for (V entity : chunk) {
                            K key = pair.source.keyExtractor().apply(entity);
                            if (!targetRepo.exists(key).join()) {
                                targetRepo.save(entity).join();
                                entitiesWritten++;
                            }
                        }
                        break;

                    default:
                        // FAIL_FAST and CONTINUE: batch upsert
                        targetRepo.saveAll(chunk).join();
                        entitiesWritten += chunk.size();
                        break;
                }
            } catch (Exception e) {
                report.addError(new TransferError(srcCollection, null, e));
                if (errorPolicy == ErrorPolicy.FAIL_FAST) {
                    aborted = true;
                }
                // CONTINUE: error collected, next batch proceeds
            }

            // Notify progress after each batch (even if some entities were skipped)
            if (!aborted) {
                long elapsed = System.currentTimeMillis() - startMs;
                if (progressListener != null) {
                    progressListener.accept(
                        new TransferProgress(srcCollection, entitiesWritten, sourceCount, elapsed));
                }
                // Mirror the same milestone to the log sink (TRANSFER topic, DEBUG).
                long writtenSoFar = entitiesWritten;
                log.emit(StorageOp.TRANSFER_PROGRESS, StorageLogLevel.DEBUG, b -> b
                    .collection(srcCollection)
                    .affected(writtenSoFar).total(sourceCount)
                    .percent(sourceCount == 0 ? 100 : (int) (writtenSoFar * 100L / sourceCount))
                    .durationMs(elapsed));
            }
        }

        long targetCountAfter = targetRepo.count().join();
        long durationMs = System.currentTimeMillis() - startMs;

        report.addCollectionStats(new CollectionStats(
            srcCollection, tgtCollection,
            sourceCount, targetCountBefore, targetCountAfter,
            entitiesWritten, durationMs
        ));

        long written = entitiesWritten;
        log.emit(StorageOp.TRANSFER_COLLECTION, StorageLogLevel.INFO, b -> b
            .collection(srcCollection)
            .affected(written).total(sourceCount)
            .durationMs(durationMs)
            .detail("target=" + tgtCollection));

        return aborted;
    }

    // ------------------------------------------------------------------
    //  Count verification
    // ------------------------------------------------------------------

    private void verifyAllCounts(TransferReport.Builder report) {
        for (CollectionStats stats : report.collections.values()) {
            long written  = stats.entitiesWritten();
            long expected = stats.sourceCount();

            // SKIP_EXISTING: relaxed check - skipped entities reduce written count intentionally
            // All other policies: strict equality
            boolean ok = (errorPolicy == ErrorPolicy.SKIP_EXISTING)
                ? written <= expected
                : written == expected;

            if (!ok) {
                String msg = "Count mismatch for collection '" + stats.sourceCollection()
                    + "': expected=" + expected + " entities written, actual=" + written;
                report.addError(new TransferError(stats.sourceCollection(), null,
                    new RuntimeException(msg)));
            }
        }
    }

    // ------------------------------------------------------------------
    //  toString
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return "StorageTransfer{"
            + "source=" + source.getClass().getSimpleName()
            + ", target=" + target.getClass().getSimpleName()
            + ", descriptors=" + descriptors.size()
            + ", batchSize=" + batchSize
            + ", errorPolicy=" + errorPolicy
            + ", applyTargetMigrations=" + applyTargetMigrations
            + ", failIfTargetCollectionNotEmpty=" + failIfTargetCollectionNotEmpty
            + ", verifyCounts=" + verifyCounts
            + "}";
    }
}
