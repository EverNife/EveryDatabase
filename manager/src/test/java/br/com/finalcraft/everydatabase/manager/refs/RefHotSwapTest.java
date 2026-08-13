package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.RefResolver;
import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hot-swap contract, surgically on the memory backend: a manager generation is replaced in
 * place ({@link RefRegistry#replace} / {@link RefRegistry#managerReplacing}) and a live {@link Ref}
 * - never rebound - resolves the replacement on its next access, because the retired generation's
 * {@code clearCache()} marks every cell evicted and that is what drops the ref's memo. The negative
 * case pins the same mechanism from the other side: <b>without</b> the {@code clearCache()} an
 * always-fresh ref keeps serving the retired generation's memo forever, so the eviction is a
 * mandatory step of the teardown, not decoration.
 */
class RefHotSwapTest {

    private final UUID id = UUID.randomUUID();
    private RefRegistry registry;
    private InMemoryStorage storageA;
    private InMemoryStorage storageB;
    private EntityDescriptor<UUID, Guild> descriptor;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storageA = Storages.createInMemory();
        storageB = Storages.createInMemory();
        storageA.init().join();
        storageB.init().join();
        descriptor = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        storageA.close().join();
        storageB.close().join();
    }

    @Test
    void aLiveRefResolvesTheReplacementManagerAfterAHotSwap() {
        CachingManager<UUID, Guild> genOne = registry.manager(descriptor, storageA, CachePolicy.always());
        genOne.saveAndCache(new Guild(id, "GenOne")).join();

        Ref<UUID, Guild> live = registry.ref(id, Guild.class);
        assertEquals("GenOne", live.join().getName());          // memoizes genOne's cell

        // the reload order: the "new backend" truth exists, the new manager replaces, THEN teardown
        storageB.repository(descriptor).save(new Guild(id, "GenTwo")).join();
        AtomicReference<RefResolver<UUID, Guild>> retired = new AtomicReference<>();
        CachingManager<UUID, Guild> genTwo =
                registry.managerReplacing(descriptor, storageB, CachePolicy.always(), retired::set);

        assertSame(genOne, retired.get(), "the replaced resolver is handed to the caller for teardown");
        assertSame(genTwo, registry.resolver(Guild.class), "the type resolves the replacement immediately");

        genOne.clearCache();                                    // marks every cell evicted -> memos die

        // CachePolicy.always() would serve the memo forever - ONLY the eviction can drop it:
        assertEquals("GenTwo", live.join().getName(),
                "the SAME Ref instance, never rebound, resolves the replacement manager");
        assertEquals("GenTwo", live.peek().map(Guild::getName).orElse(null),
                "after the resolve, peek serves the gen-two cell lock-free");
    }

    @Test
    void peekAloneAlsoFallsThroughToTheReplacementManager() {
        CachingManager<UUID, Guild> genOne = registry.manager(descriptor, storageA, CachePolicy.always());
        genOne.saveAndCache(new Guild(id, "GenOne")).join();

        Ref<UUID, Guild> live = registry.ref(id, Guild.class);
        assertEquals("GenOne", live.peek().map(Guild::getName).orElse(null)); // memoizes genOne's cell

        storageB.repository(descriptor).save(new Guild(id, "GenTwo")).join();
        CachingManager<UUID, Guild> genTwo =
                registry.managerReplacing(descriptor, storageB, CachePolicy.always(), r -> {});
        genTwo.resolve(id).join();                              // warm gen two: peek is cache-only
        genOne.clearCache();

        assertEquals("GenTwo", live.peek().map(Guild::getName).orElse(null),
                "the dead memo makes the synchronous path re-consult the registry too");
    }

    @Test
    void withoutClearCacheOnTheRetiredManager_aLiveRefKeepsServingItsMemo() {
        CachingManager<UUID, Guild> genOne = registry.manager(descriptor, storageA, CachePolicy.always());
        genOne.saveAndCache(new Guild(id, "GenOne")).join();

        Ref<UUID, Guild> live = registry.ref(id, Guild.class);
        assertEquals("GenOne", live.join().getName());

        storageB.repository(descriptor).save(new Guild(id, "GenTwo")).join();
        registry.managerReplacing(descriptor, storageB, CachePolicy.always(), r -> {});

        // the registry already routes to gen two, but this ref's memoized cell is alive and
        // always-fresh - so the swap alone changes nothing for it:
        assertEquals("GenOne", live.join().getName(),
                "without the retired manager's clearCache(), the memo is served forever");

        genOne.clearCache();
        assertEquals("GenTwo", live.join().getName(),
                "the eviction is the mandatory teardown step that makes live refs re-resolve");
    }

    @Test
    void replace_returnsThePreviousResolver_andNullWhenNone() {
        RefResolver<UUID, Guild> first  = fakeResolver();
        RefResolver<UUID, Guild> second = fakeResolver();

        assertNull(registry.replace(Guild.class, first), "nothing replaced on first install");
        assertSame(first, registry.replace(Guild.class, second), "the replaced resolver is returned");
        assertSame(second, registry.resolver(Guild.class));

        // the accidental-duplicate guard is untouched: register still refuses a DIFFERENT resolver
        assertThrows(IllegalStateException.class, () -> registry.register(Guild.class, first));
        assertSame(second, registry.resolver(Guild.class), "the failed register changed nothing");
    }

    private static <K, V> RefResolver<K, V> fakeResolver() {
        return new RefResolver<K, V>() {
            @Override public CachePolicy defaultPolicy() { return CachePolicy.always(); }
            @Override public CacheEntry<V> peekCell(K key, CachePolicy policy) { return null; }
            @Override public CompletableFuture<CacheEntry<V>> resolveCell(K key, CachePolicy policy) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
