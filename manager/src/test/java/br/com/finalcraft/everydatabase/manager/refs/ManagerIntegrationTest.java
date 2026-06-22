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

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Multi-manager scenarios: a reference resolves across managers, and a list of refs batches. */
class ManagerIntegrationTest {

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

    private EntityDescriptor<UUID, Guild> guildDescriptor() {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }

    private EntityDescriptor<UUID, Player> playerDescriptor() {
        return EntityDescriptor.builder(UUID.class, Player.class)
                .collection("players")
                .keyExtractor(Player::getUuid)
                .codec(registry.codec(Player.class))
                .build();
    }

    @Test
    void a_player_resolves_its_guild_ref_end_to_end_through_two_managers() {
        CachingManager<UUID, Guild> guilds = new CachingManager<>(guildDescriptor(), storage, CachePolicy.always(), registry);
        CachingManager<UUID, Player> players = new CachingManager<>(playerDescriptor(), storage, CachePolicy.always(), registry);

        UUID guildId = UUID.randomUUID();
        guilds.saveAndCache(new Guild(guildId, "Knights")).join();

        UUID playerId = UUID.randomUUID();
        players.saveAndCache(new Player(playerId, Ref.of(guildId, Guild.class, null))).join();

        // Force a fresh deserialization of the player (serialize -> store -> decode -> resolve).
        players.evict(playerId);
        Player loaded = players.resolve(playerId).join().orElseThrow(AssertionError::new);

        // The player's guild Ref resolves through the Guild manager - a different type, its own cache.
        Guild guild = loaded.getGuild().resolve().join().orElseThrow(AssertionError::new);
        assertEquals("Knights", guild.getName());
    }

    @Test
    void a_guild_member_list_resolves_all_members_in_one_batch() {
        CachingManager<UUID, Player> players = new CachingManager<>(playerDescriptor(), storage, CachePolicy.always(), registry);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        players.saveAndCache(new Player(a, "A")).join();
        players.saveAndCache(new Player(b, "B")).join();
        players.saveAndCache(new Player(c, "C")).join();

        Guild guild = new Guild(UUID.randomUUID(), "Knights");
        guild.setMembers(Arrays.asList(
                Ref.of(a, Player.class, null), Ref.of(b, Player.class, null), Ref.of(c, Player.class, null)));

        // Batch-resolve the members' keys via the player manager (the in-loop N+1 antidote).
        List<UUID> keys = guild.getMembers().stream().map(Ref::key).collect(Collectors.toList());
        List<Player> members = players.getAll(keys).join();

        Set<String> names = members.stream().map(Player::getName).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("A", "B", "C")), names);
    }
}
