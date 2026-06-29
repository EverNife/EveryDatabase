package br.com.finalcraft.everydatabase.manager.observ;

/**
 * An immutable snapshot of a {@code CacheSync}'s operational state and routing counters. Carries only
 * the current {@link CacheSyncMode}, transport connectivity, time spent in fallback, and signal
 * counts - never entity content. Read it with {@code cacheSync.stats()}.
 */
public final class CacheSyncStats {

    private final CacheSyncMode mode;
    private final boolean transportConnected;
    private final long timeInFallbackMillis;
    private final long signalsReceived;
    private final long signalsApplied;
    private final long signalsSkippedOwnOrigin;
    private final long signalsUnmapped;
    private final long parseFailures;

    public CacheSyncStats(CacheSyncMode mode, boolean transportConnected, long timeInFallbackMillis,
                          long signalsReceived, long signalsApplied, long signalsSkippedOwnOrigin,
                          long signalsUnmapped, long parseFailures) {
        this.mode                   = mode;
        this.transportConnected     = transportConnected;
        this.timeInFallbackMillis   = timeInFallbackMillis;
        this.signalsReceived        = signalsReceived;
        this.signalsApplied         = signalsApplied;
        this.signalsSkippedOwnOrigin = signalsSkippedOwnOrigin;
        this.signalsUnmapped        = signalsUnmapped;
        this.parseFailures          = parseFailures;
    }

    public CacheSyncMode mode()           { return mode; }
    /** Whether the transport is currently connected (only meaningful when a transport is in use). */
    public boolean transportConnected()   { return transportConnected; }
    /** Total wall-clock time the transport has spent disconnected (the standby poller covering for it). */
    public long timeInFallbackMillis()    { return timeInFallbackMillis; }
    public long signalsReceived()         { return signalsReceived; }
    public long signalsApplied()          { return signalsApplied; }
    public long signalsSkippedOwnOrigin() { return signalsSkippedOwnOrigin; }
    /** Signals for a collection no bound manager handles (ignored). */
    public long signalsUnmapped()         { return signalsUnmapped; }
    /** Signals whose key could not be parsed back to the cache key type. */
    public long parseFailures()           { return parseFailures; }

    @Override
    public String toString() {
        return "CacheSyncStats{mode=" + mode + ", connected=" + transportConnected
                + ", fallbackMs=" + timeInFallbackMillis
                + ", received=" + signalsReceived + ", applied=" + signalsApplied
                + ", skippedOwn=" + signalsSkippedOwnOrigin + ", unmapped=" + signalsUnmapped
                + ", parseFailures=" + parseFailures + "}";
    }
}
