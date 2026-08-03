package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.VersionedTestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.AbstractTransactionalStorageTest;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.testutil.DotEnvTestUtil;
import br.com.finalcraft.everydatabase.testutil.ThrowawayDatabaseSupport;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete test suite for {@link MongoStorage}.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} (health, CRUD,
 * codec round-trip, PlayerDataRepository facade) and adds Mongo-specific tests:
 * <ul>
 *   <li>Order 1001 - {@link TransactionalStorage} capability assertion.</li>
 *   <li>Order 1002 - {@link SchemaAwareStorage} capability assertion.</li>
 *   <li>Order 1010+ - {@link SchemaAwareStorage} migration lifecycle tests.</li>
 * </ul>
 *
 * <h3>Running these tests</h3>
 * <p>A MongoDB 4.2+ server must be reachable (configurable via env vars or system property
 * below). If no server is available the entire class is <em>skipped</em> automatically -
 * the suite never fails due to a missing server.
 *
 * <h3>Configuration - via env var or {@code -Dkey=value} (see {@link DotEnvTestUtil})</h3>
 * <pre>
 * MONGO_USER  - default: root
 * MONGO_PASS  - default: root
 * MONGO_HOST  - default: localhost
 * MONGO_PORT  - default: 39308
 * MONGO_URL   - overrides all of the above (e.g. mongodb://user:pass@host:port)
 * </pre>
 *
 * <pre>
 * # Start MongoDB locally with auth (matches the defaults above):
 * docker run -d -p 39308:27017 -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:7
 *
 * # Then run:
 * ./gradlew :common-storage:test --tests "*MongoStorageTest"
 * </pre>
 *
 * <h3>Isolation</h3>
 * <p>Each test method gets its own database named {@code enc_NNN_mg_<methodName>}, where
 * {@code NNN} is the run number shared by all tests in this execution (computed once in
 * {@link #assumeMongoAvailable()} by scanning existing {@code enc_*} databases).
 * All created databases are dropped in {@link #cleanupDatabases()}.
 *
 * <h3>Transactions</h3>
 * <p>Multi-document transactions in MongoDB require a replica set (MongoDB 4.0+). The
 * docker-compose test server is a 1-node replica set, so this suite extends
 * {@link AbstractTransactionalStorageTest} and runs the full shared {@code [tx]}
 * commit/rollback/lifecycle contract like every SQL dialect.
 */
@DisplayName("MongoStorage (requires MongoDB 4.2+ replica set)")
class MongoStorageTest extends AbstractTransactionalStorageTest {

    // ------------------------------------------------------------------
    //  Connection coordinates - env vars with fallback defaults
    // ------------------------------------------------------------------

    static final String MONGO_USER = DotEnvTestUtil.getOrDefault("MONGO_USER", "");
    static final String MONGO_PASS = DotEnvTestUtil.getOrDefault("MONGO_PASS", "");
    static final String MONGO_HOST = DotEnvTestUtil.getOrDefault("MONGO_HOST", "localhost");
    static final String MONGO_PORT = DotEnvTestUtil.getOrDefault("MONGO_PORT", "39308");
    // The compose Mongo is an open 1-node replica set: no credentials, and directConnection=true
    // because the node advertises an in-container host the test machine cannot route to.
    static final String MONGO_URL  = "mongodb://"
            + (MONGO_USER.isEmpty() ? "" : MONGO_USER + ":" + MONGO_PASS + "@")
            + MONGO_HOST + ":" + MONGO_PORT + "/?directConnection=true";

    private static final ThrowawayDatabaseSupport DBS = ThrowawayDatabaseSupport.mongo(MONGO_URL, "mg");

    /** Database of the storage under test, so the extra storage can attach to the same data. */
    private String currentTestDbName;

    @BeforeAll
    static void assumeMongoAvailable() {
        DBS.assumeAvailable("MongoStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MongoStorageTest");
    }

    // ------------------------------------------------------------------
    //  AbstractStorageTest / AbstractTransactionalStorageTest contract
    // ------------------------------------------------------------------

    @Override
    protected Storage createStorage(String testMethodName) {
        currentTestDbName = DBS.newDatabase(testMethodName);
        return new MongoStorage(new MongoConfig(MONGO_URL, currentTestDbName));
    }

    @Override
    protected Storage openExtraStorageOnSameDatabase() {
        return new MongoStorage(new MongoConfig(MONGO_URL, currentTestDbName));
    }

    /** The replace is filtered on the stored version - a stale write matches no document and is rejected. */
    @Override
    protected boolean expectedEnforcesOptimisticLock() {
        return true;
    }

    // ------------------------------------------------------------------
    //  Mongo-specific: capability assertions
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("MongoStorage implements TransactionalStorage")
    void mongoStorage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            "MongoStorage must implement TransactionalStorage");
    }

    @Test
    @Order(1002)
    @DisplayName("MongoStorage implements SchemaAwareStorage")
    void mongoStorage_implementsSchemaAwareStorage() {
        assertInstanceOf(SchemaAwareStorage.class, storage,
            "MongoStorage must implement SchemaAwareStorage");
    }

    // ------------------------------------------------------------------
    //  Mongo-specific: SchemaAwareStorage - before any migration
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("currentVersion() returns SchemaVersion.none() before any migration")
    void currentVersion_beforeMigrate_isNone() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(noOpMigration());
        // NOTE: migrate() NOT called

        SchemaVersion v = sas.currentVersion().join();
        assertEquals(SchemaVersion.none().version(), v.version(),
            "currentVersion() must return SchemaVersion.none() when no migration has run");
    }

    @Test
    @Order(1011)
    @DisplayName("pending() lists the migration before it runs")
    void pending_beforeMigrate_containsMigration() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        Migration m = noOpMigration();
        sas.register(m);
        // NOTE: migrate() NOT called

        List<Migration> pending = sas.pending().join();
        assertEquals(1, pending.size());
        assertEquals(m.version(), pending.get(0).version());
    }

    // ------------------------------------------------------------------
    //  Mongo-specific: SchemaAwareStorage - after migrate()
    // ------------------------------------------------------------------

    @Test
    @Order(1012)
    @DisplayName("migrate() runs successfully (no-op migration)")
    void migrate_runsSuccessfully() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        assertDoesNotThrow(() ->
            sas.register(noOpMigration()).migrate().join(),
            "migrate() must not throw for a well-behaved migration"
        );
    }

    @Test
    @Order(1013)
    @DisplayName("currentVersion() reflects the applied migration version after migrate()")
    void currentVersion_afterMigrate_reflectsVersion() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        Migration m = noOpMigration();
        sas.register(m).migrate().join();

        SchemaVersion v = sas.currentVersion().join();
        assertEquals(m.version(), v.version(),
            "currentVersion() must return the version of the applied migration");
        assertTrue(v.appliedAt() > 0, "appliedAt timestamp must be set");
    }

    @Test
    @Order(1014)
    @DisplayName("pending() is empty after all migrations are applied")
    void pending_afterMigrate_isEmpty() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(noOpMigration()).migrate().join();

        List<Migration> pending = sas.pending().join();
        assertTrue(pending.isEmpty(),
            "pending() must return an empty list when all migrations have been applied");
    }

    @Test
    @Order(1015)
    @DisplayName("migrate() is idempotent - running twice does not corrupt migration history")
    void migrate_idempotent_noDuplicateRecords() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(noOpMigration());

        sas.migrate().join();
        sas.migrate().join(); // second call must be a no-op

        assertTrue(sas.pending().join().isEmpty(),
            "pending() must remain empty after a repeated migrate()");
        assertEquals("001", sas.currentVersion().join().version(),
            "currentVersion() must not be duplicated or corrupted");
    }

    // ------------------------------------------------------------------
    //  Mongo-specific: versioned saveAll inside a transaction
    // ------------------------------------------------------------------

    /** Versioned descriptor for the transactional saveAll test - optimistic locking active. */
    private static final EntityDescriptor<UUID, VersionedTestPlayer> TX_VERSIONED_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
            .collection("tx_versioned_players")
            .keyExtractor(VersionedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
            .versioned()
            .build();

    /**
     * A versioned {@code saveAll()} run inside a Mongo transaction must insert every entity at
     * version 0 and make them all visible after the transaction commits. This exercises the
     * per-entity optimistic-lock check-then-act on a shared transactional {@code ClientSession}
     * (the chained, one-at-a-time branch of {@link MongoRepository#saveAll}).
     */
    @Test
    @Order(1030)
    @DisplayName("inTransaction() - versioned saveAll() lands every entity at version 0 and commits")
    void inTransaction_versionedSaveAll_landsAtVersionZeroAndCommits() {
        TransactionalStorage tx = (TransactionalStorage) storage;
        Repository<UUID, VersionedTestPlayer> vRepo = storage.repository(TX_VERSIONED_DESCRIPTOR);

        UUID uuidA = UUID.fromString("11111111-0000-0000-0000-000000000001");
        UUID uuidB = UUID.fromString("11111111-0000-0000-0000-000000000002");
        UUID uuidC = UUID.fromString("11111111-0000-0000-0000-000000000003");
        List<VersionedTestPlayer> players = Arrays.asList(
            new VersionedTestPlayer(uuidA, "Alpha", 10),
            new VersionedTestPlayer(uuidB, "Beta",  20),
            new VersionedTestPlayer(uuidC, "Gamma", 30));

        tx.inTransaction(scope ->
            scope.repository(TX_VERSIONED_DESCRIPTOR).saveAll(players)
        ).join();

        // Every entity is a fresh insert, so it must land at version 0.
        for (VersionedTestPlayer p : players) {
            assertEquals(0L, p.getLockVersion(),
                "A fresh versioned insert must land at version 0, key=" + p.getUuid());
        }

        // All three must be visible through the normal (non-tx) repo after commit.
        assertEquals(3L, vRepo.count().join(), "All entities must be visible after commit");
        for (VersionedTestPlayer p : players) {
            VersionedTestPlayer loaded = vRepo.find(p.getUuid()).join()
                .orElseThrow(() -> new AssertionError("Missing after commit: " + p.getUuid()));
            assertEquals(0L, loaded.getLockVersion(), "Stored version must be 0 for a fresh insert");
        }
    }

    // ------------------------------------------------------------------
    //  Private: test-only no-op migration
    // ------------------------------------------------------------------

    /**
     * Returns a fresh no-op {@link MongoMigration} with version {@code "001"} for use in
     * schema-lifecycle tests. A new instance per call prevents accidental state sharing.
     */
    private static MongoMigration noOpMigration() {
        return new MongoMigration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "no-op test migration for schema tracking"; }
            @Override protected void executeOnDatabase(MongoDatabase db) { /* intentionally empty */ }
        };
    }
}
