package br.com.finalcraft.everydatabase.manager.sync.jedis;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangePayload;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.manager.sync.CacheSyncTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * A Redis/Valkey pub/sub implementation of {@link CacheSyncTransport} using Jedis. It carries
 * cache-invalidation signals between instances, decoupled from the data backend - so it works for
 * any backend, including those with no native change feed.
 *
 * <h3>One channel per store</h3>
 * The configured {@link JedisCacheSyncConfig#channel() channel} is a <b>prefix</b>: a signal from a
 * store goes to {@code <channel>:<backendId>}, and an instance subscribes only to the stores its
 * bound managers actually read. Two servers sharing a Redis but not a database therefore never even
 * receive each other's signals - the server routes them apart, instead of every instance receiving
 * everything and discarding most of it. A signal that names no store (a producer built before store
 * identities existed) goes to the bare prefix, which every instance also subscribes to, so it still
 * reaches everyone.
 *
 * <p><b>The subscribed set only ever grows.</b> Each {@link #subscribe(Set, ChangeListener)} adds the
 * channels it names to the live subscription - on a connection already blocked in {@code SUBSCRIBE},
 * incrementally - but nothing ever removes one. Re-subscribing with a smaller set does not narrow the
 * scope; only closing the transport does. This mirrors the caller's own lifecycle, where the set of
 * bound managers likewise never shrinks.
 *
 * <p>Store identities are readable by anyone who can run {@code PUBSUB CHANNELS} on the server (they
 * carry host/path shape, never credentials). Where that matters, isolate the applications on separate
 * Redis servers or ACL-restricted databases rather than relying on channel names being private.
 *
 * <p>Modeled on the SQL {@code LISTEN/NOTIFY} listener: a daemon thread holds a <b>dedicated</b>
 * connection blocked on {@code SUBSCRIBE} (Jedis blocks the connection for the whole subscription),
 * with a reconnect loop and clean shutdown; publishing goes through a separate, thread-safe
 * {@link JedisPool}. A publish or subscribe failure is routed to the optional error handler and
 * swallowed - it never breaks the write that produced the signal. Delivery is fire-and-forget
 * (at-least-once, unordered, lossy); the cache cell's monotonic stamp and a TTL policy make that safe.
 *
 * <p>The same client works unchanged against Redis and Valkey (identical RESP wire protocol).
 */
public final class JedisCacheSyncTransport implements CacheSyncTransport {

    /** Separates the configured channel prefix from the store identity it is scoped to. */
    private static final String CHANNEL_SEPARATOR = ":";

    private final HostAndPort hostAndPort;
    private final JedisClientConfig clientConfig;     // carries auth (user/password), db, ssl, timeouts
    private final String channelBase;

    /** Channels the subscriber holds (or will hold on its next connect). Grows only; never shrinks. */
    private final Set<String> subscribedChannels = new CopyOnWriteArraySet<>();

    /** Stable per-instance origin id, stamped on published signals so a consumer can skip its own. */
    private final String originId = "jedis-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChangeFeedSupport feed;
    private final JedisPool publishPool;
    private final Consumer<Throwable> errorHandler;   // nullable
    private volatile Consumer<Boolean> connectionListener;   // nullable; notified on connect/disconnect
    private volatile Boolean lastConnected;                  // last reported state (dedupe transitions)

    // Observability counters (always-on, ~free).
    private final LongAdder publishCount = new LongAdder();
    private final LongAdder publishFailureCount = new LongAdder();
    private final LongAdder reconnectCount = new LongAdder();

    private volatile boolean running = false;
    private volatile boolean closed = false;   // terminal: once closed, the subscriber is never resurrected
    private volatile Thread thread;
    private volatile Jedis subscriberConn;            // dedicated, blocked by subscribe()
    private volatile JedisPubSub pubSub;              // unsubscribed from close()'s thread
    private volatile CountDownLatch subscribedLatch;  // counted down in onSubscribe

    private JedisCacheSyncTransport(JedisCacheSyncConfig config, Consumer<Throwable> errorHandler) {
        this.hostAndPort  = new HostAndPort(config.host(), config.port());
        this.clientConfig = clientConfig(config);
        this.channelBase  = config.channel();
        this.errorHandler = errorHandler;
        this.feed         = new ChangeFeedSupport(errorHandler);
        this.publishPool  = new JedisPool(config.poolConfig(), hostAndPort, clientConfig);
    }

    /** Opens a transport for {@code config}; failures are swallowed silently (lossy by contract). */
    public static JedisCacheSyncTransport connect(JedisCacheSyncConfig config) {
        return connect(config, null);
    }

    /**
     * Opens a transport for {@code config}, routing publish/subscribe failures (e.g. the server being
     * unreachable, a reconnect) to {@code errorHandler} instead of swallowing them silently.
     */
    public static JedisCacheSyncTransport connect(JedisCacheSyncConfig config, Consumer<Throwable> errorHandler) {
        return new JedisCacheSyncTransport(config, errorHandler);
    }

    private static JedisClientConfig clientConfig(JedisCacheSyncConfig config) {
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectTimeoutMs())
                .socketTimeoutMillis(config.socketTimeoutMs())
                .database(config.database())
                .ssl(config.ssl());
        if (config.username() != null && !config.username().isEmpty()) {
            b.user(config.username());
        }
        if (config.password() != null && !config.password().isEmpty()) {
            b.password(config.password());
        }
        if (config.clientName() != null && !config.clientName().isEmpty()) {
            b.clientName(config.clientName());
        }
        return b.build();
    }

    @Override
    public String originId() {
        return originId;
    }

    /** Number of signals successfully published. */
    public long publishCount() {
        return publishCount.sum();
    }

    /** Number of publishes that failed (swallowed - the cache self-heals via TTL). */
    public long publishFailureCount() {
        return publishFailureCount.sum();
    }

    /** Number of subscriber reconnect attempts (after a dropped connection or a server-side unsubscribe). */
    public long reconnectCount() {
        return reconnectCount.sum();
    }

    /** Whether the subscriber is currently connected (best-effort; an unknown/initial state reads false). */
    public boolean connected() {
        return Boolean.TRUE.equals(lastConnected);
    }

    /**
     * The channel a signal from {@code backendId} travels on. A signal that names no store falls back
     * to the bare prefix, which every subscriber also listens to.
     */
    static String channelFor(String channelBase, String backendId) {
        if (backendId == null || backendId.isEmpty()) {
            return channelBase;
        }
        return channelBase + CHANNEL_SEPARATOR + backendId;
    }

    /**
     * The channels a subscriber reading {@code backendIds} must hold. Always includes the bare prefix,
     * so a signal from a producer that names no store still arrives.
     */
    static Set<String> channelsFor(String channelBase, Set<String> backendIds) {
        Set<String> channels = new LinkedHashSet<>();
        channels.add(channelBase);
        if (backendIds != null) {
            for (String backendId : backendIds) {
                channels.add(channelFor(channelBase, backendId));
            }
        }
        return channels;
    }

    @Override
    public void publish(ChangeEvent event) {
        String payload = ChangePayload.encode(mapper, event);
        try (Jedis jedis = publishPool.getResource()) {
            jedis.publish(channelFor(channelBase, event.backendId()), payload);
            publishCount.increment();
        } catch (Exception e) {
            // A failed publish must never break the write it follows; cache freshness self-heals.
            publishFailureCount.increment();
            reportError(e);
        }
    }

    /**
     * Subscribes without naming any store, so only the bare prefix is held: this instance receives
     * signals that name no store, but none of the per-store traffic. For the scoped form the caller
     * must say which stores it reads - see {@link #subscribe(Set, ChangeListener)}.
     */
    @Override
    public ChangeSubscription subscribe(ChangeListener listener) {
        return subscribe(Collections.emptySet(), listener);
    }

    @Override
    public ChangeSubscription subscribe(Set<String> backendIds, ChangeListener listener) {
        if (closed) {
            // Fail fast instead of returning a live-looking subscription that never delivers.
            throw new IllegalStateException("transport is closed");
        }
        ChangeSubscription subscription = feed.subscribe(listener);
        widenSubscription(channelsFor(channelBase, backendIds));
        return subscription;
    }

    /**
     * Adds any channel of {@code wanted} not held yet, then makes sure the subscriber is running. When
     * the connection is already blocked in {@code SUBSCRIBE}, the new channels are added to it in
     * place; otherwise the (re)connect picks them up from the set, which it always reads fresh.
     */
    private synchronized void widenSubscription(Set<String> wanted) {
        List<String> added = new ArrayList<>();
        for (String channel : wanted) {
            if (subscribedChannels.add(channel)) {
                added.add(channel);
            }
        }
        // Publishing the set before reading pubSub is what makes this safe against the subscriber
        // thread, which does the opposite: it publishes pubSub before reading the set. Either the
        // connect sees the new channels, or it is already live and gets them here - never neither.
        JedisPubSub ps = pubSub;
        if (!added.isEmpty() && ps != null) {
            try {
                ps.subscribe(added.toArray(new String[0]));
            } catch (Exception e) {
                // The channels are already in the set, so the next reconnect subscribes them anyway.
                reportError(e);
            }
        }
        ensureSubscriberStarted();
    }

    @Override
    public void onConnectionStateChanged(Consumer<Boolean> listener) {
        this.connectionListener = listener;
        // Deliver the current known state immediately so a late/replacement listener (a second or a
        // restarted CacheSync registering on an already-settled transport) learns up/down without
        // waiting for the next transition - otherwise its fallback poller could never activate.
        Boolean current = lastConnected;
        if (listener != null && current != null) {
            try {
                listener.accept(current);
            } catch (Throwable ignored) {
                // a connection listener must never break delivery
            }
        }
    }

    /** Lazily starts the SUBSCRIBE listener thread on first subscribe. Idempotent; a no-op once closed. */
    private synchronized void ensureSubscriberStarted() {
        if (running || closed) {
            return;
        }
        running = true;
        Thread t = new Thread(this::runSubscribeLoop, "everydatabase-jedis-changefeed");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    private void runSubscribeLoop() {
        while (running) {
            Jedis jedis = null;
            try {
                // new Jedis(hostAndPort, clientConfig) connects + authenticates EAGERLY in the
                // constructor (unlike the bare new Jedis(host,port)). A hung handshake here is not
                // force-closable by stop() yet (subscriberConn is still null), but it is bounded by the
                // connect/socket timeouts (default 2000ms each), after which it throws and the loop exits.
                jedis = new Jedis(hostAndPort, clientConfig);
                subscriberConn = jedis;   // visible now, so stop() can close it during the blocking subscribe
                CountDownLatch latch = new CountDownLatch(1);
                subscribedLatch = latch;

                JedisPubSub ps = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String subscribedChannel, int subscribedChannels) {
                        latch.countDown();   // now safe for close() to call unsubscribe()
                        setConnected(true);  // the channel is live
                    }
                    @Override
                    public void onMessage(String messageChannel, String payload) {
                        dispatch(payload);
                    }
                };
                pubSub = ps;

                // Read the channels AFTER publishing pubSub, so a widenSubscription() racing this
                // connect either lands in the array below or finds a live pubSub to add itself to.
                jedis.subscribe(ps, currentChannels());   // BLOCKS until unsubscribe() or a dropped connection
                if (running) {
                    // subscribe() returned WITHOUT throwing while still running: a server-side
                    // unsubscribe/RESET drained the last channel. Back off so a server that keeps doing
                    // this cannot spin us in a tight, zero-delay reconnect loop (the catch path below
                    // already backs off on a thrown drop; this covers the exception-free exit).
                    setConnected(false);
                    reconnectCount.increment();
                    reportError(new IllegalStateException(
                            "Jedis SUBSCRIBE returned unexpectedly (server unsubscribe?); reconnecting"));
                    sleepBeforeReconnect();
                }
            } catch (Exception e) {
                if (!running) {
                    return;   // expected: the transport is closing
                }
                setConnected(false);
                reconnectCount.increment();
                reportError(e);
                sleepBeforeReconnect();
            } finally {
                closeQuietly(jedis);
                subscriberConn = null;
                pubSub = null;
            }
        }
    }

    /** The channels to (re)subscribe on connect; the bare prefix alone if nothing was named yet. */
    private String[] currentChannels() {
        if (subscribedChannels.isEmpty()) {
            return new String[] { channelBase };
        }
        return subscribedChannels.toArray(new String[0]);
    }

    private void dispatch(String payload) {
        // Route a malformed/foreign payload to the error handler instead of dropping it silently: a
        // pub/sub channel is global per server (not scoped by DB index), so an undecodable message is
        // usually a channel collision with another application - worth surfacing. Mirrors PostgresChangeFeed.
        ChangeEvent event = ChangePayload.decode(mapper, payload, reason -> reportError(
                new IllegalStateException("dropped a malformed cache-sync payload (channel collision?): " + reason)));
        if (event != null) {
            feed.emit(event);
        }
    }

    @Override
    public void close() {
        stopSubscriber();
        feed.closeAll();
        try {
            publishPool.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private synchronized void stopSubscriber() {
        running = false;   // set first, so the loop's catch treats the teardown as expected shutdown
        closed = true;     // terminal: a later subscribe() must not resurrect the listener thread
        JedisPubSub ps = pubSub;
        CountDownLatch latch = subscribedLatch;
        if (ps != null) {
            try {
                // unsubscribe() only works once the pubsub has actually subscribed; gate on the latch.
                if (latch == null || latch.await(2, TimeUnit.SECONDS)) {
                    ps.unsubscribe();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        // Fallback: tear down the socket so a blocked/connecting subscribe throws and the loop exits.
        Jedis c = subscriberConn;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();   // breaks Thread.sleep in the backoff window
            try {
                t.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Notifies the connection listener on a connect/disconnect transition (deduped, never throwing). */
    private void setConnected(boolean connected) {
        Boolean last = lastConnected;
        if (last != null && last == connected) {
            return;   // only notify on a transition
        }
        lastConnected = connected;
        Consumer<Boolean> listener = connectionListener;
        if (listener != null) {
            try {
                listener.accept(connected);
            } catch (Throwable ignored) {
                // a connection listener must never break delivery
            }
        }
    }

    private void reportError(Throwable t) {
        if (errorHandler != null) {
            try {
                errorHandler.accept(t);
            } catch (Throwable ignored) {
                // an error handler must never break delivery either
            }
        }
    }

    private static void closeQuietly(Jedis jedis) {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
