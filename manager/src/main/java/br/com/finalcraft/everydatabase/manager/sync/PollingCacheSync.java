package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.manager.CachingManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Keeps {@link CachingManager} caches fresh on backends <b>without</b> a native change feed
 * (MySQL/MariaDB) by polling. On a fixed interval it reads the current
 * {@link br.com.finalcraft.everydatabase.Repository#versions(java.util.Collection) versions} of each
 * bound manager's <b>currently cached</b> keys (a cheap key+version read, bounded by cache size, not
 * table size) and:
 * <ul>
 *   <li>invalidates a key whose backend version has increased since the last poll (another instance
 *       updated it), so the next read reloads it;</li>
 *   <li>evicts a key that is cached here but no longer present in the backend (deleted elsewhere).</li>
 * </ul>
 *
 * <pre>{@code
 * PollingCacheSync poller = PollingCacheSync.every(Duration.ofSeconds(10))
 *         .bind(guilds)
 *         .bind(players)
 *         .start();
 * // ... on shutdown:
 * poller.close();
 * }</pre>
 *
 * <p>This is the pull-based counterpart to the push-based {@link CacheSync}; use it where the backend
 * cannot push (MySQL/MariaDB), or alongside a TTL policy as a safety net. It works on any backend,
 * but the push feed is preferred where available.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li><b>Latency</b> is one poll interval - not instant.</li>
 *   <li><b>Updates need versioning.</b> Detecting an in-place update requires the descriptor to be
 *       versioned (an increasing {@code lock_version}). On a non-versioned descriptor (or H2, which
 *       does not enforce versioning) every existing key reports version {@code 0}, so polling detects
 *       only <em>deletes</em>, not updates. Use versioned entities for update propagation.</li>
 *   <li><b>First-observation gap.</b> A key is assumed as fresh as the backend the first time it is
 *       polled (it usually was just loaded); a write landing in the brief window between a cache load
 *       and the first poll of that key can be missed. All later writes are caught. Pair with a TTL
 *       policy if that window matters.</li>
 * </ul>
 */
public final class PollingCacheSync implements AutoCloseable {

    private final Duration interval;
    private final CopyOnWriteArrayList<Bound<?>> bounds = new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();

    private volatile Consumer<Throwable> errorHandler;
    private volatile ScheduledExecutorService scheduler;

    private PollingCacheSync(Duration interval) {
        this.interval = interval;
    }

    /** Creates a poller that runs every {@code interval} (must be positive). */
    public static PollingCacheSync every(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        return new PollingCacheSync(interval);
    }

    /** Binds a manager to be version-polled. May be called before or after {@link #start()}. */
    public <K, V> PollingCacheSync bind(CachingManager<K, V> manager) {
        bounds.add(new Bound<>(manager));
        return this;
    }

    /** Routes poll errors (e.g. the backend being unreachable) here instead of swallowing them. */
    public PollingCacheSync onError(Consumer<Throwable> handler) {
        this.errorHandler = handler;
        return this;
    }

    /** Schedules the poll loop on a daemon thread. Idempotent. */
    public PollingCacheSync start() {
        synchronized (lifecycle) {
            if (scheduler == null) {
                ThreadFactory tf = r -> {
                    Thread t = new Thread(r, "everydatabase-cache-poller");
                    t.setDaemon(true);
                    return t;
                };
                ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(tf);
                long ms = interval.toMillis();
                s.scheduleWithFixedDelay(this::pollOnce, ms, ms, TimeUnit.MILLISECONDS);
                scheduler = s;
            }
        }
        return this;
    }

    /** Stops the poll loop. Idempotent. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    /** Whether the poll loop is currently scheduled. */
    public boolean isRunning() {
        ScheduledExecutorService s = scheduler;
        return s != null && !s.isShutdown();
    }

    /**
     * Runs one poll cycle across every bound manager, synchronously on the calling thread. Invoked by
     * the scheduler, and exposed for deterministic tests (poll on demand instead of waiting a tick).
     */
    public void pollOnce() {
        for (Bound<?> bound : bounds) {
            try {
                bound.poll();
            } catch (Throwable t) {
                Consumer<Throwable> h = errorHandler;
                if (h != null) {
                    h.accept(t);
                }
            }
        }
    }

    /** A bound manager plus the last backend version this poller observed for each of its keys. */
    private static final class Bound<K> {
        private final CachingManager<K, ?> manager;
        private final Map<K, Long> lastSeen = new HashMap<>();

        Bound(CachingManager<K, ?> manager) {
            this.manager = manager;
        }

        void poll() {
            Set<K> keys = manager.cachedKeys();
            if (keys.isEmpty()) {
                lastSeen.clear();
                return;
            }
            Map<K, Long> current = manager.repository().versions(keys).join();
            for (K key : keys) {
                Long dbVersion = current.get(key);
                if (dbVersion == null) {
                    // Cached here, but gone from the backend -> deleted by another instance.
                    manager.evict(key);
                    lastSeen.remove(key);
                    continue;
                }
                Long seen = lastSeen.get(key);
                if (seen == null) {
                    // First observation: assume the cache is as fresh as the backend. Only a later
                    // version bump triggers an invalidation.
                    lastSeen.put(key, dbVersion);
                } else if (dbVersion > seen) {
                    manager.invalidate(key);
                    lastSeen.put(key, dbVersion);
                }
            }
            // Drop tracking for keys no longer cached (evicted/expired) so the map can't grow unbounded.
            lastSeen.keySet().retainAll(keys);
        }
    }
}
