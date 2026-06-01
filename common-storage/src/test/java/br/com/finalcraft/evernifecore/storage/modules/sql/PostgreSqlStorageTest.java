package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.sql.postgresql.PostgreSqlStorage;
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
 * Concrete test suite for the PostgreSQL storage backend ({@link PostgreSqlStorage}:
 * double-quote identifiers, {@code TEXT}, {@code INSERT ... ON CONFLICT DO UPDATE}).
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} (health, CRUD,
 * codec round-trip, PlayerDataRepository facade) and adds PostgreSQL-specific tests:
 * <ul>
 *   <li>Transaction commit / rollback semantics (real SQL ROLLBACK).</li>
 *   <li>Exception propagation from transactional work.</li>
 *   <li>Lifecycle idempotency (init/close twice).</li>
 *   <li>Health reporting before and after close.</li>
 *   <li>{@code ON CONFLICT DO UPDATE} upsert semantics.</li>
 * </ul>
 *
 * <h3>Running these tests</h3>
 * <p>A PostgreSQL 13+ server must be reachable. If no server is available the entire
 * class is <em>skipped</em> automatically - the suite never fails due to a missing server.
 *
 * <h3>Configuration - via env var or {@code -Dkey=value} (see {@link DotEnvTestUtil})</h3>
 * <pre>
 * POSTGRES_USER  - default: root
 * POSTGRES_PASS  - default: root
 * POSTGRES_HOST  - default: localhost
 * POSTGRES_PORT  - default: 39307
 * POSTGRES_URL   - overrides host+port construction (e.g. jdbc:postgresql://host:port).
 *                  Must NOT include a database name; the suite creates one per test.
 * </pre>
 *
 * <pre>
 * # Start PostgreSQL locally with auth (matches the defaults above):
 * docker run -d -p 39307:5432 -e POSTGRES_USER=root -e POSTGRES_PASSWORD=root postgres:16
 *
 * # Then run:
 * ./gradlew :common-tests:test --tests "*PostgreSqlStorageTest"
 * </pre>
 *
 * <h3>Isolation</h3>
 * <p>Each test method gets its own database named {@code enc_NNN_pg_<methodName>}, where
 * {@code NNN} is the run number shared by all tests in this execution (computed once in
 * {@link #assumePostgreSqlAvailable()} by scanning existing {@code enc_*} databases).
 * All created databases are dropped in {@link #cleanupDatabases()}.
 * {@code DROP DATABASE ... WITH (FORCE)} is used to evict any lingering connection.
 */
@DisplayName("PostgreSqlStorage (requires PostgreSQL 13+)")
class PostgreSqlStorageTest extends AbstractStorageTest {

    // ------------------------------------------------------------------
    //  Connection coordinates - env vars with fallback defaults
    // ------------------------------------------------------------------

    static final String PG_USER = DotEnvTestUtil.getOrDefault("POSTGRES_USER", "root");
    static final String PG_PASS = DotEnvTestUtil.getOrDefault("POSTGRES_PASS", "root");
    static final String PG_HOST = DotEnvTestUtil.getOrDefault("POSTGRES_HOST", "localhost");
    static final String PG_PORT = DotEnvTestUtil.getOrDefault("POSTGRES_PORT", "39307");

    /**
     * Server URL WITHOUT a database name. Used as a base for all connections; PostgreSQL
     * requires a database in every connection URL, so the admin operations target the
     * built-in {@code postgres} database (see {@link #adminUrl()}).
     */
    static final String PG_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "POSTGRES_URL",
        "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT
    );

    /** Built-in admin database used to run {@code CREATE DATABASE} / {@code DROP DATABASE}. */
    private static String adminUrl() { return PG_SERVER_URL + "/postgres"; }

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
     * Computed once in {@link #assumePostgreSqlAvailable()} by scanning existing {@code enc_*}
     * databases; every test in this run uses {@code enc_NNN_pg_<methodName>}.
     */
    private static int runNumber = 1;

    /** Database names created during this test run - all dropped in {@link #cleanupDatabases()}. */
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    /** JDBC URL for the database created for the current test - used by schema-drift tests. */
    private String currentTestDbUrl;

    // ------------------------------------------------------------------
    //  Availability guard - skip the whole class if PostgreSQL is not up
    // ------------------------------------------------------------------

    @BeforeAll
    static void assumePostgreSqlAvailable() {
        Properties props = new Properties();
        props.setProperty("user",            PG_USER);
        props.setProperty("password",        PG_PASS);
        props.setProperty("connectTimeout",  "3"); // PG driver expects SECONDS here, not millis
        props.setProperty("socketTimeout",   "3");

        try (Connection conn = DriverManager.getConnection(adminUrl(), props);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (Exception e) {
            assumeTrue(false,
                "PostgreSQL not available at " + adminUrl() + " - skipping all PostgreSqlStorageTest. "
                + "Start a PostgreSQL 13+ server on that address to run these tests. "
                + "Cause: " + e.getMessage());
        }

        // Determine run number: scan existing enc_* databases, use max+1.
        try (Connection conn = DriverManager.getConnection(adminUrl(), PG_USER, PG_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT datname FROM pg_database WHERE datname LIKE 'enc_%'")) {
            List<String> existing = new ArrayList<>();
            while (rs.next()) existing.add(rs.getString(1));
            runNumber = computeRunNumber(existing);
        } catch (SQLException ignored) {
            runNumber = 1; // safe fallback
        }
        System.out.println("[PostgreSqlStorageTest] Run number: " + runNumber
            + "  (databases will be prefixed enc_" + String.format("%03d", runNumber) + "_pg_*)");
    }

    /**
     * Drop all test databases created during this run.
     * Skipped when {@link #CLEAN_TEST_RESIDUALS} is {@code false}.
     * Best-effort; never fails the build.
     */
    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[PostgreSqlStorageTest] CLEAN_TEST_RESIDUALS=false - keeping databases for inspection:");
            createdDbs.forEach(name -> System.out.println("  -> " + name));
            return;
        }
        if (createdDbs.isEmpty()) return;
        try (Connection conn = DriverManager.getConnection(adminUrl(), PG_USER, PG_PASS);
             Statement stmt = conn.createStatement()) {
            for (String name : createdDbs) {
                try {
                    // WITH (FORCE) terminates lingering connections (PostgreSQL 13+).
                    stmt.execute("DROP DATABASE IF EXISTS \"" + name + "\" WITH (FORCE)");
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
        String dbName = buildDbName("pg", runNumber, testMethodName);

        // CREATE DATABASE via the admin connection (targets the built-in 'postgres' DB).
        try (Connection conn = DriverManager.getConnection(adminUrl(), PG_USER, PG_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE for test: " + dbName, e);
        }
        createdDbs.add(dbName);

        currentTestDbUrl = PG_SERVER_URL + "/" + dbName;
        return new PostgreSqlStorage(new SqlConfig(currentTestDbUrl, PG_USER, PG_PASS, TEST_POOL, Optional.empty()));
    }

    // ------------------------------------------------------------------
    //  PostgreSQL-specific: TransactionalStorage capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("PostgreSqlStorage implements TransactionalStorage")
    void postgreSqlStorage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            "PostgreSqlStorage must implement TransactionalStorage");
    }

    // ------------------------------------------------------------------
    //  PostgreSQL-specific: transaction commit
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
    //  PostgreSQL-specific: real transaction rollback
    // ------------------------------------------------------------------

    @Test
    @Order(1011)
    @DisplayName("inTransaction() - scope.rollback() actually undoes writes (real SQL rollback)")
    void inTransaction_rollback_actuallyUndoesWrites() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenRun(scope::rollback);
        }).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent(),
            "PostgreSQL ROLLBACK must undo the save - Alice should not be visible");
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
    //  PostgreSQL-specific: exception propagation from transactional work
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
    //  PostgreSQL-specific: upsert semantics
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("save() inside a transaction upserts correctly (ON CONFLICT DO UPDATE)")
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
    //  PostgreSQL-specific: lifecycle idempotency
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
    //  PostgreSQL-specific: health reporting
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
    //  PostgreSQL-specific: repository identity
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
    //  PostgreSQL-specific: schema drift (new IndexHint on existing table)
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
        PostgreSqlStorage storageV1 = new PostgreSqlStorage(
            new SqlConfig(currentTestDbUrl, PG_USER, PG_PASS, TEST_POOL, Optional.empty()));
        storageV1.init().join();
        storageV1.repository(v1).save(alice()).join();
        storageV1.close().join();

        // --- V2 descriptor: "name" + "score" indexed (score is the new hint) ---
        EntityDescriptor<UUID, TestPlayer> v2 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))   // new!
            .build();

        // Open a NEW storage on the SAME database with V2.
        // ensureIndexColumn() must add _idx_score via ALTER TABLE without throwing.
        PostgreSqlStorage storageV2 = new PostgreSqlStorage(
            new SqlConfig(currentTestDbUrl, PG_USER, PG_PASS, TEST_POOL, Optional.empty()));
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
