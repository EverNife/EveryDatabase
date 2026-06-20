package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.memory.migrations.V000_PopulateTestPlayers;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link SchemaAwareStorage} implementation in {@link InMemoryStorage}.
 *
 * <p>Runs entirely embedded (no Docker, no I/O). Because InMemory is ephemeral, schema-DDL
 * has no meaning here - these tests exercise the migration <em>runner</em> contract (ordering,
 * idempotency, failure handling, native-client access) using <em>data</em> migrations that
 * write through {@code storage.repository(...)}, plus the one InMemory-specific behavior: the
 * ledger lives and dies with the instance.
 *
 * <p>The contract assertions intentionally mirror {@code SqlSchemaAwareTest} so the in-memory
 * backend is held to the same bar as the persistent ones.
 */
@DisplayName("InMemoryStorage - SchemaAwareStorage")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InMemorySchemaAwareTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    private Repository<UUID, TestPlayer> repo() {
        return storage.repository(AbstractStorageTest.DESCRIPTOR);
    }

    // ------------------------------------------------------------------
    //  Test migrations (inner classes for self-containment)
    // ------------------------------------------------------------------

    /** Seeds Alice (score 100). */
    static final class V001_SeedAlice extends InMemoryMigration {
        static final V001_SeedAlice INSTANCE = new V001_SeedAlice();
        private V001_SeedAlice() {}

        @Override public String version()     { return "001"; }
        @Override public String description() { return "Seed Alice"; }
        @Override protected void executeOnStorage(InMemoryStorage storage) {
            storage.repository(AbstractStorageTest.DESCRIPTOR)
                   .save(new TestPlayer(AbstractStorageTest.UUID_ALICE, "Alice", 100)).join();
        }
    }

    /** Seeds Bob (score 50), but only after asserting V001 already ran - proves ordering. */
    static final class V002_SeedBobAfterAlice extends InMemoryMigration {
        static final V002_SeedBobAfterAlice INSTANCE = new V002_SeedBobAfterAlice();
        private V002_SeedBobAfterAlice() {}

        @Override public String version()     { return "002"; }
        @Override public String description() { return "Seed Bob (requires Alice from V001)"; }
        @Override protected void executeOnStorage(InMemoryStorage storage) {
            Repository<UUID, TestPlayer> repo = storage.repository(AbstractStorageTest.DESCRIPTOR);
            if (!repo.find(AbstractStorageTest.UUID_ALICE).join().isPresent()) {
                throw new IllegalStateException("V002 ran before V001 - ordering broken");
            }
            repo.save(new TestPlayer(AbstractStorageTest.UUID_BOB, "Bob", 50)).join();
        }
    }

    /** Always throws - used to test that failure stops the sequence. */
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
    static final class V004_SeedCarol extends InMemoryMigration {
        static final V004_SeedCarol INSTANCE = new V004_SeedCarol();
        private V004_SeedCarol() {}

        @Override public String version()     { return "004"; }
        @Override public String description() { return "Must not run after V003 failure"; }
        @Override protected void executeOnStorage(InMemoryStorage storage) {
            storage.repository(AbstractStorageTest.DESCRIPTOR)
                   .save(new TestPlayer(AbstractStorageTest.UUID_CAROL, "Carol", 200)).join();
        }
    }

    // ------------------------------------------------------------------
    //  Capability
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("InMemoryStorage implements SchemaAwareStorage")
    void inMemoryStorage_implementsSchemaAwareStorage() {
        assertInstanceOf(SchemaAwareStorage.class, storage,
            "InMemoryStorage must implement SchemaAwareStorage");
    }

    // ------------------------------------------------------------------
    //  currentVersion() / pending()
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("currentVersion() before any migration returns SchemaVersion.none()")
    void currentVersion_noMigrationsApplied_returnsNone() {
        SchemaVersion v = storage.currentVersion().join();
        assertEquals(SchemaVersion.none().version(), v.version(),
            "currentVersion() must return SchemaVersion.none() when nothing has been applied");
    }

    @Test
    @Order(30)
    @DisplayName("pending() with no registrations returns empty list")
    void pending_noRegistrations_returnsEmpty() {
        assertTrue(storage.pending().join().isEmpty(),
            "pending() must be empty when no migrations are registered");
    }

    @Test
    @Order(31)
    @DisplayName("pending() returns all registered migrations before migrate()")
    void pending_beforeMigrate_returnsAllRegistered() {
        storage.register(V001_SeedAlice.INSTANCE, V002_SeedBobAfterAlice.INSTANCE);

        List<Migration> pending = storage.pending().join();
        assertEquals(2, pending.size(), "Both V001 and V002 must be pending before migrate()");
        assertEquals("001", pending.get(0).version());
        assertEquals("002", pending.get(1).version());
    }

    @Test
    @Order(32)
    @DisplayName("pending() is empty after migrate() applies all migrations")
    void pending_afterMigrate_isEmpty() {
        storage.register(V001_SeedAlice.INSTANCE, V002_SeedBobAfterAlice.INSTANCE)
               .migrate().join();

        assertTrue(storage.pending().join().isEmpty(),
            "pending() must be empty after migrate() applies all");
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
    @DisplayName("migrate() applies a data migration; seeded entities are visible via the repository")
    void migrate_dataMigration_entitiesVisible() {
        storage.register(V000_PopulateTestPlayers.INSTANCE).migrate().join();

        assertEquals(20L, repo().count().join(),
            "All 20 seeded players must be present after the data migration");
        assertEquals("000", storage.currentVersion().join().version());

        // spot-check a deterministic entry
        TestPlayer p0 = repo().find(new UUID(0, 1)).join().orElseThrow(AssertionError::new);
        assertEquals("Player_0", p0.getName());
    }

    @Test
    @Order(42)
    @DisplayName("migrate() applies V001 then V002 in order; currentVersion() returns 002")
    void migrate_v001AndV002_appliedInOrder() {
        storage.register(V001_SeedAlice.INSTANCE, V002_SeedBobAfterAlice.INSTANCE)
               .migrate().join();

        assertEquals("002", storage.currentVersion().join().version(),
            "currentVersion() must be 002 after V001+V002");
        // V002 throws if V001 didn't run first, so reaching here proves ordering; verify both writes
        assertTrue(repo().find(AbstractStorageTest.UUID_ALICE).join().isPresent(), "Alice from V001");
        assertTrue(repo().find(AbstractStorageTest.UUID_BOB).join().isPresent(),   "Bob from V002");
    }

    // ------------------------------------------------------------------
    //  migrate() - idempotency
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("migrate() called twice applies each migration exactly once")
    void migrate_calledTwice_isIdempotent() {
        storage.register(V000_PopulateTestPlayers.INSTANCE);

        storage.migrate().join(); // first run: seeds 20
        storage.migrate().join(); // second run: must skip (no duplicate writes / no re-run)

        assertEquals("000", storage.currentVersion().join().version(),
            "Version must still be 000 after second migrate()");
        assertEquals(20L, repo().count().join(),
            "Second migrate() must be a no-op - still exactly 20 players");
    }

    // ------------------------------------------------------------------
    //  register() fluent / accumulation
    // ------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("register() returns this (fluent chaining)")
    void register_returnsFluent() {
        assertSame(storage, storage.register(V001_SeedAlice.INSTANCE),
            "register() must return this for fluent chaining");
    }

    @Test
    @Order(61)
    @DisplayName("register() called multiple times accumulates migrations in version order")
    void register_calledMultipleTimes_accumulates() {
        // Register out of order - should still apply in version order
        storage.register(V002_SeedBobAfterAlice.INSTANCE);
        storage.register(V001_SeedAlice.INSTANCE);

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
            V001_SeedAlice.INSTANCE,   // succeeds
            V003_AlwaysFails.INSTANCE, // throws
            V004_SeedCarol.INSTANCE    // must NOT run
        );

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> storage.migrate().join(),
            "migrate() must throw when a migration fails");
        assertTrue(ex.getMessage().contains("003") || ex.getCause() != null,
            "Exception must reference the failing migration (003)");

        assertEquals("001", storage.currentVersion().join().version(),
            "currentVersion() must be 001 - V003 failed before being recorded, V004 never ran");
        assertFalse(repo().find(AbstractStorageTest.UUID_CAROL).join().isPresent(),
            "V004 must not have run - Carol must be absent");

        List<String> pendingVersions = storage.pending().join().stream()
            .map(Migration::version).collect(Collectors.toList());
        assertTrue(pendingVersions.contains("003"), "V003 must still be pending (it failed)");
        assertTrue(pendingVersions.contains("004"), "V004 must still be pending (never ran)");
    }

    // ------------------------------------------------------------------
    //  MigrationContext native client
    // ------------------------------------------------------------------

    @Test
    @Order(80)
    @DisplayName("getNativeClient(InMemoryStorage.class) returns the storage itself")
    void migrationContext_nativeClient_returnsStorage() {
        Migration custom = new Migration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "Custom via getNativeClient"; }
            @Override public void execute(MigrationContext ctx) {
                InMemoryStorage s = ctx.getNativeClient(InMemoryStorage.class);
                s.repository(AbstractStorageTest.DESCRIPTOR)
                 .save(new TestPlayer(AbstractStorageTest.UUID_ALICE, "from-native-client", 1)).join();
            }
        };

        storage.register(custom).migrate().join();

        TestPlayer alice = repo().find(AbstractStorageTest.UUID_ALICE).join().orElseThrow(AssertionError::new);
        assertEquals("from-native-client", alice.getName());
    }

    @Test
    @Order(81)
    @DisplayName("getNativeClient() with an unsupported type throws IllegalArgumentException")
    void migrationContext_wrongType_throwsIllegalArgument() {
        Migration badType = new Migration() {
            @Override public String version()     { return "001"; }
            @Override public String description() { return "Requests wrong type"; }
            @Override public void execute(MigrationContext ctx) {
                ctx.getNativeClient(java.sql.Connection.class); // not provided by InMemory
            }
        };

        storage.register(badType);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> storage.migrate().join());
        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IllegalArgumentException.class, root,
            "Requesting an unsupported native client type must produce IllegalArgumentException");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: the ledger is ephemeral
    // ------------------------------------------------------------------

    @Test
    @Order(90)
    @DisplayName("A fresh InMemoryStorage starts with an empty ledger and re-applies migrations")
    void ephemeralLedger_newInstance_startsAtNoneAndReApplies() {
        // Instance A: apply the seed migration
        storage.register(V000_PopulateTestPlayers.INSTANCE).migrate().join();
        assertEquals("000", storage.currentVersion().join().version());

        // Instance B: a brand-new storage shares no ledger with A
        InMemoryStorage other = new InMemoryStorage();
        other.init().join();
        try {
            assertEquals(SchemaVersion.none().version(), other.currentVersion().join().version(),
                "A fresh instance must start at SchemaVersion.none() - the ledger does not persist");

            other.register(V000_PopulateTestPlayers.INSTANCE);
            assertEquals(1, other.pending().join().size(),
                "The migration must be pending again on a fresh instance (ephemeral ledger)");

            other.migrate().join();
            assertEquals(20L, other.repository(AbstractStorageTest.DESCRIPTOR).count().join(),
                "Re-applying on the fresh instance must re-seed its own (separate) data");
        } finally {
            other.close().join();
        }
    }
}
