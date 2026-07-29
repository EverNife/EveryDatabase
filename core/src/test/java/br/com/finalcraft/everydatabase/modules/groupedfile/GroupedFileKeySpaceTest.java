package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Key spaces: a collection can declare which sub-directory of the base its files live in.
 *
 * <p>One base directory tends to accumulate collections keyed by unrelated things. They share the
 * directory but never share a meaningful key, so every scan reads files that cannot possibly hold
 * the collection being scanned, and an accidental key collision puts two unrelated collections in
 * the same file, behind the same lock. These tests pin the split - and, just as importantly, that a
 * configuration declaring no key space still produces exactly the tree it produced before.
 */
@DisplayName("GroupedFile - key spaces")
class GroupedFileKeySpaceTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB   = UUID.randomUUID();

    private static final String PLAYERDATA = "playerdata";
    private static final String COOLDOWNS  = "cooldowns";

    // ------------------------------------------------------------------
    //  Declaring nothing changes nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("without a key space the tree is exactly the flat one")
    void withoutKeySpace_treeIsFlat() {
        GroupedFileStorage plain = open(new GroupedFileConfig(baseDir));
        repo(plain, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo(plain, COOLDOWNS).save(new TestPlayer(ALICE, "Alice", 1)).join();

        assertTree("both collections share one file, directly under the base",
            "_schema/layout.json", ALICE + ".json");
    }

    @Test
    @DisplayName("a config built through the builder without key spaces is the same config")
    void builderWithoutKeySpaces_matchesTheFlatConstructor() {
        GroupedFileStorage built = open(GroupedFileConfig.builder(baseDir).build());
        repo(built, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo(built, COOLDOWNS).save(new TestPlayer(ALICE, "Alice", 1)).join();

        assertTree(null, "_schema/layout.json", ALICE + ".json");
    }

    // ------------------------------------------------------------------
    //  Declaring one
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a declared key space puts its collection in its own sub-directory")
    void keySpace_ownsASubdirectory() {
        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player", PLAYERDATA)
            .build());

        repo(storage, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo(storage, COOLDOWNS).save(new TestPlayer(ALICE, "Alice", 1)).join();

        assertTree("the key-spaced collection moves out; the undeclared one stays in the base",
            "_schema/layout.json", "player/" + ALICE + ".json", ALICE + ".json");
    }

    @Test
    @DisplayName("a scan of a key-spaced collection never looks at the other directories")
    void scan_ignoresDecoysElsewhere() throws Exception {
        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player",  PLAYERDATA)
            .keySpace("account", COOLDOWNS)
            .build());

        Repository<UUID, TestPlayer> players = repo(storage, PLAYERDATA);
        players.save(new TestPlayer(ALICE, "Alice", 100)).join();

        // Decoys claiming to be this collection, in the base and in the sibling key space. A scan
        // that listed the whole base would pick them up; one that lists its own directory cannot.
        String decoy = "{\"" + PLAYERDATA + "\":{\"uuid\":\"" + BOB + "\",\"name\":\"Decoy\",\"score\":1,"
                     + "\"world\":\"w\",\"active\":true,\"createdAt\":0}}";
        Files.write(baseDir.resolve(BOB + ".json"), decoy.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(baseDir.resolve("account"));
        Files.write(baseDir.resolve("account").resolve(BOB + ".json"), decoy.getBytes(StandardCharsets.UTF_8));

        assertEquals(1L, players.count().join());
        assertEquals(List.of("Alice"), players.all().join().map(TestPlayer::getName).collect(Collectors.toList()));
        assertFalse(players.exists(BOB).join(), "a decoy outside the key space is not this collection's row");
    }

    // ------------------------------------------------------------------
    //  Isolation: two key spaces, one key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same key in two key spaces means two files")
    void sameKeyInTwoKeySpaces_meansTwoFiles() {
        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player",  PLAYERDATA)
            .keySpace("account", COOLDOWNS)
            .build());

        repo(storage, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo(storage, COOLDOWNS).save(new TestPlayer(ALICE, "Alice", 1)).join();

        assertTree("no shared file, so no shared blast radius",
            "_schema/layout.json", "account/" + ALICE + ".json", "player/" + ALICE + ".json");
    }

    @Test
    @DisplayName("and two locks - the stores cannot even name each other's paths")
    void sameKeyInTwoKeySpaces_meansTwoLocks() {
        ContainerFormat format = ContainerFormat.byName(ContainerFormat.JSON);
        KeyFileStore players  = new KeyFileStore(baseDir.resolve("player"),  format, 0);
        KeyFileStore accounts = new KeyFileStore(baseDir.resolve("account"), format, 0);

        String key = KeyFileStore.sanitize(ALICE);
        assertNotSame(players.lockFor(key), accounts.lockFor(key),
            "one lock per file, and these are different files");
        assertNotEquals(players.keyFile(key), accounts.keyFile(key));
        assertSame(players.lockFor(key), players.lockFor(key), "within a store, still one lock per key");
    }

    @Test
    @DisplayName("concurrent writes of one key across two key spaces all land")
    void concurrentWritesAcrossKeySpaces_allLand() {
        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player",  PLAYERDATA)
            .keySpace("account", COOLDOWNS)
            .build());

        Repository<UUID, TestPlayer> players  = repo(storage, PLAYERDATA);
        Repository<UUID, TestPlayer> accounts = repo(storage, COOLDOWNS);

        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writes.add(players.save(new TestPlayer(ALICE, "player-" + i, i)));
            writes.add(accounts.save(new TestPlayer(ALICE, "account-" + i, i)));
        }
        CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).join();

        assertTrue(players.find(ALICE).join().map(TestPlayer::getName).orElse("").startsWith("player-"));
        assertTrue(accounts.find(ALICE).join().map(TestPlayer::getName).orElse("").startsWith("account-"),
            "neither key space may overwrite the other's file");
    }

    // ------------------------------------------------------------------
    //  Validation, at config time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the reserved directory cannot be a key space")
    void schemaIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> GroupedFileConfig.builder(baseDir).keySpace("_schema", PLAYERDATA));
        assertTrue(thrown.getMessage().contains("reserved"), thrown.getMessage());
    }

    @Test
    @DisplayName("a name that is not a safe directory name is refused")
    void unsafeNamesAreRefused() {
        for (String name : List.of("../escape", "with space", "9leading", "trailing-dash", "")) {
            assertThrows(IllegalArgumentException.class,
                () -> GroupedFileConfig.builder(baseDir).keySpace(name, PLAYERDATA),
                () -> "must refuse key space name: " + name);
        }
    }

    @Test
    @DisplayName("a collection cannot belong to two key spaces, and a key space cannot be split in two calls")
    void ambiguousDeclarationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> GroupedFileConfig.builder(baseDir)
            .keySpace("player",  PLAYERDATA)
            .keySpace("account", PLAYERDATA));

        assertThrows(IllegalArgumentException.class, () -> GroupedFileConfig.builder(baseDir)
            .keySpace("player", PLAYERDATA)
            .keySpace("player", COOLDOWNS));

        assertThrows(IllegalArgumentException.class, () -> GroupedFileConfig.builder(baseDir).keySpace("player"));
    }

    // ------------------------------------------------------------------
    //  Divergence from what is on disk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("declaring a key space for a collection already stored flat fails on open")
    void movingACollectionWithoutRelayout_failsOnOpen() {
        GroupedFileStorage flat = open(new GroupedFileConfig(baseDir));
        repo(flat, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();

        GroupedFileConfig moved = GroupedFileConfig.builder(baseDir).keySpace("player", PLAYERDATA).build();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> repo(open(moved), PLAYERDATA));

        String message = thrown.getMessage();
        assertTrue(message.contains("player"),           () -> "names the configured side: " + message);
        assertTrue(message.contains("base directory"),   () -> "names the recorded side: " + message);
        assertTrue(message.contains(PLAYERDATA),         () -> "names the collection: " + message);
    }

    // ------------------------------------------------------------------
    //  The relayout utility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("relayout lifts one collection out of a shared file and leaves the rest behind")
    void relayout_splitsASharedFile() {
        GroupedFileStorage flat = open(new GroupedFileConfig(baseDir));
        repo(flat, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo(flat, COOLDOWNS).save(new TestPlayer(ALICE, "Alice", 7)).join();
        repo(flat, PLAYERDATA).save(new TestPlayer(BOB, "Bob", 50)).join();
        flat.close().join();

        GroupedFileConfig moved = GroupedFileConfig.builder(baseDir).keySpace("player", PLAYERDATA).build();
        GroupedFileRelayout.RelayoutReport report = GroupedFileRelayout.relayout(moved);

        assertTrue(report.changed());
        assertEquals(List.of(PLAYERDATA), report.collectionsMoved());
        assertEquals(2, report.entriesMoved(), "one entry per key that held the collection");
        assertEquals(1, report.filesRemoved(), "Bob's file held nothing else, so it goes");

        assertTree(null, "_schema/layout.json",
            "player/" + ALICE + ".json", "player/" + BOB + ".json", ALICE + ".json");

        GroupedFileStorage reopened = open(moved);
        assertEquals("Alice", repo(reopened, PLAYERDATA).find(ALICE).join().map(TestPlayer::getName).orElse(null));
        assertEquals("Bob",   repo(reopened, PLAYERDATA).find(BOB).join().map(TestPlayer::getName).orElse(null));
        assertEquals(7,       repo(reopened, COOLDOWNS).find(ALICE).join().map(TestPlayer::getScore).orElse(-1));
        assertEquals(2L,      repo(reopened, PLAYERDATA).count().join());
        assertEquals(1L,      repo(reopened, COOLDOWNS).count().join());
    }

    @Test
    @DisplayName("relayout run twice is a no-op the second time")
    void relayout_isIdempotent() {
        GroupedFileStorage flat = open(new GroupedFileConfig(baseDir));
        repo(flat, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        flat.close().join();

        GroupedFileConfig moved = GroupedFileConfig.builder(baseDir).keySpace("player", PLAYERDATA).build();
        assertTrue(GroupedFileRelayout.relayout(moved).changed());

        List<String> after = tree();
        GroupedFileRelayout.RelayoutReport second = GroupedFileRelayout.relayout(moved);
        assertFalse(second.changed(), "nothing left to move");
        assertEquals(0, second.entriesMoved());
        assertEquals(after, tree());
    }

    @Test
    @DisplayName("relayout also moves a collection back out of a key space")
    void relayout_movesBackToTheBase() {
        GroupedFileConfig moved = GroupedFileConfig.builder(baseDir).keySpace("player", PLAYERDATA).build();
        GroupedFileStorage storage = open(moved);
        repo(storage, PLAYERDATA).save(new TestPlayer(ALICE, "Alice", 100)).join();
        storage.close().join();

        GroupedFileConfig flat = new GroupedFileConfig(baseDir);
        assertTrue(GroupedFileRelayout.relayout(flat).changed());
        assertTree(null, "_schema/layout.json", ALICE + ".json");
        assertEquals("Alice", repo(open(flat), PLAYERDATA).find(ALICE).join().map(TestPlayer::getName).orElse(null));
    }

    @Test
    @DisplayName("relayout on a directory nothing was ever stored in does nothing")
    void relayout_onAnUntouchedDirectory_doesNothing() {
        GroupedFileRelayout.RelayoutReport report = GroupedFileRelayout.relayout(
            GroupedFileConfig.builder(baseDir).keySpace("player", PLAYERDATA).build());

        assertFalse(report.changed());
        assertTree(null);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private GroupedFileStorage open(GroupedFileConfig config) {
        GroupedFileStorage storage = Storages.createGroupedFile(config);
        storage.init().join();
        return storage;
    }

    private Repository<UUID, TestPlayer> repo(GroupedFileStorage storage, String collection) {
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build());
    }

    /** The base holds exactly these files - as a set, since directory order carries no meaning. */
    private void assertTree(String why, String... expected) {
        List<String> want = new ArrayList<>(List.of(expected));
        want.sort(Comparator.naturalOrder());
        assertEquals(want, tree(), why);
    }

    /** Every regular file under the base, as a sorted list of forward-slash relative paths. */
    private List<String> tree() {
        try (Stream<Path> paths = Files.walk(baseDir)) {
            return paths.filter(Files::isRegularFile)
                .map(p -> baseDir.relativize(p).toString().replace('\\', '/'))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
