package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonYamlCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link LocalFileStorage} uses the correct file extension when the codec is
 * {@link JacksonYamlCodec}: files are stored as {@code .yml}, round-trips work correctly,
 * and {@code count()} / {@code all()} count only {@code .yml} files.
 */
@DisplayName("LocalFileStorage - YAML codec")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalFileStorageYamlTest {

    static final UUID UUID_ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID UUID_BOB   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID UUID_CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static final EntityDescriptor<UUID, TestPlayer> YAML_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("test_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonYamlCodec<>(TestPlayer.class))
            .index(IndexHint.by("name"))
            .build();

    @TempDir
    Path tempDir;

    Storage storage;
    Repository<UUID, TestPlayer> repo;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(new LocalFileConfig(tempDir));
        storage.init().join();
        repo = storage.repository(YAML_DESCRIPTOR);
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    TestPlayer alice() { return new TestPlayer(UUID_ALICE, "Alice", 100); }
    TestPlayer bob()   { return new TestPlayer(UUID_BOB,   "Bob",    50); }
    TestPlayer carol() { return new TestPlayer(UUID_CAROL, "Carol", 200); }

    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("save() creates a file with .yml extension (not .json)")
    void save_createsYmlFile() throws IOException {
        repo.save(alice()).join();

        Path collectionDir = tempDir.resolve("test_players");
        List<Path> ymlFiles;
        try (java.util.stream.Stream<Path> s = Files.walk(collectionDir, 1)) {
            ymlFiles = s.filter(p -> p.toString().endsWith(".yml")).collect(Collectors.toList());
        }
        List<Path> jsonFiles;
        try (java.util.stream.Stream<Path> s = Files.walk(collectionDir, 1)) {
            jsonFiles = s.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        }

        assertEquals(1, ymlFiles.size(),  "Exactly 1 .yml file must exist");
        assertEquals(0, jsonFiles.size(), "No .json file must be created");
    }

    @Test
    @Order(2)
    @DisplayName("save() + find() performs correct round-trip via YAML")
    void save_thenFind_roundTripWorks() {
        TestPlayer original = alice();
        repo.save(original).join();

        Optional<TestPlayer> loaded = repo.find(UUID_ALICE).join();
        assertTrue(loaded.isPresent(), "Entity must be present after save()");
        assertEquals(original, loaded.get(), "Loaded entity must equal the original");
    }

    @Test
    @Order(3)
    @DisplayName("Generated YAML is readable and contains the expected fields")
    void save_yamlContentIsReadable() throws IOException {
        repo.save(alice()).join();

        Path ymlFile = tempDir.resolve("test_players")
            .resolve(UUID_ALICE + ".yml");
        assertTrue(Files.exists(ymlFile), ".yml file must exist");

        String content = new String(Files.readAllBytes(ymlFile));
        assertTrue(content.contains("Alice"),         "YAML must contain the name");
        assertTrue(content.contains("100"),           "YAML must contain the score");
        assertFalse(content.startsWith("{"),          "Must not start with '{' (that would be JSON)");
    }

    @Test
    @Order(4)
    @DisplayName("count() counts only .yml files")
    void count_countOnlyYmlFiles() throws IOException {
        repo.save(alice()).join();
        repo.save(bob()).join();

        // Plant an intruder .json file in the same directory - must not be counted
        Path collectionDir = tempDir.resolve("test_players");
        Files.write(collectionDir.resolve("intruder.json"), "{}".getBytes());

        assertEquals(2L, repo.count().join(),
            "count() must ignore the intruder .json file");
    }

    @Test
    @Order(5)
    @DisplayName("all() returns only entities from .yml files")
    void all_returnsOnlyYmlEntities() throws IOException {
        repo.save(alice()).join();
        repo.save(bob()).join();

        // Plant an invalid .json in the directory - all() must ignore it
        Path collectionDir = tempDir.resolve("test_players");
        Files.write(collectionDir.resolve("garbage.json"), "{not-yaml}".getBytes());

        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertEquals(2, all.size(), "all() must return only the 2 YAML entities");
    }

    @Test
    @Order(6)
    @DisplayName("saveAll() + findMany() works with YAML codec")
    void saveAll_thenFindMany_returnsAll() {
        List<TestPlayer> players = Arrays.asList(alice(), bob(), carol());
        repo.saveAll(players).join();

        List<TestPlayer> found = repo.findMany(
            Arrays.asList(UUID_ALICE, UUID_BOB, UUID_CAROL)).join();

        assertEquals(3, found.size());
        assertTrue(found.containsAll(players));
    }

    @Test
    @Order(7)
    @DisplayName("delete() removes the .yml file from disk")
    void delete_removesYmlFile() throws IOException {
        repo.save(alice()).join();

        Path ymlFile = tempDir.resolve("test_players").resolve(UUID_ALICE + ".yml");
        assertTrue(Files.exists(ymlFile), "File must exist before delete()");

        boolean deleted = repo.delete(UUID_ALICE).join();
        assertTrue(deleted, "delete() must return true");
        assertFalse(Files.exists(ymlFile), ".yml file must have been removed from disk");
    }
}
