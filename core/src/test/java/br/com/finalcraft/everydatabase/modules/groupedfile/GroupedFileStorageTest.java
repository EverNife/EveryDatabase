package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.groupedfile.migrations.V000_PopulateTestPlayers;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract suite for {@link GroupedFileStorage} in JSON mode - the contract {@code DESCRIPTOR}
 * carries a {@code JacksonJsonCodec}, so the storage resolves to JSON (inherits tests from
 * {@link AbstractStorageTest}), plus GroupedFile-specific tests that assert the key-major on-disk
 * layout:
 * <ul>
 *   <li>multiple collections of the same key live in ONE file</li>
 *   <li>delete removes only its sub-node (and drops the file only when empty)</li>
 *   <li>the migration ledger is isolated under the reserved {@code _schema/} directory</li>
 *   <li>{@link SchemaAwareStorage} migrations work through the public API</li>
 * </ul>
 */
@DisplayName("GroupedFileStorage - JSON")
class GroupedFileStorageTest extends AbstractStorageTest {

    /**
     * Set to {@code true} to delete all test files after the run.
     * When {@code false} (default), files survive under {@link #RESIDUALS_ROOT} for inspection.
     */
    static final boolean CLEAN_TEST_RESIDUALS = false;

    static final Path RESIDUALS_ROOT = Path.of("build/test-residuals/GroupedFileStorage");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // Managed manually (no @TempDir) so CLEAN_TEST_RESIDUALS can control deletion.
    Path tempDir;

    @Override
    protected Storage createStorage(String testMethodName) {
        // JSON container: the contract DESCRIPTOR carries a JacksonJsonCodec, so the format resolves to JSON.
        tempDir = RESIDUALS_ROOT.resolve(testMethodName);
        deleteQuietly(tempDir);    // start each test with a clean slate
        tempDir.toFile().mkdirs();
        return new GroupedFileStorage(new GroupedFileConfig(tempDir));
    }

    /** Aggregate files are rewritten wholesale with no compare-and-set - a versioned descriptor degrades to last-write-wins. */
    @Override
    protected boolean expectedEnforcesOptimisticLock() {
        return false;
    }

    @AfterAll
    static void handleResiduals() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[GroupedFileStorageTest] CLEAN_TEST_RESIDUALS=false - keeping files for inspection:");
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

    @Override
    protected boolean injectCorruptRow(String collection) throws IOException {
        // A key file that fails to parse must surface as a failed ScanRow (not silently skipped).
        Files.write(tempDir.resolve("corrupt.json"), "{ this is not valid json".getBytes(StandardCharsets.UTF_8));
        return true;
    }

    @Override
    protected boolean injectUndecodableRow(String collection) throws IOException {
        // A key file that parses fine and declares this collection, so the row is unambiguously ours -
        // only the payload under it is unreadable. Contrast with injectCorruptRow, whose file cannot be
        // attributed to any collection at all.
        Files.write(tempDir.resolve("poisoned.json"),
            ("{\"" + collection + "\":{\"uuid\":\"not-a-uuid\"}}").getBytes(StandardCharsets.UTF_8));
        return true;
    }

    // Two collections sharing the SAME key space (UUID) - the grouping case.
    static final EntityDescriptor<UUID, TestPlayer> PLAYER_DATA =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("player_data")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    static final EntityDescriptor<UUID, TestPlayer> AUTH_ME =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("auth_me")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    private Path keyFile(UUID key) {
        return tempDir.resolve(key + ".json");
    }

    // ------------------------------------------------------------------
    //  Capability assertions
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("implements SchemaAwareStorage but not TransactionalStorage")
    void capabilities() {
        assertInstanceOf(SchemaAwareStorage.class, storage);
        assertFalse(storage instanceof TransactionalStorage,
            "GroupedFileStorage must not advertise transactions");
    }

    // ------------------------------------------------------------------
    //  Key-major layout: grouping
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("collections sharing a key are grouped into ONE file with one node per collection")
    void groupsCollectionsOfSameKeyIntoOneFile() throws IOException {
        Repository<UUID, TestPlayer> playerData = storage.repository(PLAYER_DATA);
        Repository<UUID, TestPlayer> authMe     = storage.repository(AUTH_ME);

        playerData.save(new TestPlayer(UUID_ALICE, "Alice", 100)).join();
        authMe.save(new TestPlayer(UUID_ALICE, "AliceAuth", 7)).join();

        // Exactly one file on disk for Alice's key, holding both collections as top-level fields.
        Path file = keyFile(UUID_ALICE);
        assertTrue(Files.exists(file), "a single <key>.json must hold both collections");

        try (Stream<Path> s = Files.list(tempDir)) {
            List<Path> jsonFiles = s.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
            assertEquals(1, jsonFiles.size(), "two collections of one key must not create two files");
        }

        JsonNode root = JSON.readTree(Files.readAllBytes(file));
        assertTrue(root.has("player_data"), "file must contain the player_data sub-node");
        assertTrue(root.has("auth_me"),     "file must contain the auth_me sub-node");

        // Each collection reads back its own entity independently.
        assertEquals("Alice",     playerData.find(UUID_ALICE).join().orElseThrow(AssertionError::new).getName());
        assertEquals("AliceAuth", authMe.find(UUID_ALICE).join().orElseThrow(AssertionError::new).getName());
    }

    @Test
    @Order(1011)
    @DisplayName("delete of one collection keeps the other collections in the same key file")
    void deleteOneCollection_keepsOthers() throws IOException {
        Repository<UUID, TestPlayer> playerData = storage.repository(PLAYER_DATA);
        Repository<UUID, TestPlayer> authMe     = storage.repository(AUTH_ME);
        playerData.save(new TestPlayer(UUID_ALICE, "Alice", 100)).join();
        authMe.save(new TestPlayer(UUID_ALICE, "AliceAuth", 7)).join();

        assertTrue(playerData.delete(UUID_ALICE).join(), "delete must report the sub-node existed");

        Path file = keyFile(UUID_ALICE);
        assertTrue(Files.exists(file), "the file must survive - auth_me still lives there");
        JsonNode root = JSON.readTree(Files.readAllBytes(file));
        assertFalse(root.has("player_data"), "deleted collection sub-node must be gone");
        assertTrue(root.has("auth_me"),      "untouched collection must remain");

        assertFalse(playerData.find(UUID_ALICE).join().isPresent());
        assertTrue(authMe.find(UUID_ALICE).join().isPresent());
    }

    @Test
    @Order(1012)
    @DisplayName("delete of the last collection drops the now-empty key file")
    void deleteLastCollection_removesFile() {
        Repository<UUID, TestPlayer> playerData = storage.repository(PLAYER_DATA);
        playerData.save(new TestPlayer(UUID_ALICE, "Alice", 100)).join();

        Path file = keyFile(UUID_ALICE);
        assertTrue(Files.exists(file));

        assertTrue(playerData.delete(UUID_ALICE).join());
        assertFalse(Files.exists(file), "the file must be removed once its last collection is deleted");
    }

    @Test
    @Order(1013)
    @DisplayName("keys that sanitise to the same name do not collide (file-per-key naming)")
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

        assertEquals(2L, stringRepo.count().join(), "'a/b' and 'a_b' must map to two distinct files");
        assertEquals(slash,      stringRepo.find("a/b").join().orElseThrow(AssertionError::new));
        assertEquals(underscore, stringRepo.find("a_b").join().orElseThrow(AssertionError::new));

        assertTrue(stringRepo.delete("a/b").join());
        assertTrue(stringRepo.find("a/b").join().isEmpty());
        assertEquals(underscore, stringRepo.find("a_b").join().orElseThrow(AssertionError::new));
    }

    @Test
    @Order(1015)
    @DisplayName("keys differing only in letter case map to distinct files (case-insensitive file systems)")
    void caseDifferingKeys_doNotCollide() {
        EntityDescriptor<String, TestPlayer> byName = EntityDescriptor
            .builder(String.class, TestPlayer.class)
            .collection("case_keyed")
            .keyExtractor(TestPlayer::getName)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
        Repository<String, TestPlayer> stringRepo = storage.repository(byName);

        TestPlayer upper = new TestPlayer(UUID.randomUUID(), "Alice", 1);
        TestPlayer lower = new TestPlayer(UUID.randomUUID(), "alice", 2);
        stringRepo.save(upper).join();
        stringRepo.save(lower).join();

        assertEquals(2L, stringRepo.count().join(), "'Alice' and 'alice' must map to two distinct key files");
        assertEquals(upper, stringRepo.find("Alice").join().orElseThrow(AssertionError::new));
        assertEquals(lower, stringRepo.find("alice").join().orElseThrow(AssertionError::new));

        assertTrue(stringRepo.delete("Alice").join());
        assertTrue(stringRepo.find("Alice").join().isEmpty());
        assertEquals(lower, stringRepo.find("alice").join().orElseThrow(AssertionError::new));
    }

    @Test
    @Order(1016)
    @DisplayName("reserved Windows device names round-trip as regular files")
    void reservedDeviceNameKeys_roundTrip() {
        EntityDescriptor<String, TestPlayer> byName = EntityDescriptor
            .builder(String.class, TestPlayer.class)
            .collection("reserved_keyed")
            .keyExtractor(TestPlayer::getName)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
        Repository<String, TestPlayer> stringRepo = storage.repository(byName);

        TestPlayer con = new TestPlayer(UUID.randomUUID(), "CON", 1);
        TestPlayer nul = new TestPlayer(UUID.randomUUID(), "NUL", 2);
        stringRepo.save(con).join();
        stringRepo.save(nul).join();

        assertEquals(2L, stringRepo.count().join(), "'CON' and 'NUL' must persist as regular files");
        assertEquals(con, stringRepo.find("CON").join().orElseThrow(AssertionError::new));
        assertEquals(nul, stringRepo.find("NUL").join().orElseThrow(AssertionError::new));
    }

    @Test
    @Order(1014)
    @DisplayName("save leaves no .tmp residue behind (atomic write)")
    void save_leavesNoTmpFiles() throws IOException {
        for (int i = 0; i < 25; i++) {
            repo.save(new TestPlayer(new UUID(0, i), "player_" + i, i)).join();
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            List<Path> leftovers = paths.filter(p -> p.toString().endsWith(".tmp")).collect(Collectors.toList());
            assertTrue(leftovers.isEmpty(), "no .tmp files may remain after save: " + leftovers);
        }
    }

    @Test
    @Order(1016)
    @DisplayName("mixing JSON and YAML codecs in one base directory fails fast")
    void mixedCodecFormats_throws() {
        // setUp already created the JSON-codec DESCRIPTOR repo, locking this storage to JSON.
        EntityDescriptor<UUID, TestPlayer> yamlDesc = EntityDescriptor
            .builder(UUID.class, TestPlayer.class)
            .collection("yaml_collection")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonYamlCodec<>(TestPlayer.class))
            .build();
        assertThrows(IllegalStateException.class, () -> storage.repository(yamlDesc),
            "a YAML codec must not be mixed into a JSON-format grouped storage");
    }

    @Test
    @Order(1017)
    @DisplayName("parallel save() of 6 collections sharing each key loses no update (global per-key lock)")
    void parallelSavesAcrossCollections_noLostUpdate() throws IOException {
        final int COLLECTIONS = 6;   // >= 5 distinct collection "types" sharing one key space
        final int KEYS        = 25;

        // One repository per collection, all keyed by UUID, so collections of one key share its file.
        List<String> names = new ArrayList<>();
        List<Repository<UUID, TestPlayer>> repos = new ArrayList<>();
        for (int c = 0; c < COLLECTIONS; c++) {
            String name = "subsystem_" + c;
            names.add(name);
            repos.add(storage.repository(EntityDescriptor
                .builder(UUID.class, TestPlayer.class)
                .collection(name)
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build()));
        }

        List<UUID> keys = new ArrayList<>();
        for (int k = 0; k < KEYS; k++) keys.add(new UUID(7, k));

        // Fire EVERY (collection, key) save up front so they all contend on the shared executor: the
        // saves for one key all read-modify-write that key's single file concurrently.
        List<CompletableFuture<Void>> futures = new ArrayList<>(COLLECTIONS * KEYS);
        for (UUID key : keys) {
            for (int c = 0; c < COLLECTIONS; c++) {
                futures.add(repos.get(c).save(new TestPlayer(key, names.get(c), c)));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Every key file must hold ALL collections. The global per-key lock serialises the concurrent
        // read-modify-writes so none clobbers another - a per-repository lock would lose updates here.
        for (UUID key : keys) {
            Path file = keyFile(key);
            assertTrue(Files.exists(file), "missing file for key " + key);
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            assertEquals(COLLECTIONS, root.size(),
                "key " + key + " must hold exactly " + COLLECTIONS + " collection nodes, was " + root.size());
            for (String name : names) {
                assertTrue(root.has(name), "lost update: '" + name + "' missing for key " + key);
            }
        }

        // And each collection reads back exactly its own entity for every key.
        for (UUID key : keys) {
            for (int c = 0; c < COLLECTIONS; c++) {
                TestPlayer found = repos.get(c).find(key).join().orElseThrow(AssertionError::new);
                assertEquals(names.get(c), found.getName());
                assertEquals(c, found.getScore());
            }
        }
    }

    // ------------------------------------------------------------------
    //  Reserved schema ledger
    // ------------------------------------------------------------------

    @Test
    @Order(1015)
    @DisplayName("migration ledger lives under reserved _schema/ and is not counted as an entity")
    void ledger_isolatedUnderSchemaDir() {
        ((SchemaAwareStorage) storage).register(V000_PopulateTestPlayers.INSTANCE).migrate().join();

        Path ledger = tempDir.resolve("_schema").resolve("migrations.json");
        assertTrue(Files.exists(ledger), "ledger must live at <base>/_schema/migrations.json");

        // The reserved directory must not leak into a collection scan.
        assertEquals(20L, repo.count().join(), "count() must ignore the reserved _schema/ directory");
    }

    // ------------------------------------------------------------------
    //  Migrations through the public API (layout-agnostic)
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("V000 migration seeds exactly 20 players and is findable")
    void migration_V000_seedsAndIsFindable() {
        ((SchemaAwareStorage) storage).register(V000_PopulateTestPlayers.INSTANCE).migrate().join();

        assertEquals(20L, repo.count().join());
        for (TestPlayer seeded : V000_PopulateTestPlayers.SEEDED_PLAYERS) {
            assertEquals(seeded, repo.find(seeded.getUuid()).join().orElseThrow(AssertionError::new));
        }
    }

    @Test
    @Order(1021)
    @DisplayName("migrate() is idempotent and currentVersion()/pending() track state")
    void migration_idempotentAndVersionTracking() {
        SchemaAwareStorage sas = (SchemaAwareStorage) storage;
        sas.register(V000_PopulateTestPlayers.INSTANCE);

        assertEquals(SchemaVersion.none().version(), sas.currentVersion().join().version());
        assertEquals(1, sas.pending().join().size());

        sas.migrate().join();
        sas.migrate().join(); // second call must be a no-op

        assertEquals(20L, repo.count().join(), "second migrate() must not duplicate entries");
        assertEquals(V000_PopulateTestPlayers.INSTANCE.version(), sas.currentVersion().join().version());
        List<Migration> pending = sas.pending().join();
        assertTrue(pending.isEmpty(), "pending() must be empty after migrate()");
    }

    @Test
    @Order(1022)
    @DisplayName("seeded data survives a storage close + reopen on the same directory")
    void migration_dataSurvivesReopen() {
        ((SchemaAwareStorage) storage).register(V000_PopulateTestPlayers.INSTANCE).migrate().join();
        storage.close().join();

        Storage reopened = new GroupedFileStorage(new GroupedFileConfig(tempDir));
        reopened.init().join();
        Repository<UUID, TestPlayer> freshRepo = reopened.repository(DESCRIPTOR);

        assertEquals(20L, freshRepo.count().join(), "all 20 players must persist across reopen");
        TestPlayer first = V000_PopulateTestPlayers.SEEDED_PLAYERS.get(0);
        assertEquals(first, freshRepo.find(first.getUuid()).join().orElseThrow(AssertionError::new));

        reopened.close().join();
    }
}
