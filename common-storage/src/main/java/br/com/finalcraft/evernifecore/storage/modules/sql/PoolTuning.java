package br.com.finalcraft.evernifecore.storage.modules.sql;

import java.time.Duration;

/**
 * HikariCP connection-pool tuning parameters for the SQL backend.
 *
 * <p>Use {@link #defaults()} for sensible out-of-the-box values suitable for
 * a Minecraft server (small connection count, conservative timeouts).
 */
public final class PoolTuning {

    private final int minIdle;
    private final int maxSize;
    private final Duration connectTimeout;
    private final Duration idleTimeout;

    public PoolTuning(int minIdle, int maxSize, Duration connectTimeout, Duration idleTimeout) {
        this.minIdle        = minIdle;
        this.maxSize        = maxSize;
        this.connectTimeout = connectTimeout;
        this.idleTimeout    = idleTimeout;
    }

    /**
     * Sensible defaults: 2 idle connections, up to 10 total,
     * 30 s connection timeout, 10 min idle timeout.
     */
    public static PoolTuning defaults() {
        return new PoolTuning(2, 10, Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    /** Minimum number of idle connections kept alive in the pool. */
    public int minIdle()             { return minIdle; }

    /** Maximum pool size (hard cap on simultaneous connections). */
    public int maxSize()             { return maxSize; }

    /** Maximum time to wait for a connection to become available. */
    public Duration connectTimeout() { return connectTimeout; }

    /** Time a connection may remain idle before being evicted. */
    public Duration idleTimeout()    { return idleTimeout; }

    @Override
    public String toString() {
        return "PoolTuning{idle=" + minIdle + "/" + maxSize
            + ", connect=" + connectTimeout.getSeconds() + "s"
            + ", idle=" + idleTimeout.toMinutes() + "m}";
    }
}
