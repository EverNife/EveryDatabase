package br.com.finalcraft.everydatabase.manager.observ;

/**
 * An immutable snapshot of a {@code CachingManager}'s counters (Caffeine-style). Carries only counts -
 * never entity content or keys - so it is safe to log/export. Read it with {@code manager.stats()}.
 *
 * <p>Counts <b>manager-mediated</b> reads (resolve/peek/getAll); a {@code Ref} that has memoized its
 * cell reads the value lock-free without going through the manager, so those reads are not counted.
 */
public final class CacheStats {

    private final long hitCount;
    private final long missCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;
    private final long invalidationCount;
    private final long evictionCount;
    private final long liveSize;

    public CacheStats(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
                      long invalidationCount, long evictionCount, long liveSize) {
        this.hitCount          = hitCount;
        this.missCount         = missCount;
        this.loadSuccessCount  = loadSuccessCount;
        this.loadFailureCount  = loadFailureCount;
        this.invalidationCount = invalidationCount;
        this.evictionCount     = evictionCount;
        this.liveSize          = liveSize;
    }

    public long hitCount()          { return hitCount; }
    public long missCount()         { return missCount; }
    public long loadSuccessCount()  { return loadSuccessCount; }
    public long loadFailureCount()  { return loadFailureCount; }
    public long invalidationCount() { return invalidationCount; }
    public long evictionCount()     { return evictionCount; }
    /** Number of live cached cells at snapshot time (tombstones excluded). */
    public long liveSize()          { return liveSize; }

    /** Total reads served or attempted ({@code hit + miss}). */
    public long requestCount() {
        return hitCount + missCount;
    }

    /** Fraction of reads served from cache, in {@code [0,1]} (1.0 when there were no reads). */
    public double hitRate() {
        long total = requestCount();
        return total == 0 ? 1.0 : (double) hitCount / total;
    }

    /** Fraction of loads that failed with an exception, in {@code [0,1]} (0.0 when there were no loads). */
    public double loadFailureRate() {
        long loads = loadSuccessCount + loadFailureCount;
        return loads == 0 ? 0.0 : (double) loadFailureCount / loads;
    }

    @Override
    public String toString() {
        return "CacheStats{hits=" + hitCount + ", misses=" + missCount
                + ", hitRate=" + String.format("%.3f", hitRate())
                + ", loads=" + loadSuccessCount + " (fail=" + loadFailureCount + ")"
                + ", invalidations=" + invalidationCount + ", evictions=" + evictionCount
                + ", liveSize=" + liveSize + "}";
    }
}
