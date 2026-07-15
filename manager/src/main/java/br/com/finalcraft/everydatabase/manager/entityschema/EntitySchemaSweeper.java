package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.cache.DirtyAccessor;
import br.com.finalcraft.everydatabase.manager.log.ManagerLog;
import br.com.finalcraft.everydatabase.manager.sync.KeyParsers;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * at a time"), and any freeze/kill-switch policy. {@link SweepOptions#abortCheck()} is polled at
 * every batch boundary so the caller can cut a sweep short.
 */
public final class EntitySchemaSweeper {

    private EntitySchemaSweeper() {
    }

    /**
     * The framework-owned meta collection holding one {@link EntitySchemaSweepMarker} per data
     * collection. Lives in the reserved underscore namespace, like {@code _schema_migrations}.
     */
    public static final String MARKER_COLLECTION = "_entity_schema_sweeps";

    /** The descriptor of the marker meta-collection; safe to install on any backend. */
    public static final EntityDescriptor<String, EntitySchemaSweepMarker> MARKER_DESCRIPTOR =
            EntityDescriptor.builder(String.class, EntitySchemaSweepMarker.class)
                    .collection(MARKER_COLLECTION)
                    .reserved()
                    .keyExtractor(EntitySchemaSweepMarker::getCollection)
                    .codec(new JacksonJsonCodec<>(EntitySchemaSweepMarker.class))
                    .build(); // @OptimisticLock auto-wired from the lockVersion field

    /** Sweeps scanning right now in THIS process - the substrate of {@link #isSweeping}. */
    private static final Set<ActiveSweep> ACTIVE_SWEEPS = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    //  Core sweep
    // ------------------------------------------------------------------

    /**
     * Sweeps the manager's collection to its eager target version, parsing scanned keys with the
     * built-in {@link KeyParsers} for the manager's key type. Convenience over the explicit-parser
     * overload for the common key contract (UUID, String, Long, Integer); throws on unusual key
     * types (use the other overload for those).
     */
    public static <K, V extends EntitySchema> SweepReport sweep(CachingManager<K, V> manager,
                                                                SweepOptions options) {
        return sweep(manager, options, KeyParsers.forType(manager.keyType()));
    }

    /**
     * Sweeps the manager's collection to its eager target version. Idempotent and
     * crash-restartable: re-running it after a crash resumes rather than redoes, and a sweep that
     * finds nothing pending costs one marker read.
     *
     * <p>Everything the sweep touches is derived from {@code manager} - the repository it scans and
     * rewrites, the storage its marker lives on, and the cache it invalidates for every row it
     * rewrote behind the manager's back.
     *
     * @param keyParser recovers the entity key from a scanned row's string key; supply an explicit
     *                  one for a composite/record key that {@link KeyParsers} cannot build
     * @throws IllegalArgumentException if the entity type has no dirty tracking: the sweep detects
     *                                  an upcast row through its dirty flag, so without one it would
     *                                  rewrite nothing and still mark the collection complete
     */
    public static <K, V extends EntitySchema> SweepReport sweep(CachingManager<K, V> manager,
                                                                SweepOptions options,
                                                                Function<String, K> keyParser) {
        Class<V> type = manager.type();
        String collection = manager.collection();
        Repository<K, V> repository = manager.repository();
        Storage storage = manager.storage();
        ManagerLog log = options.logger();

        DirtyAccessor dirtyAccessor = DirtyAccessor.forType(type);
        if (dirtyAccessor == null) {
            throw new IllegalArgumentException(type.getName() + " has no dirty tracking (implement"
                    + " IDirtyable or annotate a boolean field with @DirtyFlag) - an eager sweep of it"
                    + " could not tell a migrated row from an untouched one, so it would advance the"
                    + " completion marker without rewriting anything.");
        }

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
                && !options.runnerId().equals(marker.getRunnerId())) {
            return SweepReport.of(collection, "contended (live lease held by another instance)");
        }
        marker.setInProgressVersion(target);
        marker.setRunnerId(options.runnerId());
        marker.setLeaseExpiresAtEpochMs(now + options.leaseMillis());
        try {
            markerRepo.save(marker).join();
        } catch (Throwable claimFailure) {
            // OptimisticLockException (enforcing) or a first-insert duplicate-key: another instance won.
            log.log(Level.INFO, "[EntitySchemaSweep] " + collection + ": could not claim the sweep lease"
                    + " (another instance is sweeping, or the marker write failed) - skipping this boot.");
            return SweepReport.of(collection, "lease not claimed");
        }

        ActiveSweep active = new ActiveSweep(storage, collection);
        ACTIVE_SWEEPS.add(active);
        try {
            return scan(manager, options, keyParser, dirtyAccessor, markerRepo, marker, target);
        } finally {
            ACTIVE_SWEEPS.remove(active);
        }
    }

    /** The scan/rewrite loop proper - entered only with the lease claimed. */
    private static <K, V extends EntitySchema> SweepReport scan(
            CachingManager<K, V> manager,
            SweepOptions options,
            Function<String, K> keyParser,
            DirtyAccessor dirtyAccessor,
            Repository<String, EntitySchemaSweepMarker> markerRepo,
            EntitySchemaSweepMarker marker,
            int target) {
        Class<V> type = manager.type();
        String collection = manager.collection();
        Repository<K, V> repository = manager.repository();
        ManagerLog log = options.logger();
        long leaseMillis = options.leaseMillis();

        long scanned = 0, rewritten = 0, failed = 0, conflicted = 0, skippedDirty = 0, skippedAhead = 0;
        long lastProgressLog = System.currentTimeMillis();
        boolean exhausted = false;
        Cursor cursor = Cursor.scan();
        while (true) {
            if (options.abortCheck().getAsBoolean()) {
                log.log(Level.INFO, "[EntitySchemaSweep] " + collection
                        + ": aborted mid-sweep (frozen/shutdown) - will resume next boot.");
                releaseLease(markerRepo, marker);
                return SweepReport.of(collection, "aborted");
            }
            Slice<ScanRow<V>> slice = repository.scanAll(cursor, options.batchSize()).join();
            List<V> toWrite = new ArrayList<>();
            List<K> toWriteKeys = new ArrayList<>(); // index-aligned with toWrite; null when unparseable
            for (ScanRow<V> row : slice.content()) {
                scanned++;
                if (row.isFailed()) {
                    failed++;
                    log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                            + ": row '" + row.key() + "' could not be migrated/decoded: "
                            + describe(row.error()));
                    continue;
                }
                V entity = row.value();
                if (!dirtyAccessor.isDirty(entity)) {
                    if (EntitySchemaMigrations.isAhead(entity)) {
                        skippedAhead++; // written by a newer schema - leave it (an ahead-write guard elsewhere protects it)
                    }
                    continue;
                }
                K key = parseKey(keyParser, row.key());
                if (key != null && isResidentDirty(manager, dirtyAccessor, key)) {
                    skippedDirty++; // the resident dirty copy is already upcast; its flush persists it
                    continue;
                }
                toWrite.add(entity);
                toWriteKeys.add(key);
            }
            List<K> rewrittenKeys = new ArrayList<>();
            if (!toWrite.isEmpty()) {
                try {
                    repository.saveAll(toWrite, WriteMode.UPDATE_ONLY).join();
                    rewritten += toWrite.size();
                    addParsed(rewrittenKeys, toWriteKeys);
                } catch (Throwable batchFailure) {
                    // fall back per-entity so one conflict does not lose the whole batch (versioned SQL
                    // rolls the batch back)
                    for (int i = 0; i < toWrite.size(); i++) {
                        V entity = toWrite.get(i);
                        try {
                            repository.saveAll(Collections.singletonList(entity), WriteMode.UPDATE_ONLY).join();
                            rewritten++;
                            K key = toWriteKeys.get(i);
                            if (key != null) rewrittenKeys.add(key);
                        } catch (Throwable single) {
                            if (isOptimisticLock(single)) {
                                conflicted++; // a live write won; lazy is the backstop for this row
                            } else {
                                failed++;
                                log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                                        + ": failed to rewrite a row: " + describe(single));
                            }
                        }
                    }
                }
            }
            // the rows were rewritten behind the manager's back, so a clean cached cell of one of
            // them now holds a stale lock version - its next write would conflict for no reason.
            // Marking stale keeps any dirty cell (whose flush is what carries the migrated shape).
            if (!rewrittenKeys.isEmpty()) {
                manager.invalidateAll(rewrittenKeys);
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

            if (System.currentTimeMillis() - lastProgressLog >= options.progressLogMillis()) {
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
        boolean complete = exhausted && failed == 0;
        if (complete) {
            marker.setCompletedVersion(target);
            marker.setInProgressVersion(0);
            marker.setLastSweepEpochMs(System.currentTimeMillis());
            try {
                markerRepo.save(marker).join();
            } catch (Throwable completionFailure) {
                // the data IS swept; only the O(1) skip hint failed to land, so the next boot
                // re-scans and finds nothing to do
                log.log(Level.WARNING, "[EntitySchemaSweep] " + collection
                        + ": swept to v" + target + " but the completion marker write failed ("
                        + describe(completionFailure) + ") - the next boot re-scans.");
                return SweepReport.of(collection, type, target, scanned, rewritten, conflicted,
                        skippedDirty, skippedAhead, failed, false, "completion write failed");
            }
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
                skippedAhead, failed, complete);
    }

    /**
     * Hands the lease back on the abort path, so a quick restart can resume immediately instead of
     * seeing a live lease held by a runner that is already gone. Best-effort: if this write does not
     * land, the lease simply expires on its own.
     */
    private static void releaseLease(Repository<String, EntitySchemaSweepMarker> markerRepo,
                                     EntitySchemaSweepMarker marker) {
        marker.setLeaseExpiresAtEpochMs(0); // inProgressVersion stays: it records what was attempted
        try {
            markerRepo.save(marker).join();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    //  Active-sweep registry
    // ------------------------------------------------------------------

    /**
     * Whether {@code collection} on {@code storage} is being swept by this process right now -
     * the hook for excluding a sweep and a bulk job (a runtime collection transfer, a bulk import)
     * from running over the same rows at once.
     *
     * <p>Scoped to the storage INSTANCE, not just the collection name: two storages may hold
     * same-named collections, and only one of them is being swept. Says nothing about a sweep in
     * another process - that is what the marker's lease is for.
     */
    public static boolean isSweeping(Storage storage, String collection) {
        return ACTIVE_SWEEPS.contains(new ActiveSweep(storage, collection));
    }

    /** One in-flight sweep: the storage instance (by identity) plus the collection name. */
    private static final class ActiveSweep {

        private final Storage storage;
        private final String collection;

        private ActiveSweep(Storage storage, String collection) {
            this.storage = storage;
            this.collection = collection;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ActiveSweep)) return false;
            ActiveSweep that = (ActiveSweep) other;
            return this.storage == that.storage && this.collection.equals(that.collection);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(storage) * 31 + collection.hashCode();
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static <K> K parseKey(Function<String, K> keyParser, String keyStr) {
        try {
            return keyParser.apply(keyStr);
        } catch (Exception notParseable) {
            return null;
        }
    }

    private static <K> void addParsed(List<K> target, List<K> keys) {
        for (K key : keys) {
            if (key != null) target.add(key);
        }
    }

    private static <K, V> boolean isResidentDirty(CachingManager<K, V> manager,
                                                  DirtyAccessor dirtyAccessor, K key) {
        Optional<V> cell = manager.peek(key);
        return cell.isPresent() && dirtyAccessor.isDirty(cell.get());
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static boolean isOptimisticLock(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) return true;
        }
        return false;
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
            return of(collection, type, targetVersion, scanned, rewritten, conflicted, skippedDirty,
                    skippedAhead, failed, advanced, advanced ? "complete" : "incomplete");
        }

        public static SweepReport of(String collection, Class<?> type, int targetVersion, long scanned, long rewritten,
                                     long conflicted, long skippedDirty, long skippedAhead, long failed,
                                     boolean advanced, String note) {
            return new SweepReport(collection, type, targetVersion, scanned, rewritten, conflicted,
                    skippedDirty, skippedAhead, failed, advanced, note);
        }

        public String collection()      { return collection; }
        public Class<?> type()          { return type; }
        public int targetVersion()      { return targetVersion; }
        public long scanned()           { return scanned; }
        public long rewritten()         { return rewritten; }

        /**
         * Rows a concurrent write beat the sweep to - harmless, since that write persisted the
         * migrated shape anyway and a lazy read heals whatever it did not.
         *
         * <p>Slightly over-counts on a backend whose failed bulk write partially landed: the
         * per-entity retry of an already-written row loses on the lock version and is counted here.
         * These counters are telemetry; the rows on disk are correct either way.
         */
        public long conflicted()        { return conflicted; }
        public long skippedDirty()      { return skippedDirty; }
        public long skippedAhead()      { return skippedAhead; }
        public long failed()            { return failed; }
        public boolean markerAdvanced() { return markerAdvanced; }
        public String note()            { return note; }
    }
}
