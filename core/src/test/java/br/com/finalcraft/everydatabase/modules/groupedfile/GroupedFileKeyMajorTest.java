package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.keymajor.KeyBundle;
import br.com.finalcraft.everydatabase.keymajor.KeyMajorStorage;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.modules.mongo.MongoStorage;
import br.com.finalcraft.everydatabase.modules.sql.SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The key-major capability: one key's collections read with one parse and written with one move.
 *
 * <p>What is being pinned here is not convenience, it is cost and atomicity. The counters on
 * {@link KeyFileStore} make both observable - a bundle that quietly did three reads, or a batch that
 * quietly did three writes, would pass every behavioural assertion and still be pointless.
 */
@DisplayName("GroupedFile - key-major reads and writes")
class GroupedFileKeyMajorTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.randomUUID();

    private static final EntityDescriptor<UUID, TestPlayer> PLAYER  = descriptor("playerdata");
    private static final EntityDescriptor<UUID, TestPlayer> ECONOMY = descriptor("economy");
    private static final EntityDescriptor<UUID, TestPlayer> HOMES   = descriptor("homes");

    // ------------------------------------------------------------------
    //  Reading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loading three collections of a key costs one parse")
    void loadKey_parsesOnce() {
        GroupedFileStorage writer = open(new GroupedFileConfig(baseDir));
        writer.repository(PLAYER).save(new TestPlayer(ALICE, "Alice", 100)).join();
        writer.repository(ECONOMY).save(new TestPlayer(ALICE, "Alice", 7)).join();
        writer.repository(HOMES).save(new TestPlayer(ALICE, "Alice", 3)).join();
        writer.close().join();

        // A fresh storage, so the memo starts cold and the parse count means something.
        GroupedFileStorage reader = open(new GroupedFileConfig(baseDir));
        long before = reader.keyFileStore().rootParseCount();

        KeyBundle bundle = reader.loadKey(ALICE, PLAYER, ECONOMY, HOMES).join();

        assertEquals(1, reader.keyFileStore().rootParseCount() - before,
            "the whole bundle comes from one read of one file");
        assertEquals(100, bundle.get(PLAYER).map(TestPlayer::getScore).orElse(-1));
        assertEquals(7,   bundle.get(ECONOMY).map(TestPlayer::getScore).orElse(-1));
        assertEquals(3,   bundle.get(HOMES).map(TestPlayer::getScore).orElse(-1));
        assertFalse(bundle.isEmpty());
    }

    @Test
    @DisplayName("a collection the key does not hold comes back empty, not missing")
    void loadKey_absentCollection() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER).save(new TestPlayer(ALICE, "Alice", 100)).join();

        KeyBundle bundle = storage.loadKey(ALICE, PLAYER, ECONOMY).join();

        assertTrue(bundle.get(PLAYER).isPresent());
        assertFalse(bundle.get(ECONOMY).isPresent());
        assertEquals(2, bundle.collections().size());
    }

    @Test
    @DisplayName("a key that holds nothing yields an empty bundle, not a failure")
    void loadKey_unknownKey() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);

        KeyBundle bundle = storage.loadKey(UUID.randomUUID(), PLAYER).join();
        assertTrue(bundle.isEmpty());
        assertFalse(bundle.get(PLAYER).isPresent());
    }

    @Test
    @DisplayName("asking a bundle for a collection it did not read is a mistake, not an empty")
    void bundle_refusesUnreadCollections() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        KeyBundle bundle = storage.loadKey(ALICE, PLAYER).join();

        assertThrows(IllegalArgumentException.class, () -> bundle.get(ECONOMY));
    }

    // ------------------------------------------------------------------
    //  Writing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a batch of three writes publishes the file once")
    void batchKey_writesOnce() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        storage.repository(ECONOMY);
        storage.repository(HOMES);

        long before = storage.keyFileStore().atomicWriteCount();
        storage.batchKey(ALICE, batch -> batch
            .put(PLAYER,  new TestPlayer(ALICE, "Alice", 100))
            .put(ECONOMY, new TestPlayer(ALICE, "Alice", 7))
            .put(HOMES,   new TestPlayer(ALICE, "Alice", 3))).join();

        assertEquals(1, storage.keyFileStore().atomicWriteCount() - before,
            "three saves would have published the same file three times");

        KeyBundle bundle = storage.loadKey(ALICE, PLAYER, ECONOMY, HOMES).join();
        assertEquals(100, bundle.get(PLAYER).map(TestPlayer::getScore).orElse(-1));
        assertEquals(7,   bundle.get(ECONOMY).map(TestPlayer::getScore).orElse(-1));
        assertEquals(3,   bundle.get(HOMES).map(TestPlayer::getScore).orElse(-1));
    }

    @Test
    @DisplayName("what a batch writes is visible to the plain repositories too")
    void batchKey_isVisibleThroughRepositories() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        storage.repository(ECONOMY);

        storage.batchKey(ALICE, batch -> batch
            .put(PLAYER,  new TestPlayer(ALICE, "Alice", 100))
            .put(ECONOMY, new TestPlayer(ALICE, "Alice", 7))).join();

        // Reading straight after the batch is what proves the memo was refreshed from the published
        // file rather than left holding the document as it was before the write.
        assertEquals(100, storage.repository(PLAYER).find(ALICE).join().map(TestPlayer::getScore).orElse(-1));
        assertEquals(7,   storage.repository(ECONOMY).find(ALICE).join().map(TestPlayer::getScore).orElse(-1));
        assertEquals(1L,  storage.repository(PLAYER).count().join());
    }

    @Test
    @DisplayName("a batch that throws while being assembled leaves the file byte-identical")
    void batchKey_throwingConsumer_changesNothing() throws Exception {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        storage.repository(ECONOMY);
        storage.repository(PLAYER).save(new TestPlayer(ALICE, "Alice", 100)).join();

        Path file = baseDir.resolve(ALICE + ".json");
        byte[] before = Files.readAllBytes(file);
        long writesBefore = storage.keyFileStore().atomicWriteCount();

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> storage.batchKey(ALICE, batch -> {
                batch.put(ECONOMY, new TestPlayer(ALICE, "Alice", 7));
                throw new IllegalStateException("the caller changed its mind");
            }).join());

        assertEquals("the caller changed its mind", thrown.getCause().getMessage());
        assertArrayEquals(before, Files.readAllBytes(file), "nothing may have been written");
        assertEquals(writesBefore, storage.keyFileStore().atomicWriteCount());
        assertFalse(storage.repository(ECONOMY).exists(ALICE).join());
    }

    @Test
    @DisplayName("removing the last collection of a key deletes the file")
    void batchKey_removingEverything_deletesTheFile() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        storage.repository(ECONOMY);
        storage.batchKey(ALICE, batch -> batch
            .put(PLAYER,  new TestPlayer(ALICE, "Alice", 100))
            .put(ECONOMY, new TestPlayer(ALICE, "Alice", 7))).join();
        assertTrue(Files.exists(baseDir.resolve(ALICE + ".json")));

        storage.batchKey(ALICE, batch -> batch.remove(PLAYER).remove(ECONOMY)).join();

        assertFalse(Files.exists(baseDir.resolve(ALICE + ".json")), "an empty key leaves no file behind");
        assertTrue(storage.loadKey(ALICE, PLAYER, ECONOMY).join().isEmpty());
    }

    @Test
    @DisplayName("a batch mixing put and remove keeps the collections that stay")
    void batchKey_mixedOperations() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        storage.repository(ECONOMY);
        storage.batchKey(ALICE, batch -> batch
            .put(PLAYER,  new TestPlayer(ALICE, "Alice", 100))
            .put(ECONOMY, new TestPlayer(ALICE, "Alice", 7))).join();

        storage.batchKey(ALICE, batch -> batch
            .put(PLAYER, new TestPlayer(ALICE, "Alice", 200))
            .remove(ECONOMY)).join();

        KeyBundle bundle = storage.loadKey(ALICE, PLAYER, ECONOMY).join();
        assertEquals(200, bundle.get(PLAYER).map(TestPlayer::getScore).orElse(-1));
        assertFalse(bundle.get(ECONOMY).isPresent());
    }

    @Test
    @DisplayName("an empty batch touches nothing")
    void batchKey_empty() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);
        long before = storage.keyFileStore().atomicWriteCount();

        storage.batchKey(ALICE, batch -> { }).join();

        assertEquals(before, storage.keyFileStore().atomicWriteCount());
    }

    // ------------------------------------------------------------------
    //  What it refuses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("collections in different key spaces are refused, not silently read twice")
    void differentKeySpaces_areRefused() {
        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player",  "playerdata")
            .keySpace("account", "economy")
            .build());
        storage.repository(PLAYER);
        storage.repository(ECONOMY);

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> storage.loadKey(ALICE, PLAYER, ECONOMY).join());

        String message = thrown.getCause().getMessage();
        assertTrue(message.contains("player"),  () -> "names both key spaces: " + message);
        assertTrue(message.contains("account"), () -> "names both key spaces: " + message);

        // Within one key space it is fine again.
        assertDoesNotThrow(() -> storage.loadKey(ALICE, PLAYER).join());
    }

    @Test
    @DisplayName("a key of the wrong type is refused before any file is touched")
    void wrongKeyType_isRefused() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        storage.repository(PLAYER);

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> storage.loadKey("not-a-uuid", PLAYER).join());
        assertTrue(thrown.getCause().getMessage().contains("UUID"), thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("no descriptors at all is a mistake")
    void noDescriptors_isRefused() {
        GroupedFileStorage storage = open(new GroupedFileConfig(baseDir));
        assertThrows(CompletionException.class, () -> storage.loadKey(ALICE).join());
    }

    // ------------------------------------------------------------------
    //  Who has it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only the key-major backend has the capability")
    void onlyGroupedFileIsKeyMajor() {
        assertInstanceOf(KeyMajorStorage.class, open(new GroupedFileConfig(baseDir)));
        // isInstance rather than instanceof: these classes are final and unrelated to the
        // capability, so the compiler rejects the pattern outright - which is itself a guarantee,
        // but not one that survives someone making them implement it later.
        assertFalse(KeyMajorStorage.class.isInstance(new InMemoryStorage()));
        assertFalse(KeyMajorStorage.class.isInstance(
            new LocalFileStorage(new LocalFileConfig(baseDir.resolve("lf")))));

        // Type-level for the ones that would need a live server to instantiate.
        for (Class<?> backend : new Class<?>[] {
                SqlStorage.class, H2SqlStorage.class, PostgreSqlStorage.class, MongoStorage.class }) {
            assertFalse(KeyMajorStorage.class.isAssignableFrom(backend),
                backend.getSimpleName() + " stores collections apart and must not claim to be key-major");
        }
    }

    @Test
    @DisplayName("per-key atomicity is not a transaction, and the type still says so")
    void keyMajorIsNotTransactional() {
        assertFalse(TransactionalStorage.class.isInstance(open(new GroupedFileConfig(baseDir))),
            "batchKey is atomic per key; promising transactions would be promising across keys");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private GroupedFileStorage open(GroupedFileConfig config) {
        GroupedFileStorage storage = Storages.createGroupedFile(config);
        storage.init().join();
        return storage;
    }

    private static EntityDescriptor<UUID, TestPlayer> descriptor(String collection) {
        return EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();
    }
}
