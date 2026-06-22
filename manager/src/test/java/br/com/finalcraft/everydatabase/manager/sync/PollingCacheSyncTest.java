package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code PollingCacheSync} primitive in isolation, driven deterministically via
 * {@link PollingCacheSync#pollOnce()} on InMemory: a version bump invalidates, a backend delete evicts.
 * (The cross-instance behavior over real SQL is covered by {@code MariaDbCacheSyncTest} and the rest of
 * the {@code AbstractCacheSyncTest} contract.)
 */
class PollingCacheSyncTest {

    private final List<Storage> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Storage s : opened) {
            try { s.close().join(); } catch (Exception ignored) { }
        }
        opened.clear();
    }

    private <S extends Storage> S open(S s) {
        s.init().join();
        opened.add(s);
        return s;
    }

    private EntityDescriptor<UUID, Quest> descriptor() {
        return EntityDescriptor.builder(UUID.class, Quest.class)
                .collection("quests")
                .keyExtractor(Quest::getId)
                .codec(new JacksonJsonCodec<>(Quest.class))
                .build();
    }

    @Test
    void poll_invalidates_a_key_whose_backend_version_increased() {
        InMemoryStorage storage = open(Storages.createInMemory());
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Quest> mgr = registry.manager(descriptor(), storage, CachePolicy.always());

        UUID id = UUID.randomUUID();
        mgr.saveAndCache(new Quest(id, "v0", 0L)).join();
        mgr.resolve(id).join();
        assertTrue(mgr.peek(id).isPresent(), "cached at v0");

        PollingCacheSync poller = PollingCacheSync.every(Duration.ofHours(1)).bind(mgr);
        poller.pollOnce();   // first observation: records version 0, no invalidation
        assertTrue(mgr.peek(id).isPresent(), "first poll does not invalidate a freshly-loaded key");

        // Another instance bumps the version in the backend (written straight to the repository).
        mgr.repository().save(new Quest(id, "v1", 1L)).join();

        poller.pollOnce();   // sees 1 > 0 -> invalidate
        assertFalse(mgr.peek(id).isPresent(), "poll invalidated the key after a version bump");
        assertEquals("v1", mgr.resolve(id).join().orElseThrow(AssertionError::new).getTitle());

        poller.close();
    }

    @Test
    void poll_evicts_a_key_deleted_in_the_backend() {
        InMemoryStorage storage = open(Storages.createInMemory());
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Quest> mgr = registry.manager(descriptor(), storage, CachePolicy.always());

        UUID id = UUID.randomUUID();
        mgr.saveAndCache(new Quest(id, "here", 0L)).join();
        mgr.resolve(id).join();
        assertEquals(1, mgr.cachedSize());

        PollingCacheSync poller = PollingCacheSync.every(Duration.ofHours(1)).bind(mgr);
        poller.pollOnce();   // record

        mgr.repository().delete(id).join();   // another instance deletes it
        poller.pollOnce();   // key now absent from versions() -> evict

        assertFalse(mgr.peek(id).isPresent(), "poll evicted the deleted key");
        assertEquals(0, mgr.cachedSize());

        poller.close();
    }
}
