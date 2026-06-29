package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;

/**
 * A pluggable pub/sub channel that carries cache-invalidation signals between instances, decoupled
 * from the data backend. It is the third cache-sync mechanism, alongside a backend-native change feed
 * ({@code ChangeFeedStorage}) and polling ({@code PollingCacheSync}): wire it with
 * {@link CacheSync#via(CacheSyncTransport)} and it works for <b>any</b> backend - including the ones
 * with no native feed (MySQL/MariaDB) - by replacing the polling fallback with real push.
 *
 * <p>This SPI is transport-agnostic: it speaks only {@link ChangeEvent}/{@link ChangeListener} and
 * carries no client-specific types. A Redis/Valkey implementation (via Jedis) is the first concrete
 * one; other buses (NATS, MQTT, ...) could implement the same interface.
 *
 * <h3>Delivery semantics</h3>
 * At-least-once, unordered, and fire-and-forget (lossy): a signal may be duplicated, reordered, or
 * dropped. Safe for cache invalidation - the cache cell's monotonic stamp makes duplicates/reorders
 * harmless, and a dropped signal self-heals under a TTL policy. Not a reliable event log.
 */
public interface CacheSyncTransport extends AutoCloseable {

    /**
     * A stable identifier for <b>this transport instance</b>. Stamped on the {@link ChangeEvent#originId()}
     * of signals this instance publishes, so a consumer can skip invalidating the cache that just wrote.
     */
    String originId();

    /**
     * Publishes a change signal to the channel. Fire-and-forget: an implementation must never let a
     * publish failure break the write that produced the signal (log and swallow).
     */
    void publish(ChangeEvent event);

    /** Registers {@code listener} to receive signals from the channel. Close the handle to stop delivery. */
    ChangeSubscription subscribe(ChangeListener listener);

    /** Closes the transport's connections/threads. Idempotent. */
    @Override
    void close();
}
