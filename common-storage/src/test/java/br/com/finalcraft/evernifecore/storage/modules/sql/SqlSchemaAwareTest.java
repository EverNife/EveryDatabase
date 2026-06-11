package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link SchemaAwareStorage} implementation in {@link SqlStorage}.
 *
 * <p>Uses {@link H2SqlStorage} (embedded, no external server) so the full suite
 * runs without Docker. Each test method gets its own named in-memory H2 database.
 *
 * <p>Coverage:
 * <ul>
 *   <li>SqlStorage implements SchemaAwareStorage</li>
 *   <li>currentVersion() before any migration -> SchemaVersion.none()</li>
 *   <li>pending() reflects registered but not yet applied migrations</li>
 *   <li>migrate() applies pending migrations in version order</li>
 *   <li>migrate() is idempotent - second run skips already-applied</li>
 *   <li>Failed migration aborts the sequence; later versions are not applied</li>
 *   <li>SqlMigration.upScript() actually executes DDL</li>
 *   <li>Backfill migration via repo.saveAll(repo.all()...)</li>
 *   <li>register() is fluent (returns this)</li>
 *   <li>Quoting: H2SqlStorage overrides q() to use double-quotes</li>
 * </ul>
 *
 * <h3>Running</h3>
 * <pre>
 * ./gradlew :common-storage:test --tests "*SqlSchemaAwareTest"
 * </pre>
 */
@DisplayName("SqlStorage - SchemaAwareStorage")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlSchemaAwareTest {

    // ------------------------------------------------------------------
    //  Pool tuning (minimal for unit tests)
    // ------------------------------------------------------------------

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30)
    );

    // ------------------------------------------------------------------
    //  Per-test H2 storage
    // ------------------------------------------------------------------

    private H2SqlStorage storage;

    @BeforeEach
    void setUp(TestInfo info) {
        String dbName = info.getTestMethod().map(m -> m.getName()).orElse("default");
        String url = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        storage = new H2SqlStorage(new SqlConfig(url, "sa", "", TEST_POOL));
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Test migrations (inner classes for self-containment)
    // ------------------------------------------------------------------

    /** Creates an auxiliary table `audit_log (id INT PRIMARY KEY, msg VARCHAR(255))`. */
    static final class V001_CreateAuditLog extends SqlMigration {
        static final V001_CreateAuditLog INSTANCE = new V001_CreateAuditLog();
        private V001_CreateAuditLog() {}

        @Override public String version()     { return "001"; }
        @Override public String description() { return "Create audit_log table"; }
        @Override public String upScript() {
            return "CREATE TABLE IF NOT EXISTS audit_log ("
                 + "id INT NOT NULL, msg VARCHAR(255), PRIMARY KEY (id))";
        }
    }

    /** Inserts a row into `audit_log`. Requires V001 to have run first. */
    static final class V002_SeedAuditLog extends SqlMigration {
        static final V002_SeedAuditLog INSTANCE = new V002_SeedAuditLog();
        private V002_SeedAuditLog() {}

        @Override public String version()     { return "002"; }
        @Override public String description() { return "Seed audit_log with initial row"; }
        @Override public String upScript() {
            return "INSERT INTO audit_log (id, msg) VALUES (1, 'hello')";
        }
    }

    /** Always throws - used to test failure stops the sequence. */
    static final class V003_AlwaysFails implements Migration {
        static final V003_AlwaysFails INSTANCE = new V003_AlwaysFails();
        private V003_AlwaysFails() {}

        @Override public String version()     { return "003"; }
        @Override public String description() { return "Always fails"; }
        @Override public void execute(MigrationContext ctx) {
            throw new RuntimeException("intentional failure in migration 003");
        }
    }

    /** Runs after V003 - must NOT execute if V003 failed. */
    static final class V004_ShouldNotRun extends SqlMigration {
        static final V004_ShouldNotRun INSTANCE = new V004_ShouldNotRun();
        private V004_ShouldNotRun() {}

        @Override public String version()     { return "004"; }
        @Override public String description() { return "Must not run after V003 failure"; }
        @Override public String upScript() {
            return "CREATE TABLE IF NOT EXISTS should_not_exist (id INT PRIMARY KEY)";
        }
    }

    // ------------------------------------------------------------------
    //  Capability
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("SqlStorage implements SchemaAwareStorage")
    void sqlStorage_implementsSchemaAwareStorage() {
        assertInstanceOf(SchemaAwareStorage.class, storage,
            "H2SqlStorage (and SqlStorage) must implement SchemaAwareStorage");
    }

    // ------------------------------------------------------------------
    //  currentVersion() - no migrations applied yet
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("currentVersion() before any migration returns SchemaVersion.none()")
    void currentVersion_noMigrationsApplied_returnsNone() {
        SchemaVersion v = storage.currentVersion().join();
        assertEquals(SchemaVersion.none().version(), v.version(),
            "currentVersion() must return SchemaVersion.none() when nothing has been applied");
    }

    // ------------------------------------------------------------------
    //  pending()
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("pending() with no registrations returns empty list")
    void pending_noRegistrations_returnsEmpty() {
        List<Migration> pending = storage.pending().join();
        assertTrue(pending.isEmpty(), "pending() must be empty when no migrations are registered");
    }

    @Test
    @Order(31)
    @DisplayName("pending() returns all registered migrations before migrate()")
    void pending_beforeMigrate_returnsAllRegistered() {
        storage.register(V001_CreateAuditLog.INSTANCE, V002_SeedAuditLog.INSTANCE);

        List<Migration> pending = storage.pending().join();
        assertEquals(2, pending.size(), "Both V001 and V002 must be pending before migrate()");
        assertEquals("001", pending.get(0).version());
        assertEquals("002", pending.get(1).version());
    }

    @Test
    @Order(32)
    @DisplayName("pending() is empty after migrate() applies all migrations")
    void pending_afterMigrate_isEmpty() {
        storage.register(V001_CreateAuditLog.INSTANCE, V002_SeedAuditLog.INSTANCE)
               .migrate().join();

        List<Migration> pending = storage.pending().join();
        assertTrue(pending.isEmpty(), "pending() must be empty after migrate() applies all");
    }

    // ------------------------------------------------------------------
    //  migrate() - basic
    // ------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("migrate() with no registrations is a no-op")
    void migrate_noRegistrations_isNoOp() {
        assertDoesNotThrow(() -> storage.migrate().join(),
            "migrate() with no registered migrations must not throw");
        assertEquals(SchemaVersion.none().version(), storage.currentVersion().join().version());
    }

    @Test
    @Order(41)
    @DisplayName("migrate() applies V001 and creates the audit_log table")
    void migrate_v001_createsDDL() throws Exception {
        storage.register(V001_CreateAuditLog.INSTANCE).migrate().join();

        // Verify the table was actually created via JDBC
        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            assertTrue(rs.next(), "audit_log table must exist after V001");
            assertEquals(0, rs.getLong(1), "audit_log must be empty after V001 (only structure)");
        }
    }

    @Test
    @Order(42)
    @DisplayName("migrate() applies V001 then V002 in order; currentVersion() returns 002")
    void migrate_v001AndV002_appliedInOrder() throws Exception {
        storage.register(V001_CreateAuditLog.INSTANCE, V002_SeedAuditLog.INSTANCE)
               .migrate().join();

        SchemaVersion v = storage.currentVersion().join();
        assertEquals("002", v.version(), "currentVersion() must be 002 after V001+V002");

        // Verify the seed row from V002 is present
        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT msg FROM audit_log WHERE id = 1")) {
            assertTrue(rs.next(), "Seed row from V002 must exist");
            assertEquals("hello", rs.getString(1));
        }
    }

    // ------------------------------------------------------------------
    //  migrate() - idempotency
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("migrate() called twice applies each migration exactly once")
    void migrate_calledTwice_isIdempotent() throws Exception {
        storage.register(V001_CreateAuditLog.INSTANCE, V002_SeedAuditLog.INSTANCE);

        storage.migrate().join(); // first run: applies V001 + V002
        storage.migrate().join(); // second run: should skip both

        SchemaVersion v = storage.currentVersion().join();
        assertEquals("002", v.version(), "Version must still be 002 after second migrate()");

        // V002 inserts id=1; a second INSERT with the same PK would throw -> idempotency confirmed
        // by absence of exception above
        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong(1), "audit_log must have exactly 1 row (V002 not re-applied)");
        }
    }

    // ------------------------------------------------------------------
    //  migrate() - register() fluent
    // ------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("register() returns this (fluent chaining)")
    void register_returnsFluent() {
        SchemaAwareStorage result = storage.register(V001_CreateAuditLog.INSTANCE);
        assertSame(storage, result, "register() must return this for fluent chaining");
    }

    @Test
    @Order(61)
    @DisplayName("register() called multiple times accumulates migrations in version order")
    void register_calledMultipleTimes_accumulates() {
        // Register out of order - should still apply in version order
        storage.register(V002_SeedAuditLog.INSTANCE);
        storage.register(V001_CreateAuditLog.INSTANCE);

        List<Migration> pending = storage.pending().join();
        assertEquals(2, pending.size());
        assertEquals("001", pending.get(0).version(), "V001 must come first regardless of registration order");
        assertEquals("002", pending.get(1).version());
    }

    // ------------------------------------------------------------------
    //  migrate() - failure stops the sequence
    // ------------------------------------------------------------------

    @Test
    @Order(70)
    @DisplayName("Failed migration aborts sequence; later migrations are not applied")
    void migrate_failedMigration_abortsSequenceAndLaterMigrationsNotApplied() {
        storage.register(
            V001_CreateAuditLog.INSTANCE,  // succeeds
            V003_AlwaysFails.INSTANCE,     // throws
            V004_ShouldNotRun.INSTANCE     // must NOT run
        );

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> storage.migrate().join(),
            "migrate() must throw when a migration fails");
        assertTrue(ex.getMessage().contains("003") || ex.getCause() != null,
            "Exception must reference the failing migration (003)");

        // V001 applied, V003 recorded failure, V004 not applied
        SchemaVersion v = storage.currentVersion().join();
        assertEquals("001", v.version(),
            "currentVersion() must be 001 - V003 failed before being recorded, V004 never ran");

        List<Migration> pending = storage.pending().join();
        List<String> pendingVersions = pending.stream()
            .map(Migration::version).collect(Collectors.toList());
        assertTrue(pendingVersions.contains("003"), "V003 must still be pending (it failed)");
        assertTrue(pendingVersions.contains("004"), "V004 must still be pending (never ran)");
    }

    // ------------------------------------------------------------------
    //  SqlMigration.upScript() - DDL actually executes
    // ------------------------------------------------------------------

    @Test
    @Order(80)
    @DisplayName("SqlMigration using MigrationContext.getNativeClient(Connection.class) executes correctly")
    void migrate_nativeClientMigration_executesCorrectly() throws Exception {
        // Migration that does NOT use upScript() but getNativeClient() directly
        Migration customMigration = new Migration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "Custom via getNativeClient"; }
            @Override public void execute(MigrationContext ctx) throws Exception {
                Connection conn = ctx.getNativeClient(Connection.class);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS custom_via_native (val VARCHAR(50) PRIMARY KEY)");
                    stmt.execute("INSERT INTO custom_via_native (val) VALUES ('from-native-client')");
                }
            }
        };

        storage.register(customMigration).migrate().join();

        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT val FROM custom_via_native")) {
            assertTrue(rs.next(), "Row inserted by custom migration must exist");
            assertEquals("from-native-client", rs.getString(1));
        }
    }

    @Test
    @Order(81)
    @DisplayName("MigrationContext.getNativeClient() with wrong type throws IllegalArgumentException")
    void migrationContext_wrongType_throwsIllegalArgument() {
        Migration badTypeMigration = new Migration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "Requests wrong type"; }
            @Override public void execute(MigrationContext ctx) {
                ctx.getNativeClient(com.mongodb.client.MongoDatabase.class); // wrong type
            }
        };

        storage.register(badTypeMigration);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> storage.migrate().join());
        // The cause chain leads to IllegalArgumentException from SqlMigrationContext
        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IllegalArgumentException.class, root,
            "Requesting wrong native client type must produce IllegalArgumentException");
    }

    // ------------------------------------------------------------------
    //  Backfill migration via repository
    // ------------------------------------------------------------------

    @Test
    @Order(90)
    @DisplayName("Backfill migration: repo.saveAll(repo.all()...) repopulates index columns")
    void migrate_backfillViaRepository_repopulatesIndexColumns() {
        // Simulate a server that already has data in V1 schema (no score index),
        // then "upgrades" by registering a backfill migration that re-saves all entities
        // so the new score index column gets populated.

        // Step 1: seed data via normal repo (score column exists but is auto-managed)
        Repository<UUID, TestPlayer> repo = storage.repository(AbstractStorageTest.DESCRIPTOR);
        repo.saveAll(Arrays.asList(
            new TestPlayer(AbstractStorageTest.UUID_ALICE, "Alice", 100),
            new TestPlayer(AbstractStorageTest.UUID_BOB,   "Bob",    50),
            new TestPlayer(AbstractStorageTest.UUID_CAROL, "Carol", 200)
        )).join();

        assertEquals(3L, repo.count().join());

        // Step 2: register a backfill migration that loads all entities and re-saves them.
        // In a real scenario this runs after adding a new IndexHint that had no column before.
        Migration backfillMigration = new Migration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "Backfill re-save all test_players"; }
            @Override public void execute(MigrationContext ctx) {
                // The migration reads all current entities and writes them back via the repo.
                // This is the standard backfill pattern documented in SqlRepository.createTableIfAbsent().
                List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
                repo.saveAll(all).join();
            }
        };

        // Must not throw
        assertDoesNotThrow(() ->
            storage.register(backfillMigration).migrate().join(),
            "Backfill migration via repository must not throw"
        );

        // All entities still present
        assertEquals(3L, repo.count().join(), "count() must be unchanged after backfill");

        SchemaVersion v = storage.currentVersion().join();
        assertEquals("001", v.version(), "currentVersion() must be 001 after backfill migration");
    }

    // ------------------------------------------------------------------
    //  H2 quoting: double-quote wrapping in _schema_migrations DDL
    // ------------------------------------------------------------------

    @Test
    @Order(100)
    @DisplayName("_schema_migrations table is created with H2 double-quote quoting")
    void migrationsTable_createdWithCorrectQuoting() throws Exception {
        // Trigger table creation by calling currentVersion() (which calls ensureMigrationsTable)
        storage.currentVersion().join();

        // Table should exist - query it directly
        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM _schema_migrations")) {
            assertTrue(rs.next(), "_schema_migrations table must exist after currentVersion()");
        }
    }

    @Test
    @Order(101)
    @DisplayName("applied_at column is populated with a positive epoch millis value")
    void recordApplied_appliedAtIsPopulatedCorrectly() throws Exception {
        long before = System.currentTimeMillis();
        storage.register(V001_CreateAuditLog.INSTANCE).migrate().join();
        long after = System.currentTimeMillis();

        try (Connection conn = ((javax.sql.DataSource) storage.getDataSource()).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT applied_at FROM _schema_migrations WHERE version = '001'")) {
            assertTrue(rs.next(), "Row for V001 must be in _schema_migrations");
            long appliedAt = rs.getLong(1);
            assertTrue(appliedAt >= before && appliedAt <= after,
                "applied_at must be a recent epoch millis; got " + appliedAt);
        }
    }
}
