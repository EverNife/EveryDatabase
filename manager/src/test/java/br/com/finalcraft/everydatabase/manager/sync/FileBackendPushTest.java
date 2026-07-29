package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncMode;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The file backends reach {@code CacheSync} through the push path now, not the polling fallback.
 *
 * <p>{@code CacheSync.attach(storage)} chooses by capability, so this is what proves the change feed
 * added to local and grouped files is actually the path taken - a poller would keep every one of
 * these assertions passing while quietly being slower.
 */
@DisplayName("CacheSync - the file backends are push, not poll")
class FileBackendPushTest {

    @TempDir Path baseDir;

    private final List<Storage> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() {
        for (Storage storage : opened) {
            try { storage.close().join(); } catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("LocalFile syncs from its own change feed")
    void localFile_isFeedMode() {
        assertFeedMode(open(Storages.createLocalFile(new LocalFileConfig(baseDir.resolve("local")))));
    }

    @Test
    @DisplayName("GroupedFile syncs from its own change feed")
    void groupedFile_isFeedMode() {
        assertFeedMode(open(Storages.createGroupedFile(new GroupedFileConfig(baseDir.resolve("grouped")))));
    }

    /** Attaching without {@code pollEvery} has to work at all - that alone means a feed was found. */
    private void assertFeedMode(Storage storage) {
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Quest> manager = registry.manager(
            EntityDescriptor.builder(UUID.class, Quest.class)
                .collection("quests")
                .keyExtractor(Quest::getId)
                .codec(new JacksonJsonCodec<>(Quest.class))
                .build(),
            storage, CachePolicy.always());

        try (CacheSync sync = CacheSync.attach(storage).bind(manager).start()) {
            assertEquals(CacheSyncMode.FEED, sync.stats().mode(),
                "attaching with no poll interval only succeeds on a backend that has a feed");
        }
    }

    private Storage open(Storage storage) {
        storage.init().join();
        opened.add(storage);
        return storage;
    }
}
