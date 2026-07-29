package br.com.finalcraft.everydatabase.changefeed;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFilePartitioner;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The file backends' change feed: the operating system reports what changed in the directory.
 *
 * <p>This is the one feed that sees a change made <em>outside</em> the application - an
 * administrator editing a file by hand - so the tests write through a second storage over the same
 * directory rather than through the one being watched.
 *
 * <p>Timing is inherent here, so nothing sleeps for a fixed period: each assertion waits for a
 * condition with a generous ceiling, which fails fast when the feed is broken and never flakes when
 * the machine is slow.
 */
@DisplayName("File backends - change feed from the file system")
class DirectoryChangeFeedTest {

    @TempDir Path sharedDir;

    private final List<Storage> opened = new ArrayList<>();

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Duration CEILING = Duration.ofSeconds(20);

    @AfterEach
    void closeEverything() {
        for (Storage storage : opened) {
            try { storage.close().join(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------
    //  The capability
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both file backends now have a change feed")
    void bothFileBackendsHaveAFeed() {
        assertInstanceOf(ChangeFeedStorage.class, localFile(sharedDir.resolve("lf")));
        assertInstanceOf(ChangeFeedStorage.class, groupedFile(sharedDir.resolve("gf")));
    }

    // ------------------------------------------------------------------
    //  Local files
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LocalFile: a write by another instance arrives as a SAVE")
    void localFile_externalWrite() {
        Path dir = sharedDir.resolve("lf-save");
        Storage watched = localFile(dir);
        Repository<UUID, TestPlayer> watchedRepo = repoOn(watched);   // create the collection directory
        watchedRepo.save(new TestPlayer(UUID.randomUUID(), "seed", 0)).join();

        CopyOnWriteArrayList<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        ((ChangeFeedStorage) watched).subscribe(seen::add);

        repoOn(localFile(dir)).save(new TestPlayer(ALICE, "Alice", 100)).join();

        await(() -> seen.stream().anyMatch(e -> e.op() == ChangeOp.SAVE
            && ALICE.toString().equals(e.key())
            && "quests".equals(e.collection())), () -> "no SAVE for " + ALICE + " in " + seen);
    }

    @Test
    @DisplayName("LocalFile: a delete by another instance arrives as a DELETE")
    void localFile_externalDelete() {
        Path dir = sharedDir.resolve("lf-delete");
        Storage watched = localFile(dir);
        Repository<UUID, TestPlayer> watchedRepo = repoOn(watched);
        watchedRepo.save(new TestPlayer(ALICE, "Alice", 100)).join();

        CopyOnWriteArrayList<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        ((ChangeFeedStorage) watched).subscribe(seen::add);

        repoOn(localFile(dir)).delete(ALICE).join();

        await(() -> seen.stream().anyMatch(e -> e.op() == ChangeOp.DELETE
            && ALICE.toString().equals(e.key())), () -> "no DELETE for " + ALICE + " in " + seen);
    }

    // ------------------------------------------------------------------
    //  Grouped files
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GroupedFile: a write reaches every collection of the key space")
    void groupedFile_externalWrite_reachesEveryCollection() {
        Path dir = sharedDir.resolve("gf-save");
        Storage watched = groupedFile(dir);
        watched.repository(descriptor("quests"));
        watched.repository(descriptor("companions"));

        CopyOnWriteArrayList<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        ((ChangeFeedStorage) watched).subscribe(seen::add);

        repoOn(groupedFile(dir)).save(new TestPlayer(ALICE, "Alice", 100)).join();

        // The file holds both collections and the OS reports the file, not the part of it that
        // changed - so both are woken. A false wake-up, never a missed one.
        await(() -> seen.stream().anyMatch(e -> "quests".equals(e.collection())),
            () -> "the written collection must be woken: " + seen);
        await(() -> seen.stream().anyMatch(e -> "companions".equals(e.collection())),
            () -> "its file-mates must be woken too: " + seen);
        assertTrue(seen.stream().allMatch(e -> ALICE.toString().equals(e.key())));
    }

    @Test
    @DisplayName("GroupedFile: a file created in a bucket that did not exist is still seen")
    void groupedFile_newBucket_isWatched() {
        Path dir = sharedDir.resolve("gf-fanout");
        GroupedFileConfig config = GroupedFileConfig.builder(dir)
            .keySpace("player", GroupedFilePartitioner.hashFanout(2), "quests")
            .build();

        Storage watched = open(Storages.createGroupedFile(config));
        watched.repository(descriptor("quests"));

        CopyOnWriteArrayList<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        ((ChangeFeedStorage) watched).subscribe(seen::add);

        // Nothing has been written yet, so player/10/c5/ does not exist when the watch starts: the
        // feed has to register directories that appear afterwards or fan-out would be a blind spot.
        Storage other = open(Storages.createGroupedFile(config));
        other.repository(descriptor("quests")).save(new TestPlayer(ALICE, "Alice", 100)).join();

        await(() -> seen.stream().anyMatch(e -> ALICE.toString().equals(e.key())),
            () -> "a write into a freshly created bucket must still be seen: " + seen);
    }

    @Test
    @DisplayName("GroupedFile: the memoized document is dropped before the event goes out")
    void groupedFile_memoIsDroppedFirst() {
        Path dir = sharedDir.resolve("gf-memo");
        Storage watched = groupedFile(dir);
        Repository<UUID, TestPlayer> watchedRepo = repoOn(watched);
        watchedRepo.save(new TestPlayer(ALICE, "before", 1)).join();
        assertEquals("before", watchedRepo.find(ALICE).join().map(TestPlayer::getName).orElse(null));

        CopyOnWriteArrayList<String> readBack = new CopyOnWriteArrayList<>();
        ((ChangeFeedStorage) watched).subscribe(event ->
            readBack.add(watchedRepo.find(ALICE).join().map(TestPlayer::getName).orElse("<gone>")));

        repoOn(groupedFile(dir)).save(new TestPlayer(ALICE, "after", 2)).join();

        // A listener that reads on the spot must see what is on disk. If the memo were still
        // installed it would answer "before" - the whole point of dropping it first.
        await(() -> readBack.contains("after"),
            () -> "listener kept reading the memoized document: " + readBack);
    }

    // ------------------------------------------------------------------
    //  Lifecycle and isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("closing twice is fine and leaves no thread behind")
    void closeIsIdempotent() {
        Path dir = sharedDir.resolve("close");
        Storage watched = localFile(dir);
        repoOn(watched);
        ((ChangeFeedStorage) watched).subscribe(event -> { });

        await(DirectoryChangeFeedTest::watchThreadAlive, () -> "the watch thread never started");

        watched.close().join();
        assertDoesNotThrow(() -> watched.close().join(), "close() must be idempotent");
        await(() -> !watchThreadAlive(), () -> "the watch thread outlived close()");
    }

    @Test
    @DisplayName("a listener that throws breaks neither the write nor the other listeners")
    void throwingListener_isIsolated() {
        Path dir = sharedDir.resolve("throwing");
        Storage watched = localFile(dir);
        Repository<UUID, TestPlayer> watchedRepo = repoOn(watched);
        watchedRepo.save(new TestPlayer(UUID.randomUUID(), "seed", 0)).join();

        CopyOnWriteArrayList<ChangeEvent> survivor = new CopyOnWriteArrayList<>();
        ChangeFeedStorage feed = (ChangeFeedStorage) watched;
        feed.subscribe(event -> { throw new IllegalStateException("listener is broken"); });
        feed.subscribe(survivor::add);

        assertDoesNotThrow(() -> repoOn(localFile(dir)).save(new TestPlayer(ALICE, "Alice", 1)).join(),
            "a broken listener must not fail the write that triggered it");
        await(() -> survivor.stream().anyMatch(e -> ALICE.toString().equals(e.key())),
            () -> "the healthy listener must still be served: " + survivor);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static boolean watchThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("everydatabase-localfile-watch") && t.isAlive()) return true;
        }
        return false;
    }

    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> message) {
        long deadline = System.nanoTime() + CEILING.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(25);       // a poll of the assertion, not a guess at how long to wait
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(message.get());
    }

    private Storage localFile(Path dir) {
        return open(Storages.createLocalFile(new LocalFileConfig(dir)));
    }

    private Storage groupedFile(Path dir) {
        return open(Storages.createGroupedFile(new GroupedFileConfig(dir).rootCacheSize(8)));
    }

    private Storage open(Storage storage) {
        storage.init().join();
        opened.add(storage);
        return storage;
    }

    private Repository<UUID, TestPlayer> repoOn(Storage storage) {
        return storage.repository(descriptor("quests"));
    }

    private static EntityDescriptor<UUID, TestPlayer> descriptor(String collection) {
        return EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
    }
}
