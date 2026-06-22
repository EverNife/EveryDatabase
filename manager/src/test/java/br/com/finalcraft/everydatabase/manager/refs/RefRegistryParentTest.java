package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Parent chaining on the registry: local-first resolution with fallback to a shared parent. */
class RefRegistryParentTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = Storages.createInMemory();
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    private EntityDescriptor<UUID, Guild> guilds(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection(collection)
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }

    @Test
    void a_child_falls_back_to_the_parent_for_unregistered_types() {
        RefRegistry global = new RefRegistry();
        RefRegistry plugin = new RefRegistry(global);

        // Guild is registered only in the GLOBAL (parent) registry.
        CachingManager<UUID, Guild> mgr = global.manager(guilds(global, "guilds"), storage, CachePolicy.always());

        assertSame(mgr, plugin.<UUID, Guild>resolver(Guild.class), "the child resolves Guild via the parent");
        assertTrue(plugin.isRegisteredInChain(Guild.class));
        assertFalse(plugin.isRegistered(Guild.class), "registration stays local to the parent");

        // A Ref bound to the child resolves through the parent's manager.
        UUID id = UUID.randomUUID();
        mgr.saveAndCache(new Guild(id, "Knights")).join();
        assertEquals("Knights", plugin.ref(id, Guild.class).resolve().join().get().getName());
    }

    @Test
    void a_local_registration_shadows_the_parent() {
        RefRegistry global = new RefRegistry();
        RefRegistry plugin = new RefRegistry(global);

        CachingManager<UUID, Guild> globalMgr =
                global.manager(guilds(global, "guilds_global"), storage, CachePolicy.always());
        CachingManager<UUID, Guild> localMgr =
                plugin.manager(guilds(plugin, "guilds_local"), storage, CachePolicy.always());

        assertSame(localMgr, plugin.<UUID, Guild>resolver(Guild.class), "the child's own registration wins");
        assertSame(globalMgr, global.<UUID, Guild>resolver(Guild.class));
    }

    @Test
    void a_root_registry_has_no_parent() {
        RefRegistry root = new RefRegistry();
        assertNull(root.parent());
        assertNull(root.resolver(Guild.class));
        assertFalse(root.isRegisteredInChain(Guild.class));
    }
}
