package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version polling over the file backends, driven directly.
 *
 * <p>{@code CacheSync.attach} now finds a change feed on these two and takes the push path, so the
 * contract suites no longer exercise the poller against them. The polling substrate still exists and
 * still matters - it is the fallback where a watch service is useless (a network mount that reports
 * no events, macOS's second-scale polling watcher) - so it is proven here on its own: a remote
 * <em>update</em>, not just a delete, which is exactly what these backends could not detect before
 * their version stopped being a constant zero.
 */
@DisplayName("PollingCacheSync - file backends detect updates, not only deletes")
class FilePollingCacheSyncTest {

    @TempDir Path baseDir;

    private final List<Storage> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() {
        for (Storage storage : opened) {
            try { storage.close().join(); } catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("LocalFile: polling notices a write made by another instance")
    void localFile_pollDetectsUpdate() {
        Path shared = baseDir.resolve("local");
        assertPollDetectsUpdate(
            open(Storages.createLocalFile(new LocalFileConfig(shared))),
            open(Storages.createLocalFile(new LocalFileConfig(shared))));
    }

    @Test
    @DisplayName("GroupedFile: polling notices a write made by another instance")
    void groupedFile_pollDetectsUpdate() {
        Path shared = baseDir.resolve("grouped");
        assertPollDetectsUpdate(
            open(Storages.createGroupedFile(new GroupedFileConfig(shared).rootCacheSize(0))),
            open(Storages.createGroupedFile(new GroupedFileConfig(shared).rootCacheSize(0))));
    }

    private void assertPollDetectsUpdate(Storage writerStorage, Storage readerStorage) {
        String collection = "quests_" + UUID.randomUUID().toString().replace("-", "");
        CachingManager<UUID, Quest> writer = managerOn(writerStorage, collection);
        CachingManager<UUID, Quest> reader = managerOn(readerStorage, collection);

        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "v0", 0L)).join();
        assertEquals("v0", reader.resolve(id).join().orElseThrow(AssertionError::new).getTitle());

        try (PollingCacheSync polling = PollingCacheSync.every(Duration.ofHours(1)).bind(reader)) {
            polling.pollOnce();          // first observation: the reader is as fresh as the backend

            int[] round = { 0 };
            await(() -> {
                Quest current = writer.resolve(id).join().orElseThrow(AssertionError::new);
                current.setTitle("v-" + (++round[0]));
                writer.saveAndCache(current).join();
                polling.pollOnce();
                Quest seen = reader.resolve(id).join().orElse(null);
                return seen != null && seen.getTitle().startsWith("v-");
            }, "the poller never noticed the remote write - the version is not moving");
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private CachingManager<UUID, Quest> managerOn(Storage storage, String collection) {
        RefRegistry registry = new RefRegistry();
        return registry.manager(EntityDescriptor.builder(UUID.class, Quest.class)
            .collection(collection)
            .keyExtractor(Quest::getId)
            .codec(new JacksonJsonCodec<>(Quest.class))
            .build(), storage, CachePolicy.always());
    }

    private Storage open(Storage storage) {
        storage.init().join();
        opened.add(storage);
        return storage;
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(message);
    }
}
