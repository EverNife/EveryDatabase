package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.manager.cache.*;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A cache-backed façade in front of a single {@link Repository}: it owns an in-memory
 * identity map of its entities (one instance per key) and resolves {@link Ref}s to that type.
 *
 * <p>Construct one per entity type at startup, passing the {@link RefRegistry} it belongs to; it
 * self-registers there (keyed by the descriptor's entity type), so every {@code Ref<?, ThatType>}
 * bound to that registry resolves through it. Prefer {@link RefRegistry#manager} for the common
 * case; subclass it for a domain-named manager ({@code class GuildManager extends
 * CachingManager<UUID, Guild>}) and pass the registry to {@code super(...)}.
 *
 * <h3>Freshness vs capacity</h3>
 * Freshness is a {@link CachePolicy} (default from {@link CacheOptions}, overridable per
 * reference); capacity is the LRU bound from {@link CacheOptions#maxSize()}. The two are
 * separate by design - see {@code CacheOptions}.
 *
 * <h3>Cells &amp; handles</h3>
 * Each key maps to a stable {@link CacheEntry} <b>cell</b>; writes and reloads update that cell
 * <b>in place</b> (swap the value, guarded by a monotonic stamp). A {@link Ref} memoizes the cell,
 * so once resolved its reads are lock-free and it always observes the latest value. On eviction the
 * cell is flagged so a holder re-resolves on next access.
 *
 * <h3>Invalidation</h3>
 * {@link #saveAndCache(Object)} is write-through (the cell is updated in place with the saved value)
 * and auto-evicts on an {@link OptimisticLockException} (a stale cached write means the cache is
 * behind the backend). Cross-process writes are invisible here - bound their staleness with a
 * TTL policy and/or wire an external signal to {@link #invalidate(Object)}/{@link #evict(Object)}.
 *
 * <h3>Write-back</h3>
 * When a cached value is dirty-trackable - it implements {@link IDirtyable} or carries a
 * {@link DirtyFlag @DirtyFlag} field - the manager supports a mutate-in-memory / flush-later flow: a
 * dirty cell is always served and never reloaded over (so unsaved changes are never lost to a
 * reload), {@link #seedIfAbsent(Object, Object)} caches a not-yet-persisted default, and
 * {@link #flushDirty()} / {@link #saveAllAndCache(Collection)} persist the dirty set in a batch.
 * Plain entities are unaffected.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class CachingManager<K, V> implements RefResolver<K, V> {

    protected final Repository<K, V> repository;
    /** The storage this manager was built on. */
    protected final Storage storage;
    protected final Class<V> type;
    /** Key type, captured from the descriptor. */
    protected final Class<K> keyType;
    /** Backing collection name, captured from the descriptor. */
    protected final String collection;
    protected final Function<V, K> keyOf;
    protected final CacheOptions options;
    protected final LruCacheStore<K, V> store;
    /** Monotonic source for publication stamps (orders writes/reloads so none regress a newer one). */
    protected final AtomicLong stampGen = new AtomicLong();
    /** Dirty-tracking accessor for the entity type (write-back), or {@code null} when not trackable. */
    protected final DirtyAccessor dirtyAccessor;
    /** Optional hook fired after a successful local write; used by {@code CacheSync.via(...)} to publish a signal. */
    private volatile LocalWriteListener<K> localWriteListener;

    /** Creates a manager with the given options and registers it in {@code registry}. */
    public CachingManager(EntityDescriptor<K, V> descriptor, Storage storage, CacheOptions options, RefRegistry registry) {
        this.repository = storage.repository(descriptor);
        this.storage    = storage;
        this.type       = descriptor.type();
        this.keyType    = descriptor.keyType();
        this.collection = descriptor.collection();
        this.dirtyAccessor = DirtyAccessor.forType(type);
        this.keyOf      = descriptor.keyExtractor();
        this.options    = options;
        this.store      = new LruCacheStore<>(options.maxSize());
        registry.register(type, this);
    }

    /** Convenience: unbounded cache with the given default policy. */
    public CachingManager(EntityDescriptor<K, V> descriptor, Storage storage, CachePolicy policy, RefRegistry registry) {
        this(descriptor, storage, CacheOptions.of(policy), registry);
    }

    public Repository<K, V> getRepository() {
        return repository;
    }

    // ------------------------------------------------------------------
    //  RefResolver
    // ------------------------------------------------------------------

    @Override
    public CachePolicy defaultPolicy() {
        return options.policy();
    }

    @Override
    public CacheEntry<V> peekCell(K key, CachePolicy policy) {
        CacheEntry<V> cell = store.get(key);
        return serveable(cell, policy) ? cell : null;
    }

    @Override
    public CompletableFuture<CacheEntry<V>> resolveCell(K key, CachePolicy policy) {
        CacheEntry<V> existing = store.get(key);
        if (serveable(existing, policy)) {
            return CompletableFuture.completedFuture(existing);
        }
        if (!policy.cacheable()) {
            // True bypass: load without caching; hand back a throwaway, unshared cell.
            return repository.find(key)
                    .thenApply(opt -> opt.isPresent() ? new CacheEntry<>(opt.get()) : null);
        }
        // A cold miss has no cell yet; anything else (stale, or a tombstone) is a reload.
        final boolean coldMiss = (existing == null);
        final long stamp = stampGen.incrementAndGet();
        return repository.find(key).thenApply(opt -> {
            if (!opt.isPresent()) {
                return null;
            }
            V value = opt.get();
            CacheEntry<V> cell = coldMiss
                    ? store.installColdMiss(key, value, stamp)   // keep-first, but loses to a newer delete
                    : updateInPlace(key, value, stamp);          // stamp-guarded (resurrects an older tombstone)
            return cell.isDeleted() ? null : cell;               // a newer delete won -> treat as absent
        });
    }

    /**
     * A cell is serveable when it is present, live (not a tombstone), not evicted, and either fresh
     * or {@linkplain IDirtyable#isDirty() dirty}: a write-back cell with unsaved local changes is
     * always served and never reloaded over, so the freshness policy can't overwrite them.
     */
    private boolean serveable(CacheEntry<?> cell, CachePolicy policy) {
        if (cell == null || cell.isEvicted() || cell.isDeleted()) {
            return false;
        }
        return isDirty(cell) || policy.isFresh(cell);
    }

    /** Whether the cell holds a dirty-trackable value reporting unsaved local changes. */
    private boolean isDirty(CacheEntry<?> cell) {
        return dirtyAccessor != null && cell.getValue() != null && dirtyAccessor.isDirty(cell.getValue());
    }

    /**
     * Authoritative / stale-reload publish: update the key's cell <b>in place</b> (swap value),
     * guarded by a monotonic stamp so a slower writer never regresses a newer one. Creates the cell
     * if absent. Returns the live cell - the same instance any memoized holder already points at.
     */
    private CacheEntry<V> updateInPlace(K key, V value, long stamp) {
        CacheEntry<V> cell = store.installIfAbsent(key, new CacheEntry<>(value));
        cell.publish(value, stamp);
        return cell;
    }

    // ------------------------------------------------------------------
    //  Bulk
    // ------------------------------------------------------------------

    /**
     * Batched multi-get with partial cache hits: fresh entries are served from memory, only the
     * misses go to the backend in a single {@link Repository#findMany}. The order of the result
     * is not significant. This is the in-loop antidote to N+1 when you have the keys up front.
     */
    public CompletableFuture<List<V>> getAll(Collection<K> keys) {
        CachePolicy policy = options.policy();
        Map<K, V> hits = new LinkedHashMap<>();
        List<K> misses = new ArrayList<>();
        for (K key : keys) {
            CacheEntry<V> entry = store.get(key);
            if (serveable(entry, policy)) {
                hits.put(key, entry.getValue());
            } else {
                misses.add(key);
            }
        }
        if (misses.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(hits.values()));
        }
        final boolean cacheable = policy.cacheable();
        return repository.findMany(misses).thenApply(loaded -> {
            List<V> result = new ArrayList<>(hits.values());
            for (V value : loaded) {
                if (cacheable) {
                    // Update the cell in place (refreshes a stale entry; creates one if absent).
                    result.add(updateInPlace(keyOf.apply(value), value, stampGen.incrementAndGet()).getValue());
                } else {
                    result.add(value);
                }
            }
            return result;
        });
    }

    /**
     * Loads the collection into the cache (the "in-memory mirror" of a small hot set). Entries are
     * installed only where absent, so a concurrently-saved value is never clobbered and held
     * instances stay stable; for a hard refresh, {@link #clearCache()} first.
     *
     * <p>With a bounded {@link CacheOptions#maxSize()}, a collection larger than the bound is
     * truncated by LRU eviction - only {@code maxSize} entries survive. It is a full mirror only
     * when the cache is unbounded or {@code maxSize} covers the collection.
     */
    public CompletableFuture<Void> preloadAll() {
        return repository.all().thenAccept(stream ->
                stream.forEach(value -> store.installIfAbsent(keyOf.apply(value), new CacheEntry<>(value))));
    }

    // ------------------------------------------------------------------
    //  Writes (write-through) + invalidation
    // ------------------------------------------------------------------

    /**
     * Persists {@code value} <b>and</b> updates the cache write-through - the cache-aware
     * counterpart of {@code repository().save(value)} (which persists without touching the cache
     * and would leave a stale entry behind). Prefer this from manager-mediated code.
     *
     * <p>On an {@link OptimisticLockException} the (stale) cached entry is evicted so the next read
     * reloads the current backend state; the original exception still propagates.
     */
    public CompletableFuture<Void> saveAndCache(V value) {
        final K key = keyOf.apply(value);
        final long stamp = stampGen.incrementAndGet();
        return repository.save(value).whenComplete((ignored, ex) -> {
            if (ex == null) {
                if (options.policy().cacheable()) {
                    updateInPlace(key, value, stamp);   // write-through: update the cell in place
                }
                fireLocalWrite(ChangeOp.SAVE, key);
            } else if (isOptimisticLock(ex)) {
                store.remove(key);
            }
        });
    }

    /**
     * Deletes the entity from the backend <b>and</b> evicts it from the cache - the delete
     * counterpart of {@link #saveAndCache}. The eviction always runs (whether or not the key was
     * cached), so a deleted entity is never left dangling in the cache. Prefer this over
     * {@code repository().delete(key)}, which removes from the backend but leaves a stale cache entry.
     *
     * <p>On success the cache slot becomes a stamp-ordered <b>tombstone</b> rather than just being
     * removed: this blocks a concurrent in-flight reload (one that read the entity before the delete)
     * from re-installing the now-deleted entity. The tombstone is cleared by a later re-save (which
     * resurrects it), by {@link #purgeExpired()}, or by LRU eviction. A failed delete just
     * invalidates (the entity may still exist, so the next read reloads it).
     *
     * @return whether the entity existed in the backend
     */
    public CompletableFuture<Boolean> deleteAndEvict(K key) {
        final long stamp = stampGen.incrementAndGet();
        return repository.delete(key).whenComplete((existed, ex) -> {
            if (ex == null) {
                store.tombstone(key, stamp);
                fireLocalWrite(ChangeOp.DELETE, key);
            } else {
                store.remove(key);
            }
        });
    }

    // ------------------------------------------------------------------
    //  Write-back (dirty tracking: mutate in memory, flush in a batch)
    // ------------------------------------------------------------------

    /**
     * Installs {@code value} as the live cached instance for {@code key} <b>without persisting it</b>,
     * but only when no live instance is already cached; returns the instance now resident (the
     * pre-existing one if a concurrent caller won, otherwise {@code value}).
     *
     * <p>The write-back counterpart of a lazy default: a freshly created entity becomes the canonical
     * cached instance (so repeated reads return the same object and accumulate mutations) before it
     * exists in the backend, to be persisted later by {@link #flushDirty()} /
     * {@link #saveAllAndCache(Collection)} / {@link #saveAndCache(Object)}. Unlike {@code saveAndCache}
     * it performs no I/O. An older delete tombstone is resurrected.
     */
    public V seedIfAbsent(K key, V value) {
        return store.installColdMiss(key, value, stampGen.incrementAndGet()).getValue();
    }

    /**
     * Batch write-through: persists every entity via {@link Repository#saveAll} and updates each
     * cached cell in place - the bulk counterpart of {@link #saveAndCache(Object)}. If the batch
     * fails as a unit it is retried entity by entity, so one bad record never loses the rest, and the
     * per-entity failures are collected into the returned {@link BatchSaveReport}. An
     * {@link OptimisticLockException} on an entity evicts its (stale) cell so the next read reloads
     * the backend; any other error leaves the cell untouched. The future completes normally - inspect
     * the report.
     */
    public CompletableFuture<BatchSaveReport<K>> saveAllAndCache(Collection<V> values) {
        List<V> entities = new ArrayList<>(values);
        if (entities.isEmpty()) {
            return CompletableFuture.completedFuture(BatchSaveReport.<K>empty());
        }
        return repository.saveAll(entities).handle((ignored, batchError) -> {
            if (batchError == null) {
                boolean cacheable = options.policy().cacheable();
                for (V value : entities) {
                    K savedKey = keyOf.apply(value);
                    if (cacheable) {
                        updateInPlace(savedKey, value, stampGen.incrementAndGet());
                    }
                    fireLocalWrite(ChangeOp.SAVE, savedKey);
                }
                return CompletableFuture.completedFuture(BatchSaveReport.<K>empty());
            }
            List<CompletableFuture<KeyOutcome<K>>> singles = new ArrayList<>(entities.size());
            for (V value : entities) {
                singles.add(saveOneAndCache(value));
            }
            return CompletableFuture.allOf(singles.toArray(new CompletableFuture[0]))
                    .thenApply(done -> BatchSaveReport.of(joinAll(singles)));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<KeyOutcome<K>> saveOneAndCache(V value) {
        final K key = keyOf.apply(value);
        final long stamp = stampGen.incrementAndGet();
        return repository.save(value).handle((ignored, error) -> {
            if (error == null) {
                if (options.policy().cacheable()) {
                    updateInPlace(key, value, stamp);
                }
                fireLocalWrite(ChangeOp.SAVE, key);
                return new KeyOutcome<>(key, KeyOutcome.Status.SAVED, null);
            }
            if (isOptimisticLock(error)) {
                store.remove(key);   // stale cached write -> drop it so the next read reloads
                return new KeyOutcome<>(key, KeyOutcome.Status.CONFLICT, unwrap(error));
            }
            return new KeyOutcome<>(key, KeyOutcome.Status.ERROR, unwrap(error));
        });
    }

    /**
     * Write-back flush: collects every cached cell whose value is dirty-trackable and reporting dirty,
     * {@linkplain IDirtyable#markClean() clears} its flag, and persists them via
     * {@link #saveAllAndCache(Collection)}. A transient (non-conflict) failure
     * {@linkplain IDirtyable#markDirty() re-marks} the entity dirty so the next flush retries it; a
     * conflicting entity is evicted (re-read on next access). Returns the {@link BatchSaveReport} of
     * the failures - empty when the flush was clean or nothing was dirty.
     *
     * <p>A value re-dirtied by another thread <em>during</em> the flush re-sets its own flag, so it is
     * simply picked up by the next flush: a flush is at-least-once, never lossy.
     */
    public CompletableFuture<BatchSaveReport<K>> flushDirty() {
        List<V> dirty = new ArrayList<>();
        for (CacheEntry<V> cell : store.valuesSnapshot()) {
            V value = cell.getValue();
            if (!cell.isDeleted() && value != null && dirtyAccessor != null && dirtyAccessor.isDirty(value)) {
                dirtyAccessor.markClean(value);   // clear before persisting; a concurrent change re-sets it
                dirty.add(value);
            }
        }
        if (dirty.isEmpty()) {
            return CompletableFuture.completedFuture(BatchSaveReport.<K>empty());
        }
        return saveAllAndCache(dirty).thenApply(report -> {
            for (KeyOutcome<K> failure : report.failures()) {
                if (failure.status() == KeyOutcome.Status.ERROR) {
                    CacheEntry<V> cell = store.get(failure.key());
                    if (cell != null && cell.getValue() != null && dirtyAccessor != null) {
                        dirtyAccessor.markDirty(cell.getValue());   // transient: retry on the next flush
                    }
                }
                // CONFLICT cells were already evicted by saveAllAndCache - dropped on purpose (reload on read)
            }
            return report;
        });
    }

    private static <K> List<KeyOutcome<K>> joinAll(List<CompletableFuture<KeyOutcome<K>>> singles) {
        List<KeyOutcome<K>> outcomes = new ArrayList<>(singles.size());
        for (CompletableFuture<KeyOutcome<K>> single : singles) {
            outcomes.add(single.join());   // each completes normally (saveOneAndCache never rethrows)
        }
        return outcomes;
    }

    /** Unwraps {@link CompletableFuture} wrappers to the underlying cause, for the report. */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Sets a hook fired after each successful local write through this manager - {@code SAVE} on save,
     * {@code DELETE} on delete - so an external cache-sync transport can publish a change signal. Wired
     * by {@code CacheSync.via(...)}; pass {@code null} to clear it. The hook must not throw: a failure is
     * swallowed so it never breaks the write it follows.
     *
     * <p>Only one hook may be installed at a time: setting a non-null hook while one is already present
     * throws, so a manager accidentally wired to two transports fails fast instead of silently
     * publishing to only the last one. Clear it ({@code null}) before re-wiring.
     */
    public void setLocalWriteListener(LocalWriteListener<K> listener) {
        if (listener != null && this.localWriteListener != null) {
            throw new IllegalStateException(
                "this manager already has a local-write listener (it is already wired to a CacheSync "
                + "transport); close that CacheSync before wiring another");
        }
        this.localWriteListener = listener;
    }

    /** Fires the local-write hook if set, isolating any failure (incl. {@link Error}) from the write path. */
    private void fireLocalWrite(ChangeOp op, K key) {
        LocalWriteListener<K> listener = localWriteListener;
        if (listener != null) {
            try {
                listener.onWrite(op, key);
            } catch (Throwable ignored) {
                // a publish failure must never break the write it follows (the write already succeeded;
                // this runs inside whenComplete/handle, which would otherwise flip it to a failed future)
            }
        }
    }

    /** Marks a cached entry stale (atomically, under the store lock): the next read reloads it. */
    public void invalidate(K key) {
        store.markStale(key);
    }

    /** Removes a cached entry outright. */
    public void evict(K key) {
        store.remove(key);
    }

    /** Marks every cached entry stale (e.g. after a bulk external change). */
    public void invalidateAll() {
        for (CacheEntry<V> entry : store.valuesSnapshot()) {
            entry.markStale();
        }
    }

    /** Empties the cache. */
    public void clearCache() {
        store.clear();
    }

    /**
     * Evicts every entry the default policy no longer considers fresh; returns the number removed.
     *
     * <p>TTL governs freshness, not memory: a cached entry is held by a <b>strong</b> reference, so
     * an expired-but-untouched entry is not GC-eligible until it is overwritten or evicted. Bound
     * memory with {@link CacheOptions#maxSize()} (evicted entries become collectable), and/or call
     * this periodically to proactively release stale entries.
     */
    public int purgeExpired() {
        return store.purge(entry -> entry.isDeleted() || !options.policy().isFresh(entry));
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    /** The underlying repository (uncached) - use for queries, deletes, or cache-bypassing reads. */
    public Repository<K, V> repository() {
        return repository;
    }

    public Class<V> type() {
        return type;
    }

    /** The key type this manager caches. Used by {@code CacheSync} to pick a default key parser. */
    public Class<K> keyType() {
        return keyType;
    }

    /**
     * The backing collection name. Used by {@code CacheSync} to route incoming change events to this
     * manager.
     */
    public String collection() {
        return collection;
    }

    /**
     * The storage this manager was built on. Used by {@code CacheSync.auto()} to route each manager to
     * its own backend's change feed (push) or to the polling fallback.
     */
    public Storage storage() {
        return storage;
    }

    /** Current number of <b>live</b> cached entries (tombstones from deletes are not counted). */
    public int cachedSize() {
        return store.liveCount();
    }

    /**
     * A snapshot of the keys of currently cached <b>live</b> entries. Used by
     * {@code PollingCacheSync} to know which keys to version-check on backends without a change feed.
     */
    public Set<K> cachedKeys() {
        return store.liveKeysSnapshot();
    }

    /**
     * A snapshot of the currently cached <b>live</b> values (tombstones excluded) - for bulk
     * iteration over what the cache holds (e.g. scanning or flushing a resident working set).
     */
    public List<V> cachedValues() {
        List<V> values = new ArrayList<>();
        for (CacheEntry<V> entry : store.valuesSnapshot()) {
            if (!entry.isDeleted()) {
                values.add(entry.getValue());
            }
        }
        return values;
    }

    /** Unwraps {@link CompletableFuture}/reflection wrappers to detect an optimistic-lock failure. */
    static boolean isOptimisticLock(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * A hook invoked after a successful local write through this manager, so an external cache-sync
     * transport can publish a change signal to other instances. Wired by {@code CacheSync.via(...)}.
     */
    @FunctionalInterface
    public interface LocalWriteListener<K> {
        void onWrite(ChangeOp op, K key);
    }
}
