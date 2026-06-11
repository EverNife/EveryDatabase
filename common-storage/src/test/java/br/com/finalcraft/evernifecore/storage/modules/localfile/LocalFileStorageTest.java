package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.localfile.migrations.V000_PopulateTestPlayers;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete test suite for {@link LocalFileStorage}.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} (tests 1-80)
 * and adds LocalFile-specific tests:
 * <ul>
 *   <li>Order 1001 - {@link SchemaAwareStorage} capability assertion</li>
 *   <li>Order 1010+ - Migration tests using {@link V000_PopulateTestPlayers}</li>
 * </ul>
 *
 * <p>Each test gets its own subdirectory under {@link #RESIDUALS_ROOT}, cleaned before
 * the test starts. After the run, files are kept ({@link #CLEAN_TEST_RESIDUALS}{@code = false})
 * or deleted ({@code true}) so you can inspect them at will.
 */
@DisplayName("LocalFileStorage")
class LocalFileStorageTest extends AbstractStorageTest {

    /**
     * Set to {@code true} to delete all test files after the run.
     * When {@code false} (default), files survive under {@link #RESIDUALS_ROOT} for inspection.
     */
    static final boolean CLEAN_TEST_RESIDUALS = false;

    static final Path RESIDUALS_ROOT = Path.of("build/test-residuals/LocalFileStorage");

    // Managed manually (no @TempDir) so CLEAN_TEST_RESIDUALS can control deletion.
    Path tempDir;

    @Override
    protected Storage createStorage(String testMethodName) {
        tempDir = RESIDUALS_ROOT.resolve(testMethodName);
        deleteQuietly(tempDir);    // start each test with a clean slate
        tempDir.toFile().mkdirs();
        return new LocalFileStorage(new LocalFileConfig(tempDir));
    }

    @AfterAll
    static void handleResiduals() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[LocalFileStorageTest] CLEAN_TEST_RESIDUALS=false - keeping files for inspection:");
            System.out.println("  -> " + RESIDUALS_ROOT.toAbsolutePath());
        } else {
            deleteQuietly(RESIDUALS_ROOT);
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------
    //  LocalFile-specific: SchemaAwareStorage capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("LocalFileStorage implements SchemaAwareStorage")
    void localFileStorage_implementsSchemaAwareStorage() {
        assertInstanceOf(SchemaAwareStorage.class, storage,
            "LocalFileStorage must implement SchemaAwareStorage");
    }

    // ------------------------------------------------------------------
    //  Migration: V000_PopulateTestPlayers
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("V000 migration seeds exactly 20 players")
    void migration_V000_seeds20Players() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;

        sas.register(V000_PopulateTestPlayers.INSTANCE)
           .migrate()
           .join();

        assertEquals(20L, repo.count().join(),
            "Exactly 20 players must be present after migration");
    }

    @Test
    @Order(1011)
    @DisplayName("V000 migration - each player score is in [0, 10]")
    void migration_V000_scoresInRange() {
        ((SchemaAwareStorage) storage)
            .register(V000_PopulateTestPlayers.INSTANCE)
            .migrate()
            .join();

        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());

        for (TestPlayer player : all) {
            assertTrue(player.getScore() >= 0 && player.getScore() <= 10,
                "Score must be in [0, 10] for player " + player.getName()
                + ", was " + player.getScore());
        }
    }

    @Test
    @Order(1012)
    @DisplayName("V000 migration - each seeded player is findable by UUID")
    void migration_V000_eachPlayerFindable() {
        ((SchemaAwareStorage) storage)
            .register(V000_PopulateTestPlayers.INSTANCE)
            .migrate()
            .join();

        for (TestPlayer seeded : V000_PopulateTestPlayers.SEEDED_PLAYERS) {
            TestPlayer found = repo.find(seeded.getUuid()).join()
                .orElseThrow(() -> new AssertionError(
                    "Player not found after migration: uuid=" + seeded.getUuid()
                    + " name=" + seeded.getName()));

            assertEquals(seeded, found,
                "Player loaded from disk must equal the seeded player");
        }
    }

    @Test
    @Order(1013)
    @DisplayName("V000 migration - data survives a storage close + reopen")
    void migration_V000_dataSurvivesReopen() {
        // Populate and close
        ((SchemaAwareStorage) storage)
            .register(V000_PopulateTestPlayers.INSTANCE)
            .migrate()
            .join();
        storage.close().join();

        // Reopen the SAME tempDir with a fresh Storage instance
        Storage reopened = new LocalFileStorage(new LocalFileConfig(tempDir));
        reopened.init().join();
        Repository<UUID, TestPlayer> freshRepo = reopened.repository(DESCRIPTOR);

        assertEquals(20L, freshRepo.count().join(),
            "All 20 players must still be present after storage reopen");

        // Spot-check first and last seeded player
        TestPlayer first = V000_PopulateTestPlayers.SEEDED_PLAYERS.get(0);
        TestPlayer last  = V000_PopulateTestPlayers.SEEDED_PLAYERS.get(19);

        assertEquals(first, freshRepo.find(first.getUuid()).join().orElseThrow(AssertionError::new));
        assertEquals(last,  freshRepo.find(last.getUuid()).join().orElseThrow(AssertionError::new));

        reopened.close().join();
    }

    @Test
    @Order(1020)
    @DisplayName("migrate() is idempotent - running twice does not duplicate players")
    void migration_idempotent_noDuplicates() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE);

        sas.migrate().join();
        sas.migrate().join(); // second call must be a no-op

        assertEquals(20L, repo.count().join(),
            "Second migrate() must not produce duplicate entries");
    }

    @Test
    @Order(1021)
    @DisplayName("currentVersion() reflects the applied migration version after migrate()")
    void currentVersion_afterMigrate_reflectsVersion() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE).migrate().join();

        SchemaVersion v = sas.currentVersion().join();
        assertEquals(V000_PopulateTestPlayers.INSTANCE.version(), v.version(),
            "currentVersion() must return the version of the applied migration");
        assertTrue(v.appliedAt() > 0, "appliedAt timestamp must be set");
    }

    @Test
    @Order(1022)
    @DisplayName("pending() is empty after all migrations are applied")
    void pending_afterMigrate_isEmpty() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE).migrate().join();

        List<Migration> pending = sas.pending().join();
        assertTrue(pending.isEmpty(),
            "pending() must return an empty list when all migrations have been applied");
    }

    @Test
    @Order(1023)
    @DisplayName("pending() lists the migration before it runs")
    void pending_beforeMigrate_containsMigration() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE);
        // NOTE: migrate() NOT called yet

        List<Migration> pending = sas.pending().join();
        assertEquals(1, pending.size());
        assertEquals(V000_PopulateTestPlayers.INSTANCE.version(), pending.get(0).version());
    }

    @Test
    @Order(1024)
    @DisplayName("currentVersion() returns SchemaVersion.none() before any migration runs")
    void currentVersion_beforeMigrate_isNone() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE);
        // NOTE: migrate() NOT called

        SchemaVersion v = sas.currentVersion().join();
        assertEquals(SchemaVersion.none().version(), v.version());
    }

    // ------------------------------------------------------------------
    //  LocalFile-specific: file-name sanitisation and atomic writes
    // ------------------------------------------------------------------

    @Test
    @Order(1030)
    @DisplayName("keys that sanitise to the same file name do not collide")
    void sanitizedKeys_doNotCollide() {
        EntityDescriptor<String, TestPlayer> byName = EntityDescriptor
            .builder(String.class, TestPlayer.class)
            .collection("string_keyed")
            .keyExtractor(TestPlayer::getName)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
        Repository<String, TestPlayer> stringRepo = storage.repository(byName);

        TestPlayer slash      = new TestPlayer(UUID.randomUUID(), "a/b", 1);
        TestPlayer underscore = new TestPlayer(UUID.randomUUID(), "a_b", 2);
        stringRepo.save(slash).join();
        stringRepo.save(underscore).join();

        assertEquals(2L, stringRepo.count().join(),
            "'a/b' and 'a_b' must map to two distinct files");
        assertEquals(slash,      stringRepo.find("a/b").join().orElseThrow(AssertionError::new));
        assertEquals(underscore, stringRepo.find("a_b").join().orElseThrow(AssertionError::new));

        // delete must only remove its own file
        assertTrue(stringRepo.delete("a/b").join());
        assertTrue(stringRepo.find("a/b").join().isEmpty());
        assertEquals(underscore, stringRepo.find("a_b").join().orElseThrow(AssertionError::new));
    }

    @Test
    @Order(1031)
    @DisplayName("save leaves no .tmp residue behind (atomic write)")
    void save_leavesNoTmpFiles() throws IOException {
        for (int i = 0; i < 25; i++) {
            repo.save(new TestPlayer(UUID.randomUUID(), "player_" + i, i)).join();
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
            List<Path> leftovers = paths
                .filter(p -> p.toString().endsWith(".tmp"))
                .collect(Collectors.toList());
            assertTrue(leftovers.isEmpty(), "no .tmp files may remain after save: " + leftovers);
        }
    }
}
