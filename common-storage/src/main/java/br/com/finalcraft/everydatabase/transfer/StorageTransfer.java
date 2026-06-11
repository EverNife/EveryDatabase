package br.com.finalcraft.everydatabase.transfer;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Copies entities from one {@link Storage} backend to another.
 *
 * <p>Designed for <b>maintenance-mode transfers</b>: the server is running but writes
 * are frozen (whitelist / maintenance window) and the plugins have flushed their
 * in-memory caches. The source storage is <em>never modified</em> - the transfer only
 * reads. Cutover (pointing plugins at the new backend) happens on restart.
 *
 * <h3>Quick example</h3>
 * <pre>{@code
 * TransferReport report = StorageTransfer.builder()
 *     .from(oldLocalFileStorage)
 *     .to(newSqlStorage)
 *     .descriptor(PLAYER_DATA_DESCRIPTOR)
 *     .descriptor(ECONOMY_DESCRIPTOR)
 *     .applyTargetMigrations(true)
 *     .failIfTargetCollectionNotEmpty(true)
 *     .verifyCounts(true)
 *     .errorPolicy(ErrorPolicy.FAIL_FAST)
 *     .progressListener(p -> log.info("{}: {}/{}", p.collection(), p.done(), p.total()))
 *     .build()
 *     .execute()
 *     .join();
 *
 * if (report.success()) {
 *     log.info("Done: {} entities in {}ms", report.totalEntities(), report.durationMs());
 * } else {
 *     report.errors().forEach(e -> log.error("[{}] {}", e.collection(), e.cause().getMessage()));
 * }
 * }</pre>
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li><b>Source is intact.</b> The transfer copies, never moves. Deleting the source is
 *       an explicit, separate action by the operator.</li>
 *   <li><b>Idempotent.</b> Running twice produces the same target state because all backends
 *       use upsert semantics. Combine with {@link Builder#failIfTargetCollectionNotEmpty(boolean)}
 *       to control whether re-runs are allowed.</li>
 *   <li><b>Granular.</b> Only the registered descriptors are transferred. Other collections
 *       in both source and target are left untouched.</li>
 * </ul>
 *
 * @see Builder
 * @see TransferReport
 * @see ErrorPolicy
 */
public interface StorageTransfer {

    /**
     * Executes the transfer asynchronously.
     *
     * <p>The returned future completes with a {@link TransferReport} describing what happened.
     * The future itself does <em>not</em> complete exceptionally - errors are captured inside
     * {@link TransferReport#errors()} and {@link TransferReport#success()} is {@code false}.
     * An exception from the future itself indicates an unexpected JVM-level failure.
     */
    CompletableFuture<TransferReport> execute();

    /** Returns a new {@link Builder}. */
    static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    /**
     * Fluent builder for {@link StorageTransfer}.
     *
     * <p>Mandatory fields: {@link #from(Storage)}, {@link #to(Storage)}, and at least one
     * {@link #descriptor(EntityDescriptor)} call. All other settings have defaults.
     */
    final class Builder {

        private Storage source;
        private Storage target;

        @SuppressWarnings("rawtypes")
        private final List<DescriptorPair> descriptors = new ArrayList<>();

        private int batchSize = 500;
        private ErrorPolicy errorPolicy = ErrorPolicy.FAIL_FAST;
        private boolean applyTargetMigrations = true;
        private boolean failIfTargetCollectionNotEmpty = true;
        private boolean verifyCounts = true;
        private Consumer<TransferProgress> progressListener = null;

        private Builder() {}

        /**
         * Sets the source storage to read from.
         */
        public Builder from(Storage source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the target storage to write to.
         */
        public Builder to(Storage target) {
            this.target = target;
            return this;
        }

        /**
         * Registers a descriptor to transfer, using the <em>same</em> descriptor for both
         * source and target (same collection name and codec on both sides).
         *
         * <p>This is the common case. For collection renames or codec changes, use
         * {@link #descriptor(EntityDescriptor, EntityDescriptor)}.
         */
        public <K, V> Builder descriptor(EntityDescriptor<K, V> descriptor) {
            return descriptor(descriptor, descriptor);
        }

        /**
         * Registers a descriptor pair where the source and target may have different collection
         * names or codecs. Both descriptors must share the same {@code <K, V>} types so entities
         * decoded from the source can be written to the target without conversion.
         *
         * <p>Use this for:
         * <ul>
         *   <li>Renaming a collection during the transfer (different {@code collection()} values)</li>
         *   <li>Changing the codec between backends (e.g. YAML on LocalFile, JSON on SQL)</li>
         * </ul>
         */
        public <K, V> Builder descriptor(EntityDescriptor<K, V> sourceDescriptor,
                                         EntityDescriptor<K, V> targetDescriptor) {
            descriptors.add(new DescriptorPair<>(sourceDescriptor, targetDescriptor));
            return this;
        }

        /**
         * Number of entities passed to {@code targetRepo.saveAll()} per batch.
         * Higher values reduce round-trips but consume more memory.
         * Default: {@code 500}.
         */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the error handling policy for write failures.
         * Default: {@link ErrorPolicy#FAIL_FAST}.
         */
        public Builder errorPolicy(ErrorPolicy errorPolicy) {
            this.errorPolicy = errorPolicy;
            return this;
        }

        /**
         * If {@code true} and the target implements
         * {@link SchemaAwareStorage},
         * {@code target.migrate()} is called during the pre-flight phase.
         *
         * <p>For SQL targets ({@code SqlStorage} / subclasses) this also ensures all registered
         * migrations run before any entity tables are touched.
         *
         * <p>Default: {@code true}.
         */
        public Builder applyTargetMigrations(boolean apply) {
            this.applyTargetMigrations = apply;
            return this;
        }

        /**
         * If {@code true}, the transfer checks {@code targetRepo.count()} before writing
         * and aborts that collection if it already has data (count &gt; 0).
         *
         * <p>Set to {@code false} only when you intentionally want to merge into an existing
         * target (combine with {@link ErrorPolicy#SKIP_EXISTING} to avoid overwriting).
         *
         * <p>Default: {@code true}.
         */
        public Builder failIfTargetCollectionNotEmpty(boolean fail) {
            this.failIfTargetCollectionNotEmpty = fail;
            return this;
        }

        /**
         * If {@code true}, after all batches for a collection complete, the transfer compares
         * {@code entitiesWritten} vs {@code sourceCount} and marks the report as failed if they
         * diverge.
         *
         * <p>The check is relaxed to {@code entitiesWritten <= sourceCount} when
         * {@link ErrorPolicy#SKIP_EXISTING} is active.
         *
         * <p>Default: {@code true}.
         */
        public Builder verifyCounts(boolean verify) {
            this.verifyCounts = verify;
            return this;
        }

        /**
         * Callback invoked after each batch write, for all registered collections.
         * Receives a {@link TransferProgress} snapshot with monotonically increasing
         * {@code done} values.
         *
         * <p>Default: {@code null} (no callback).
         */
        public Builder progressListener(Consumer<TransferProgress> listener) {
            this.progressListener = listener;
            return this;
        }

        /**
         * Validates the configuration and builds the {@link StorageTransfer}.
         *
         * @throws IllegalStateException if {@code from}, {@code to}, or at least one descriptor
         *                               have not been set
         */
        @SuppressWarnings("unchecked")
        public StorageTransfer build() {
            if (source == null)
                throw new IllegalStateException("StorageTransfer: source storage must be set via from(...)");
            if (target == null)
                throw new IllegalStateException("StorageTransfer: target storage must be set via to(...)");
            if (descriptors.isEmpty())
                throw new IllegalStateException(
                    "StorageTransfer: at least one descriptor must be registered via descriptor(...)");

            return new StorageTransferImpl(
                source,
                target,
                Collections.unmodifiableList(new ArrayList<>(descriptors)),
                batchSize,
                errorPolicy,
                applyTargetMigrations,
                failIfTargetCollectionNotEmpty,
                verifyCounts,
                progressListener
            );
        }

        // ------------------------------------------------------------------
        //  Package-private accessors for StorageTransferImpl
        // ------------------------------------------------------------------

        Storage source()                         { return source; }
        Storage target()                         { return target; }
        @SuppressWarnings("rawtypes")
        List<DescriptorPair> descriptors()       { return descriptors; }
        int batchSize()                          { return batchSize; }
        ErrorPolicy errorPolicy()                { return errorPolicy; }
        boolean applyTargetMigrations()          { return applyTargetMigrations; }
        boolean failIfTargetCollectionNotEmpty() { return failIfTargetCollectionNotEmpty; }
        boolean verifyCounts()                   { return verifyCounts; }
        Consumer<TransferProgress> progressListener() { return progressListener; }
    }
}
