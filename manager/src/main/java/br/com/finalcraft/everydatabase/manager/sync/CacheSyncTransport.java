package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;

import java.util.Set;
import java.util.function.Consumer;

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

    /**
     * Registers {@code listener} and tells the transport <b>which stores</b> the caller actually reads,
     * so an implementation able to scope its channel by store can subscribe to those alone instead of
     * receiving every signal on the bus and discarding most of them.
     *
     * <p>{@code backendIds} holds the distinct {@code Storage.backendIdentity()} values of the managers
     * bound to this transport. It is computed once, when the caller starts, and never recomputed: an
     * implementation may therefore treat the set it receives as additive - the scope only ever grows
     * over the life of a transport, it never shrinks.
     *
     * <p>The default implementation ignores {@code backendIds} and delegates to
     * {@link #subscribe(ChangeListener)}, which keeps the single-channel behaviour every transport had
     * before this method existed. A transport that wants the traffic saving overrides it; one that
     * cannot scope its channel (or does not care to) is free to leave it alone.
     */
    default ChangeSubscription subscribe(Set<String> backendIds, ChangeListener listener) {
        return subscribe(listener);
    }

    /**
     * Optional: registers a {@code listener} notified when the transport connects ({@code true}) or
     * disconnects ({@code false}), so a consumer can fall back to another mechanism (e.g. version
     * polling) while the channel is down and step aside when it recovers. Fired only on transitions.
     *
     * <p>A transport that cannot report connectivity leaves this a no-op; the consumer then assumes the
     * transport is always available. At most one listener - the latest registration wins; pass
     * {@code null} to clear it.
     */
    default void onConnectionStateChanged(Consumer<Boolean> listener) {
        // no-op: transports that cannot observe connectivity opt out, and callers assume always-connected
    }

    /** Closes the transport's connections/threads. Idempotent. */
    @Override
    void close();
}
