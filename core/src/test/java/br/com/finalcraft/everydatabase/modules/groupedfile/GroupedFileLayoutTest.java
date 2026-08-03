package br.com.finalcraft.everydatabase.modules.groupedfile;

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
 * The directory describes itself, and disagreeing with that description is an error.
 *
 * <p>Before {@code _schema/layout.json} existed, the container format was whatever the first codec
 * to open the directory happened to be, and nothing on disk recorded it. Opening a YAML directory
 * with a JSON codec was therefore not a failure of any kind: the key-file listing filters by
 * extension, so it found nothing, reported an empty collection, and the first save wrote a parallel
 * {@code .json} file beside the {@code .yml} one still holding the data. These tests pin the
 * behaviour that replaced it - fail on open, name both formats, touch nothing.
 */
@DisplayName("GroupedFile - the directory describes itself")
class GroupedFileLayoutTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.randomUUID();

    // ------------------------------------------------------------------
    //  The bug this file exists for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reopening a YAML directory with a JSON codec fails instead of hiding the data")
    void yamlDirectory_reopenedWithJsonCodec_fails() {
        writeAlice(new JacksonYamlCodec<>(TestPlayer.class));
        assertEquals(1, keyFilesWithSuffix(".yml"), "precondition: the data is on disk as YAML");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));

        String message = thrown.getMessage();
        assertTrue(message.contains("YAML"), () -> "must name the stored format: " + message);
        assertTrue(message.contains("JSON"), () -> "must name the codec's format: " + message);
        assertTrue(message.contains("players"), () -> "must name the collection being opened: " + message);

        // What the failure protects: the data is untouched and no parallel file was started.
        assertEquals(1, keyFilesWithSuffix(".yml"), "the stored files must be left alone");
        assertEquals(0, keyFilesWithSuffix(".json"), "no parallel key file may be created");
    }

    @Test
    @DisplayName("the same directory still reads back through a matching codec")
    void yamlDirectory_reopenedWithYamlCodec_reads() {
        writeAlice(new JacksonYamlCodec<>(TestPlayer.class));

        Repository<UUID, TestPlayer> repo = openRepository(new JacksonYamlCodec<>(TestPlayer.class));
        assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null));
    }

    // ------------------------------------------------------------------
    //  Writing the layout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new directory gains a layout file naming its format")
    void newDirectory_gainsLayoutFile() throws Exception {
        openRepository(new JacksonJsonCodec<>(TestPlayer.class));

        assertEquals("json", readLayout().path("format").asText());
    }

    @Test
    @DisplayName("a YAML directory records yaml, not the layout file's own format")
    void yamlDirectory_recordsYaml() throws Exception {
        openRepository(new JacksonYamlCodec<>(TestPlayer.class));

        // The layout file is always JSON (it is machine state, not user data); what it records is
        // the format of the key files around it.
        assertEquals("yaml", readLayout().path("format").asText());
        assertTrue(Files.readAllBytes(layoutFile()).length > 0);
    }

    @Test
    @DisplayName("reopening with the same codec leaves the layout untouched")
    void reopeningWithSameCodec_keepsLayout() throws Exception {
        writeAlice(new JacksonJsonCodec<>(TestPlayer.class));
        byte[] before = Files.readAllBytes(layoutFile());

        openRepository(new JacksonJsonCodec<>(TestPlayer.class));
        assertArrayEquals(before, Files.readAllBytes(layoutFile()));
    }

    // ------------------------------------------------------------------
    //  Directories from before the layout file existed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a directory written before layouts existed stays readable and gains an inferred one")
    void legacyDirectory_isInferred() throws Exception {
        // Hand-written, exactly as the backend would have left it: no _schema/layout.json anywhere.
        Files.write(baseDir.resolve(ALICE + ".yml"),
            ("players:\n"
             + "  uuid: \"" + ALICE + "\"\n"
             + "  name: \"Alice\"\n"
             + "  score: 100\n"
             + "  world: \"world\"\n"
             + "  active: true\n"
             + "  createdAt: 0\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(Files.exists(layoutFile()), "precondition: nothing describes this directory yet");

        Repository<UUID, TestPlayer> repo = openRepository(new JacksonYamlCodec<>(TestPlayer.class));

        assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null));
        assertEquals("yaml", readLayout().path("format").asText(),
            "the format must be inferred from the files that were already there");
    }

    @Test
    @DisplayName("a legacy directory whose files disagree with the codec fails rather than being adopted")
    void legacyDirectory_disagreeingWithCodec_fails() throws Exception {
        Files.write(baseDir.resolve(ALICE + ".yml"), "players: {}\n".getBytes(StandardCharsets.UTF_8));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));
        assertTrue(thrown.getMessage().contains("inferred"),
            () -> "the message must say where the stored format came from: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a directory holding both formats refuses to be opened by either")
    void directoryWithBothFormats_fails() throws Exception {
        Files.write(baseDir.resolve(ALICE + ".yml"),  "players: {}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(baseDir.resolve(ALICE + ".json"), "{\"players\":{}}".getBytes(StandardCharsets.UTF_8));

        for (Codec<TestPlayer> codec : List.of(
                new JacksonJsonCodec<>(TestPlayer.class), new JacksonYamlCodec<>(TestPlayer.class))) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> openRepository(codec));
            String message = thrown.getMessage();
            assertTrue(message.contains("1 .json") && message.contains("1 .yml"),
                () -> "the message must count both sets so the operator can tell which to keep: " + message);
        }
    }

    // ------------------------------------------------------------------
    //  Files this version cannot reach
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a key file in a sub-directory fails the open instead of being ignored")
    void keyFilesBelowBase_failOpen() throws Exception {
        Path below = baseDir.resolve("player").resolve(ALICE + ".json");
        Files.createDirectories(below.getParent());
        Files.write(below, "{\"players\":{}}".getBytes(StandardCharsets.UTF_8));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));

        String message = thrown.getMessage();
        assertTrue(message.contains("player"), () -> "must name the sub-directory: " + message);

        // What the failure protects: nothing was adopted and nothing was written beside the data.
        assertFalse(Files.exists(layoutFile()), "the directory must not describe itself from a guess");
        assertEquals(0, keyFilesWithSuffix(".json"), "no parallel key file may be created");
    }

    @Test
    @DisplayName("a layout from a build that placed files in sub-directories is not rewritten")
    void layoutRecordingSubdirectory_failsWithoutRewriting() throws Exception {
        Path below = baseDir.resolve("player").resolve(ALICE + ".json");
        Files.createDirectories(below.getParent());
        Files.write(below, "{\"players\":{}}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(layoutFile().getParent());
        Files.write(layoutFile(),
            "{\"format\":\"json\",\"collections\":{\"players\":\"player\"}}".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(layoutFile());

        assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));

        assertArrayEquals(before, Files.readAllBytes(layoutFile()),
            "the open must fail before anything is written back");
    }

    @Test
    @DisplayName("the reserved directory is the storage's own and never counts as a stray")
    void reservedDirectory_isNotAStray() {
        Repository<UUID, TestPlayer> repo = openRepository(new JacksonJsonCodec<>(TestPlayer.class));
        repo.save(new TestPlayer(ALICE, "Alice", 100)).join();

        // _schema/layout.json is a .json file below the base, and the guard must not trip on it.
        assertTrue(Files.exists(layoutFile()));
        assertEquals("Alice", openRepository(new JacksonJsonCodec<>(TestPlayer.class))
            .find(ALICE).join().map(TestPlayer::getName).orElse(null));
    }

    // ------------------------------------------------------------------
    //  A layout that cannot be trusted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unreadable layout fails the open rather than falling back to a guess")
    void corruptLayout_failsOpen() throws Exception {
        Files.createDirectories(layoutFile().getParent());
        Files.write(layoutFile(), "{ this is not json".getBytes(StandardCharsets.UTF_8));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));
        assertTrue(thrown.getMessage().contains(GroupedFileLayout.LAYOUT_FILE),
            () -> "the message must name the file to repair: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a layout declaring an unknown format is treated as unreadable")
    void unknownFormatInLayout_failsOpen() throws Exception {
        Files.createDirectories(layoutFile().getParent());
        Files.write(layoutFile(), "{\"format\":\"toml\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
            () -> openRepository(new JacksonJsonCodec<>(TestPlayer.class)));
    }

    @Test
    @DisplayName("the layout lives beside the migration ledger without disturbing it")
    void layoutAndMigrationLedger_coexist() throws Exception {
        Files.createDirectories(layoutFile().getParent());
        Path ledger = layoutFile().resolveSibling(GroupedFileStorage.MIGRATIONS_FILE);
        Files.write(ledger, "[]".getBytes(StandardCharsets.UTF_8));

        openRepository(new JacksonJsonCodec<>(TestPlayer.class));

        assertTrue(Files.exists(layoutFile()));
        assertEquals("[]", new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8),
            "the ledger is a different file and must not be rewritten");
        assertNotEquals(GroupedFileLayout.LAYOUT_FILE, GroupedFileStorage.MIGRATIONS_FILE);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Path layoutFile() {
        return baseDir.resolve(GroupedFileStorage.SCHEMA_DIR).resolve(GroupedFileLayout.LAYOUT_FILE);
    }

    private JsonNode readLayout() throws Exception {
        return new ObjectMapper().readTree(Files.readAllBytes(layoutFile()));
    }

    private Repository<UUID, TestPlayer> openRepository(Codec<TestPlayer> codec) {
        GroupedFileStorage storage = Storages.createGroupedFile(new GroupedFileConfig(baseDir));
        storage.init().join();
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(codec)
            .build());
    }

    private void writeAlice(Codec<TestPlayer> codec) {
        openRepository(codec).save(new TestPlayer(ALICE, "Alice", 100)).join();
    }

    private long keyFilesWithSuffix(String suffix) {
        try (Stream<Path> entries = Files.list(baseDir)) {
            return entries.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .count();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
