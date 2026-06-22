package br.com.finalcraft.everydatabase.manager.cache;

import java.time.Instant;

/**
 * A mutable cache <b>cell</b>: a stable per-key slot whose value is swapped in place on each
 * write/reload. Holders (a memoized {@code Ref}) keep a reference to the cell, so they always
 * observe the latest value without re-consulting the store.
 *
 * <p>Reads ({@link #getValue()}, {@link #getLoadedAt()}, {@link #isStale()}, {@link #isEvicted()},
 * {@link #isDeleted()}) are lock-free volatile reads. Publication ({@link #publish}) and deletion
 * ({@link #tombstone}) are synchronized and guarded by a monotonic stamp, so a slower writer never
 * regresses a newer one (a stale reload never overwrites a newer save, and never resurrects a newer
 * delete). Value swaps are atomic reference assignments, so a reader never sees a torn object.
 *
 * <p>A cell can be <b>live</b> (a value), a <b>tombstone</b> ({@link #isDeleted()} after a delete),
 * or <b>evicted</b> ({@link #isEvicted()} once removed from the store). Only a live, fresh,
 * non-evicted cell is served.
 *
 * @param <S> the cached value type
 */
public class CacheEntry<S> {

    protected volatile S value;
    protected volatile Instant loadedAt;
    protected volatile boolean stale = false;
    protected volatile boolean evicted = false;
    protected volatile boolean deleted = false;
    protected volatile long stamp = 0L;

    public CacheEntry(S value) {
        this(value, Instant.now());
    }

    public CacheEntry(S value, Instant loadedAt) {
        this.value = value;
        this.loadedAt = loadedAt;
    }

    /** Internal: a cell carrying its publication stamp from the start. */
    public CacheEntry(S value, long stamp) {
        this.value = value;
        this.loadedAt = Instant.now();
        this.stamp = stamp;
    }

    public S getValue() {
        return value;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    /** Manual invalidation: the next read that consults a policy reloads from the backend. */
    public void markStale() {
        this.stale = true;
    }

    public boolean isStale() {
        return stale;
    }

    // ------------------------------------------------------------------
    //  Cell mechanics (internal - used by the cache layer / manager)
    // ------------------------------------------------------------------

    /** Internal: the monotonic publication stamp of the current value. */
    public long stamp() {
        return stamp;
    }

    /** Internal: marks this cell detached from the store, so a holder re-resolves on next access. */
    public void markEvicted() {
        this.evicted = true;
    }

    /** Internal: whether this cell has been evicted from the store. */
    public boolean isEvicted() {
        return evicted;
    }

    /** Internal: whether this cell is a tombstone (the entity was deleted). Never served. */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Internal: update-in-place (swap value), guarded by a monotonic stamp so a slower writer never
     * regresses a newer one. Refreshes {@code loadedAt}, clears the stale flag, and resurrects a
     * tombstone on success.
     *
     * @return {@code true} when the update applied; {@code false} when {@code newStamp} was older.
     */
    public synchronized boolean publish(S newValue, long newStamp) {
        if (newStamp < this.stamp) {
            return false;
        }
        this.value = newValue;
        this.loadedAt = Instant.now();
        this.stale = false;
        this.deleted = false;
        this.stamp = newStamp;
        return true;
    }

    /**
     * Internal: turns this cell into a tombstone (deleted), guarded by the same monotonic stamp so a
     * slower delete never overrides a newer write. The value reference is dropped for the GC.
     *
     * @return {@code true} when the tombstone applied; {@code false} when {@code newStamp} was older.
     */
    public synchronized boolean tombstone(long newStamp) {
        if (newStamp < this.stamp) {
            return false;
        }
        this.value = null;
        this.deleted = true;
        this.stale = false;
        this.loadedAt = Instant.now();
        this.stamp = newStamp;
        return true;
    }
}
