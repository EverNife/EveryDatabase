package br.com.finalcraft.everydatabase.manager.observ;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache-metrics surface ({@code CachingManager.stats()} -> {@link CacheStats}). Counting is always
 * on, so no opt-in is needed; this asserts the counters reflect hits/misses/loads/invalidations/evictions.
 */
class CacheObservabilityTest {

    private CachingManager<UUID, Guild> manager(InMemoryStorage storage, RefRegistry registry) {
        EntityDescriptor<UUID, Guild> descriptor = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
        return new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);
    }

    @Test
    void stats_count_hits_misses_loads_invalidations_and_evictions() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> manager = manager(storage, registry);

        UUID cached = UUID.randomUUID();
        manager.saveAndCache(new Guild(cached, "g")).join();   // write-through (caches it; not a read)

        manager.resolve(cached).join();                         // hit (served from cache)

        manager.resolve(UUID.randomUUID()).join();              // miss, no entity -> no loadSuccess

        UUID backendOnly = UUID.randomUUID();
        manager.repository().save(new Guild(backendOnly, "b")).join();   // backend only, not cached
        manager.resolve(backendOnly).join();                    // miss + loadSuccess

        manager.invalidate(cached);                             // invalidation
        manager.evict(backendOnly);                            // eviction

        CacheStats s = manager.stats();
        assertEquals(1, s.hitCount(), "one cache hit");
        assertEquals(2, s.missCount(), "two misses (the absent key + the backend-only key)");
        assertEquals(1, s.loadSuccessCount(), "one load found an entity");
        assertEquals(0, s.loadFailureCount(), "no load threw");
        assertEquals(1, s.invalidationCount());
        assertEquals(1, s.evictionCount());
        assertEquals(3, s.requestCount());
        assertEquals(1.0 / 3.0, s.hitRate(), 1e-9);

        manager.resetStats();
        CacheStats z = manager.stats();
        assertEquals(0, z.requestCount(), "reset clears the counters");
        assertEquals(0, z.evictionCount());

        storage.close().join();
    }

    @Test
    void getAll_counts_partial_hits_and_batched_loads() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> manager = manager(storage, registry);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        manager.saveAndCache(new Guild(a, "a")).join();   // a is cached
        manager.repository().save(new Guild(b, "b")).join();   // b only in the backend
        manager.resetStats();

        manager.getAll(java.util.Arrays.asList(a, b)).join();   // a = hit, b = miss + loadSuccess

        CacheStats s = manager.stats();
        assertEquals(1, s.hitCount());
        assertEquals(1, s.missCount());
        assertEquals(1, s.loadSuccessCount());
        assertTrue(s.liveSize() >= 1);

        storage.close().join();
    }
}
