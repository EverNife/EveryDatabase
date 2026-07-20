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
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoStorage;
import br.com.finalcraft.everydatabase.modules.sql.PoolTuning;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The cache-sync analogue of {@code MultiBackendRefExampleTest}: a writer instance and a reader instance,
 * each holding a {@link Guild} cache on <b>several different backends at once</b>, all kept fresh through
 * <b>one shared pub/sub transport</b> via {@link CacheSync#auto()} + {@code via(...)}. A write on any
 * backend's writer propagates to that backend's reader over the single channel - showing the transport is
 * backend-agnostic and routes purely by collection (V2-2).
 *
 * <p>Backend-agnostic over the <b>transport</b> server, mirroring {@link AbstractJedisCacheSyncTest}: a
 * concrete subclass picks Valkey or Redis via {@link #transportPort()}/{@link #transportName()} and the
 * suite runs against each. H2 (a shared in-memory DB) always participates as a data backend; MariaDB /
 * PostgreSQL / MongoDB join when reachable. {@link Guild} is reused from {@code :manager}'s test classes.
 */
public abstract class AbstractMultiBackendJedisCacheSyncTest {

    /** The Jedis transport server port this subclass connects to (Valkey 39309 / Redis 39310). */
    protected abstract int transportPort();

    /** Human name of the transport server (for skip messages), e.g. "Valkey"/"Redis". */
    protected abstract String transportName();

    /** Logs the cache-sync mode/connectivity during the run (audit output). */
    private static final CacheSyncObserver LOGGING_OBSERVER = new CacheSyncObserver() {
        @Override public void onTransportConnected() { System.out.println("[cache-sync] transport connected (push)"); }
        @Override public void onTransportDisconnected() { System.out.println("[cache-sync] transport disconnected -> fallback polling"); }
        @Override public void onModeChange(CacheSyncMode mode) { System.out.println("[cache-sync] mode -> " + mode); }
    };

    @Test
    void one_transport_syncs_caches_across_every_available_backend() {
        Assumptions.assumeTrue(reachable(transportPort()),
                transportName() + " not reachable on " + transportPort() + " - run 'docker compose up -d "
                        + transportName().toLowerCase() + "'. Skipping.");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String channel = "everydatabase:mbex:" + suffix;

        List<Backend> backends = availableBackends(suffix);
        List<Storage> storages = new ArrayList<>();
        List<CachingManager<UUID, Guild>> writers = new ArrayList<>();
        List<CachingManager<UUID, Guild>> readers = new ArrayList<>();

        JedisCacheSyncTransport writerTransport = JedisCacheSyncTransport.connect(
                new JedisCacheSyncConfig("localhost", transportPort()).withChannel(channel));
        JedisCacheSyncTransport readerTransport = JedisCacheSyncTransport.connect(
                new JedisCacheSyncConfig("localhost", transportPort()).withChannel(channel));
        CacheSync writerSync = CacheSync.auto().via(writerTransport).observe(LOGGING_OBSERVER);
        CacheSync readerSync = CacheSync.auto().via(readerTransport).observe(LOGGING_OBSERVER);

        try {
            for (Backend backend : backends) {
                Storage writerStorage = backend.open.get();
                Storage readerStorage = backend.open.get();
                writerStorage.init().join();
                readerStorage.init().join();
                storages.add(writerStorage);
                storages.add(readerStorage);

                String collection = backend.name + "_guilds_" + suffix;   // unique per backend (no collision)
                writers.add(manager(writerStorage, collection));
                readers.add(manager(readerStorage, collection));
            }
            writers.forEach(writerSync::bind);
            readers.forEach(readerSync::bind);
            writerSync.start();
            readerSync.start();

            for (int i = 0; i < backends.size(); i++) {
                CachingManager<UUID, Guild> writer = writers.get(i);
                CachingManager<UUID, Guild> reader = readers.get(i);
                String backendName = backends.get(i).name;

                UUID id = UUID.randomUUID();
                writer.saveAndCache(new Guild(id, "v0")).join();
                reader.resolve(id).join();
                assertTrue(reader.peek(id).isPresent(), backendName + ": reader cached v0");

                AtomicInteger n = new AtomicInteger();
                awaitUntil(() -> {
                    writer.saveAndCache(new Guild(id, "v-" + n.incrementAndGet())).join();
                    Guild seen = reader.resolve(id).join().orElse(null);
                    return seen != null && seen.getName().startsWith("v-");
                }, Duration.ofSeconds(20), backendName);

                assertTrue(reader.resolve(id).join().orElseThrow(AssertionError::new).getName().startsWith("v-"),
                        backendName + ": reader observed the remote update via the shared transport");
            }
        } finally {
            closeQuietly(writerSync);
            closeQuietly(readerSync);
            closeQuietly(writerTransport);
            closeQuietly(readerTransport);
            for (Storage s : storages) {
                try { s.close().join(); } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Backend discovery
    // ------------------------------------------------------------------

    private static final class Backend {
        final String name;
        final Supplier<Storage> open;
        Backend(String name, Supplier<Storage> open) { this.name = name; this.open = open; }
    }

    private static List<Backend> availableBackends(String suffix) {
        List<Backend> backends = new ArrayList<>();

        // Every backend here is machine-local (an H2 mem DB, or a server on localhost), which under the
        // RECOMMENDED default does not publish. This test simulates two instances sharing one physical
        // store, so each backend's writer/reader pair gets one explicit shared identity (distinct per
        // backend, equal within a pair): that declares the store shareable, restores publishing, and
        // keeps both ends on the same per-identity channel so the transport still routes.
        String h2Url = "jdbc:h2:mem:mbex_" + suffix + ";DB_CLOSE_DELAY=-1";
        String h2Id = "mbex-h2-" + suffix;
        backends.add(new Backend("h2", () -> Storages.createH2(new SqlConfig(h2Url, "", "", PoolTuning.defaults(), h2Id))));

        String mariaServer = "jdbc:mysql://localhost:39306";
        if (ensureSqlDatabase(mariaServer + "/", "everydatabase_mbex")) {
            String mariaId = "mbex-mariadb-" + suffix;
            backends.add(new Backend("mariadb",
                    () -> Storages.createSQL(new SqlConfig(mariaServer + "/everydatabase_mbex", "root", "root", PoolTuning.defaults(), mariaId))));
        }

        String pgServer = "jdbc:postgresql://localhost:39307";
        if (ensurePostgresDatabase(pgServer, "everydatabase_mbex")) {
            String pgId = "mbex-postgres-" + suffix;
            backends.add(new Backend("postgres",
                    () -> Storages.createPostgreSQL(new SqlConfig(pgServer + "/everydatabase_mbex", "root", "root", PoolTuning.defaults(), pgId))));
        }

        String mongoUrl = "mongodb://localhost:39308/?directConnection=true";
        if (mongoReachable(mongoUrl)) {
            String mongoId = "mbex-mongo-" + suffix;
            backends.add(new Backend("mongo",
                    () -> new MongoStorage(new MongoConfig(mongoUrl, "everydatabase_mbex", java.util.Optional.empty(), mongoId))));
        }

        return backends;
    }

    private CachingManager<UUID, Guild> manager(Storage storage, String collection) {
        // A fresh registry per manager: RefRegistry registers by entity type, and every backend here caches
        // the same Guild type - separate registries keep them from colliding (refs aren't resolved here).
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection(collection)
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
        return new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);
    }

    // ------------------------------------------------------------------
    //  Reachability helpers
    // ------------------------------------------------------------------

    private static boolean reachable(int redisPort) {
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean ensureSqlDatabase(String serverUrl, String db) {
        DriverManager.setLoginTimeout(3);
        try (Connection c = DriverManager.getConnection(serverUrl, "root", "root");
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + db + "`");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean ensurePostgresDatabase(String server, String db) {
        DriverManager.setLoginTimeout(3);
        try (Connection c = DriverManager.getConnection(server + "/postgres", "root", "root");
             Statement st = c.createStatement()) {
            boolean exists;
            try (java.sql.ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                st.execute("CREATE DATABASE \"" + db + "\"");
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean mongoReachable(String url) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(url))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout, String label) {
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
        fail(label + ": condition not met within " + timeout);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }
}
