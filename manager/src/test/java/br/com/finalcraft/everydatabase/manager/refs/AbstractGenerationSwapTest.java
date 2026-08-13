package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.RefResolver;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Alliance;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The generation-swap mini-flow, per real backend - the miniature of what a host framework's live
 * storage reload does, pinned here where the backend matrix lives:
 *
 * <ol>
 *   <li><b>Generation 1</b>: a storage, a {@link Guild} manager and an {@link Alliance} manager in
 *       one registry; the alliance (holding a <b>list</b> of guild refs) is re-read from the
 *       backend, so its refs are born deserialized and bound - the path a host application's rows
 *       actually take.</li>
 *   <li><b>Swap</b>: a second storage over the <em>same data</em> (what a reload opens), whose
 *       guild manager replaces the first via {@link RefRegistry#managerReplacing}; the retired one
 *       is torn down in the fixed order flush → {@code clearCache()} → close.</li>
 *   <li><b>Proof</b>: the live refs of the generation-1 root - never rebound - resolve the
 *       generation-2 truth (a rename written through the backend, invisible to generation 1's
 *       cache), and nothing throws at any point in the sequence.</li>
 * </ol>
 */
abstract class AbstractGenerationSwapTest {

    protected final List<Storage> opened = new ArrayList<>();

    /**
     * Opens the next generation's storage over the <b>same data</b> as every previous call - a new
     * instance on backends with external state (files, SQL); the very same instance on the memory
     * backend, whose data cannot outlive its storage (a memory reload keeps the storage and
     * rebuilds the managers). Implementations register what they open in {@link #opened}.
     */
    protected abstract Storage openGeneration();

    @AfterEach
    void closeOpened() {
        for (Storage storage : opened) {
            try {
                storage.close().join();     // close is idempotent - an already-closed gen-1 is fine
            } catch (Exception ignored) {
                // best-effort
            }
        }
        opened.clear();
    }

    @Test
    void liveRefsOfADeserializedRootResolveTheReplacementGeneration() {
        RefRegistry registry = new RefRegistry();
        Storage gen1 = openGeneration();

        EntityDescriptor<UUID, Guild> guilds = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
        EntityDescriptor<UUID, Alliance> alliances = EntityDescriptor.builder(UUID.class, Alliance.class)
                .collection("alliances")
                .keyExtractor(Alliance::getId)
                .codec(registry.codec(Alliance.class))
                .build();

        CachingManager<UUID, Guild> guildsGen1 = registry.manager(guilds, gen1, CachePolicy.always());
        CachingManager<UUID, Alliance> alliancesGen1 = registry.manager(alliances, gen1, CachePolicy.always());

        UUID knightsId = UUID.randomUUID();
        UUID magesId   = UUID.randomUUID();
        UUID pactId    = UUID.randomUUID();
        guildsGen1.saveAndCache(new Guild(knightsId, "Knights")).join();
        guildsGen1.saveAndCache(new Guild(magesId, "Mages")).join();
        alliancesGen1.saveAndCache(new Alliance(pactId, "The Pact", Arrays.asList(
                registry.ref(knightsId, Guild.class),
                registry.ref(magesId, Guild.class)))).join();

        // re-READ the root so its refs are born DESERIALIZED, bound by the codec - the real path
        alliancesGen1.evict(pactId);
        Alliance pact = alliancesGen1.resolve(pactId).join().orElseThrow(AssertionError::new);
        Ref<UUID, Guild> knightsRef = pact.getGuilds().get(0);
        Ref<UUID, Guild> magesRef   = pact.getGuilds().get(1);
        assertEquals("Knights", knightsRef.join().getName());   // memoize generation-1 cells
        assertEquals("Mages",   magesRef.join().getName());

        // ---- the swap: generation 2 over the same data ----
        Storage gen2 = openGeneration();
        // the generation-2 truth: a rename written THROUGH the backend, invisible to gen-1's cache
        gen2.repository(guilds).save(new Guild(knightsId, "Knights Reborn")).join();

        AtomicReference<RefResolver<UUID, Guild>> retired = new AtomicReference<>();
        CachingManager<UUID, Guild> guildsGen2 =
                registry.managerReplacing(guilds, gen2, CachePolicy.always(), retired::set);
        assertSame(guildsGen1, retired.get(), "the replaced manager is handed over for teardown");

        // the teardown order a reload must follow: flush pending -> clearCache -> close
        guildsGen1.flushDirty().join();
        guildsGen1.clearCache();
        if (gen2 != gen1) {
            gen1.close().join();
        }

        // ---- the proof: the SAME live Ref instances, never rebound, resolve generation 2 ----
        assertEquals("Knights Reborn", knightsRef.join().getName(),
                "a live deserialized ref resolves the replacement generation");
        assertEquals("Mages", magesRef.join().getName());
        assertEquals("Knights Reborn", knightsRef.peek().map(Guild::getName).orElse(null),
                "after the resolve, peek serves the generation-2 cell");
        assertSame(guildsGen2, registry.resolver(Guild.class));
    }
}
