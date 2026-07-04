package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.RefResolver;
import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registration contract of {@link RefRegistry}: a second, different resolver for a type already
 * registered is rejected, so two managers for the same type on one registry can never silently
 * collide (last-writer-wins). Replacing is explicit ({@code unregister} then {@code register}).
 */
class RefRegistryTest {

    private static <K, V> RefResolver<K, V> fakeResolver() {
        return new RefResolver<K, V>() {
            @Override public CachePolicy defaultPolicy() { return CachePolicy.always(); }
            @Override public CacheEntry<V> peekCell(K key, CachePolicy policy) { return null; }
            @Override public CompletableFuture<CacheEntry<V>> resolveCell(K key, CachePolicy policy) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Test
    void register_rejectsADifferentResolverForAnAlreadyRegisteredType() {
        RefRegistry registry = new RefRegistry();
        RefResolver<UUID, Guild> first  = fakeResolver();
        RefResolver<UUID, Guild> second = fakeResolver();

        registry.register(Guild.class, first);

        // Re-registering the very same instance is a harmless no-op.
        assertDoesNotThrow(() -> registry.register(Guild.class, first));

        // A different resolver for the same type is the collision we refuse to swallow.
        assertThrows(IllegalStateException.class, () -> registry.register(Guild.class, second));
        assertSame(first, registry.resolver(Guild.class), "the original registration is untouched");

        // Explicit replacement is allowed after unregister().
        registry.unregister(Guild.class);
        assertDoesNotThrow(() -> registry.register(Guild.class, second));
        assertSame(second, registry.resolver(Guild.class));
    }

    @Test
    void aSecondManagerForTheSameTypeOnTheSameRegistry_failsFastFromTheConstructor() {
        RefRegistry registry = new RefRegistry();
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        try {
            new CachingManager<>(guildDescriptor(registry, "guilds_a"), storage, CachePolicy.always(), registry);
            assertThrows(IllegalStateException.class, () ->
                new CachingManager<>(guildDescriptor(registry, "guilds_b"), storage, CachePolicy.always(), registry));
        } finally {
            storage.close().join();
        }
    }

    private static EntityDescriptor<UUID, Guild> guildDescriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection(collection)
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }
}
