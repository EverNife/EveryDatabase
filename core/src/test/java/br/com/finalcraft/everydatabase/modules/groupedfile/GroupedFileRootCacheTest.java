package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How often the key-major backend parses a whole aggregate document.
 *
 * <p>A key file holds every collection sharing its key, so loading one entity-root means reading the
 * same file once per collection. The document is memoized between those reads, which is invisible
 * from the outside - every read returns the same answer either way - so these tests assert the
 * parse counter the store keeps instead.
 */
@DisplayName("GroupedFile - aggregate document memo")
class GroupedFileRootCacheTest {

    @TempDir Path baseDir;

    private static final UUID KEY = UUID.randomUUID();

    private GroupedFileStorage storage;

    @AfterEach
    void tearDown() {
        if (storage != null) storage.close().join();
    }

    private GroupedFileStorage open(GroupedFileConfig config) {
        storage = new GroupedFileStorage(config);
        storage.init().join();
        return storage;
    }

    private Repository<UUID, TestPlayer> repo(String collection) {
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build());
    }

    private long parses() {
        return storage.keyFileStore().rootParseCount();
    }

    private static final List<String> COLLECTIONS =
        List.of("player_data", "economy", "homes", "quests", "auth");

    @Test
    @DisplayName("reading five collections of one key parses the document once")
    void pointReadsOfOneKey_parseOnce() {
        open(new GroupedFileConfig(baseDir));
        for (String collection : COLLECTIONS) {
            repo(collection).save(new TestPlayer(KEY, "in_" + collection, 1)).join();
        }
        // Reopen so the reads start cold - the writes above already left the document memoized,
        // which is the point of the next test, not this one.
        storage.close().join();
        open(new GroupedFileConfig(baseDir));

        for (String collection : COLLECTIONS) {
            assertEquals("in_" + collection, repo(collection).find(KEY).join()
                .map(TestPlayer::getName).orElse(null));
        }

        assertEquals(1, parses(),
            "the five reads share one key file, so only the first may parse it");
    }

    @Test
    @DisplayName("with the memo disabled, every read parses again")
    void memoDisabled_parsesPerRead() {
        open(new GroupedFileConfig(baseDir).rootCacheSize(0));
        for (String collection : COLLECTIONS) {
            repo(collection).save(new TestPlayer(KEY, "in_" + collection, 1)).join();
        }

        long before = parses();
        for (String collection : COLLECTIONS) {
            repo(collection).find(KEY).join();
        }

        assertEquals(COLLECTIONS.size(), parses() - before, "nothing is memoized, so each read parses");
        assertEquals(0, storage.keyFileStore().cachedRootCount(), "a disabled memo holds nothing");
    }

    @Test
    @DisplayName("a write by another process is picked up on the next read")
    void externalWrite_invalidatesTheMemo() throws IOException {
        open(new GroupedFileConfig(baseDir));
        Repository<UUID, TestPlayer> repo = repo("player_data");
        repo.save(new TestPlayer(KEY, "Alice", 1)).join();
        assertEquals("Alice", repo.find(KEY).join().map(TestPlayer::getName).orElse(null));

        // Rewrite the file behind the storage's back, the way an admin editing it would.
        Path file = baseDir.resolve(KEY + ".json");
        String edited = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .replace("\"Alice\"", "\"EditedByHand\"");
        assertNotEquals((long) edited.length(), Files.size(file), "the edit must change the file's length");
        Files.write(file, edited.getBytes(StandardCharsets.UTF_8));

        assertEquals("EditedByHand", repo.find(KEY).join().map(TestPlayer::getName).orElse(null),
            "the stamp no longer matches, so the memoized document must be dropped");
    }

    @Test
    @DisplayName("a write through the storage refreshes the memo instead of dropping it")
    void ownWrite_refreshesTheMemo() {
        open(new GroupedFileConfig(baseDir));
        Repository<UUID, TestPlayer> repo = repo("player_data");
        repo.save(new TestPlayer(KEY, "Alice", 1)).join();

        long before = parses();
        repo.save(new TestPlayer(KEY, "Alice2", 2)).join();
        assertEquals("Alice2", repo.find(KEY).join().map(TestPlayer::getName).orElse(null));

        assertEquals(0, parses() - before,
            "the writer already held the document in tree form; nothing needs re-parsing");
    }

    @Test
    @DisplayName("the memo stays within its bound")
    void memo_isBounded() {
        open(new GroupedFileConfig(baseDir).rootCacheSize(2));
        Repository<UUID, TestPlayer> repo = repo("player_data");

        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID key = UUID.randomUUID();
            keys.add(key);
            repo.save(new TestPlayer(key, "p" + i, i)).join();
        }
        for (int round = 0; round < 4; round++) {
            for (UUID key : keys) repo.find(key).join();
        }

        assertTrue(storage.keyFileStore().cachedRootCount() <= 2,
            "the memo must evict rather than grow with the key set");
    }

    @Test
    @DisplayName("a scan running against a concurrent write of the same key never sees a half-written document")
    void concurrentScanAndWrite_neverObservesAPartialDocument() throws Exception {
        open(new GroupedFileConfig(baseDir));
        Repository<UUID, TestPlayer> players = repo("player_data");
        Repository<UUID, TestPlayer> economy = repo("economy");
        players.save(new TestPlayer(KEY, "Alice", 1)).join();
        economy.save(new TestPlayer(KEY, "Wallet", 1)).join();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        // The writer replaces the document while readers walk it. Reads take no write lock, so a
        // shared mutable tree would surface here as a missing collection or a torn read.
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 300; i++) players.save(new TestPlayer(KEY, "Alice" + i, i)).join();
            } catch (Throwable t) { failure.compareAndSet(null, t); }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 300; i++) {
                    assertEquals(1L, economy.count().join(), "the untouched collection must stay visible");
                    assertTrue(economy.find(KEY).join().isPresent(), "the untouched collection must stay readable");
                }
            } catch (Throwable t) { failure.compareAndSet(null, t); }
        });

        writer.start();
        reader.start();
        start.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        reader.join(TimeUnit.SECONDS.toMillis(30));

        assertNull(failure.get(), () -> "concurrent read/write failed: " + failure.get());
    }
}
