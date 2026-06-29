package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncMode;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncObserver;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The single entry point for keeping {@link CachingManager} caches fresh across instances. It hides
 * <b>which</b> mechanism a backend uses: for each bound manager it picks the backend-native
 * <b>push</b> feed ({@link ChangeFeedStorage} - MongoDB, PostgreSQL, InMemory) when available, and
 * otherwise falls back to <b>polling</b> (MySQL/MariaDB) - so the same wiring code works regardless
 * of backend.
 *
 * <h3>Single backend</h3>
 * <pre>{@code
 * CacheSync sync = CacheSync.attach(storage)
 *         .pollEvery(Duration.ofSeconds(10))   // only used if `storage` can't push
 *         .bind(guilds)
 *         .bind(players)
 *         .start();
 * // ... on shutdown:
 * sync.close();
 * }</pre>
 * If {@code storage} implements {@link ChangeFeedStorage} it subscribes (push) and {@code pollEvery}
 * is ignored; otherwise it polls every interval. With neither a feed nor a poll interval,
 * {@link #start()} throws a clear error.
 *
 * <h3>Mixed backends ({@link #auto()})</h3>
 * When managers live on <b>different</b> backends, {@link #auto()} routes each one by its own
 * {@link CachingManager#storage()} - push where the backend supports it, poll where it doesn't:
 * <pre>{@code
 * CacheSync sync = CacheSync.auto()
 *         .pollEvery(Duration.ofSeconds(10))   // fallback for the non-push managers
 *         .bind(guildsOnMongo)     // -> push (change stream)
 *         .bind(walletsOnMySql)    // -> poll (version polling)
 *         .start();
 * }</pre>
 *
 * <h3>Per-event behavior (push)</h3>
 * <ol>
 *   <li><b>Own-origin skip</b> (default): an event whose {@link ChangeEvent#originId()} matches the
 *       producing storage's {@link ChangeFeedStorage#originId()} is ignored - this instance just
 *       wrote it (its cache was already refreshed write-through). {@link #includeOwnOrigin()}
 *       disables the skip (several caches over one storage in one process, or in-process tests). When
 *       the source cannot attribute origin (Mongo oplog) the field is empty and the skip never fires.</li>
 *   <li><b>Route by collection</b> to a bound manager; unmapped collections are ignored.</li>
 *   <li><b>Parse the key</b> back to {@code K} and apply: {@code SAVE -> invalidate}, {@code DELETE ->
 *       evict}. An unparseable key is handed to {@link #onError} and skipped - never thrown into the
 *       source delivery thread.</li>
 * </ol>
 *
 * <p>Delivery is at-least-once and unordered; the cache cell's monotonic stamp makes that safe. For
 * fire-and-forget transports (Postgres {@code NOTIFY}) pair the bound managers with a
 * {@code CachePolicy.ttl(...)} so a dropped event self-heals. For finer control, the underlying
 * primitives {@code PollingCacheSync} (pull) and the {@link ChangeFeedStorage} subscription (push)
 * remain available directly.
 */
public final class CacheSync implements AutoCloseable {

    /** Fallback poll cadence used while a transport is disconnected, when no {@code pollEvery} was set. */
    private static final Duration DEFAULT_FALLBACK_POLL = Duration.ofSeconds(30);

    /** Non-null in {@link #attach(Storage)} mode (the single feed source); null in {@link #auto()} mode. */
    private final Storage attachedSource;
    private final boolean autoMode;

    private final List<Binding<?>> bindings = new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();

    private volatile Duration pollInterval;
    private volatile boolean includeOwnOrigin = false;
    private volatile Consumer<Throwable> errorHandler;
    private volatile CacheSyncTransport transport;
    private volatile boolean transportFallback = true;

    // Observability (always-on, ~free): operational state + routing counters, read via stats().
    private volatile CacheSyncObserver observer;
    private volatile boolean transportConnected = false;
    private volatile long fallbackEnteredAt = -1L;        // wall-clock when fallback began, -1 when not in fallback
    private volatile long accumulatedFallbackMillis = 0L;
    private final LongAdder signalsReceived = new LongAdder();
    private final LongAdder signalsApplied = new LongAdder();
    private final LongAdder signalsSkippedOwnOrigin = new LongAdder();
    private final LongAdder signalsUnmapped = new LongAdder();
    private final LongAdder parseFailures = new LongAdder();

    private boolean started;
    private final List<ChangeSubscription> subscriptions = new ArrayList<>();
    private final List<PollingCacheSync> pollers = new ArrayList<>();

    private CacheSync(Storage attachedSource, boolean autoMode) {
        this.attachedSource = attachedSource;
        this.autoMode = autoMode;
    }

    /**
     * Keeps the bound managers fresh using {@code storage} as the change source: push if it
     * implements {@link ChangeFeedStorage}, otherwise polling (set {@link #pollEvery(Duration)}).
     */
    public static CacheSync attach(Storage storage) {
        if (storage == null) {
            throw new IllegalArgumentException("storage must not be null");
        }
        return new CacheSync(storage, false);
    }

    /**
     * Routes each bound manager by its own {@link CachingManager#storage()} - for managers spread
     * across different backends. Each manager's storage decides push vs poll independently.
     */
    public static CacheSync auto() {
        return new CacheSync(null, true);
    }

    /** Sets the interval used to poll managers whose backend cannot push. Required for those. */
    public CacheSync pollEvery(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        this.pollInterval = interval;
        return this;
    }

    /**
     * Process events this instance produced too (default: skip them). Use when several caches share
     * one storage in one process, or in single-process tests where the writer's own change must
     * still fan out. Applies to every push group.
     */
    public CacheSync includeOwnOrigin() {
        this.includeOwnOrigin = true;
        return this;
    }

    /** Routes key-parse and poll errors here instead of swallowing them. */
    public CacheSync onError(Consumer<Throwable> handler) {
        this.errorHandler = handler;
        return this;
    }

    /**
     * Routes invalidation through an explicit pub/sub {@code transport} instead of the backend's native
     * feed or polling: every bound manager publishes a signal on each local write, and the sync
     * subscribes to the transport to invalidate/evict on signals from other instances. Takes
     * <b>precedence</b> over a native change feed and over polling, and works for any backend (including
     * feedless ones like MySQL/MariaDB).
     *
     * <p>Works in both {@link #attach(Storage)} and {@link #auto()} mode: a single shared transport is
     * the push source for <b>every</b> bound manager (the transport is backend-agnostic; events route by
     * collection - so collection names must be unique across all managers bound to one sync).
     *
     * <p>The transport's lifecycle is the caller's: {@link #close()} stops the subscriptions and clears
     * the publish hooks, but does not close the transport. With the {@link #transportFallback(boolean)
     * fallback} enabled, prefer <b>one transport per {@code CacheSync}</b>: a transport drives a single
     * connectivity listener, so sharing one across syncs would leave the earlier syncs' fallback inactive.
     */
    public CacheSync via(CacheSyncTransport transport) {
        this.transport = transport;
        return this;
    }

    /**
     * Whether, when a {@link #via(CacheSyncTransport) transport} is used, a standby version-poller takes
     * over while the transport reports itself disconnected and steps aside when it reconnects
     * (default {@code true} - the safer setup). The fallback cadence is {@link #pollEvery(Duration)} if
     * set, otherwise 30s. Disable it to rely purely on the transport (plus any TTL policy).
     *
     * <p>Only effective for transports that report connectivity via
     * {@link CacheSyncTransport#onConnectionStateChanged}; for those that don't, the standby poller is
     * created but never activated.
     *
     * <p>The standby poller inherits {@code PollingCacheSync}'s first-observation gap on each reconnect (a
     * key changed in the brief window straddling a disconnect may be missed); pair the managers with a
     * {@code CachePolicy.ttl(...)} as the self-heal.
     */
    public CacheSync transportFallback(boolean enabled) {
        this.transportFallback = enabled;
        return this;
    }

    /**
     * Registers an optional {@link CacheSyncObserver} notified of transport connect/disconnect and mode
     * changes (e.g. to log "transport down, falling back to polling"). At most one; the latest wins.
     */
    public CacheSync observe(CacheSyncObserver observer) {
        this.observer = observer;
        return this;
    }

    /** Binds {@code manager} using the built-in parser for its key type (see {@link KeyParsers}). */
    public <K, V> CacheSync bind(CachingManager<K, V> manager) {
        return bind(manager, null);
    }

    /**
     * Binds {@code manager} with an explicit {@code String -> K} parser - for composite/record/wrapper
     * keys that have no built-in parser. (The parser is only used on the push path; polling compares
     * cached keys directly.)
     */
    public <K, V> CacheSync bind(CachingManager<K, V> manager, Function<String, K> keyParser) {
        synchronized (lifecycle) {
            if (started) {
                throw new IllegalStateException("bind(...) must be called before start()");
            }
            bindings.add(new Binding<>(manager, keyParser));
        }
        return this;
    }

    /**
     * Sets up the chosen mechanism for every bound manager and starts delivering. Idempotent.
     *
     * @throws IllegalStateException if a manager's backend cannot push and no {@link #pollEvery} was set
     */
    public CacheSync start() {
        synchronized (lifecycle) {
            if (started) {
                return this;
            }
            started = true;
            if (transport != null) {
                // One explicit transport is the push source for every manager, in attach AND auto mode.
                setupTransport(new ArrayList<>(bindings));
            } else {
                for (Map.Entry<Storage, List<Binding<?>>> group : groupBindings().entrySet()) {
                    setupGroup(group.getKey(), group.getValue());
                }
            }
        }
        return this;
    }

    /** Stops all push subscriptions and pollers. Idempotent. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            for (ChangeSubscription sub : subscriptions) {
                try { sub.close(); } catch (RuntimeException ignored) { }
            }
            subscriptions.clear();
            for (PollingCacheSync poller : pollers) {
                try { poller.close(); } catch (RuntimeException ignored) { }
            }
            pollers.clear();
            if (transport != null) {
                transport.onConnectionStateChanged(null);          // detach the fallback connectivity listener
                for (Binding<?> binding : bindings) {
                    binding.manager.setLocalWriteListener(null);   // stop publishing once the sync is closed
                }
            }
            started = false;
        }
    }

    /** Whether this sync is currently active (started with at least one push or poll group). */
    public boolean isRunning() {
        synchronized (lifecycle) {
            return started && (!subscriptions.isEmpty() || !pollers.isEmpty());
        }
    }

    /**
     * The mechanism currently in use - the highest-value operational signal. With a transport, reports
     * {@link CacheSyncMode#TRANSPORT_PUSH} while connected and {@link CacheSyncMode#TRANSPORT_FALLBACK_POLL}
     * while disconnected; otherwise {@code FEED}/{@code POLL}; {@code IDLE} before {@link #start()}.
     */
    public CacheSyncMode mode() {
        synchronized (lifecycle) {
            if (!started) {
                return CacheSyncMode.IDLE;
            }
            if (transport != null) {
                return transportConnected ? CacheSyncMode.TRANSPORT_PUSH : CacheSyncMode.TRANSPORT_FALLBACK_POLL;
            }
            if (!subscriptions.isEmpty()) {
                return CacheSyncMode.FEED;
            }
            if (!pollers.isEmpty()) {
                return CacheSyncMode.POLL;
            }
            return CacheSyncMode.IDLE;
        }
    }

    /** Whether the transport is currently connected (only meaningful when a transport is in use). */
    public boolean transportConnected() {
        return transportConnected;
    }

    /** Total wall-clock time the transport has spent disconnected (the standby poller covering for it). */
    public long timeInFallbackMillis() {
        long acc = accumulatedFallbackMillis;
        long entered = fallbackEnteredAt;
        return entered >= 0 ? acc + (System.currentTimeMillis() - entered) : acc;
    }

    /** An immutable snapshot of the sync's mode, connectivity, fallback time, and routing counters. */
    public CacheSyncStats stats() {
        return new CacheSyncStats(mode(), transportConnected, timeInFallbackMillis(),
                signalsReceived.sum(), signalsApplied.sum(), signalsSkippedOwnOrigin.sum(),
                signalsUnmapped.sum(), parseFailures.sum());
    }

    /**
     * Runs one poll cycle on every internal poller (the non-push groups), synchronously. Push groups
     * are unaffected. Exposed for deterministic tests; production relies on the scheduled interval.
     */
    public void pollOnce() {
        List<PollingCacheSync> snapshot;
        synchronized (lifecycle) {
            snapshot = new ArrayList<>(pollers);
        }
        for (PollingCacheSync poller : snapshot) {
            poller.pollOnce();
        }
    }

    // ------------------------------------------------------------------
    //  Setup
    // ------------------------------------------------------------------

    /** Partitions bindings by their change source: one group in attach mode, by storage in auto mode. */
    private Map<Storage, List<Binding<?>>> groupBindings() {
        Map<Storage, List<Binding<?>>> groups = new LinkedHashMap<>();
        for (Binding<?> binding : bindings) {
            Storage source = autoMode ? binding.manager.storage() : attachedSource;
            groups.computeIfAbsent(source, k -> new ArrayList<>()).add(binding);
        }
        return groups;
    }

    /**
     * Sets up the one explicit transport as the push source for every bound manager (used in both attach
     * and auto mode; the transport is backend-agnostic and routes by collection).
     */
    private void setupTransport(List<Binding<?>> all) {
        if (transportFallback) {
            // A standby poller takes over while the transport is disconnected. Registered BEFORE the
            // subscription so the very first connect failure is observed. It only runs while the
            // connectivity listener reports the transport down (a no-op transport never activates it).
            Duration interval = pollInterval != null ? pollInterval : DEFAULT_FALLBACK_POLL;
            PollingCacheSync fallback = PollingCacheSync.every(interval);
            if (errorHandler != null) {
                fallback.onError(errorHandler);
            }
            for (Binding<?> binding : all) {
                fallback.bind(binding.manager);
            }
            pollers.add(fallback);
            transport.onConnectionStateChanged(connected -> onTransportConnectivity(connected, fallback));
        }
        Map<String, Bound<?>> byCollection = buildByCollection(all);
        subscriptions.add(transport.subscribe(new PushGroup(transport.originId(), byCollection)::onChange));
        for (Binding<?> binding : all) {
            installPublishHook(binding, transport);
        }
    }

    /** Activates the standby fallback poller while the transport is disconnected; pauses it when connected. */
    private void onTransportConnectivity(boolean connected, PollingCacheSync fallback) {
        synchronized (lifecycle) {
            if (!started) {
                return;   // the sync was closed; ignore late connectivity callbacks
            }
            transportConnected = connected;
            if (connected) {
                if (fallbackEnteredAt >= 0) {
                    accumulatedFallbackMillis += System.currentTimeMillis() - fallbackEnteredAt;
                    fallbackEnteredAt = -1L;
                }
                fallback.close();   // push restored: stop the safety-net polling (bindings/state are kept)
            } else {
                if (fallbackEnteredAt < 0) {
                    fallbackEnteredAt = System.currentTimeMillis();
                }
                fallback.start();   // push down: schedule the safety-net polling
            }
            notifyObserver(connected);
        }
    }

    /** Fires the observer (if any) on a connectivity transition; never lets it break delivery. */
    private void notifyObserver(boolean connected) {
        CacheSyncObserver obs = observer;
        if (obs == null) {
            return;
        }
        try {
            if (connected) {
                obs.onTransportConnected();
            } else {
                obs.onTransportDisconnected();
            }
            obs.onModeChange(mode());
        } catch (RuntimeException ignored) {
            // an observer must never break delivery
        }
    }

    private void setupGroup(Storage source, List<Binding<?>> groupBindings) {
        if (source instanceof ChangeFeedStorage) {
            ChangeFeedStorage feed = (ChangeFeedStorage) source;
            Map<String, Bound<?>> byCollection = buildByCollection(groupBindings);
            subscriptions.add(feed.subscribe(new PushGroup(feed.originId(), byCollection)::onChange));
        } else if (pollInterval != null) {
            PollingCacheSync poller = PollingCacheSync.every(pollInterval);
            if (errorHandler != null) {
                poller.onError(errorHandler);
            }
            for (Binding<?> binding : groupBindings) {
                poller.bind(binding.manager);
            }
            poller.start();
            pollers.add(poller);
        } else {
            throw new IllegalStateException(
                "Storage " + source.getClass().getName() + " has no change feed (does not implement "
                + "ChangeFeedStorage) and no poll interval was set. Call .pollEvery(Duration) to enable "
                + "the polling fallback, or back these managers with a push-capable storage "
                + "(MongoDB, PostgreSQL).");
        }
    }

    /**
     * Indexes a group's bindings by collection name, resolving each one's key parser. Rejects a
     * duplicate collection: events route purely by collection name, so two managers under the same
     * name would route ambiguously (and one would silently never be invalidated) - fail fast at start.
     */
    private static Map<String, Bound<?>> buildByCollection(List<Binding<?>> groupBindings) {
        Map<String, Bound<?>> byCollection = new HashMap<>();
        for (Binding<?> binding : groupBindings) {
            String collection = binding.manager.collection();
            Bound<?> previous = byCollection.putIfAbsent(collection, binding.resolve());
            if (previous != null) {
                throw new IllegalStateException(
                    "two bound managers share collection '" + collection + "'; cache-sync routes purely "
                    + "by collection name, so it must be unique across all managers bound to one CacheSync");
            }
        }
        return byCollection;
    }

    /** Makes {@code binding}'s manager publish a signal to {@code transport} on every local write. */
    private <K> void installPublishHook(Binding<K> binding, CacheSyncTransport transport) {
        CachingManager<K, ?> manager = binding.manager;
        String collection = manager.collection();
        String originId = transport.originId();
        manager.setLocalWriteListener((op, key) ->
                transport.publish(new ChangeEvent(collection, key.toString(), op, ChangeEvent.UNKNOWN_VERSION, originId)));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** A bound manager and its (possibly absent) explicit key parser, before grouping. */
    private static final class Binding<K> {
        private final CachingManager<K, ?> manager;
        private final Function<String, K> keyParser;   // nullable -> default parser resolved at setup

        Binding(CachingManager<K, ?> manager, Function<String, K> keyParser) {
            this.manager = manager;
            this.keyParser = keyParser;
        }

        Bound<K> resolve() {
            Function<String, K> parser = keyParser != null ? keyParser : KeyParsers.forType(manager.keyType());
            return new Bound<>(manager, parser);
        }
    }

    /** A bound manager plus the resolved parser that recovers its key type from the event string key. */
    private static final class Bound<K> {
        private final CachingManager<K, ?> manager;
        private final Function<String, K> keyParser;

        Bound(CachingManager<K, ?> manager, Function<String, K> keyParser) {
            this.manager = manager;
            this.keyParser = keyParser;
        }

        boolean apply(ChangeEvent event, Consumer<Throwable> errorHandler) {
            K key;
            try {
                key = keyParser.apply(event.key());
            } catch (RuntimeException ex) {
                if (errorHandler != null) {
                    errorHandler.accept(ex);
                }
                return false; // unparseable key; skip rather than break the source delivery thread
            }
            switch (event.op()) {
                case SAVE:
                    manager.invalidate(key);
                    break;
                case DELETE:
                    manager.evict(key);
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    /** Routes a single push source's events to the managers bound to it (a native feed or a transport). */
    private final class PushGroup {
        private final String ownOrigin;
        private final Map<String, Bound<?>> byCollection;

        PushGroup(String ownOrigin, Map<String, Bound<?>> byCollection) {
            this.ownOrigin = ownOrigin;
            this.byCollection = byCollection;
        }

        void onChange(ChangeEvent event) {
            signalsReceived.increment();
            if (!includeOwnOrigin) {
                String origin = event.originId();
                if (origin != null && !origin.isEmpty() && origin.equals(ownOrigin)) {
                    signalsSkippedOwnOrigin.increment();
                    return; // our own write; this cache was already refreshed write-through
                }
            }
            Bound<?> bound = byCollection.get(event.collection());
            if (bound == null) {
                signalsUnmapped.increment();
                return;
            }
            if (bound.apply(event, errorHandler)) {
                signalsApplied.increment();
            } else {
                parseFailures.increment();
            }
        }
    }
}
