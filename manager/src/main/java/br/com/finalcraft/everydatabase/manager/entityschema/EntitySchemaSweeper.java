package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.manager.sync.KeyParsers;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Runs {@link EntitySchemaMigrationMode#EAGER EAGER} entity-schema migrations: when a bound entity
 * type has a pending eager step, this sweeps the whole collection and rewrites every stale row
 * instead of waiting for a lazy read. It is not a second migration engine - migration still happens
 * inside the codec on decode (via {@link EntitySchemaMigratingCodec}); the sweep is a BULK READ
 * job that pulls every row through the codec (which migrates and marks the entity dirty), then
 * writes the dirtied set back with {@link WriteMode#UPDATE_ONLY}.
 *
 * <p>Idempotent and crash-restartable (per-row version gating), single-runner (a
 * {@link EntitySchemaSweepMarker} row on the data's own backend holds a CAS lease on enforcing
 * backends), and it advances the completion marker only when the whole collection was scanned
 * without a failed row. The marker is only ever an O(1) skip hint - the lazy decode-hook stays
 * authoritative, so stragglers (a row written by an old instance after the sweep) still heal on
 * their next read.
 *
 * <p>Pure utility - the caller owns the executor, the scheduling ("run after boot", "one collection
 * at a time"), and any freeze/kill-switch policy. The {@code abortCheck} lambda is polled at every
 * batch boundary so the caller can cut a sweep short.
 */
public final class EntitySchemaSweeper {

    private EntitySchemaSweeper() {
    }

    /**
     * The framework-owned meta collection holding one {@link EntitySchemaSweepMarker} per data
     * collection. Lives in the reserved underscore namespace, like {@code _schema_migrations}.
     */
    public static final String MARKER_COLLECTION = "_entity_schema_sweeps";

    /** Default lease-renewal window - one heartbeat per batch, expires after this if not renewed. */
    public static final long DEFAULT_LEASE_MILLIS = 60_000L;

    /** Default throttle for the "still going" progress line. */
    public static final long DEFAULT_PROGRESS_LOG_MILLIS = 5_000L;

    /** The descriptor of the marker meta-collection; safe to install on any backend. */
    public static final EntityDescriptor<String, EntitySchemaSweepMarker> MARKER_DESCRIPTOR =
            EntityDescriptor.builder(String.class, EntitySchemaSweepMarker.class)
                    .collection(MARKER_COLLECTION)
                    .reserved()
                    .keyExtractor(EntitySchemaSweepMarker::getCollection)
                    .codec(new JacksonJsonCodec<>(EntitySchemaSweepMarker.class))
                    .build(); // @OptimisticLock auto-wired from the lockVersion field

    // ------------------------------------------------------------------
    //  Core sweep
    // ------------------------------------------------------------------

    /**
     * Sweeps ONE collection to its eager target version, using the built-in {@link KeyParsers} for
     * the descriptor's key type. Convenience over the fully-parameterized overload for the common
     * key contract (UUID, String, Long, Integer); throws on unusual key types (bind the explicit
     * overload for those).
     */
    public static <K, V extends EntitySchema> SweepReport sweep(
            EntityDescriptor<K, V> descriptor,
            Repository<K, V> repository,
            CachingManager<K, V> manager,
            Storage storage,
            String runnerId,
            int batchSize,
            BooleanSupplier abortCheck,
            Logger logger) {
        Function<String, K> keyParser = KeyParsers.forType(descriptor.keyType());
        return sweep(descriptor, repository, manager, storage, runnerId, batchSize, abortCheck,
                logger, keyParser, DEFAULT_LEASE_MILLIS, DEFAULT_PROGRESS_LOG_MILLIS);
    }

    /**
     * Sweeps ONE collection to its eager target version. Fully parameterized: the caller supplies
     * a {@code keyParser} (for the resident-dirty peek on custom key types) and can tune lease /
     * progress-log intervals. Idempotent and crash-restartable.
     */
    public static <K, V extends EntitySchema> SweepReport sweep(
            EntityDescriptor<K, V> descriptor,
            Repository<K, V> repository,
            CachingManager<K, V> manager,
            Storage storage,
            String runnerId,
            int batchSize,
            BooleanSupplier abortCheck,
            Logger logger,
            Function<String, K> keyParser,
            long leaseMillis,
            long progressLogMillis) {
        Class<V> type = descriptor.type();
        String collection = descriptor.collection();
        Logger log = logger == null ? Logger.SILENT : logger;

        int target = EntitySchemaMigrations.eagerTargetVersion(type);
        if (target <= EntitySchema.INITIAL_SCHEMA_VERSION) {
            return SweepReport.of(collection, "no eager step");
        }
        Repository<String, EntitySchemaSweepMarker> markerRepo = storage.repository(MARKER_DESCRIPTOR);

        EntitySchemaSweepMarker marker = markerRepo.find(collection).join().orElse(null);
        if (marker != null && marker.getCompletedVersion() >= target) {
            return SweepReport.of(collection, "already at v" + marker.getCompletedVersion()); // O(1) boot
        }

        long now = System.currentTimeMillis();
        if (marker == null) {
            marker = new EntitySchemaSweepMarker();
            marker.setCollection(collection);
            marker.setTypeName(type.getName());
            marker.setCompletedVersion(EntitySchema.INITIAL_SCHEMA_VERSION);
        } else if (marker.getInProgressVersion() > 0
                && marker.getLeaseExpiresAtEpochMs() > now
                && !runnerId.equals(marker.getRunnerId())) {
            return SweepReport.of(collection, "contended (live lease held by another instance)");
        }
        marker.setInProgressVersion(target);
        marker.setRunnerId(runnerId);
        marker.setLeaseExpiresAtEpochMs(now + leaseMillis);
        try {
            markerRepo.save(marker).join();
        } catch (Throwable claimFailure) {
            // OptimisticLockException (enforcing) or a first-insert duplicate-key: another instance won.
            log.log(Level.INFO, "[EntitySchemaSweep] " + collection + ": could not claim the sweep lease"
                    + " (another instance is sweeping, or the marker write failed) - skipping this boot.");
            return SweepReport.of(collection, "lease not claimed");
        }

        long scanned = 0, rewritten = 0, failed = 0, conflicted = 0, skippedDirty = 0, skippedAhead = 0;
        long lastProgressLog = System.currentTimeMillis();
        boolean exhausted = false;
        int effectiveBatch = Math.max(1, batchSize);
        Cursor cursor = Cursor.scan();
        while (true) {
            if (abortCheck != null && abortCheck.getAsBoolean()) {
                log.log(Level.INFO, "[EntitySchemaSweep] " + collection
                        + ": aborted mid-sweep (frozen/shutdown) - will resume next boot.");
                return SweepReport.of(collection, "aborted");
            }
            Slice<ScanRow<V>> slice = repository.scanAll(cursor, effectiveBatch).join();
            List<V> toWrite = new ArrayList<>();
            for (ScanRow<V> row : slice.content()) {
                scanned++;
                if (row.isFailed()) {
                    failed++;
                    log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                            + ": row '" + row.key() + "' could not be migrated/decoded: "
                            + String.valueOf(row.error()));
                    continue;
                }
                V entity = row.value();
                boolean dirtied = entity instanceof IDirtyable && ((IDirtyable) entity).isDirty();
                if (dirtied) {
                    if (cachedDirty(manager, keyParser, row.key())) {
                        skippedDirty++; // the resident dirty copy is already upcast; its flush persists it
                    } else {
                        toWrite.add(entity);
                    }
                } else if (EntitySchemaMigrations.isAhead(entity)) {
                    skippedAhead++; // written by a newer schema - leave it (an ahead-write guard elsewhere protects it)
                }
            }
            if (!toWrite.isEmpty()) {
                try {
                    repository.saveAll(toWrite, WriteMode.UPDATE_ONLY).join();
                    rewritten += toWrite.size();
                } catch (Throwable batchFailure) {
                    // fall back per-entity so one conflict does not lose the whole batch (versioned SQL
                    // rolls the batch back)
                    for (V entity : toWrite) {
                        try {
                            repository.saveAll(Collections.singletonList(entity), WriteMode.UPDATE_ONLY).join();
                            rewritten++;
                        } catch (Throwable single) {
                            if (isOptimisticLock(single)) {
                                conflicted++; // a live write won; lazy is the backstop for this row
                            } else {
                                failed++;
                                log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                                        + ": failed to rewrite a row: "
                                        + String.valueOf(single.getMessage()));
                            }
                        }
                    }
                }
            }

            // heartbeat: renew the lease and persist running telemetry once per batch
            marker.setScanned(scanned);
            marker.setRewritten(rewritten);
            marker.setFailed(failed);
            marker.setLeaseExpiresAtEpochMs(System.currentTimeMillis() + leaseMillis);
            try {
                markerRepo.save(marker).join();
            } catch (Throwable renewFailure) {
                log.log(Level.INFO, "[EntitySchemaSweep] " + collection
                        + ": lost the sweep lease mid-run - aborting (resumes next boot).");
                return SweepReport.of(collection, "lease lost");
            }

            if (System.currentTimeMillis() - lastProgressLog >= progressLogMillis) {
                log.log(Level.INFO, "[EntitySchemaSweep] " + collection
                        + ": " + scanned + " scanned, " + rewritten + " rewritten, "
                        + conflicted + " conflicts, " + failed + " failed...");
                lastProgressLog = System.currentTimeMillis();
            }

            if (!slice.hasNext()) { exhausted = true; break; }
            Optional<Cursor> next = slice.nextCursor();
            if (!next.isPresent()) { exhausted = true; break; }
            cursor = next.get();
        }

        // completion: only when the whole collection scanned cleanly (never gate on a live count(),
        // which a concurrent insert/delete makes both too strict and too weak)
        if (exhausted && failed == 0) {
            marker.setCompletedVersion(target);
            marker.setInProgressVersion(0);
            marker.setLastSweepEpochMs(System.currentTimeMillis());
            markerRepo.save(marker).join();
            log.log(Level.INFO, "[EntitySchemaSweep] " + collection
                    + ": swept to v" + target + " (scanned=" + scanned
                    + " rewritten=" + rewritten + " conflicts=" + conflicted
                    + " skipped-dirty=" + skippedDirty + " ahead=" + skippedAhead + ").");
        } else {
            log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                    + ": NOT marking complete (exhausted=" + exhausted + ", failed=" + failed
                    + ") - will retry on the next boot.");
        }
        return SweepReport.of(collection, type, target, scanned, rewritten, conflicted, skippedDirty,
                skippedAhead, failed, exhausted && failed == 0);
    }

    private static <K, V> boolean cachedDirty(CachingManager<K, V> manager,
                                              Function<String, K> keyParser, String keyStr) {
        try {
            K key = keyParser.apply(keyStr);
            Optional<V> cell = manager.peek(key);
            return cell.isPresent() && cell.get() instanceof IDirtyable && ((IDirtyable) cell.get()).isDirty();
        } catch (Exception notParseableOrNoCell) {
            return false;
        }
    }

    private static boolean isOptimisticLock(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Logger seam
    // ------------------------------------------------------------------

    /**
     * The sweep's log seam. Sweep progress and per-collection notices are emitted through this;
     * the caller decides whether to route them to SLF4J, {@code java.util.logging}, a plugin's own
     * logger, or nowhere. The default ({@link #SILENT}) drops everything - which matches
     * EveryDatabase's silent-by-default posture.
     */
    @FunctionalInterface
    public interface Logger {
        void log(Level level, String message);

        /** Drop-everything sink; the sweep's default when no logger is supplied. */
        Logger SILENT = (level, message) -> { };
    }

    // ------------------------------------------------------------------
    //  Report
    // ------------------------------------------------------------------

    /** The outcome of one collection's sweep - for logging, admin tooling and tests. */
    public static final class SweepReport {
        private final String collection;
        private final Class<?> type;
        private final int targetVersion;
        private final long scanned, rewritten, conflicted, skippedDirty, skippedAhead, failed;
        private final boolean markerAdvanced;
        private final String note;

        private SweepReport(String collection, Class<?> type, int targetVersion, long scanned, long rewritten,
                            long conflicted, long skippedDirty, long skippedAhead, long failed,
                            boolean markerAdvanced, String note) {
            this.collection = collection;
            this.type = type;
            this.targetVersion = targetVersion;
            this.scanned = scanned;
            this.rewritten = rewritten;
            this.conflicted = conflicted;
            this.skippedDirty = skippedDirty;
            this.skippedAhead = skippedAhead;
            this.failed = failed;
            this.markerAdvanced = markerAdvanced;
            this.note = note;
        }

        public static SweepReport of(String collection, String note) {
            return new SweepReport(collection, null, 0, 0, 0, 0, 0, 0, 0, false, note);
        }

        public static SweepReport of(String collection, Class<?> type, int targetVersion, long scanned, long rewritten,
                                     long conflicted, long skippedDirty, long skippedAhead, long failed, boolean advanced) {
            return new SweepReport(collection, type, targetVersion, scanned, rewritten, conflicted,
                    skippedDirty, skippedAhead, failed, advanced, advanced ? "complete" : "incomplete");
        }

        public String collection()      { return collection; }
        public Class<?> type()          { return type; }
        public int targetVersion()      { return targetVersion; }
        public long scanned()           { return scanned; }
        public long rewritten()         { return rewritten; }
        public long conflicted()        { return conflicted; }
        public long skippedDirty()      { return skippedDirty; }
        public long skippedAhead()      { return skippedAhead; }
        public long failed()            { return failed; }
        public boolean markerAdvanced() { return markerAdvanced; }
        public String note()            { return note; }
    }
}
