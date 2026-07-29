package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The local-file store describes itself, and disagreeing with that description is an error.
 *
 * <p>Every path this backend resolves ends in {@code codec.fileExtension()}. Before
 * {@code _schema_layout.json} existed nothing recorded which extension a collection had actually
 * been written with, so opening one with a codec of another format was not a failure of any kind:
 * the listing matched nothing, the collection read as empty, and the first save wrote a parallel
 * file beside the one still holding the data.
 *
 * <p>Unlike grouped files, the record is <em>per collection</em>: each one owns a sub-directory, so
 * two collections in one store may legitimately differ.
 */
@DisplayName("LocalFile - the store describes itself")
class LocalFileLayoutTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.randomUUID();

    // ------------------------------------------------------------------
    //  The bug this file exists for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reopening a YAML collection with a JSON codec fails instead of hiding the data")
    void yamlCollection_reopenedWithJsonCodec_fails() {
        openRepository("quests", new JacksonYamlCodec<>(TestPlayer.class))
            .save(new TestPlayer(ALICE, "Alice", 100)).join();
        assertEquals(1, filesWithSuffix("quests", ".yml"), "precondition: the data is on disk as YAML");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class)));

        String message = thrown.getMessage();
        assertTrue(message.contains(".yml"),  () -> "must name the stored format: " + message);
        assertTrue(message.contains(".json"), () -> "must name the codec's format: " + message);
        assertTrue(message.contains("quests"), () -> "must name the collection: " + message);

        assertEquals(1, filesWithSuffix("quests", ".yml"),  "the stored files must be left alone");
        assertEquals(0, filesWithSuffix("quests", ".json"), "no parallel file may be created");
    }

    @Test
    @DisplayName("the same collection still reads back through a matching codec")
    void yamlCollection_reopenedWithYamlCodec_reads() {
        openRepository("quests", new JacksonYamlCodec<>(TestPlayer.class))
            .save(new TestPlayer(ALICE, "Alice", 100)).join();

        assertEquals("Alice", openRepository("quests", new JacksonYamlCodec<>(TestPlayer.class))
            .find(ALICE).join().map(TestPlayer::getName).orElse(null));
    }

    // ------------------------------------------------------------------
    //  Per collection, not per store
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two collections of one store may use different formats")
    void collectionsAreIndependent() throws Exception {
        openRepository("quests", new JacksonYamlCodec<>(TestPlayer.class))
            .save(new TestPlayer(ALICE, "Alice", 100)).join();
        openRepository("players", new JacksonJsonCodec<>(TestPlayer.class))
            .save(new TestPlayer(ALICE, "Alice", 100)).join();

        JsonNode collections = readLayout().path("collections");
        assertEquals("yml",  collections.path("quests").asText());
        assertEquals("json", collections.path("players").asText());

        assertEquals(1, filesWithSuffix("quests", ".yml"));
        assertEquals(1, filesWithSuffix("players", ".json"));
    }

    @Test
    @DisplayName("a new collection is recorded on its first open")
    void newCollection_isRecorded() throws Exception {
        openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class));

        assertTrue(Files.exists(layoutFile()));
        assertEquals("json", readLayout().path("collections").path("quests").asText());
    }

    @Test
    @DisplayName("reopening with the same codec leaves the layout untouched")
    void reopeningWithSameCodec_keepsLayout() throws Exception {
        openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class));
        byte[] before = Files.readAllBytes(layoutFile());

        openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class));
        assertArrayEquals(before, Files.readAllBytes(layoutFile()));
    }

    // ------------------------------------------------------------------
    //  Collections from before the layout file existed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collection written before layouts existed stays readable and gains an inferred record")
    void legacyCollection_isInferred() throws Exception {
        Files.createDirectories(baseDir.resolve("quests"));
        Files.write(baseDir.resolve("quests").resolve(ALICE + ".yml"),
            ("uuid: \"" + ALICE + "\"\n"
             + "name: \"Alice\"\n"
             + "score: 100\n"
             + "world: \"world\"\n"
             + "active: true\n"
             + "createdAt: 0\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(Files.exists(layoutFile()), "precondition: nothing describes this store yet");

        Repository<UUID, TestPlayer> repo = openRepository("quests", new JacksonYamlCodec<>(TestPlayer.class));

        assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null));
        assertEquals("yml", readLayout().path("collections").path("quests").asText(),
            "the format must be inferred from the files that were already there");
    }

    @Test
    @DisplayName("a legacy collection whose files disagree with the codec fails rather than being adopted")
    void legacyCollection_disagreeingWithCodec_fails() throws Exception {
        Files.createDirectories(baseDir.resolve("quests"));
        Files.write(baseDir.resolve("quests").resolve(ALICE + ".yml"), "name: x\n".getBytes(StandardCharsets.UTF_8));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class)));
        assertTrue(thrown.getMessage().contains("inferred"),
            () -> "the message must say where the stored format came from: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a collection holding both formats refuses to be opened by either")
    void collectionWithBothFormats_fails() throws Exception {
        Files.createDirectories(baseDir.resolve("quests"));
        Files.write(baseDir.resolve("quests").resolve(ALICE + ".yml"),  "name: x\n".getBytes(StandardCharsets.UTF_8));
        Files.write(baseDir.resolve("quests").resolve(ALICE + ".json"), "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        for (Codec<TestPlayer> codec : List.of(
                new JacksonJsonCodec<>(TestPlayer.class), new JacksonYamlCodec<>(TestPlayer.class))) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> openRepository("quests", codec));
            String message = thrown.getMessage();
            assertTrue(message.contains("1 .json") && message.contains("1 .yml"),
                () -> "the message must count both sets so the operator can tell which to keep: " + message);
        }
    }

    @Test
    @DisplayName("an orphan .tmp from an interrupted write does not count as a format")
    void orphanTempFile_isIgnored() {
        assertDoesNotThrow(() -> {
            Files.createDirectories(baseDir.resolve("quests"));
            Files.write(baseDir.resolve("quests").resolve(ALICE + ".json"), "{}".getBytes(StandardCharsets.UTF_8));
            Files.write(baseDir.resolve("quests").resolve(ALICE + ".json.tmp"), "{}".getBytes(StandardCharsets.UTF_8));
            openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class));
        });
    }

    // ------------------------------------------------------------------
    //  A layout that cannot be trusted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unreadable layout fails the open rather than falling back to a guess")
    void corruptLayout_failsOpen() throws Exception {
        Files.createDirectories(baseDir);
        Files.write(layoutFile(), "{ this is not json".getBytes(StandardCharsets.UTF_8));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class)));
        assertTrue(thrown.getMessage().contains(LocalFileLayout.LAYOUT_FILE),
            () -> "the message must name the file to repair: " + thrown.getMessage());
    }

    @Test
    @DisplayName("the layout lives beside the migration ledger without disturbing it")
    void layoutAndMigrationLedger_coexist() throws Exception {
        Files.createDirectories(baseDir);
        Path ledger = baseDir.resolve(LocalFileStorage.MIGRATIONS_FILE);
        Files.write(ledger, "[]".getBytes(StandardCharsets.UTF_8));

        openRepository("quests", new JacksonJsonCodec<>(TestPlayer.class));

        assertTrue(Files.exists(layoutFile()));
        assertEquals("[]", new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8),
            "the ledger is a different file and must not be rewritten");
        assertNotEquals(LocalFileLayout.LAYOUT_FILE, LocalFileStorage.MIGRATIONS_FILE);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Path layoutFile() {
        return baseDir.resolve(LocalFileLayout.LAYOUT_FILE);
    }

    private JsonNode readLayout() throws Exception {
        return new ObjectMapper().readTree(Files.readAllBytes(layoutFile()));
    }

    private Repository<UUID, TestPlayer> openRepository(String collection, Codec<TestPlayer> codec) {
        LocalFileStorage storage = Storages.createLocalFile(new LocalFileConfig(baseDir));
        storage.init().join();
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(codec)
            .build());
    }

    private long filesWithSuffix(String collection, String suffix) {
        Path directory = baseDir.resolve(collection);
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .count();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
