package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Query;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete test suite for the H2 storage backend ({@link H2SqlStorage}:
 * ANSI double-quote identifiers, {@code TEXT}, {@code MERGE INTO ... KEY (...) VALUES (?)}).
 *
 * <p>H2 runs in-process as an embedded database - no external server or Docker container
 * is required. Each test method gets its own named in-memory database for full isolation.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} (health, CRUD,
 * codec round-trip, PlayerDataRepository facade) and adds H2-specific tests:
 * <ul>
 *   <li>Transaction commit / rollback semantics (real SQL ROLLBACK via H2).</li>
 *   <li>Exception propagation from transactional work.</li>
 *   <li>Lifecycle idempotency (init/close twice).</li>
 *   <li>Health reporting before and after close.</li>
 *   <li>{@code MERGE INTO} upsert semantics.</li>
 *   <li>Schema drift - new {@link IndexHint} added after table creation is ALTERed in.</li>
 * </ul>
 *
 * <h3>Running these tests</h3>
 * <pre>
 * ./gradlew :common-storage:test --tests "*H2StorageTest"
 * </pre>
 *
 * <h3>Isolation</h3>
 * <p>Each test gets its own named H2 in-memory database ({@code mem:<methodName>}).
 * {@code DB_CLOSE_DELAY=-1} keeps the database alive while the HikariCP pool holds
 * connections; H2 discards the data automatically when the JVM exits.
 */
@DisplayName("H2Storage (embedded, no external server required)")
class H2StorageTest extends AbstractStorageTest {

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1,                       // minIdle
        5,                       // maxSize
        Duration.ofSeconds(5),   // connectTimeout
        Duration.ofSeconds(30)   // idleTimeout
    );

    /**
     * JDBC URL for the database created for the current test method.
     * Reused by the schema-drift subtest to open V1 and V2 on the same database.
     */
    private String currentTestDbUrl;

    @Override
    protected Storage createStorage(String testMethodName) {
        // Each test gets its own named in-memory database for full isolation.
        // DATABASE_TO_UPPER=FALSE preserves column/table names as declared.
        // DB_CLOSE_DELAY=-1 keeps the database alive for the duration of the pool.
        currentTestDbUrl = "jdbc:h2:mem:" + testMethodName
            + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        return new H2SqlStorage(new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL, Optional.empty()));
    }

    // ------------------------------------------------------------------
    //  H2-specific: TransactionalStorage capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("H2SqlStorage implements TransactionalStorage")
    void h2Storage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            "H2SqlStorage must implement TransactionalStorage");
    }

    // ------------------------------------------------------------------
    //  H2-specific: transaction commit
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
    //  H2-specific: real transaction rollback
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
            "H2 ROLLBACK must undo the save - Alice should not be visible");
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
    //  H2-specific: exception propagation from transactional work
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
    //  H2-specific: upsert semantics (MERGE INTO)
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("save() inside a transaction upserts correctly (MERGE INTO)")
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
    //  H2-specific: lifecycle idempotency
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
    //  H2-specific: health reporting
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
    //  H2-specific: repository identity
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
    //  H2-specific: schema drift (new IndexHint on existing table)
    // ------------------------------------------------------------------

    @Test
    @Order(1060)
    @DisplayName("ensureIndexColumn + auto-populate: new IndexHint is ALTERed in and backfilled from existing rows")
    void schemaEvolution_newIndexHint_columnAddedAndBackfilled() {
        // V1 and V2 share the same in-memory database as the main storage for this test method.
        // They use a distinct collection name ("schema_evolution") to avoid colliding with
        // the "test_players" table used by the inherited contract tests.

        // --- V1 descriptor: only "name" indexed ---
        EntityDescriptor<UUID, TestPlayer> v1 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        // Create the table via V1 and save Alice (score column does NOT exist yet).
        H2SqlStorage storageV1 = new H2SqlStorage(
            new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL, Optional.empty()));
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
        // ensureIndexColumn() must add _idx_score via ALTER TABLE without throwing.
        H2SqlStorage storageV2 = new H2SqlStorage(
            new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL, Optional.empty()));
        storageV2.init().join();
        Repository<UUID, TestPlayer> repoV2 = assertDoesNotThrow(
            () -> storageV2.repository(v2),
            "repository() with a new IndexHint must not throw - ensureIndexColumn should ADD the column");

        // Alice is still readable, and basic CRUD works.
        assertTrue(repoV2.find(UUID_ALICE).join().isPresent(), "Alice must still be readable after ALTER");
        assertEquals(1L, repoV2.count().join());

        // Auto-populate: opening V2 backfilled _idx_score for the pre-existing Alice row, so she is
        // findable via the brand-new score index WITHOUT being re-saved first.
        List<TestPlayer> found = repoV2.query(Query.eq("score", 100)).join();
        assertEquals(1, found.size(),
            "Auto-populate must backfill the new score index so Alice is found without a re-save");
        assertEquals(UUID_ALICE, found.get(0).getUuid());

        storageV2.close().join();
    }

    @Test
    @Order(1061)
    @DisplayName("enforcement: an IndexHint removed from the descriptor drops its _idx_ column and index")
    void schemaEnforcement_removedIndexHint_columnAndIndexDropped() throws Exception {
        // --- V1 descriptor: BOTH "name" and "score" indexed ---
        EntityDescriptor<UUID, TestPlayer> v1 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_enforcement")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .build();

        H2SqlStorage storageV1 = new H2SqlStorage(
            new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL, Optional.empty()));
        storageV1.init().join();
        storageV1.repository(v1).save(alice()).join();
        storageV1.close().join();

        assertTrue(indexColumnPresent("schema_enforcement", "_idx_score"),
            "Precondition: _idx_score must exist after V1 created the table");

        // --- V2 descriptor: only "name" indexed ("score" removed) ---
        EntityDescriptor<UUID, TestPlayer> v2 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_enforcement")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        H2SqlStorage storageV2 = new H2SqlStorage(
            new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL, Optional.empty()));
        storageV2.init().join();
        Repository<UUID, TestPlayer> repoV2 = storageV2.repository(v2);

        // Enforcement must have dropped the undeclared _idx_score column (its index goes with it).
        assertFalse(indexColumnPresent("schema_enforcement", "_idx_score"),
            "Enforcement must drop the _idx_score column that is no longer declared");

        // The still-declared name index keeps working and the data is intact.
        assertEquals(1L, repoV2.count().join());
        assertEquals(1, repoV2.query(Query.eq("name", "Alice")).join().size(),
            "The retained name index must still return Alice");

        storageV2.close().join();
    }

    /** Opens a direct JDBC connection to the current test database and reports whether a column exists. */
    private boolean indexColumnPresent(String table, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection(currentTestDbUrl, "sa", "")) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String tbl : new String[]{table, table.toUpperCase()}) {
                try (ResultSet rs = meta.getColumns(null, null, tbl, null)) {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                    }
                }
            }
            return false;
        }
    }
}
