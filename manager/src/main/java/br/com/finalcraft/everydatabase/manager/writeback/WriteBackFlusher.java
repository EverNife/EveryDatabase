package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.BatchSaveReport;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchema;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchemaMigrations;
import br.com.finalcraft.everydatabase.manager.log.ManagerLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The generic write-back persist + conflict-resolution engine over a {@link CachingManager}: it
 * persists an already-collected dirty set in ONE batch, reacts to the per-key
 * {@link BatchSaveReport} (transient error -&gt; re-mark dirty; optimistic-lock conflict -&gt; adopt
 * the stored winner under the live instance's lock; success -&gt; {@code onPersisted}), and always
 * re-installs the SAME live instance as the canonical cached cell so references held by other code
 * stay flushable.
 *
 * <p>This is the piece every mutate-in-memory/flush-later consumer would otherwise re-implement, and
 * the subtle part is the last step: {@code saveAllAndCache} evicts a conflicted cell, so without the
 * re-install a held reference would keep accumulating changes into an instance the cache no longer
 * knows about - the changes would then be silently lost. What differs per entity type (its key, its
 * lock, how it takes on a winner) is supplied by {@link ConflictHooks}.
 *
 * <p><b>Input contract.</b> {@code entities} must already be collected AND mark-cleaned by the
 * caller before the call: the collection pass owns the entity's lock and any veto (a frozen or
 * read-only type), and clearing the flag before persisting is what makes a concurrent change re-mark
 * the entity and be picked up by the next flush instead of being lost. The flusher re-marks dirty on
 * failure only.
 *
 * <p>Instantiate one per flush pipeline; it owns the health counters. It does not serialize anything
 * itself - the caller is expected to run at most one flush at a time per manager (two flushers over
 * one manager would race on the same dirty set).
 */
public class WriteBackFlusher {

    private final ManagerLog log;

    /** Writes that failed since the caller last drained the count for its aggregate summary. */
    private final AtomicInteger writeFailures = new AtomicInteger();
    /** Optimistic-lock conflicts resolved by adopting the stored winner, since this flusher was built. */
    private final AtomicInteger conflictsAdopted = new AtomicInteger();
    /** Epoch millis of the last failed write; 0 = none. */
    private volatile long lastWriteFailureAt = 0L;
    /** Once-per-class guard for the "written by a newer schema" refusal warning. */
    private final Set<String> aheadSchemaWarned = ConcurrentHashMap.newKeySet();

    /** @param log where the flush reports conflicts and failures; {@code null} means {@link ManagerLog#SILENT}. */
    public WriteBackFlusher(ManagerLog log) {
        this.log = log != null ? log : ManagerLog.SILENT;
    }

    // ------------------------------------------------------------------
    //  Batch persist + per-key report reaction
    // ------------------------------------------------------------------

    /**
     * Persists {@code entities} (already collected + mark-cleaned) through {@code manager} in one
     * batch and reacts to the per-key report:
     * <ul>
     *   <li><b>error</b> (transient): re-mark the entity dirty so the next flush retries it;</li>
     *   <li><b>conflict</b>: adopt the stored winner into the live instance;</li>
     *   <li><b>persisted</b>: {@code onPersisted} runs (e.g. a "row exists in the backend" flag).</li>
     * </ul>
     *
     * <p>Under {@link FlushMode#FORCED} the returned future completes exceptionally on ANY failure:
     * {@link StorageWriteException} for a write that did not land (a concurrent conflict attached as
     * suppressed), {@link OptimisticConflictException} for a lost race. {@link FlushMode#BACKGROUND}
     * only logs (and retries on the next flush).
     *
     * @param what        human-readable id of the entity kind, used in logs and exceptions
     * @param onPersisted called for each successfully persisted entity; may be {@code null}
     */
    public <K, V extends IDirtyable> CompletableFuture<Void> persistBatch(CachingManager<K, V> manager,
                                                                          List<V> entities, FlushMode mode,
                                                                          String what,
                                                                          ConflictHooks<K, ? super V> hooks,
                                                                          Consumer<? super V> onPersisted) {
        final boolean forced = mode == FlushMode.FORCED;
        Map<K, V> byKey = new HashMap<>();
        for (V entity : entities) {
            byKey.put(hooks.storageKey(entity), entity);
        }
        return manager.saveAllAndCache(entities).handle((report, saveError) -> {
            if (saveError != null) {
                //defensive: the manager contract is report-never-throw - if it ever does, the dirty
                //flags were already cleared, so re-mark them or the mutations are silently lost
                for (V entity : entities) {
                    entity.markDirty();
                }
                recordWriteFailures(what, byKey.keySet());
                if (forced) throw new StorageWriteException(what, new ArrayList<>(byKey.keySet()));
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (!report.hasFailures()) {
                if (onPersisted != null) {
                    for (V entity : entities) onPersisted.accept(entity);
                }
                return CompletableFuture.<Void>completedFuture(null);
            }

            List<K> errored = report.erroredKeys();
            List<K> conflicted = report.conflictedKeys();
            Set<K> failedKeys = new HashSet<>(errored);
            failedKeys.addAll(conflicted);
            if (onPersisted != null) {
                for (V entity : entities) {
                    if (!failedKeys.contains(hooks.storageKey(entity))) onPersisted.accept(entity);
                }
            }

            for (K erroredKey : errored) {
                V live = byKey.get(erroredKey);
                if (live != null) live.markDirty(); //transient failure: retry on the next flush
                recordWriteFailure(what, erroredKey);
            }

            List<CompletableFuture<Void>> conflicts = new ArrayList<>();
            for (K conflictedKey : conflicted) {
                V live = byKey.get(conflictedKey);
                if (live == null) continue;
                conflicts.add(resolveConflict(manager, live, mode, what, conflictedKey, hooks));
            }
            CompletableFuture<Void> all = conflicts.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(conflicts.toArray(new CompletableFuture[0]));
            if (!forced) return all;
            return all.thenRun(() -> {
                //a caller-initiated save must never report success for a write that did not land;
                //when both kinds happened, the write failure is primary and the race is attached
                if (!errored.isEmpty()) {
                    StorageWriteException failure = new StorageWriteException(what, errored);
                    if (!conflicted.isEmpty()) {
                        failure.addSuppressed(new OptimisticConflictException(what, conflicted));
                    }
                    throw failure;
                }
                if (!conflicted.isEmpty()) {
                    throw new OptimisticConflictException(what, conflicted);
                }
            });
        }).thenCompose(future -> future);
    }

    // ------------------------------------------------------------------
    //  Adopt the winner
    // ------------------------------------------------------------------

    /**
     * Conflict resolution, decided under the live instance's lock:
     * <ul>
     *   <li>live still clean -&gt; copy the winner's state into it (held references stay valid) and
     *       run {@link ConflictHooks#afterAdopt}, since the winner may come from an older instance;</li>
     *   <li>live re-dirtied while resolving -&gt; KEEP the local values and adopt only the winner's
     *       lock version, so the next flush wins cleanly instead of conflicting forever;</li>
     *   <li>winner row vanished (deleted between the failed save and the re-read) -&gt; reset the lock
     *       and re-mark dirty so the next flush re-creates the row;</li>
     *   <li>the re-read itself failed -&gt; re-mark dirty and let the next flush retry.</li>
     * </ul>
     * Every branch re-installs the SAME held instance as the canonical cached cell.
     */
    private <K, V extends IDirtyable> CompletableFuture<Void> resolveConflict(CachingManager<K, V> manager,
                                                                              V live, FlushMode mode, String what,
                                                                              K key, ConflictHooks<K, ? super V> hooks) {
        conflictsAdopted.incrementAndGet();
        return manager.repository().find(key).handle((stored, findError) -> {
            ReentrantLock lock = hooks.lock(live);
            lock.lock();
            try {
                if (findError != null) {
                    live.markDirty();
                    logConflict(mode, "%s of [%s]: conflict detected but re-reading the winner FAILED"
                            + " (%s) - the local state stays dirty and retries on the next flush.",
                            what, key, String.valueOf(findError.getMessage()));
                } else if (!stored.isPresent()) {
                    hooks.resetLockForRecreate(live);
                    live.markDirty();
                    logConflict(mode, "%s of [%s]: the winning row vanished during conflict resolution"
                            + " (deleted by another instance?) - the local state will re-create it.", what, key);
                } else if (hooks.mergesOnConflict()) {
                    hooks.adoptStoredState(live, stored.get()); //combines both sides + re-marks dirty
                    logConflict(mode, "%s of [%s]: another instance saved a newer version - both states"
                            + " were MERGED into the live instance and will persist on the next flush.", what, key);
                } else if (live.isDirty()) {
                    hooks.adoptStoredLockVersion(live, stored.get());
                    logConflict(mode, "%s of [%s]: another instance saved a newer version, but the local"
                            + " instance was modified again meanwhile - keeping the LOCAL values; they"
                            + " overwrite the remote ones on the next flush.", what, key);
                } else {
                    hooks.adoptStoredState(live, stored.get());
                    hooks.afterAdopt(live); //the winner may carry an older on-disk payload
                    logConflict(mode, "%s of [%s]: another instance saved a newer version - the stored"
                            + " winner was re-adopted into the live instance (ADOPT_WINNER).", what, key);
                }
                reinstallCanonical(manager, key, live, what);
            } finally {
                lock.unlock();
            }
            return null;
        });
    }

    /**
     * Re-installs {@code live} as the canonical cached cell after a conflict evicted it. seedIfAbsent
     * is keep-first, so a concurrent resolve may have cold-loaded a foreign copy into the window - it
     * is evicted (loudly, if it already carries unsaved changes) and the seed retried, keeping every
     * held reference flushable. Gives up loudly instead of orphaning the held instance.
     */
    private <K, V extends IDirtyable> void reinstallCanonical(CachingManager<K, V> manager, K key,
                                                              V live, String what) {
        for (int attempt = 0; attempt < 3; attempt++) {
            V canonical = manager.seedIfAbsent(key, live);
            if (canonical == live) return;
            if (canonical.isDirty()) {
                warn("%s of [%s]: discarding a concurrently loaded duplicate with unsaved changes"
                        + " while re-installing the canonical instance.", what, key);
            }
            manager.evict(key); //a racer resolved a fresh copy mid-conflict; the held one must win
        }
        log.log(Level.SEVERE, String.format("%s of [%s]: could not re-install the live instance as the"
                + " canonical cache entry after a conflict - references held to it may no longer be"
                + " flushed!", what, key));
    }

    // ------------------------------------------------------------------
    //  Guards + counters
    // ------------------------------------------------------------------

    /**
     * Refuses to persist an entity written by a NEWER payload schema version: its decode already
     * dropped the newer fields (the codec ignores unknown properties), so flushing it would
     * permanently erase them while keeping the newer version stamp. The caller is expected to skip
     * the entity, which stays dirty and cached - this process is effectively read-only for that row
     * until it is updated. Warns once per type.
     *
     * <p>Entities that do not implement {@link EntitySchema} are never refused, so a consumer that
     * does not use payload versioning can ignore this guard entirely.
     *
     * @return whether the entity must NOT be flushed
     */
    public boolean refuseAheadWrite(Object entity, String what, Object key) {
        if (!(entity instanceof EntitySchema)) return false;
        EntitySchema schema = (EntitySchema) entity;
        if (!EntitySchemaMigrations.isAhead(schema)) return false;
        if (aheadSchemaWarned.add(entity.getClass().getName())) {
            warn("%s of [%s] was written by a NEWER schema version (v%s > code v%s) - REFUSING to"
                            + " save it from this instance, which would strip the newer fields. Update this"
                            + " process; until then these entities are read-only. (Warned once per type.)",
                    what, key, schema.getSchemaVersion(), EntitySchemaMigrations.currentVersion(entity.getClass()));
        }
        return true;
    }

    /** Optimistic-lock conflicts resolved so far (monotonic; for a health/status readout). */
    public int conflictsAdoptedCount() {
        return conflictsAdopted.get();
    }

    /** Epoch millis of the last failed write, or 0 when none failed yet. */
    public long lastWriteFailureAt() {
        return lastWriteFailureAt;
    }

    /** Failed-write count since the last call, resetting it - so a periodic tick logs ONE aggregate line. */
    public int drainWriteFailureCount() {
        return writeFailures.getAndSet(0);
    }

    /** Counts a failed write for the caller's aggregate summary; the per-key detail stays at FINE. */
    private void recordWriteFailure(String what, Object key) {
        writeFailures.incrementAndGet();
        lastWriteFailureAt = System.currentTimeMillis();
        log.log(Level.FINE, String.format("Failed to save %s of [%s] - re-marked dirty for the next"
                + " flush.", what, key));
    }

    private void recordWriteFailures(String what, Set<?> keys) {
        writeFailures.addAndGet(keys.size());
        lastWriteFailureAt = System.currentTimeMillis();
        log.log(Level.FINE, String.format("Failed to save a %s batch of %s entities - re-marked dirty"
                + " for the next flush.", what, keys.size()));
    }

    /** A conflict on the caller path is a warning too, but the returned future also fails (see persistBatch). */
    private void logConflict(FlushMode mode, String message, Object... args) {
        log.log(Level.WARNING, (mode == FlushMode.FORCED ? "[forced] " : "") + String.format(message, args));
    }

    private void warn(String message, Object... args) {
        log.log(Level.WARNING, String.format(message, args));
    }
}
