package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * ./gradlew :common-tests:test --tests "*MongoStorageTest"
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

    /**
     * When {@code false}, {@link #cleanupDatabases()} skips the drop so you can inspect the
     * test databases in MongoDB after the run (e.g. with Compass or mongosh).
     * Set back to {@code true} once you are done inspecting.
     */
    static final boolean CLEAN_TEST_RESIDUALS = false;

    /**
     * Run number shared by all tests in this execution.
     * Computed once in {@link #assumeMongoAvailable()} by scanning existing {@code enc_*}
     * databases; every test in this run uses {@code enc_NNN_mg_<methodName>}.
     */
    private static int runNumber = 1;

    /** Database names created during this test run - all dropped in {@link #cleanupDatabases()}. */
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    //  Availability guard - skip the whole class if MongoDB is not up
    // ------------------------------------------------------------------

    @BeforeAll
    static void assumeMongoAvailable() {
        MongoClientSettings probe = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(MONGO_URL))
            // Short timeout so the check fails fast when MongoDB is not running
            .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
            .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
            .build();

        try (MongoClient client = MongoClients.create(probe)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            assumeTrue(false,
                "MongoDB not available at " + MONGO_URL + " - skipping all MongoStorageTest. "
                + "Start a MongoDB 4.2+ server on that address to run these tests. "
                + "Cause: " + e.getMessage());
        }

        // Determine run number: scan existing enc_* databases, use max+1.
        try (MongoClient client = MongoClients.create(probe)) {
            List<String> existing = new java.util.ArrayList<>();
            for (String name : client.listDatabaseNames()) {
                if (name.startsWith("enc_")) existing.add(name);
            }
            runNumber = computeRunNumber(existing);
        } catch (Exception ignored) {
            runNumber = 1; // safe fallback
        }
        System.out.println("[MongoStorageTest] Run number: " + runNumber
            + "  (databases will be prefixed enc_" + String.format("%03d", runNumber) + "_mg_*)");
    }

    /**
     * Drop all test databases created during this run.
     * Skipped when {@link #CLEAN_TEST_RESIDUALS} is {@code false} - set it to {@code false}
     * to keep the databases alive for post-run inspection in Compass / mongosh.
     * Best-effort; never fails the build.
     */
    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[MongoStorageTest] CLEAN_TEST_RESIDUALS=false - keeping databases for inspection:");
            createdDbs.forEach(name -> System.out.println("  -> " + name));
            return;
        }
        if (createdDbs.isEmpty()) return;
        try (MongoClient client = MongoClients.create(MONGO_URL)) {
            createdDbs.forEach(name ->
                client.getDatabase(name).drop()
            );
        } catch (Exception ignored) {
            // best-effort: cleanup failure must not break the build
        }
    }

    // ------------------------------------------------------------------
    //  AbstractStorageTest contract
    // ------------------------------------------------------------------

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = buildDbName("mg", runNumber, testMethodName);
        createdDbs.add(dbName);
        return new MongoStorage(new MongoConfig(MONGO_URL, dbName));
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
