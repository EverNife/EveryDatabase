package br.com.finalcraft.everydatabase.manager.cache;

import java.util.*;
import java.util.function.Predicate;

/**
 * Thread-safe entry store for a {@code CachingManager}.
 *
 * <p>When {@code maxSize > 0} it is a bounded LRU (access-order {@link LinkedHashMap} that
 * evicts the least-recently-used entry past the bound); when {@code 0} it is unbounded.
 *
 * <p>A single lock guards every operation. Access-order {@code LinkedHashMap.get} structurally
 * reorders, so reads must hold the same lock as writes - the cost is negligible at cache sizes,
 * and it keeps the store correct without a third-party dependency. Swap in Caffeine here if a
 * deployment needs lock-striped concurrency.
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

    public LruCacheStore(int maxSize) {
        if (maxSize > 0) {
            final int bound = maxSize;
            this.map = new LinkedHashMap<K, CacheEntry<V>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                    if (size() > bound) {
                        eldest.getValue().markEvicted();   // tell any holder to re-resolve
                        return true;
                    }
                    return false;
                }
            };
        } else {
            this.map = new HashMap<>();
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
