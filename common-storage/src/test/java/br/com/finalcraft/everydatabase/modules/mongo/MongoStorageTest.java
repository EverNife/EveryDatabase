package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.testutil.DotEnvTestUtil;
import br.com.finalcraft.everydatabase.testutil.ThrowawayDatabaseSupport;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.*;

import java.util.List;

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
 * <p>Multi-document transactions in MongoDB require a replica set (MongoDB 4.0+).
 * A standalone single-node server does not support them. Transaction-specific tests are
 * therefore omitted here; they belong in an integration test against a replica-set cluster.
 * The {@link TransactionalStorage} capability assertion still verifies that the interface
 * is declared without exercising it.
 */
@DisplayName("MongoStorage (requires MongoDB 4.2+)")
class MongoStorageTest extends AbstractStorageTest {

    // ------------------------------------------------------------------
    //  Connection coordinates - env vars with fallback defaults
    // ------------------------------------------------------------------

    static final String MONGO_USER = DotEnvTestUtil.getOrDefault("MONGO_USER", "root");
    static final String MONGO_PASS = DotEnvTestUtil.getOrDefault("MONGO_PASS", "root");
    static final String MONGO_HOST = DotEnvTestUtil.getOrDefault("MONGO_HOST", "localhost");
    static final String MONGO_PORT = DotEnvTestUtil.getOrDefault("MONGO_PORT", "39308");
    static final String MONGO_URL  = "mongodb://" + MONGO_USER + ":" + MONGO_PASS + "@" + MONGO_HOST + ":" + MONGO_PORT;

    private static final ThrowawayDatabaseSupport DBS = ThrowawayDatabaseSupport.mongo(MONGO_URL, "mg");

    @BeforeAll
    static void assumeMongoAvailable() {
        DBS.assumeAvailable("MongoStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MongoStorageTest");
    }

    // ------------------------------------------------------------------
    //  AbstractStorageTest contract
    // ------------------------------------------------------------------

    @Override
    protected Storage createStorage(String testMethodName) {
        return new MongoStorage(new MongoConfig(MONGO_URL, DBS.newDatabase(testMethodName)));
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
