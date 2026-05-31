package br.com.finalcraft.evernifecore.storage.transfer;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryStorage;
import br.com.finalcraft.evernifecore.storage.modules.mongo.MongoConfig;
import br.com.finalcraft.evernifecore.storage.modules.mongo.MongoStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.PoolTuning;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.evernifecore.storage.query.Query;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import br.com.finalcraft.evernifecore.storage.codec.JacksonYamlCodec;
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileConfig;
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileStorage;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-backend transfer tests using Docker-dependent backends: MariaDB, PostgreSQL, MongoDB.
 *
 * <p>Every test in this class is <em>conditionally skipped</em> when the required backend(s)
 * are not reachable. The {@link #probeBackends()} method sets three boolean flags before any
 * test runs; each test then calls {@code assumeTrue(BACKEND_AVAILABLE)} and is marked
 * {@code SKIPPED} (not FAILED) if the flag is {@code false}. Running without Docker is safe.
 *
 * <h3>Backend pairs covered</h3>
 * <pre>
 * MariaDB    -> PostgreSQL  (UC2: cross-SQL dialect migration)
 * PostgreSQL -> MariaDB     (reverse dialect switch)
 * MariaDB    -> MongoDB     (SQL rows to Mongo documents)
 * MongoDB    -> MariaDB     (UC3: Mongo documents to SQL rows)
 * PostgreSQL -> MongoDB     (SQL to NoSQL)
 * MongoDB    -> PostgreSQL  (NoSQL to typed SQL)
 * H2         -> MariaDB     (embedded to production graduation)
 * H2         -> PostgreSQL  (embedded to PostgreSQL graduation)
 * MariaDB    -> H2          (production to embedded snapshot)
 * MongoDB    -> H2          (NoSQL to embedded SQL)
 * </pre>
 *
 * <h3>Running</h3>
 * <pre>
 * docker compose up -d mariadb postgres mongo
 * ./gradlew :common-storage:test --tests "*StorageTransferDockerBackendTest"
 * </pre>
 *
 * <h3>Configuration (env vars or -Dkey=value; see DotEnvTestUtil)</h3>
 * <pre>
 * MARIADB_USER / MARIADB_PASS / MARIADB_HOST / MARIADB_PORT / MARIADB_URL
 * POSTGRES_USER / POSTGRES_PASS / POSTGRES_HOST / POSTGRES_PORT / POSTGRES_URL
 * MONGO_USER / MONGO_PASS / MONGO_HOST / MONGO_PORT / MONGO_URL
 * </pre>
 */
@DisplayName("StorageTransfer - Docker backend pairs (MariaDB, PostgreSQL, MongoDB)")
@TestMethodOrder(MethodOrderer.DisplayName.class)
class StorageTransferDockerBackendTest {

    // ------------------------------------------------------------------
    //  Descriptors
    // ------------------------------------------------------------------

    static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR = AbstractStorageTest.DESCRIPTOR;

    /** Second collection used in the multi-descriptor test. */
    static final EntityDescriptor<UUID, TestPlayer> ECONOMY_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("economy")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    /**
     * YAML-codec variant of {@link #DESCRIPTOR}: identical collection name and index hints,
     * but serialises entities to/from YAML ({@code .yml} files in LocalFileStorage).
     *
     * <p>Used in cross-codec transfer tests:
     * <ul>
     *   <li>{@code descriptor(YAML_DESCRIPTOR, DESCRIPTOR)} - source reads .yml, target writes JSON/SQL</li>
     *   <li>{@code descriptor(DESCRIPTOR, YAML_DESCRIPTOR)} - source reads JSON/SQL, target writes .yml</li>
     * </ul>
     */
    static final EntityDescriptor<UUID, TestPlayer> YAML_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("test_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonYamlCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .index(IndexHint.string("world"))
            .index(IndexHint.bool("active"))
            .index(IndexHint.timestamp("createdAt"))
            .build();

    static final UUID UUID_A = AbstractStorageTest.UUID_ALICE;
    static final UUID UUID_B = AbstractStorageTest.UUID_BOB;
    static final UUID UUID_C = AbstractStorageTest.UUID_CAROL;

    // ------------------------------------------------------------------
    //  Connection coordinates (env vars with fallback defaults)
    // ------------------------------------------------------------------

    static final String MARIADB_USER   = DotEnvTestUtil.getOrDefault("MARIADB_USER", "root");
    static final String MARIADB_PASS   = DotEnvTestUtil.getOrDefault("MARIADB_PASS", "root");
    static final String MARIADB_HOST   = DotEnvTestUtil.getOrDefault("MARIADB_HOST", "localhost");
    static final String MARIADB_PORT   = DotEnvTestUtil.getOrDefault("MARIADB_PORT", "3306");
    static final String MARIADB_SERVER = DotEnvTestUtil.getOrDefault("MARIADB_URL",
        "jdbc:mysql://" + MARIADB_HOST + ":" + MARIADB_PORT);

    static final String PG_USER   = DotEnvTestUtil.getOrDefault("POSTGRES_USER", "root");
    static final String PG_PASS   = DotEnvTestUtil.getOrDefault("POSTGRES_PASS", "root");
    static final String PG_HOST   = DotEnvTestUtil.getOrDefault("POSTGRES_HOST", "localhost");
    static final String PG_PORT   = DotEnvTestUtil.getOrDefault("POSTGRES_PORT", "5432");
    static final String PG_SERVER = DotEnvTestUtil.getOrDefault("POSTGRES_URL",
        "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT);

    static final String MONGO_USER = DotEnvTestUtil.getOrDefault("MONGO_USER", "root");
    static final String MONGO_PASS = DotEnvTestUtil.getOrDefault("MONGO_PASS", "root");
    static final String MONGO_HOST = DotEnvTestUtil.getOrDefault("MONGO_HOST", "localhost");
    static final String MONGO_PORT = DotEnvTestUtil.getOrDefault("MONGO_PORT", "27017");
    static final String MONGO_URL  = DotEnvTestUtil.getOrDefault("MONGO_URL",
        "mongodb://" + MONGO_USER + ":" + MONGO_PASS + "@" + MONGO_HOST + ":" + MONGO_PORT);

    // ------------------------------------------------------------------
    //  Availability flags (false until probeBackends() confirms reachability)
    // ------------------------------------------------------------------

    static boolean MARIADB_AVAILABLE  = false;
    static boolean POSTGRES_AVAILABLE = false;
    static boolean MONGO_AVAILABLE    = false;

    // ------------------------------------------------------------------
    //  Database tracking for cleanup
    // ------------------------------------------------------------------

    static final Set<String> createdMariaDBs = ConcurrentHashMap.newKeySet();
    static final Set<String> createdPgDBs    = ConcurrentHashMap.newKeySet();
    static final Set<String> createdMongoDbs = ConcurrentHashMap.newKeySet();

    /** Monotonic counter appended to every database name for uniqueness within this JVM run. */
    static final AtomicInteger DB_SEQ = new AtomicInteger(0);

    // ------------------------------------------------------------------
    //  Pool tuning (fast teardown: small pool drains quickly on close())
    // ------------------------------------------------------------------

    static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    // ------------------------------------------------------------------
    //  Backend probes
    // ------------------------------------------------------------------

    @BeforeAll
    static void probeBackends() {
        // MariaDB
        try {
            Properties p = new Properties();
            p.setProperty("user",           MARIADB_USER);
            p.setProperty("password",       MARIADB_PASS);
            p.setProperty("connectTimeout", "3000");
            p.setProperty("socketTimeout",  "3000");
            try (Connection c = DriverManager.getConnection(MARIADB_SERVER + "/", p);
                 Statement  s = c.createStatement()) {
                s.execute("SELECT 1");
                MARIADB_AVAILABLE = true;
            }
        } catch (Exception e) {
            System.out.println("[DockerBackendTest] MariaDB not available: " + e.getMessage());
        }

        // PostgreSQL (driver expects seconds, not millis, for connectTimeout)
        try {
            Properties p = new Properties();
            p.setProperty("user",           PG_USER);
            p.setProperty("password",       PG_PASS);
            p.setProperty("connectTimeout", "3");
            p.setProperty("socketTimeout",  "3");
            try (Connection c = DriverManager.getConnection(PG_SERVER + "/postgres", p);
                 Statement  s = c.createStatement()) {
                s.execute("SELECT 1");
                POSTGRES_AVAILABLE = true;
            }
        } catch (Exception e) {
            System.out.println("[DockerBackendTest] PostgreSQL not available: " + e.getMessage());
        }

        // MongoDB
        try {
            MongoClientSettings probe = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO_URL))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
            try (MongoClient client = MongoClients.create(probe)) {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
                MONGO_AVAILABLE = true;
            }
        } catch (Exception e) {
            System.out.println("[DockerBackendTest] MongoDB not available: " + e.getMessage());
        }

        System.out.printf("[DockerBackendTest] Backends: MariaDB=%b  PostgreSQL=%b  MongoDB=%b%n",
            MARIADB_AVAILABLE, POSTGRES_AVAILABLE, MONGO_AVAILABLE);
    }

    @AfterAll
    static void cleanup() {
        if (MARIADB_AVAILABLE && !createdMariaDBs.isEmpty()) {
            try (Connection c = DriverManager.getConnection(MARIADB_SERVER + "/", MARIADB_USER, MARIADB_PASS);
                 Statement  s = c.createStatement()) {
                for (String db : createdMariaDBs) {
                    try { s.execute("DROP DATABASE IF EXISTS `" + db + "`"); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        if (POSTGRES_AVAILABLE && !createdPgDBs.isEmpty()) {
            try (Connection c = DriverManager.getConnection(PG_SERVER + "/postgres", PG_USER, PG_PASS);
                 Statement  s = c.createStatement()) {
                for (String db : createdPgDBs) {
                    // WITH (FORCE) terminates lingering connections (PostgreSQL 13+)
                    try { s.execute("DROP DATABASE IF EXISTS \"" + db + "\" WITH (FORCE)"); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        if (MONGO_AVAILABLE && !createdMongoDbs.isEmpty()) {
            try (MongoClient client = MongoClients.create(MONGO_URL)) {
                for (String db : createdMongoDbs) {
                    try { client.getDatabase(db).drop(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    //  Storage factories
    // ------------------------------------------------------------------

    /** Creates and inits a fresh MariaDB database named enc_tx_my_NNNN_<label>. */
    SqlStorage mariaDb(String label) {
        String dbName = "enc_tx_my_" + String.format("%04d", DB_SEQ.incrementAndGet()) + "_" + label;
        try (Connection c = DriverManager.getConnection(MARIADB_SERVER + "/", MARIADB_USER, MARIADB_PASS);
             Statement  s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + dbName + "`");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE: " + dbName, e);
        }
        createdMariaDBs.add(dbName);
        SqlStorage storage = new SqlStorage(new SqlConfig(
            MARIADB_SERVER + "/" + dbName, MARIADB_USER, MARIADB_PASS, TEST_POOL, Optional.empty()));
        storage.init().join();
        return storage;
    }

    /** Creates and inits a fresh PostgreSQL database named enc_tx_pg_NNNN_<label>. */
    PostgreSqlStorage postgreSql(String label) {
        String dbName = "enc_tx_pg_" + String.format("%04d", DB_SEQ.incrementAndGet()) + "_" + label;
        try (Connection c = DriverManager.getConnection(PG_SERVER + "/postgres", PG_USER, PG_PASS);
             Statement  s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE: " + dbName, e);
        }
        createdPgDBs.add(dbName);
        PostgreSqlStorage storage = new PostgreSqlStorage(new SqlConfig(
            PG_SERVER + "/" + dbName, PG_USER, PG_PASS, TEST_POOL, Optional.empty()));
        storage.init().join();
        return storage;
    }

    /** Creates and inits a fresh MongoDB database named enc_tx_mg_NNNN_<label>. */
    MongoStorage mongo(String label) {
        String dbName = "enc_tx_mg_" + String.format("%04d", DB_SEQ.incrementAndGet()) + "_" + label;
        createdMongoDbs.add(dbName);
        MongoStorage storage = new MongoStorage(new MongoConfig(MONGO_URL, dbName));
        storage.init().join();
        return storage;
    }

    /** Creates and inits an in-memory H2 database (always available, no Docker). */
    H2SqlStorage h2(String label) {
        String url = "jdbc:h2:mem:tx_" + label + "_" + DB_SEQ.incrementAndGet()
            + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        H2SqlStorage storage = new H2SqlStorage(new SqlConfig(url, "sa", "", TEST_POOL, Optional.empty()));
        storage.init().join();
        return storage;
    }

    InMemoryStorage inMemory() {
        InMemoryStorage s = new InMemoryStorage();
        s.init().join();
        return s;
    }

    // ------------------------------------------------------------------
    //  Test helpers
    // ------------------------------------------------------------------

    TestPlayer alice() { return new TestPlayer(UUID_A, "Alice", 100); }
    TestPlayer bob()   { return new TestPlayer(UUID_B, "Bob",    50); }
    TestPlayer carol() { return new TestPlayer(UUID_C, "Carol", 200); }

    List<TestPlayer> threeEntities() { return Arrays.asList(alice(), bob(), carol()); }

    void seed(Storage src) {
        src.repository(DESCRIPTOR).saveAll(threeEntities()).join();
    }

    TransferReport doTransfer(Storage src, Storage tgt) {
        return StorageTransfer.builder()
            .from(src).to(tgt)
            .descriptor(DESCRIPTOR)
            .failIfTargetCollectionNotEmpty(false)
            .verifyCounts(true)
            .build()
            .execute().join();
    }

    void assertAllEntitiesInTarget(Storage tgt) {
        Repository<UUID, TestPlayer> repo = tgt.repository(DESCRIPTOR);
        assertEquals(3L, repo.count().join(), "All 3 entities must be in target");
        assertEquals(alice(), repo.find(UUID_A).join().orElseThrow(AssertionError::new));
        assertEquals(bob(),   repo.find(UUID_B).join().orElseThrow(AssertionError::new));
        assertEquals(carol(), repo.find(UUID_C).join().orElseThrow(AssertionError::new));
    }

    void closeQuietly(Storage... storages) {
        for (Storage s : storages) {
            if (s != null) try { s.close().join(); } catch (Exception ignored) {}
        }
    }

    // ==================================================================
    //  Top-level tests
    // ==================================================================

    // ------------------------------------------------------------------
    //  MariaDB -> PostgreSQL  (UC2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MariaDB -> PostgreSQL] UC2: cross-SQL dialect - findBy(name) works in PG after transfer")
    void mariaDb_to_postgreSql() {
        assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        SqlStorage        src = mariaDb("m2p_s");
        PostgreSqlStorage tgt = postgreSql("m2p_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "UC2 transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // PG uses double-quote identifiers; findBy must work after transfer from backtick dialect
            List<TestPlayer> byName = tgt.repository(DESCRIPTOR).findBy("name", "Alice").join();
            assertEquals(1, byName.size(), "findBy(name) must work in PostgreSQL target");
            assertEquals(alice(), byName.get(0));

            // Source MariaDB intact - transfer copies, not moves
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source MariaDB must be unmodified after transfer");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  PostgreSQL -> MariaDB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[PostgreSQL -> MariaDB] reverse dialect switch - score range query works in MariaDB")
    void postgreSql_to_mariaDb() {
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");
        assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");

        PostgreSqlStorage src = postgreSql("p2m_s");
        SqlStorage        tgt = mariaDb("p2m_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "PG->MariaDB transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // MariaDB uses backtick identifiers and ON DUPLICATE KEY UPDATE;
            // range query must work after entities arrive from PG
            List<TestPlayer> inRange = tgt.repository(DESCRIPTOR)
                .query(Query.range("score", 50, 100)).join();
            assertEquals(2, inRange.size(), "Bob (50) and Alice (100) must be in range [50,100]");

            // Source PG intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source PostgreSQL must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  MariaDB -> MongoDB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MariaDB -> MongoDB] SQL rows land as Mongo documents - all 3 retrievable by key")
    void mariaDb_to_mongo() {
        assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");
        assumeTrue(MONGO_AVAILABLE,   "MongoDB not available");

        SqlStorage   src = mariaDb("m2mg_s");
        MongoStorage tgt = mongo("m2mg_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "MariaDB->Mongo transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // CollectionStats sanity
            CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
            assertEquals(3L, stats.sourceCount());
            assertEquals(0L, stats.targetCountBefore(), "Mongo target was empty before transfer");
            assertEquals(3L, stats.targetCountAfter());

            // Source MariaDB intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source MariaDB must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  MongoDB -> MariaDB  (UC3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MongoDB -> MariaDB] UC3: Mongo documents land as SQL rows, findBy(name) works")
    void mongo_to_mariaDb() {
        assumeTrue(MONGO_AVAILABLE,   "MongoDB not available");
        assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

        MongoStorage src = mongo("mg2m_s");
        SqlStorage   tgt = mariaDb("mg2m_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "Mongo->MariaDB transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // Verify indexed SQL query works after receiving documents from Mongo
            List<TestPlayer> found = tgt.repository(DESCRIPTOR).findBy("name", "Alice").join();
            assertEquals(1, found.size(), "findBy(name) must work in MariaDB after transfer from Mongo");
            assertEquals(alice(), found.get(0));

            // Source MongoDB intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source MongoDB must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  PostgreSQL -> MongoDB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[PostgreSQL -> MongoDB] PG rows to Mongo documents - source PG intact, stats correct")
    void postgreSql_to_mongo() {
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");
        assumeTrue(MONGO_AVAILABLE,    "MongoDB not available");

        PostgreSqlStorage src = postgreSql("p2mg_s");
        MongoStorage      tgt = mongo("p2mg_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "PG->Mongo transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // CollectionStats: targetCountBefore=0 since Mongo DB was freshly created
            CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
            assertEquals(3L, stats.sourceCount());
            assertEquals(0L, stats.targetCountBefore());
            assertEquals(3L, stats.entitiesWritten());

            // Source PG intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source PostgreSQL must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  MongoDB -> PostgreSQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MongoDB -> PostgreSQL] Mongo documents to PG rows - score range query works in PG")
    void mongo_to_postgreSql() {
        assumeTrue(MONGO_AVAILABLE,    "MongoDB not available");
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        MongoStorage      src = mongo("mg2p_s");
        PostgreSqlStorage tgt = postgreSql("mg2p_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "Mongo->PG transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // PG double-quote identifiers; range query must work after Mongo source
            List<TestPlayer> highScores = tgt.repository(DESCRIPTOR)
                .query(Query.range("score", 100, 300)).join();
            assertEquals(2, highScores.size(), "Alice (100) and Carol (200) must be in range [100,300]");

            // Source MongoDB intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source MongoDB must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  H2 -> MariaDB  (embedded to production graduation)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[H2 -> MariaDB] embedded to production graduation - CollectionStats verified, source H2 intact")
    void h2_to_mariaDb() {
        assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

        H2SqlStorage src = h2("h2m_s");
        SqlStorage   tgt = mariaDb("h2m_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "H2->MariaDB transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
            assertEquals(3L, stats.sourceCount());
            assertEquals(0L, stats.targetCountBefore());
            assertEquals(3L, stats.targetCountAfter());
            assertEquals(3L, stats.entitiesWritten());

            // Source H2 intact
            assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
                "Source H2 must be unmodified");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  H2 -> PostgreSQL  (embedded to production graduation)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[H2 -> PostgreSQL] embedded to PostgreSQL graduation - findBy(name) works in PG")
    void h2_to_postgreSql() {
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        H2SqlStorage      src = h2("h2pg_s");
        PostgreSqlStorage tgt = postgreSql("h2pg_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "H2->PG transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // Verify PG's ON CONFLICT upsert dialect was used (no duplicate rows)
            assertEquals(3L, tgt.repository(DESCRIPTOR).count().join(),
                "PG target must have exactly 3 entities - upsert must not duplicate");

            // findBy works in the PG target
            List<TestPlayer> carol = tgt.repository(DESCRIPTOR).findBy("name", "Carol").join();
            assertEquals(1, carol.size());
            assertEquals(200, carol.get(0).getScore());
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  MariaDB -> H2  (production to embedded snapshot)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MariaDB -> H2] production to embedded snapshot - applyTargetMigrations no-op on H2")
    void mariaDb_to_h2() {
        assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

        SqlStorage   src = mariaDb("m2h2_s");
        H2SqlStorage tgt = h2("m2h2_t");
        try {
            seed(src);
            // applyTargetMigrations=true (default): H2SqlStorage IS SchemaAware;
            // with no registered migrations, migrate() is a no-op - should not throw
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "MariaDB->H2 transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // H2 indexed query after transfer from MariaDB
            List<TestPlayer> byScore = tgt.repository(DESCRIPTOR)
                .query(Query.eq("score", 200)).join();
            assertEquals(1, byScore.size(), "Carol (score=200) must be findable in H2");
            assertEquals(carol(), byScore.get(0));
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  MongoDB -> H2  (NoSQL to embedded SQL)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[MongoDB -> H2] Mongo documents to H2 SQL rows - exact score query works")
    void mongo_to_h2() {
        assumeTrue(MONGO_AVAILABLE, "MongoDB not available");

        MongoStorage src = mongo("mg2h2_s");
        H2SqlStorage tgt = h2("mg2h2_t");
        try {
            seed(src);
            TransferReport report = doTransfer(src, tgt);

            assertTrue(report.success(), "Mongo->H2 transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllEntitiesInTarget(tgt);

            // H2 exact-score query after receiving Mongo documents
            List<TestPlayer> bobByScore = tgt.repository(DESCRIPTOR)
                .query(Query.eq("score", 50)).join();
            assertEquals(1, bobByScore.size(), "Bob (score=50) must be findable by exact score in H2");
            assertEquals(bob(), bobByScore.get(0));
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  Codec fidelity: full TestPlayer survives MariaDB -> PostgreSQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[Codec fidelity] Full TestPlayer (all fields, special chars) survives MariaDB -> PostgreSQL")
    void codecFidelity_mariaDb_to_postgreSql() {
        assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        TestPlayer full = new TestPlayer(
            UUID_A, "Alice Com Acento & Espaço", Integer.MAX_VALUE,
            "world_nether", true, System.currentTimeMillis()
        );

        SqlStorage        src = mariaDb("cf_m2p_s");
        PostgreSqlStorage tgt = postgreSql("cf_m2p_t");
        try {
            src.repository(DESCRIPTOR).save(full).join();
            TransferReport report = doTransfer(src, tgt);
            assertTrue(report.success(), "Codec fidelity transfer failed: " + report.errors());

            TestPlayer found = tgt.repository(DESCRIPTOR).find(UUID_A).join()
                .orElseThrow(() -> new AssertionError("Entity missing in PG after MariaDB transfer"));

            assertEquals(full.getUuid(),      found.getUuid(),      "uuid");
            assertEquals(full.getName(),      found.getName(),      "name (special chars + accents)");
            assertEquals(full.getScore(),     found.getScore(),     "score (Integer.MAX_VALUE)");
            assertEquals(full.getWorld(),     found.getWorld(),     "world");
            assertEquals(full.isActive(),     found.isActive(),     "active");
            assertEquals(full.getCreatedAt(), found.getCreatedAt(), "createdAt (millis precision)");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  Codec fidelity: full TestPlayer survives MongoDB -> PostgreSQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[Codec fidelity] Full TestPlayer (XML/quotes, negative score) survives MongoDB -> PostgreSQL")
    void codecFidelity_mongo_to_postgreSql() {
        assumeTrue(MONGO_AVAILABLE,    "MongoDB not available");
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        TestPlayer full = new TestPlayer(
            UUID_B, "Bob <script> & \"quoted\"", -999,
            "world_the_end", false, 1_700_000_000_000L
        );

        MongoStorage      src = mongo("cf_mg2p_s");
        PostgreSqlStorage tgt = postgreSql("cf_mg2p_t");
        try {
            src.repository(DESCRIPTOR).save(full).join();
            TransferReport report = doTransfer(src, tgt);
            assertTrue(report.success(), "Mongo->PG codec fidelity transfer failed: " + report.errors());

            TestPlayer found = tgt.repository(DESCRIPTOR).find(UUID_B).join()
                .orElseThrow(() -> new AssertionError("Entity missing in PG after Mongo transfer"));

            assertEquals(full.getUuid(),      found.getUuid(),      "uuid");
            assertEquals(full.getName(),      found.getName(),      "name (XML/quote chars)");
            assertEquals(full.getScore(),     found.getScore(),     "score (negative)");
            assertEquals(full.getWorld(),     found.getWorld(),     "world");
            assertEquals(full.isActive(),     found.isActive(),     "active (false)");
            assertEquals(full.getCreatedAt(), found.getCreatedAt(), "createdAt (fixed epoch)");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  Multi-descriptor: MariaDB -> PostgreSQL (2 independent collections)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[Multi-descriptor] MariaDB -> PostgreSQL: players + economy collections transferred independently")
    void multiDescriptor_mariaDb_to_postgreSql() {
        assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");
        assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

        SqlStorage        src = mariaDb("md_m2p_s");
        PostgreSqlStorage tgt = postgreSql("md_m2p_t");
        try {
            // Seed 2 entities in players, 1 entity in economy
            src.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();
            src.repository(ECONOMY_DESCRIPTOR).save(carol()).join();

            TransferReport report = StorageTransfer.builder()
                .from(src).to(tgt)
                .descriptor(DESCRIPTOR)
                .descriptor(ECONOMY_DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "Multi-descriptor transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities(), "Total must be players(2) + economy(1) = 3");
            assertEquals(2, report.collections().size(), "Two CollectionStats entries");

            // Both PG tables populated correctly
            assertEquals(2L, tgt.repository(DESCRIPTOR).count().join(),
                "players table must have 2 entities in PG");
            assertEquals(1L, tgt.repository(ECONOMY_DESCRIPTOR).count().join(),
                "economy table must have 1 entity in PG");

            // Carol is in economy, NOT in players
            assertFalse(tgt.repository(DESCRIPTOR).find(UUID_C).join().isPresent(),
                "Carol must NOT appear in the players table");
            assertTrue(tgt.repository(ECONOMY_DESCRIPTOR).find(UUID_C).join().isPresent(),
                "Carol must appear in the economy table");

            // CollectionStats order preserved (registration order: players then economy)
            List<String> keys = new ArrayList<>(report.collections().keySet());
            assertEquals(DESCRIPTOR.collection(),         keys.get(0), "First key = players");
            assertEquals(ECONOMY_DESCRIPTOR.collection(), keys.get(1), "Second key = economy");
        } finally {
            closeQuietly(src, tgt);
        }
    }

    // ------------------------------------------------------------------
    //  Chain: InMemory -> MariaDB -> MongoDB  (2-hop)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[Chain] 2-hop: InMemory -> MariaDB -> MongoDB - codec stable across two dialect changes")
    void chain_inMemory_mariaDb_mongo() {
        assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");
        assumeTrue(MONGO_AVAILABLE,   "MongoDB not available");

        InMemoryStorage mem = inMemory();
        SqlStorage      my  = mariaDb("chain_my");
        MongoStorage    mg  = mongo("chain_mg");
        try {
            // Seed InMemory with all 3 entities
            mem.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            // Hop 1: InMemory -> MariaDB
            TransferReport hop1 = doTransfer(mem, my);
            assertTrue(hop1.success(), "Hop 1 (Mem->MariaDB) failed: " + hop1.errors());
            assertEquals(3L, my.repository(DESCRIPTOR).count().join(),
                "MariaDB must have 3 entities after hop 1");

            // Hop 2: MariaDB -> MongoDB (different storage type, different codec path)
            TransferReport hop2 = doTransfer(my, mg);
            assertTrue(hop2.success(), "Hop 2 (MariaDB->Mongo) failed: " + hop2.errors());
            assertEquals(3L, mg.repository(DESCRIPTOR).count().join(),
                "MongoDB must have 3 entities after hop 2");

            // Final destination: all entities present and correct after 2 hops
            assertAllEntitiesInTarget(mg);

            // Deep field check on Carol in MongoDB (highest score, most likely to lose precision)
            TestPlayer carolInMongo = mg.repository(DESCRIPTOR).find(UUID_C).join()
                .orElseThrow(() -> new AssertionError("Carol missing in MongoDB after 2 hops"));
            assertEquals("Carol",  carolInMongo.getName(),  "name after 2 hops");
            assertEquals(200,      carolInMongo.getScore(), "score after 2 hops");
            assertEquals(UUID_C,   carolInMongo.getUuid(),  "uuid after 2 hops");

            // MariaDB intermediate must also remain intact (transfer copies, not moves)
            assertEquals(3L, my.repository(DESCRIPTOR).count().join(),
                "MariaDB intermediate must remain intact after hop 2");
        } finally {
            closeQuietly(mem, my, mg);
        }
    }

    // ==================================================================
    //  LocalFile variants: JSON and YAML codecs
    // ==================================================================

    /**
     * Tests that exercise {@link LocalFileStorage} as source or target using both
     * {@link JacksonJsonCodec} ({@code .json} files) and {@link JacksonYamlCodec} ({@code .yml} files).
     *
     * <p>The cross-codec transfers use the two-descriptor form:
     * <pre>
     * descriptor(YAML_DESCRIPTOR, DESCRIPTOR)  - source reads .yml, target writes JSON/SQL blobs
     * descriptor(DESCRIPTOR, YAML_DESCRIPTOR)  - source reads JSON/SQL blobs, target writes .yml
     * </pre>
     *
     * <p>Docker-free tests (JSON<->YAML and YAML->H2) always run.
     * Tests involving MariaDB, PostgreSQL, or MongoDB call {@code assumeTrue} and are
     * skipped gracefully if the backend is not reachable.
     */
    @Nested
    @DisplayName("LocalFile variants: JSON and YAML codecs with Docker and embedded backends")
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    class LocalFileVariants {

        @TempDir
        Path localFileDir;

        // Closed in @AfterEach; assigned at the start of each test so closeAll() is safe.
        Storage src, tgt;

        @AfterEach
        void closeAll() {
            if (src != null) try { src.close().join(); } catch (Exception ignored) {}
            if (tgt != null) try { tgt.close().join(); } catch (Exception ignored) {}
        }

        // ------------------------------------------------------------------
        //  Factories (delegate to outer class for SQL/Mongo)
        // ------------------------------------------------------------------

        LocalFileStorage localFile(String subDir) {
            Path dir = localFileDir.resolve(subDir);
            dir.toFile().mkdirs();
            LocalFileStorage s = new LocalFileStorage(new LocalFileConfig(dir));
            s.init().join();
            return s;
        }

        // ------------------------------------------------------------------
        //  Helpers
        // ------------------------------------------------------------------

        /** Count files ending with {@code ext} inside {@code baseDir/subDir/<collection>/}. */
        long countFiles(String subDir, String ext) {
            Path collDir = localFileDir.resolve(subDir).resolve(DESCRIPTOR.collection());
            try {
                if (!Files.isDirectory(collDir)) return 0L;
                return Files.list(collDir).filter(p -> p.toString().endsWith(ext)).count();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Assert all 3 standard entities are present in {@code target} via {@code desc}. */
        void assertAllIn(Storage target, EntityDescriptor<UUID, TestPlayer> desc) {
            Repository<UUID, TestPlayer> repo = target.repository(desc);
            assertEquals(3L, repo.count().join(), "All 3 entities must be in target");
            assertEquals(alice(), repo.find(UUID_A).join().orElseThrow(AssertionError::new));
            assertEquals(bob(),   repo.find(UUID_B).join().orElseThrow(AssertionError::new));
            assertEquals(carol(), repo.find(UUID_C).join().orElseThrow(AssertionError::new));
        }

        // ====================================================================
        //  Cross-codec LocalFile transfers  (no Docker required)
        // ====================================================================

        @Test
        @DisplayName("[LocalFile JSON -> LocalFile YAML] cross-codec: .json source, .yml target created on disk")
        void localFileJson_to_localFileYaml() {
            LocalFileStorage srcStorage = localFile("json_src");
            LocalFileStorage tgtStorage = localFile("yaml_tgt");
            src = srcStorage;
            tgt = tgtStorage;

            srcStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            // descriptor(DESCRIPTOR, YAML_DESCRIPTOR): source reads .json, target writes .yml
            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(tgtStorage)
                .descriptor(DESCRIPTOR, YAML_DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "JSON->YAML transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());

            // Target must have .yml files (YAML codec's fileExtension())
            assertEquals(3L, countFiles("yaml_tgt", ".yml"),
                "Target must have 3 .yml files (JacksonYamlCodec writes .yml)");
            assertEquals(0L, countFiles("yaml_tgt", ".json"),
                "Target must have NO .json files");

            // Source .json files must remain intact (transfer copies, not moves)
            assertEquals(3L, countFiles("json_src", ".json"),
                "Source .json files must remain after transfer");

            // Entities readable back from YAML target
            assertAllIn(tgtStorage, YAML_DESCRIPTOR);
        }

        @Test
        @DisplayName("[LocalFile YAML -> LocalFile JSON] cross-codec: .yml source, .json target created on disk")
        void localFileYaml_to_localFileJson() {
            LocalFileStorage srcStorage = localFile("yaml_src");
            LocalFileStorage tgtStorage = localFile("json_tgt");
            src = srcStorage;
            tgt = tgtStorage;

            // Seed source as .yml files
            srcStorage.repository(YAML_DESCRIPTOR).saveAll(threeEntities()).join();

            // descriptor(YAML_DESCRIPTOR, DESCRIPTOR): source reads .yml, target writes .json
            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(tgtStorage)
                .descriptor(YAML_DESCRIPTOR, DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "YAML->JSON transfer must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());

            // Target must have .json files
            assertEquals(3L, countFiles("json_tgt", ".json"),
                "Target must have 3 .json files (JacksonJsonCodec writes .json)");
            assertEquals(0L, countFiles("json_tgt", ".yml"),
                "Target must have NO .yml files");

            // Source .yml files must remain intact
            assertEquals(3L, countFiles("yaml_src", ".yml"),
                "Source .yml files must remain after transfer");

            // Entities readable back from JSON target
            assertAllIn(tgtStorage, DESCRIPTOR);
        }

        @Test
        @DisplayName("[LocalFile YAML -> H2] YAML files to embedded SQL (no Docker needed)")
        void localFileYaml_to_h2() {
            LocalFileStorage srcStorage = localFile("yaml_h2_src");
            H2SqlStorage     h2Storage  = h2("lf_yaml_h2");
            src = srcStorage;
            tgt = h2Storage;

            // Seed source as .yml files
            srcStorage.repository(YAML_DESCRIPTOR).saveAll(threeEntities()).join();

            // source reads .yml -> Java objects -> H2 stores JSON blobs via DESCRIPTOR
            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(h2Storage)
                .descriptor(YAML_DESCRIPTOR, DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile YAML->H2 must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(h2Storage, DESCRIPTOR);

            // Indexed query works in H2 after receiving YAML-encoded source data
            List<TestPlayer> byName = h2Storage.repository(DESCRIPTOR).findBy("name", "Bob").join();
            assertEquals(1, byName.size(), "Bob must be findable by name in H2 after YAML->SQL transfer");
            assertEquals(bob(), byName.get(0));

            // Source .yml files still on disk
            assertEquals(3L, countFiles("yaml_h2_src", ".yml"),
                "Source .yml files must remain after YAML->H2 transfer");
        }

        // ====================================================================
        //  LocalFile (JSON) -> Docker backends
        // ====================================================================

        @Test
        @DisplayName("[LocalFile JSON -> MariaDB] JSON files migrated to production SQL - source intact")
        void localFileJson_to_mariaDb() {
            assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

            LocalFileStorage srcStorage   = localFile("json_my_src");
            SqlStorage       mariaStorage = mariaDb("lf_json_m");
            src = srcStorage;
            tgt = mariaStorage;

            srcStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(mariaStorage)
                .descriptor(DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile JSON->MariaDB must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(mariaStorage, DESCRIPTOR);

            // Source .json files still on disk after migration to production SQL
            assertEquals(3L, countFiles("json_my_src", ".json"),
                "Source .json files must remain after migration to MariaDB");
        }

        @Test
        @DisplayName("[LocalFile JSON -> PostgreSQL] JSON files migrated to PostgreSQL - findBy works in PG")
        void localFileJson_to_postgreSql() {
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

            LocalFileStorage  srcStorage = localFile("json_pg_src");
            PostgreSqlStorage pgStorage  = postgreSql("lf_json_pg");
            src = srcStorage;
            tgt = pgStorage;

            srcStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(pgStorage)
                .descriptor(DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile JSON->PG must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(pgStorage, DESCRIPTOR);

            // PG double-quote identifiers work after receiving LocalFile JSON source
            List<TestPlayer> carol = pgStorage.repository(DESCRIPTOR).findBy("name", "Carol").join();
            assertEquals(1, carol.size(), "Carol must be findable in PG by name after LocalFile JSON transfer");
            assertEquals(200, carol.get(0).getScore());
        }

        @Test
        @DisplayName("[LocalFile JSON -> MongoDB] JSON files migrated to MongoDB documents")
        void localFileJson_to_mongo() {
            assumeTrue(MONGO_AVAILABLE, "MongoDB not available");

            LocalFileStorage srcStorage   = localFile("json_mg_src");
            MongoStorage     mongoStorage = mongo("lf_json_mg");
            src = srcStorage;
            tgt = mongoStorage;

            srcStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(mongoStorage)
                .descriptor(DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile JSON->MongoDB must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(mongoStorage, DESCRIPTOR);

            // Source .json files remain on disk
            assertEquals(3L, countFiles("json_mg_src", ".json"),
                "Source .json files must remain after transfer to MongoDB");
        }

        // ====================================================================
        //  LocalFile (YAML) -> Docker backends  (cross-codec)
        // ====================================================================

        @Test
        @DisplayName("[LocalFile YAML -> MariaDB] YAML files to production SQL - cross-codec, .yml source intact")
        void localFileYaml_to_mariaDb() {
            assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

            LocalFileStorage srcStorage   = localFile("yaml_my_src");
            SqlStorage       mariaStorage = mariaDb("lf_yaml_m");
            src = srcStorage;
            tgt = mariaStorage;

            // Seed source as .yml files
            srcStorage.repository(YAML_DESCRIPTOR).saveAll(threeEntities()).join();

            // YAML_DESCRIPTOR -> DESCRIPTOR: source reads .yml, MariaDB stores JSON blobs
            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(mariaStorage)
                .descriptor(YAML_DESCRIPTOR, DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile YAML->MariaDB must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(mariaStorage, DESCRIPTOR);

            // Source .yml files remain on disk; no .json in source dir
            assertEquals(3L, countFiles("yaml_my_src", ".yml"),
                "Source .yml files must remain intact after cross-codec transfer");
            assertEquals(0L, countFiles("yaml_my_src", ".json"),
                "Source dir must not contain .json files (only .yml)");
        }

        @Test
        @DisplayName("[LocalFile YAML -> PostgreSQL] YAML files to PostgreSQL - cross-codec, score range query")
        void localFileYaml_to_postgreSql() {
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

            LocalFileStorage  srcStorage = localFile("yaml_pg_src");
            PostgreSqlStorage pgStorage  = postgreSql("lf_yaml_pg");
            src = srcStorage;
            tgt = pgStorage;

            srcStorage.repository(YAML_DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(pgStorage)
                .descriptor(YAML_DESCRIPTOR, DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile YAML->PG must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(pgStorage, DESCRIPTOR);

            // PG indexed query works after cross-codec transfer from YAML source
            List<TestPlayer> inRange = pgStorage.repository(DESCRIPTOR)
                .query(Query.range("score", 50, 100)).join();
            assertEquals(2, inRange.size(), "Bob (50) and Alice (100) must be queryable in PG");
        }

        @Test
        @DisplayName("[LocalFile YAML -> MongoDB] YAML files to MongoDB - cross-codec, source .yml intact")
        void localFileYaml_to_mongo() {
            assumeTrue(MONGO_AVAILABLE, "MongoDB not available");

            LocalFileStorage srcStorage   = localFile("yaml_mg_src");
            MongoStorage     mongoStorage = mongo("lf_yaml_mg");
            src = srcStorage;
            tgt = mongoStorage;

            srcStorage.repository(YAML_DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(srcStorage).to(mongoStorage)
                .descriptor(YAML_DESCRIPTOR, DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "LocalFile YAML->MongoDB must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());
            assertAllIn(mongoStorage, DESCRIPTOR);

            // Source .yml files remain on disk
            assertEquals(3L, countFiles("yaml_mg_src", ".yml"),
                "Source .yml files must remain after cross-codec transfer to MongoDB");
        }

        // ====================================================================
        //  Docker backends -> LocalFile (YAML)  [backup scenarios]
        // ====================================================================

        @Test
        @DisplayName("[MariaDB -> LocalFile YAML] SQL backup exported as .yml files - source MariaDB intact")
        void mariaDb_to_localFileYaml() {
            assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

            SqlStorage       mariaStorage = mariaDb("m_yaml_src");
            LocalFileStorage tgtStorage   = localFile("my_yaml_tgt");
            src = mariaStorage;
            tgt = tgtStorage;

            mariaStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            // DESCRIPTOR -> YAML_DESCRIPTOR: MariaDB JSON blobs decoded to Java, written as .yml
            TransferReport report = StorageTransfer.builder()
                .from(mariaStorage).to(tgtStorage)
                .descriptor(DESCRIPTOR, YAML_DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "MariaDB->LocalFile YAML must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());

            // Backup files are .yml, not .json
            assertEquals(3L, countFiles("my_yaml_tgt", ".yml"),
                "Must have 3 .yml backup files from MariaDB export");
            assertEquals(0L, countFiles("my_yaml_tgt", ".json"),
                "Must have NO .json files in YAML backup");

            // YAML backup is readable
            assertAllIn(tgtStorage, YAML_DESCRIPTOR);

            // Source MariaDB intact
            assertEquals(3L, mariaStorage.repository(DESCRIPTOR).count().join(),
                "Source MariaDB must be intact after backup transfer");
        }

        @Test
        @DisplayName("[PostgreSQL -> LocalFile YAML] PostgreSQL backup exported as .yml files")
        void postgreSql_to_localFileYaml() {
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

            PostgreSqlStorage pgStorage  = postgreSql("pg_yaml_src");
            LocalFileStorage  tgtStorage = localFile("pg_yaml_tgt");
            src = pgStorage;
            tgt = tgtStorage;

            pgStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(pgStorage).to(tgtStorage)
                .descriptor(DESCRIPTOR, YAML_DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "PG->LocalFile YAML must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());

            // .yml backup files on disk
            assertEquals(3L, countFiles("pg_yaml_tgt", ".yml"),
                "Must have 3 .yml backup files from PostgreSQL export");

            // Backup is fully readable as YAML
            assertAllIn(tgtStorage, YAML_DESCRIPTOR);

            // Source PG intact
            assertEquals(3L, pgStorage.repository(DESCRIPTOR).count().join(),
                "Source PostgreSQL must be intact after backup export");
        }

        @Test
        @DisplayName("[MongoDB -> LocalFile YAML] MongoDB backup exported as .yml files")
        void mongo_to_localFileYaml() {
            assumeTrue(MONGO_AVAILABLE, "MongoDB not available");

            MongoStorage     mongoStorage = mongo("mg_yaml_src");
            LocalFileStorage tgtStorage   = localFile("mg_yaml_tgt");
            src = mongoStorage;
            tgt = tgtStorage;

            mongoStorage.repository(DESCRIPTOR).saveAll(threeEntities()).join();

            TransferReport report = StorageTransfer.builder()
                .from(mongoStorage).to(tgtStorage)
                .descriptor(DESCRIPTOR, YAML_DESCRIPTOR)
                .failIfTargetCollectionNotEmpty(false)
                .verifyCounts(true)
                .build()
                .execute().join();

            assertTrue(report.success(), "MongoDB->LocalFile YAML must succeed: " + report.errors());
            assertEquals(3L, report.totalEntities());

            // .yml backup files on disk
            assertEquals(3L, countFiles("mg_yaml_tgt", ".yml"),
                "Must have 3 .yml backup files from MongoDB export");

            // Backup is fully readable as YAML
            assertAllIn(tgtStorage, YAML_DESCRIPTOR);

            // Source MongoDB intact
            assertEquals(3L, mongoStorage.repository(DESCRIPTOR).count().join(),
                "Source MongoDB must be intact after backup transfer");
        }
    }

    // ==================================================================
    //  Large dataset (50 entities, batchSize=20)
    // ==================================================================

    /**
     * Nested class for large-dataset transfer tests across Docker-dependent backend pairs.
     * Each test transfers 50 entities in batches of 20 (= 3 batches: 20+20+10),
     * spot-checking the first and last entity for correctness.
     *
     * <p>All tests call {@code assumeTrue} at the start and are safely skipped when
     * the required backend is not available.
     */
    @Nested
    @DisplayName("Large dataset (50 entities, batchSize=20) across Docker backends")
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    class LargeDataset {

        Storage src, tgt;

        @AfterEach
        void closeAll() {
            if (src != null) try { src.close().join(); } catch (Exception ignored) {}
            if (tgt != null) try { tgt.close().join(); } catch (Exception ignored) {}
        }

        List<TestPlayer> fifty() {
            List<TestPlayer> list = new ArrayList<>(50);
            for (int i = 0; i < 50; i++) {
                list.add(new TestPlayer(new UUID(2, i + 1), "Player_" + i, i * 10));
            }
            return list;
        }

        void assertFiftyInTarget(Storage target) {
            assertEquals(50L, target.repository(DESCRIPTOR).count().join(),
                "All 50 entities must be in target");

            // Spot-check first entity
            TestPlayer first = target.repository(DESCRIPTOR)
                .find(new UUID(2, 1)).join()
                .orElseThrow(() -> new AssertionError("Player_0 (index 0) missing"));
            assertEquals("Player_0", first.getName());
            assertEquals(0,          first.getScore());

            // Spot-check last entity
            TestPlayer last = target.repository(DESCRIPTOR)
                .find(new UUID(2, 50)).join()
                .orElseThrow(() -> new AssertionError("Player_49 (index 49) missing"));
            assertEquals("Player_49", last.getName());
            assertEquals(490,         last.getScore());
        }

        TransferReport doLargeTransfer(Storage source, Storage target) {
            return StorageTransfer.builder()
                .from(source).to(target)
                .descriptor(DESCRIPTOR)
                .batchSize(20)  // 3 batches: 20+20+10
                .verifyCounts(true)
                .build()
                .execute().join();
        }

        @Test
        @DisplayName("[MariaDB -> PostgreSQL] 50 entities, batchSize=20")
        void large_mariaDb_to_postgreSql() {
            assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");

            src = mariaDb("ld_m2p");
            tgt = postgreSql("ld_m2p");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);
        }

        @Test
        @DisplayName("[PostgreSQL -> MariaDB] 50 entities, batchSize=20")
        void large_postgreSql_to_mariaDb() {
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");
            assumeTrue(MARIADB_AVAILABLE,  "MariaDB not available");

            src = postgreSql("ld_p2m");
            tgt = mariaDb("ld_p2m");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);
        }

        @Test
        @DisplayName("[MariaDB -> MongoDB] 50 entities, batchSize=20")
        void large_mariaDb_to_mongo() {
            assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");
            assumeTrue(MONGO_AVAILABLE,   "MongoDB not available");

            src = mariaDb("ld_m2mg");
            tgt = mongo("ld_m2mg");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);
        }

        @Test
        @DisplayName("[MongoDB -> MariaDB] 50 entities, batchSize=20")
        void large_mongo_to_mariaDb() {
            assumeTrue(MONGO_AVAILABLE,   "MongoDB not available");
            assumeTrue(MARIADB_AVAILABLE, "MariaDB not available");

            src = mongo("ld_mg2m");
            tgt = mariaDb("ld_mg2m");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);
        }

        @Test
        @DisplayName("[PostgreSQL -> MongoDB] 50 entities, batchSize=20")
        void large_postgreSql_to_mongo() {
            assumeTrue(POSTGRES_AVAILABLE, "PostgreSQL not available");
            assumeTrue(MONGO_AVAILABLE,    "MongoDB not available");

            src = postgreSql("ld_p2mg");
            tgt = mongo("ld_p2mg");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);
        }

        @Test
        @DisplayName("[MongoDB -> H2] 50 entities, batchSize=20 (NoSQL to embedded SQL - no Docker for H2)")
        void large_mongo_to_h2() {
            assumeTrue(MONGO_AVAILABLE, "MongoDB not available");

            src = mongo("ld_mg2h2");
            tgt = h2("ld_mg2h2");
            src.repository(DESCRIPTOR).saveAll(fifty()).join();

            TransferReport report = doLargeTransfer(src, tgt);

            assertTrue(report.success(), report.errors().toString());
            assertEquals(50L, report.totalEntities());
            assertFiftyInTarget(tgt);

            // Extra: H2 score range query after transfer from MongoDB
            long highScoreCount = tgt.repository(DESCRIPTOR)
                .query(Query.range("score", 400, 490)).join().size();
            assertEquals(10L, highScoreCount,
                "Players with score 400-490 (Player_40..49) must be queryable in H2 after Mongo transfer");
        }
    }
}
