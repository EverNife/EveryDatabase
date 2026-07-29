package br.com.finalcraft.everydatabase.modules;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.testutil.CountingCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version polling on the backends that have no lock column.
 *
 * <p>These two used to report {@code 0} for every existing key, which meant a poller watching them
 * could tell that a key had been deleted elsewhere but never that it had been <em>changed</em> -
 * two processes over one directory served stale data indefinitely. The poller does not actually
 * need a lock version, though: it compares one reading against the previous one and asks whether it
 * grew. A file already answers that.
 */
@DisplayName("File backends - versions() derived from the file")
class FileStampVersionsTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB   = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("an existing key reports a real stamp, and a rewrite makes it grow")
    void rewriting_growsTheVersion() {
        onEachFileBackend((repo) -> {
            repo.save(new TestPlayer(ALICE, "Alice", 1)).join();

            Long first = repo.versions(Collections.singletonList(ALICE)).join().get(ALICE);
            assertNotNull(first, "an existing key must carry a version");
            assertTrue(first > 0L, "the version must be derived from the file, not the old constant 0");

            // A longer payload, so the stamp differs even inside one clock tick: the size lives in
            // its low bits precisely so a test does not have to sleep out the filesystem's
            // timestamp granularity to observe a change.
            repo.save(new TestPlayer(ALICE, "Alice-with-a-much-longer-name", 2)).join();

            Long second = repo.versions(Collections.singletonList(ALICE)).join().get(ALICE);
            assertNotNull(second);
            assertNotEquals(first, second, "a rewrite must change the version the poller compares");
            return null;
        });
    }

    @Test
    @DisplayName("a later modification time always reads as a later version")
    void laterModificationTime_readsAsLaterVersion() {
        onEachFileBackend((repo) -> {
            repo.save(new TestPlayer(ALICE, "Alice", 1)).join();
            Long before = repo.versions(Collections.singletonList(ALICE)).join().get(ALICE);

            // Set the time directly rather than waiting for the clock: what is being pinned is that
            // the stamp is ordered by modification time, not how fast the filesystem ticks.
            Path file = onlyKeyFile();
            assertDoesNotThrow(() -> Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000)));

            Long after = repo.versions(Collections.singletonList(ALICE)).join().get(ALICE);
            assertTrue(after > before,
                "the poller only reloads on a bigger number, so time must dominate the stamp");
            return null;
        });
    }

    @Test
    @DisplayName("a deleted key drops out of the map, as before")
    void deletedKey_isOmitted() {
        onEachFileBackend((repo) -> {
            repo.save(new TestPlayer(ALICE, "Alice", 1)).join();
            repo.save(new TestPlayer(BOB, "Bob", 2)).join();
            repo.delete(BOB).join();

            Map<UUID, Long> versions = repo.versions(Arrays.asList(ALICE, BOB)).join();
            assertTrue(versions.containsKey(ALICE));
            assertFalse(versions.containsKey(BOB), "an absent key is how a poller sees a remote delete");
            return null;
        });
    }

    @Test
    @DisplayName("reading a version still decodes nothing")
    void versions_decodeNothing() {
        for (boolean grouped : new boolean[] { false, true }) {
            Path dir = baseDir.resolve("decode-" + grouped);
            CountingCodec<TestPlayer> codec = new CountingCodec<>(new JacksonJsonCodec<>(TestPlayer.class));
            Repository<UUID, TestPlayer> repo = openRepository(grouped, dir, codec);

            repo.save(new TestPlayer(ALICE, "Alice", 1)).join();
            codec.resetCounts();

            assertEquals(1, repo.versions(Collections.singletonList(ALICE)).join().size());
            assertEquals(0, codec.decodeCount(),
                (grouped ? "GroupedFile" : "LocalFile") + ": a version is a stat, never a read");
        }
    }

    @Test
    @DisplayName("on grouped files a key whose file lost the collection reads as deleted")
    void groupedFile_collectionMatters() {
        Path dir = baseDir.resolve("shared");
        Storage storage = Storages.createGroupedFile(new GroupedFileConfig(dir));
        storage.init().join();

        Repository<UUID, TestPlayer> quests    = storage.repository(descriptor("quests"));
        Repository<UUID, TestPlayer> companions = storage.repository(descriptor("companions"));
        quests.save(new TestPlayer(ALICE, "Alice", 1)).join();
        companions.save(new TestPlayer(ALICE, "Alice", 1)).join();

        quests.delete(ALICE).join();   // the file survives - the companion collection is still in it

        assertTrue(companions.versions(Collections.singletonList(ALICE)).join().containsKey(ALICE));
        assertFalse(quests.versions(Collections.singletonList(ALICE)).join().containsKey(ALICE),
            "however fresh the file is, this collection is gone from it - that is a delete");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Runs {@code body} against LocalFile and GroupedFile, naming the backend when it fails. */
    private void onEachFileBackend(Function<Repository<UUID, TestPlayer>, Void> body) {
        for (boolean grouped : new boolean[] { false, true }) {
            Path dir = baseDir.resolve((grouped ? "grouped" : "local") + "-" + UUID.randomUUID());
            try {
                body.apply(openRepository(grouped, dir, new JacksonJsonCodec<>(TestPlayer.class)));
            } catch (AssertionError e) {
                throw new AssertionError((grouped ? "GroupedFile: " : "LocalFile: ") + e.getMessage(), e);
            }
            lastDirectory = dir;
        }
    }

    private Path lastDirectory;

    private Repository<UUID, TestPlayer> openRepository(
            boolean grouped, Path dir, br.com.finalcraft.everydatabase.codec.Codec<TestPlayer> codec) {
        Storage storage = grouped
            ? Storages.createGroupedFile(new GroupedFileConfig(dir))
            : Storages.createLocalFile(new LocalFileConfig(dir));
        storage.init().join();
        lastDirectory = dir;
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("quests")
            .keyExtractor(TestPlayer::getUuid)
            .codec(codec)
            .build());
    }

    /** The single stored key file of the backend most recently opened, wherever it landed. */
    private Path onlyKeyFile() {
        try (java.util.stream.Stream<Path> paths = Files.walk(lastDirectory)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> !p.getParent().getFileName().toString().equals("_schema"))
                .findFirst().orElseThrow(() -> new AssertionError("no key file under " + lastDirectory));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static EntityDescriptor<UUID, TestPlayer> descriptor(String collection) {
        return EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
    }
}
