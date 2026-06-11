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
    private final Duration maxLifetime;

    /**
     * Full constructor.
     *
     * @param minIdle        minimum number of idle connections kept alive
     * @param maxSize        maximum pool size
     * @param connectTimeout maximum time to wait for a connection
     * @param idleTimeout    time a connection may remain idle before eviction
     * @param maxLifetime    maximum total lifetime of a pooled connection (HikariCP
     *                       {@code maxLifetime}); should be a few seconds shorter than any
     *                       server-side connection timeout (e.g. MySQL {@code wait_timeout})
     */
    public PoolTuning(int minIdle, int maxSize, Duration connectTimeout,
                      Duration idleTimeout, Duration maxLifetime) {
        this.minIdle        = minIdle;
        this.maxSize        = maxSize;
        this.connectTimeout = connectTimeout;
        this.idleTimeout    = idleTimeout;
        this.maxLifetime    = maxLifetime;
    }

    /**
     * Convenience constructor - uses the HikariCP default of 30 minutes for
     * {@link #maxLifetime()}.
     */
    public PoolTuning(int minIdle, int maxSize, Duration connectTimeout, Duration idleTimeout) {
        this(minIdle, maxSize, connectTimeout, idleTimeout, Duration.ofMinutes(30));
    }

    /**
     * Sensible defaults: 2 idle connections, up to 10 total,
     * 30 s connection timeout, 10 min idle timeout, 30 min max lifetime.
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

    /** Maximum total lifetime of a pooled connection (HikariCP {@code maxLifetime}). */
    public Duration maxLifetime()    { return maxLifetime; }

    @Override
    public String toString() {
        return "PoolTuning{idle=" + minIdle + "/" + maxSize
            + ", connect=" + connectTimeout.getSeconds() + "s"
            + ", idle=" + idleTimeout.toMinutes() + "m"
            + ", lifetime=" + maxLifetime.toMinutes() + "m}";
    }
}
