package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CacheEntry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A Ref serializes as its key; the target type and any {@code @RefPolicy} are recovered from the field. */
class RefSerializationTest {

    private final RefRegistry registry = new RefRegistry();
    private final ObjectMapper mapper = RefCodecs.newMapper(registry);

    @Test
    void ref_serializes_as_its_key_not_an_embedded_object() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID guildId = UUID.randomUUID();
        Player player = new Player(playerId, Ref.of(guildId, Guild.class, null));

        String json = mapper.writeValueAsString(player);

        // On disk the guild is just the UUID string, exactly like storing the raw key...
        assertTrue(json.contains("\"guild\":\"" + guildId + "\""),
                "expected guild to serialize as the key string, got: " + json);
        // ...never an embedded Guild object (which would carry the guild's own ref fields).
        assertFalse(json.contains("\"leader\""), "no embedded guild object expected: " + json);
        assertFalse(json.contains("\"members\""), "no embedded guild object expected: " + json);
    }

    @Test
    void ref_deserializes_with_key_and_type_recovered_from_the_field() throws Exception {
        UUID guildId = UUID.randomUUID();
        Player player = new Player(UUID.randomUUID(), Ref.of(guildId, Guild.class, null));

        Player back = mapper.readValue(mapper.writeValueAsString(player), Player.class);

        assertNotNull(back.getGuild());
        assertTrue(back.getGuild().isPresent());
        assertEquals(guildId, back.getGuild().key());
        assertEquals(Guild.class, back.getGuild().type());
        assertFalse(back.getGuild().policyOverride().isPresent(), "no override declared on Player.guild");
    }

    @Test
    void null_reference_round_trips_to_an_empty_ref() throws Exception {
        Player player = new Player(UUID.randomUUID(), (Ref<UUID, Guild>) null);

        Player back = mapper.readValue(mapper.writeValueAsString(player), Player.class);

        assertNotNull(back.getGuild(), "a JSON null becomes an empty Ref, never a bare null");
        assertFalse(back.getGuild().isPresent());
    }

    @Test
    void refPolicy_noCache_annotation_is_baked_into_the_ref() throws Exception {
        UUID playerId = UUID.randomUUID();
        Guild guild = new Guild(UUID.randomUUID(), "Knights");
        guild.setRival(Ref.of(playerId, Player.class, null));   // Guild.rival is @RefPolicy(noCache = true)

        Guild back = mapper.readValue(mapper.writeValueAsString(guild), Guild.class);

        assertTrue(back.getRival().policyOverride().isPresent());
        CachePolicy override = back.getRival().policyOverride().get();
        // noCache semantics: nothing is ever fresh.
        assertFalse(override.isFresh(new CacheEntry<>(new Player(playerId, "x"))));
    }

    @Test
    void refPolicy_ttl_annotation_is_baked_into_the_ref() throws Exception {
        UUID playerId = UUID.randomUUID();
        Guild guild = new Guild(UUID.randomUUID(), "Knights");
        guild.setFounder(Ref.of(playerId, Player.class, null));   // Guild.founder is @RefPolicy(ttlSeconds = 180)

        Guild back = mapper.readValue(mapper.writeValueAsString(guild), Guild.class);

        assertTrue(back.getFounder().policyOverride().isPresent());
        CachePolicy override = back.getFounder().policyOverride().get();
        Player p = new Player(playerId, "x");
        assertTrue(override.isFresh(new CacheEntry<>(p, Instant.now())), "within 180s is fresh");
        assertFalse(override.isFresh(new CacheEntry<>(p, Instant.now().minusSeconds(600))), "older than 180s is stale");
    }

    @Test
    void list_of_refs_round_trips_recovering_element_types() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Guild guild = new Guild(UUID.randomUUID(), "Knights");
        guild.setMembers(Arrays.asList(Ref.of(p1, Player.class, null), Ref.of(p2, Player.class, null)));

        Guild back = mapper.readValue(mapper.writeValueAsString(guild), Guild.class);

        assertEquals(2, back.getMembers().size());
        assertTrue(back.getMembers().get(0).isPresent());
        assertEquals(p1, back.getMembers().get(0).key());
        assertEquals(Player.class, back.getMembers().get(0).type());
        assertEquals(p2, back.getMembers().get(1).key());
    }
}
