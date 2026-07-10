package br.com.finalcraft.everydatabase.manager.cache;

import java.util.*;
import java.util.function.Predicate;

/**
 * Thread-safe entry store for a {@code CachingManager}.
 *
 * <p>When {@code maxSize > 0} it is a bounded LRU (access-order {@link LinkedHashMap}; entries
 * past the bound are evicted least-recently-used first); when {@code 0} it is unbounded.
 *
 * <p>A single lock guards every operation. Access-order {@code LinkedHashMap.get} structurally
 * reorders, so reads must hold the same lock as writes - the cost is negligible at cache sizes,
 * and it keeps the store correct without a third-party dependency. Swap in Caffeine here if a
 * deployment needs lock-striped concurrency.
 *
 * <p><b>Eviction veto (dirty pinning).</b> A write-back cell holding unsaved local changes must
 * never be LRU-evicted: nobody else references it, so evicting it silently loses the write before
 * the next {@code flushDirty()}. The optional {@code evictionVeto} predicate pins such entries -
 * a vetoed entry is skipped and the next-eldest evictable one goes instead. While every overflow
 * candidate is vetoed the bound is deliberately SOFT: the map exceeds {@code maxSize} and only
 * trims on the next insertion of a new key <em>after</em> a flush has cleared the dirty flags - not
 * during the flush itself, which evicts nothing. If insertions stop while cells stay dirty the map
 * stays over the bound, so under sustained dirty pressure this bound is not a hard memory ceiling.
 *
 * <p>Note: in bounded mode <em>any</em> consultation via {@link #get} counts as an LRU access and
 * promotes the key to most-recently-used, even when the caller then judges the entry stale and
 * serves nothing - so a hot read loop over non-fresh keys can pin never-served entries.
 * Acceptable for the small hot sets this layer targets.
 *
 * <p>The compound operations ({@link #installIfAbsent}, {@link #installColdMiss}, {@link #tombstone},
 * {@link #markStale}) exist so the manager keeps the identity map stable under concurrency:
 * publishing a freshly loaded value - or a delete tombstone - is one atomic, stamp-ordered step,
 * not a racy get-then-put. The class is non-final and its members are {@code protected} so a
 * deployment can subclass it (e.g. to add metrics or swap the backing map).
 *
 * @param <K> the key type
 * @param <V> the cached value type
 */
public class LruCacheStore<K, V> {

    protected final Object lock = new Object();
    protected final Map<K, CacheEntry<V>> map;
    protected final int bound;
    /** Entries this predicate accepts are pinned (never LRU-evicted); {@code null} = no veto. */
    protected final Predicate<CacheEntry<V>> evictionVeto;

    public LruCacheStore(int maxSize) {
        this(maxSize, null);
    }

    public LruCacheStore(int maxSize, Predicate<CacheEntry<V>> evictionVeto) {
        this.bound = Math.max(0, maxSize);
        this.evictionVeto = evictionVeto;
        // access-order in bounded mode (LRU iteration order); plain map when unbounded
        this.map = bound > 0 ? new LinkedHashMap<>(16, 0.75f, true) : new HashMap<>();
    }

    /**
     * Evicts least-recently-used entries until the map is back inside the bound, skipping the ones
     * the {@link #evictionVeto} pins. Called under the lock after every insertion. A no-op when
     * unbounded or inside the bound.
     */
    protected void enforceBound() {
        if (bound <= 0 || map.size() <= bound) {
            return;
        }
        Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
        while (map.size() > bound && it.hasNext()) {
            CacheEntry<V> eldest = it.next().getValue();
            if (evictionVeto != null && evictionVeto.test(eldest)) {
                continue;   // pinned (e.g. dirty write-back cell) - try the next-eldest
            }
            eldest.markEvicted();   // tell any holder to re-resolve
            it.remove();
        }
    }

    public CacheEntry<V> get(K key) {
        synchronized (lock) {
            return map.get(key);
        }
    }

    public void put(K key, CacheEntry<V> entry) {
        synchronized (lock) {
            map.put(key, entry);
            enforceBound();
        }
    }

    public void remove(K key) {
        synchronized (lock) {
            CacheEntry<V> removed = map.remove(key);
            if (removed != null) {
                removed.markEvicted();
            }
        }
    }

    /**
     * Atomically installs {@code candidate} only if no mapping exists, and returns the entry now
     * held (the existing one if present, else {@code candidate}). Lets concurrent cold misses
     * converge on a single canonical instance.
     */
    public CacheEntry<V> installIfAbsent(K key, CacheEntry<V> candidate) {
        synchronized (lock) {
            CacheEntry<V> existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            map.put(key, candidate);
            enforceBound();
            return candidate;
        }
    }

    /**
     * Cold-miss publish: install a fresh live cell when absent (so concurrent cold misses converge
     * on the first instance), but never resurrect a tombstone whose delete is newer than this read.
     * An older tombstone (a delete issued before this read started) is resurrected with {@code value}.
     *
     * @return the cell now held - a live cell, or the tombstone when a newer delete wins (the caller
     *         treats a returned tombstone as "absent")
     */
    public CacheEntry<V> installColdMiss(K key, V value, long stamp) {
        synchronized (lock) {
            CacheEntry<V> cell = map.get(key);
            if (cell == null) {
                CacheEntry<V> fresh = new CacheEntry<>(value, stamp);
                map.put(key, fresh);
                enforceBound();
                return fresh;
            }
            if (!cell.isDeleted()) {
                return cell;                 // live -> keep the first instance (convergence)
            }
            if (stamp > cell.stamp()) {
                cell.publish(value, stamp);  // tombstone older than this read -> resurrect
            }
            return cell;
        }
    }

    /**
     * Atomically turns the key's cell into a tombstone (deleted), creating one if absent. The
     * monotonic {@code stamp} guard means a slower delete never overrides a newer write, and the
     * tombstone blocks a slower in-flight reload from re-installing the just-deleted entity.
     */
    public void tombstone(K key, long stamp) {
        synchronized (lock) {
            CacheEntry<V> cell = map.get(key);
            if (cell == null) {
                cell = new CacheEntry<>(null, stamp);
                cell.tombstone(stamp);
                map.put(key, cell);
                enforceBound();
            } else {
                cell.tombstone(stamp);
            }
        }
    }

    /** Atomically marks the current entry (if any) stale, under the lock (no detached-entry race). */
    public void markStale(K key) {
        synchronized (lock) {
            CacheEntry<V> entry = map.get(key);
            if (entry != null) {
                entry.markStale();
            }
        }
    }

    /** Removes every entry matching {@code shouldEvict}; returns how many were removed. */
    public int purge(Predicate<CacheEntry<V>> shouldEvict) {
        synchronized (lock) {
            int removed = 0;
            Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                CacheEntry<V> entry = it.next().getValue();
                if (shouldEvict.test(entry)) {
                    entry.markEvicted();
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (CacheEntry<V> entry : map.values()) {
                entry.markEvicted();
            }
            map.clear();
        }
    }

    public int size() {
        synchronized (lock) {
            return map.size();
        }
    }

    /** Number of live (non-tombstone) entries currently cached. */
    public int liveCount() {
        synchronized (lock) {
            int n = 0;
            for (CacheEntry<V> entry : map.values()) {
                if (!entry.isDeleted()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Snapshot of the current entries (for bulk invalidation). */
    public List<CacheEntry<V>> valuesSnapshot() {
        synchronized (lock) {
            return new ArrayList<>(map.values());
        }
    }

    /** Snapshot of the keys of live (non-tombstone) entries - used by the version poller. */
    public Set<K> liveKeysSnapshot() {
        synchronized (lock) {
            Set<K> keys = new LinkedHashSet<>();
            for (Map.Entry<K, CacheEntry<V>> e : map.entrySet()) {
                if (!e.getValue().isDeleted()) keys.add(e.getKey());
            }
            return keys;
        }
    }
}
