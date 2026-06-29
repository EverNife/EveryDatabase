package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.manager.CachingManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** Non-null in {@link #attach(Storage)} mode (the single feed source); null in {@link #auto()} mode. */
    private final Storage attachedSource;
    private final boolean autoMode;

    private final List<Binding<?>> bindings = new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();

    private volatile Duration pollInterval;
    private volatile boolean includeOwnOrigin = false;
    private volatile Consumer<Throwable> errorHandler;
    private volatile CacheSyncTransport transport;

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
     * feedless ones like MySQL/MariaDB). Only supported in {@link #attach(Storage)} mode.
     *
     * <p>The transport's lifecycle is the caller's: {@link #close()} stops the subscriptions and clears
     * the publish hooks, but does not close the transport (it may be shared by several syncs).
     */
    public CacheSync via(CacheSyncTransport transport) {
        if (autoMode) {
            throw new IllegalStateException("via(...) is only supported in attach(storage) mode, not auto()");
        }
        this.transport = transport;
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
            for (Map.Entry<Storage, List<Binding<?>>> group : groupBindings().entrySet()) {
                setupGroup(group.getKey(), group.getValue());
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

    private void setupGroup(Storage source, List<Binding<?>> groupBindings) {
        if (transport != null) {
            // Explicit pub/sub transport: subscribe for incoming signals and make each manager publish
            // on local writes. Takes precedence over a native feed and over polling.
            Map<String, Bound<?>> byCollection = buildByCollection(groupBindings);
            subscriptions.add(transport.subscribe(new PushGroup(transport.originId(), byCollection)::onChange));
            for (Binding<?> binding : groupBindings) {
                installPublishHook(binding, transport);
            }
        } else if (source instanceof ChangeFeedStorage) {
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

    /** Indexes a group's bindings by collection name, resolving each one's key parser. */
    private static Map<String, Bound<?>> buildByCollection(List<Binding<?>> groupBindings) {
        Map<String, Bound<?>> byCollection = new HashMap<>();
        for (Binding<?> binding : groupBindings) {
            byCollection.put(binding.manager.collection(), binding.resolve());
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

        void apply(ChangeEvent event, Consumer<Throwable> errorHandler) {
            K key;
            try {
                key = keyParser.apply(event.key());
            } catch (RuntimeException ex) {
                if (errorHandler != null) {
                    errorHandler.accept(ex);
                }
                return; // unparseable key; skip rather than break the source delivery thread
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
            if (!includeOwnOrigin) {
                String origin = event.originId();
                if (origin != null && !origin.isEmpty() && origin.equals(ownOrigin)) {
                    return; // our own write; this cache was already refreshed write-through
                }
            }
            Bound<?> bound = byCollection.get(event.collection());
            if (bound != null) {
                bound.apply(event, errorHandler);
            }
        }
    }
}
