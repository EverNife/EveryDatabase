package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nested references: each entity type resolves through its own manager and its own policy. */
class NestedRefTest {

    private RefRegistry registry;
    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storage = Storages.createInMemory();
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    @Test
    void guild_resolves_its_nested_founder_through_a_separate_manager() {
        EntityDescriptor<UUID, Player> playerDesc = EntityDescriptor.builder(UUID.class, Player.class)
                .collection("players")
                .keyExtractor(Player::getUuid)
                .codec(registry.codec(Player.class))
                .build();
        EntityDescriptor<UUID, Guild> guildDesc = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))                  // Guild has Ref fields
                .build();

        // Two managers, two policies: guilds resident, players on a 3-minute TTL.
        CachingManager<UUID, Player> players =
                new CachingManager<>(playerDesc, storage, CachePolicy.ttl(Duration.ofMinutes(3)), registry);
        CachingManager<UUID, Guild> guilds =
                new CachingManager<>(guildDesc, storage, CachePolicy.always(), registry);

        UUID pid = UUID.randomUUID();
        UUID gid = UUID.randomUUID();
        players.saveAndCache(new Player(pid, "Aragorn")).join();
        Guild guild = new Guild(gid, "Knights");
        guild.setFounder(Ref.of(pid, Player.class, null));   // programmatic ref, no override
        guilds.saveAndCache(guild).join();

        // Force a load from the backend: the write-through cache holds the original instance whose
        // ref was built programmatically (no override), whereas the @RefPolicy(ttlSeconds = 180)
        // on Guild.founder is recovered only on deserialization.
        guilds.evict(gid);

        // Load the guild, then resolve the nested ref through Player's own manager.
        Guild loaded = guilds.resolve(gid).join().orElseThrow(AssertionError::new);
        assertTrue(loaded.getFounder().isPresent());
        assertTrue(loaded.getFounder().policyOverride().isPresent());

        Player founder = loaded.getFounder().resolve().join().orElseThrow(AssertionError::new);
        assertEquals("Aragorn", founder.getName());
    }
}
