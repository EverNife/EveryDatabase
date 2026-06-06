package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Query;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Concrete test suite for the MariaDB storage backend ({@link SqlStorage}
 * with the default MySQL-compatible dialect: backtick identifiers, {@code MEDIUMTEXT},
 * {@code ON DUPLICATE KEY UPDATE}).
 *
 * <p>MariaDB is wire-compatible with MySQL - the same {@code mysql-connector-j} JDBC
 * driver and the same SQL dialect work for both, so this suite implicitly also covers
 * MySQL servers.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} (health, CRUD,
 * codec round-trip, PlayerDataRepository facade) and adds SQL-specific tests:
 * <ul>
 *   <li>Transaction commit / rollback semantics.</li>
 *   <li>Exception propagation from transactional work.</li>
 *   <li>Lifecycle idempotency (init/close twice).</li>
 *   <li>Health reporting before and after close.</li>
 *   <li>{@code ON DUPLICATE KEY UPDATE} upsert semantics.</li>
 * </ul>
 *
 * <h3>Running these tests</h3>
 * <p>A MariaDB 10.x+ (or MySQL 8.x) server must be reachable. If no server is available
 * the entire class is <em>skipped</em> automatically - the suite never fails due to a missing
 * server.
 *
 * <h3>Configuration - via env var or {@code -Dkey=value} (see {@link DotEnvTestUtil})</h3>
 * <pre>
 * MARIADB_USER  - default: root
 * MARIADB_PASS  - default: root
 * MARIADB_HOST  - default: localhost
 * MARIADB_PORT  - default: 39306
 * MARIADB_URL   - overrides host+port construction (e.g. jdbc:mysql://host:port).
 *                 Must NOT include a database name; the suite creates one per test.
 * </pre>
 *
 * <pre>
 * # Start MariaDB locally (matches the defaults above):
 * docker compose up -d mariadb
 *
 * # Then run:
 * ./gradlew :common-storage:test --tests "*MariaDbStorageTest"
 * </pre>
 *
 * <h3>Isolation</h3>
 * <p>Each test method gets its own database named {@code enc_NNN_my_<methodName>}, where
 * {@code NNN} is the run number shared by all tests in this execution (computed once in
 * {@link #assumeMariaDbAvailable()} by scanning existing {@code enc_*} databases).
 * All created databases are dropped in {@link #cleanupDatabases()}.
 */
@DisplayName("MariaDbStorage (requires MariaDB 10.x+ or MySQL 8.x)")
class MariaDbStorageTest extends AbstractStorageTest {

    // ------------------------------------------------------------------
    //  Connection coordinates - env vars with fallback defaults
    // ------------------------------------------------------------------

    static final String MARIADB_USER = DotEnvTestUtil.getOrDefault("MARIADB_USER", "root");
    static final String MARIADB_PASS = DotEnvTestUtil.getOrDefault("MARIADB_PASS", "root");
    static final String MARIADB_HOST = DotEnvTestUtil.getOrDefault("MARIADB_HOST", "localhost");
    static final String MARIADB_PORT = DotEnvTestUtil.getOrDefault("MARIADB_PORT", "39306");

    /**
     * Server URL WITHOUT a database component. Used both as a probe target and as the
     * admin URL where {@code CREATE DATABASE} / {@code DROP DATABASE} are issued.
     *
     * <p>Uses the {@code jdbc:mysql:} prefix because {@code mysql-connector-j} is the
     * driver in use and is wire-compatible with MariaDB.
     */
    static final String MARIADB_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "MARIADB_URL",
        "jdbc:mysql://" + MARIADB_HOST + ":" + MARIADB_PORT
    );

    /**
     * HikariCP tuning optimised for fast unit-test teardown. Small pool drains quickly
     * when {@code close()} is called, so the database can be dropped immediately after.
     */
    private static final PoolTuning TEST_POOL = new PoolTuning(
        1,                       // minIdle
        5,                       // maxSize
        Duration.ofSeconds(5),   // connectTimeout
        Duration.ofSeconds(30)   // idleTimeout
    );

    /**
     * When {@code false}, {@link #cleanupDatabases()} skips the drop so the test databases
     * can be inspected after the run. Set back to {@code true} once inspection is done.
     */
    static final boolean CLEAN_TEST_RESIDUALS = true;

    /**
     * Run number shared by all tests in this execution.
     * Computed once in {@link #assumeMariaDbAvailable()} by scanning existing {@code enc_*}
     * databases; every test in this run uses {@code enc_NNN_my_<methodName>}.
     */
    private static int runNumber = 1;

    /** Database names created during this test run - all dropped in {@link #cleanupDatabases()}. */
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    /** JDBC URL for the database created for the current test - used by schema-drift tests. */
    private String currentTestDbUrl;

    // ------------------------------------------------------------------
    //  Availability guard - skip the whole class if MariaDB is not up
    // ------------------------------------------------------------------

    @BeforeAll
    static void assumeMariaDbAvailable() {
        Properties props = new Properties();
        props.setProperty("user",            MARIADB_USER);
        props.setProperty("password",        MARIADB_PASS);
        props.setProperty("connectTimeout",  "3000"); // fail fast
        props.setProperty("socketTimeout",   "3000");

        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", props);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (Exception e) {
            assumeTrue(false,
                "MariaDB not available at " + MARIADB_SERVER_URL + " - skipping all MariaDbStorageTest. "
                + "Start a MariaDB 10.x+ or MySQL 8.x server on that address to run these tests. "
                + "Cause: " + e.getMessage());
        }

        // Determine run number: scan existing enc_* databases, use max+1.
        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            List<String> existing = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (name.startsWith("enc_")) existing.add(name);
            }
            runNumber = computeRunNumber(existing);
        } catch (SQLException ignored) {
            runNumber = 1; // safe fallback
        }
        System.out.println("[MariaDbStorageTest] Run number: " + runNumber
            + "  (databases will be prefixed enc_" + String.format("%03d", runNumber) + "_my_*)");
    }

    /**
     * Drop all test databases created during this run.
     * Skipped when {@link #CLEAN_TEST_RESIDUALS} is {@code false}.
     * Best-effort; never fails the build.
     */
    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[MariaDbStorageTest] CLEAN_TEST_RESIDUALS=false - keeping databases for inspection:");
            createdDbs.forEach(name -> System.out.println("  -> " + name));
            return;
        }
        if (createdDbs.isEmpty()) return;
        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement()) {
            for (String name : createdDbs) {
                try {
                    stmt.execute("DROP DATABASE IF EXISTS `" + name + "`");
                } catch (SQLException ignored) {
                    // best-effort: cleanup failure must not break the build
                }
            }
        } catch (SQLException ignored) {
            // best-effort: cleanup failure must not break the build
        }
    }

    // ------------------------------------------------------------------
    //  AbstractStorageTest contract
    // ------------------------------------------------------------------

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = buildDbName("my", runNumber, testMethodName);

        // CREATE DATABASE via a one-off admin connection so each test starts with a clean schema.
        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + dbName + "`");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE for test: " + dbName, e);
        }
        createdDbs.add(dbName);

        currentTestDbUrl = MARIADB_SERVER_URL + "/" + dbName;
        return new SqlStorage(new SqlConfig(currentTestDbUrl, MARIADB_USER, MARIADB_PASS, TEST_POOL, Optional.empty()));
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: TransactionalStorage capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("SqlStorage implements TransactionalStorage")
    void sqlStorage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            "SqlStorage must implement TransactionalStorage");
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: transaction commit
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("inTransaction() - saves inside scope are visible after commit")
    void inTransaction_commit_savesAreVisible() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenCompose(__ -> txRepo.save(bob()));
        }).join();

        assertTrue(repo.find(UUID_ALICE).join().isPresent(), "Alice should be visible after tx commit");
        assertTrue(repo.find(UUID_BOB).join().isPresent(),   "Bob should be visible after tx commit");
        assertEquals(2L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: real transaction rollback
    // ------------------------------------------------------------------

    @Test
    @Order(1011)
    @DisplayName("inTransaction() - scope.rollback() actually undoes writes")
    void inTransaction_rollback_actuallyUndoesWrites() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenRun(scope::rollback);
        }).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent(),
            "ROLLBACK must undo the save - Alice should not be visible");
        assertEquals(0L, repo.count().join(),
            "count() must be 0 after a rolled-back transaction");
    }

    @Test
    @Order(1012)
    @DisplayName("inTransaction() - rollback only affects the rolled-back transaction")
    void inTransaction_rollback_doesNotAffectCommittedData() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        // First tx: commits Alice
        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(alice())
        ).join();

        // Second tx: saves Bob then rolls back
        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(bob())
                .thenRun(scope::rollback);
        }).join();

        assertTrue(repo.find(UUID_ALICE).join().isPresent(), "Alice (committed) must survive");
        assertFalse(repo.find(UUID_BOB).join().isPresent(),  "Bob (rolled back) must not be visible");
        assertEquals(1L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: exception propagation from transactional work
    // ------------------------------------------------------------------

    @Test
    @Order(1013)
    @DisplayName("inTransaction() - work that throws propagates exception")
    void inTransaction_exceptionInWork_propagatesAndDoesNotCrash() {
        TransactionalStorage tx = (TransactionalStorage) storage;
        RuntimeException boom = new RuntimeException("intentional failure");

        CompletableFuture<Void> result = tx.inTransaction(scope -> {
            throw boom;
        });

        ExecutionException ex = assertThrows(ExecutionException.class, result::get);
        assertSame(boom, ex.getCause(), "Original exception should be the cause");
    }

    @Test
    @Order(1014)
    @DisplayName("inTransaction() - exception rolls back any writes made before the throw")
    void inTransaction_exceptionAfterSave_rollsBackWrite() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .<Void>thenApply(__ -> { throw new RuntimeException("forced failure"); });
        }).exceptionally(e -> null).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent(),
            "Exception in tx work must trigger rollback - Alice should not be visible");
    }

    @Test
    @Order(1015)
    @DisplayName("inTransaction() - saveAll() inside scope commits all entities")
    void inTransaction_saveAll_commitsAllEntities() {
        TransactionalStorage tx = (TransactionalStorage) storage;
        List<TestPlayer> players = Arrays.asList(alice(), bob(), carol());

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).saveAll(players)
        ).join();

        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertEquals(3, all.size());
        assertTrue(all.containsAll(players));
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: upsert semantics
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("save() inside a transaction upserts correctly (ON DUPLICATE KEY UPDATE)")
    void inTransaction_save_upsertIsCorrect() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(alice())
        ).join();

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(new TestPlayer(UUID_ALICE, "Alice", 999))
        ).join();

        TestPlayer found = repo.find(UUID_ALICE).join().orElseThrow(AssertionError::new);
        assertEquals(999, found.getScore(), "Score must reflect last upserted value");
        assertEquals(1L,  repo.count().join(), "count() must not grow after upsert");
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: lifecycle idempotency
    // ------------------------------------------------------------------

    @Test
    @Order(1030)
    @DisplayName("init() is idempotent - calling it twice does not throw")
    void init_calledTwice_isIdempotent() {
        assertDoesNotThrow(() -> storage.init().join(),
            "Second init() must not throw");
        assertTrue(storage.health().join().isConnected(),
            "Storage must remain healthy after double init()");
    }

    @Test
    @Order(1031)
    @DisplayName("close() is idempotent - calling it twice does not throw")
    void close_calledTwice_isIdempotent() {
        assertDoesNotThrow(() -> storage.close().join(), "First explicit close() must not throw");
        assertDoesNotThrow(() -> storage.close().join(), "Second close() must not throw");
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: health reporting
    // ------------------------------------------------------------------

    @Test
    @Order(1040)
    @DisplayName("health() after init() reports connected=true and a valid ping")
    void health_afterInit_isConnectedWithPing() {
        HealthStatus h = storage.health().join();
        assertTrue(h.isConnected(), "Storage must be connected after init()");
        assertTrue(h.pingMs() >= 0, "pingMs must be non-negative");
    }

    @Test
    @Order(1041)
    @DisplayName("health() after close() reports connected=false")
    void health_afterClose_isNotConnected() {
        storage.close().join();
        HealthStatus h = storage.health().join();
        assertFalse(h.isConnected(), "Storage must report DOWN after close()");
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: repository identity
    // ------------------------------------------------------------------

    @Test
    @Order(1050)
    @DisplayName("repository() called twice with same descriptor returns same instance")
    void repository_sameDescriptor_returnsSameInstance() {
        Repository<UUID, TestPlayer> r1 = storage.repository(DESCRIPTOR);
        Repository<UUID, TestPlayer> r2 = storage.repository(DESCRIPTOR);
        assertSame(r1, r2, "Repeated calls with same descriptor must return the same Repository instance");
    }

    @Test
    @Order(1051)
    @DisplayName("Two repositories with different collection names are independent")
    void twoRepositories_differentCollections_areIndependent() {
        EntityDescriptor<UUID, TestPlayer> altDescriptor =
            EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("other_players")
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build();

        Repository<UUID, TestPlayer> altRepo = storage.repository(altDescriptor);

        repo.save(alice()).join();

        assertFalse(altRepo.find(UUID_ALICE).join().isPresent(),
            "Entity saved in 'test_players' must not appear in 'other_players'");
        assertEquals(0L, altRepo.count().join());
        assertEquals(1L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  MariaDB-specific: schema drift (new IndexHint on existing table)
    // ------------------------------------------------------------------

    @Test
    @Order(1060)
    @DisplayName("ensureIndexColumn: new IndexHint added after table creation is automatically ALTERed in")
    void schemaEvolution_newIndexHint_columnAddedAutomatically() {
        // --- V1 descriptor: only "name" indexed ---
        EntityDescriptor<UUID, TestPlayer> v1 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        // Create the table via V1 and save Alice (score column does NOT exist yet).
        SqlStorage storageV1 = new SqlStorage(
            new SqlConfig(currentTestDbUrl, MARIADB_USER, MARIADB_PASS, TEST_POOL, Optional.empty()));
        storageV1.init().join();
        storageV1.repository(v1).save(alice()).join();
        storageV1.close().join();

        // --- V2 descriptor: "name" + "score" indexed (score is the new hint) ---
        EntityDescriptor<UUID, TestPlayer> v2 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))   // added vs. the V1 descriptor above
            .build();

        // Open a NEW storage on the SAME database with V2.
        // ensureIndexColumn() must add _idx_score without throwing.
        SqlStorage storageV2 = new SqlStorage(
            new SqlConfig(currentTestDbUrl, MARIADB_USER, MARIADB_PASS, TEST_POOL, Optional.empty()));
        storageV2.init().join();
        Repository<UUID, TestPlayer> repoV2 = assertDoesNotThrow(
            () -> storageV2.repository(v2),
            "repository() with a new IndexHint must not throw - ensureIndexColumn should ADD the column");

        // Alice exists with NULL in _idx_score (not yet re-saved) - but basic CRUD still works.
        assertTrue(repoV2.find(UUID_ALICE).join().isPresent(), "Alice must still be readable after ALTER");
        assertEquals(1L, repoV2.count().join());

        // Re-save Alice to backfill _idx_score, then query by score.
        repoV2.save(alice()).join();
        List<TestPlayer> found = repoV2.query(Query.eq("score", 100)).join();
        assertEquals(1, found.size(), "After re-save, Alice must be findable via the new score index");
        assertEquals(UUID_ALICE, found.get(0).getUuid());

        storageV2.close().join();
    }
}
