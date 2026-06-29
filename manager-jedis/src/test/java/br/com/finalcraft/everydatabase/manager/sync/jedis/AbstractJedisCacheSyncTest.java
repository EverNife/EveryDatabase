package br.com.finalcraft.everydatabase.manager.sync.jedis;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncMode;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncObserver;
import br.com.finalcraft.everydatabase.manager.sync.CacheSync;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Backend-agnostic contract for the Jedis pub/sub cache-sync transport, mirroring the core's abstract
 * suites: a writer and a reader (two storages on the <b>same</b> data backend) each wired through
 * {@link CacheSync#via(br.com.finalcraft.everydatabase.manager.sync.CacheSyncTransport)} to a
 * {@link JedisCacheSyncTransport} on the <b>same</b> channel. A write/delete on the writer must
 * invalidate/evict the reader's cache via the pub/sub round-trip.
 *
 * <p>The data backend is a <b>shared in-memory H2</b> ({@code DB_CLOSE_DELAY=-1}, same URL for both
 * storages in this JVM), so the only external dependency is the Jedis server - and it shows the
 * transport propagating <em>updates</em> even on H2, which the version-polling fallback cannot do.
 * The entity ({@link Quest}) is reused from {@code :manager}'s test classes (shared via the {@code tests}
 * configuration), not recreated here.
 *
 * <p>Concrete subclasses point at a specific server (Valkey / Redis) via {@link #port()} /
 * {@link #serverName()}; each self-skips when its server is unreachable.
 */
public abstract class AbstractJedisCacheSyncTest {

    /** The Jedis server port this subclass connects to. */
    protected abstract int port();

    /** Human name of the server (for skip messages), e.g. "Valkey"/"Redis". */
    protected abstract String serverName();

    /** Logs transport connectivity/mode changes during tests, so runs surface cache-sync events. */
    private static final CacheSyncObserver LOGGING_OBSERVER = new CacheSyncObserver() {
        @Override public void onTransportConnected() { System.out.println("[cache-sync] transport connected (push)"); }
        @Override public void onTransportDisconnected() { System.out.println("[cache-sync] transport disconnected -> fallback polling"); }
        @Override public void onModeChange(CacheSyncMode mode) { System.out.println("[cache-sync] mode -> " + mode); }
    };

    /** Self-skip when the server is unreachable, so the build never fails just because it is down. */
    protected void assumeServerAvailable() {
        Assumptions.assumeTrue(reachable(port()),
                serverName() + " not reachable on localhost:" + port() + " - run 'docker compose up -d "
                        + serverName().toLowerCase() + "'. Skipping the transport contract.");
    }

    protected Storage writerStorage;
    protected Storage readerStorage;
    protected CachingManager<UUID, Quest> writer;
    protected CachingManager<UUID, Quest> reader;
    protected JedisCacheSyncTransport writerTransport;
    protected JedisCacheSyncTransport readerTransport;
    protected CacheSync writerSync;
    protected CacheSync readerSync;

    private String channel;

    @BeforeEach
    void setUp() {
        assumeServerAvailable();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String collection = "quests_" + suffix;
        channel = "everydatabase:test:" + suffix;                       // unique per test: no cross-test bleed
        String dbUrl = "jdbc:h2:mem:jedissync_" + suffix + ";DB_CLOSE_DELAY=-1";

        writerStorage = Storages.createH2(new SqlConfig(dbUrl, "", ""));
        writerStorage.init().join();
        readerStorage = Storages.createH2(new SqlConfig(dbUrl, "", ""));   // same in-memory DB (shared by URL)
        readerStorage.init().join();

        RefRegistry writerReg = new RefRegistry();
        RefRegistry readerReg = new RefRegistry();
        writer = writerReg.manager(descriptor(writerReg, collection), writerStorage, CachePolicy.always());
        reader = readerReg.manager(descriptor(readerReg, collection), readerStorage, CachePolicy.always());

        writerTransport = JedisCacheSyncTransport.connect(config());
        readerTransport = JedisCacheSyncTransport.connect(config());
        writerSync = CacheSync.attach(writerStorage).via(writerTransport).observe(LOGGING_OBSERVER).bind(writer).start();
        readerSync = CacheSync.attach(readerStorage).via(readerTransport).observe(LOGGING_OBSERVER).bind(reader).start();
    }

    @AfterEach
    void tearDown() {
        closeQuietly(writerSync);
        closeQuietly(readerSync);
        closeQuietly(writerTransport);
        closeQuietly(readerTransport);
        if (writerStorage != null) {
            try { writerStorage.close().join(); } catch (Exception ignored) { }
        }
        if (readerStorage != null) {
            try { readerStorage.close().join(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------

    @Test
    void a_remote_update_invalidates_the_local_cache() {
        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "v0", 0L)).join();
        reader.resolve(id).join();
        assertTrue(reader.peek(id).isPresent(), "reader cached v0");

        // Re-write a distinct title each round until the reader observes it: once the pub/sub round-trip
        // is live the foreign-origin SAVE invalidates the reader, whose next resolve reloads from H2.
        AtomicInteger n = new AtomicInteger();
        awaitUntil(() -> {
            writer.saveAndCache(new Quest(id, "v-" + n.incrementAndGet(), 0L)).join();
            Quest seen = reader.resolve(id).join().orElse(null);
            return seen != null && seen.getTitle().startsWith("v-");
        }, Duration.ofSeconds(20));

        assertTrue(reader.resolve(id).join().orElseThrow(AssertionError::new).getTitle().startsWith("v-"),
                "reader reloaded the remote update");

        // Observability: the writer published signals and the reader applied at least one.
        assertTrue(writerTransport.publishCount() > 0, "writer published cache-sync signals");
        assertTrue(readerSync.stats().signalsApplied() > 0, "reader applied at least one signal");
        assertTrue(readerTransport.connected(), "reader transport reports connected");
    }

    @Test
    void a_remote_delete_evicts_the_local_cache() {
        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "present", 0L)).join();
        reader.resolve(id).join();
        assertTrue(reader.peek(id).isPresent(), "reader cached the entity");

        establishFeedLive(id);                          // a one-shot delete could race subscriber startup

        writer.deleteAndEvict(id).join();               // deletes on the shared backend + publishes DELETE

        // peek (cache-only, no reload) can only empty via the sync evicting it.
        awaitUntil(() -> !reader.peek(id).isPresent(), Duration.ofSeconds(15));
        assertFalse(reader.peek(id).isPresent(), "reader evicted the deleted entity via the transport");
    }

    @Test
    void a_writer_does_not_invalidate_its_own_cache_via_the_echo() {
        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "v0", 0L)).join();
        reader.resolve(id).join();

        // The writer's updates reach the reader (signal delivered, round-trip live)...
        AtomicInteger n = new AtomicInteger();
        awaitUntil(() -> {
            writer.saveAndCache(new Quest(id, "v-" + n.incrementAndGet(), 0L)).join();
            Quest seen = reader.resolve(id).join().orElse(null);
            return seen != null && seen.getTitle().startsWith("v-");
        }, Duration.ofSeconds(20));

        // ...yet the writer's OWN cache is never invalidated by its own echo (own-origin skip + write-through).
        assertTrue(writer.peek(id).isPresent(), "writer's own cache survives its own echo");
        assertTrue(writer.peek(id).get().getTitle().startsWith("v-"), "writer still serves its write-through value");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private JedisCacheSyncConfig config() {
        return new JedisCacheSyncConfig("localhost", port()).withChannel(channel);
    }

    private static EntityDescriptor<UUID, Quest> descriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Quest.class)
                .collection(collection)
                .keyExtractor(Quest::getId)
                .codec(registry.codec(Quest.class))
                .build();
    }

    /**
     * Push pub/sub only delivers signals that occur after the subscriber is live; a one-shot delete can
     * race that startup. Re-write the key until the reader reacts (proving the round-trip is live),
     * leaving it freshly cached for the delete that follows.
     */
    private void establishFeedLive(UUID id) {
        AtomicInteger n = new AtomicInteger();
        awaitUntil(() -> {
            writer.saveAndCache(new Quest(id, "live-" + n.incrementAndGet(), 0L)).join();
            Quest seen = reader.resolve(id).join().orElse(null);
            return seen != null && seen.getTitle().startsWith("live-");
        }, Duration.ofSeconds(20));
    }

    protected static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("condition not met within " + timeout);
    }

    /** Whether a Jedis server answers PONG on {@code localhost:port}. */
    protected static boolean reachable(int port) {
        try (Jedis jedis = new Jedis("localhost", port)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }
}
