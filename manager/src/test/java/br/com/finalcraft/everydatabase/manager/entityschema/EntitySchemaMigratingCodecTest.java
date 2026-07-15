package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Rune;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Sigil;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Talisman;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The codec decorator: the single read seam where a stored payload is upcast before it is bound to
 * the POJO, and where an actually-upcast entity is flagged for re-persistence.
 */
@DisplayName("EntitySchemaMigratingCodec - migrate on decode")
class EntitySchemaMigratingCodecTest {

    private static final UUID KEY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    @AfterEach
    void resetTheGlobalRegistry() {
        EntitySchemaMigrations.clear();
    }

    /**
     * The chain {@link Talisman} evolved through: v2 added {@code element}, v3 renamed {@code power}
     * to {@code might} - a field the class no longer has, so only a raw-tree step can move it.
     */
    private static void registerTalismanChain() {
        EntitySchemaMigrations.register(Talisman.class, 1, node -> node.put("element", "neutral"));
        EntitySchemaMigrations.register(Talisman.class, 2, node -> {
            JsonNode power = node.remove("power");
            node.put("might", power == null ? 0 : power.asInt());
        });
    }

    /** Serializes a hand-written legacy shape with the codec's own mapper - JSON or YAML alike. */
    private static byte[] payload(Codec<?> codec, String json) {
        ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        try {
            return mapper.writeValueAsBytes(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("malformed test payload", e);
        }
    }

    private static String legacyTalisman() {
        return "{\"schemaVersion\":1,\"uuid\":\"" + KEY + "\",\"power\":7,\"revision\":5}";
    }

    // ==================================================================
    //  What gets wrapped
    // ==================================================================

    @Test
    @DisplayName("a non-EntitySchema type passes straight through unwrapped")
    void nonEntitySchemaTypeIsNotWrapped() {
        Codec<Quest> inner = new JacksonJsonCodec<>(Quest.class);

        assertSame(inner, EntitySchemaMigratingCodec.wrap(Quest.class, inner));
    }

    @Test
    @DisplayName("an EntitySchema type is wrapped even with no chain registered yet")
    void entitySchemaTypeIsAlwaysWrapped() {
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);

        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        assertNotNull(wrapped);
        assertFalse(wrapped == inner, "a chain registered later would otherwise never run");
    }

    @Test
    @DisplayName("an EntitySchema type whose codec exposes no ObjectMapper fails fast at wrap time")
    void codecWithoutAnObjectMapperFailsFast() {
        Codec<Talisman> opaque = new Codec<Talisman>() {
            @Override public byte[] encode(Talisman value) { return new byte[0]; }
            @Override public Talisman decode(byte[] data)  { return new Talisman(); }
            @Override public String contentType()          { return "application/octet-stream"; }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EntitySchemaMigratingCodec.wrap(Talisman.class, opaque, "uuid"));
        assertTrue(error.getMessage().contains("ObjectMapperAware"), error.getMessage());
    }

    @Test
    @DisplayName("the wrapper re-exposes the inner ObjectMapper, so index extraction still agrees with disk")
    void theWrapperIsItselfObjectMapperAware() {
        JacksonJsonCodec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);

        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        assertTrue(wrapped instanceof ObjectMapperAware);
        assertSame(inner.objectMapper(), ((ObjectMapperAware) wrapped).objectMapper());
        assertEquals(inner.contentType(), wrapped.contentType());
        assertTrue(wrapped.isJsonCodec());
    }

    // ==================================================================
    //  Decode: with and without a chain
    // ==================================================================

    @Test
    @DisplayName("with no chain the decode delegates to the inner codec, byte-for-byte identical")
    void noChainDelegatesToTheInnerCodec() {
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        byte[] data = inner.encode(new Talisman(KEY, 7, "fire", 1));
        Talisman decoded = wrapped.decode(data);

        assertEquals(7, decoded.getMight());
        assertEquals(1, decoded.getSchemaVersion(), "no chain: no upcast");
        assertFalse(decoded.isDirty(), "nothing was migrated, so nothing needs re-persisting");
    }

    @Test
    @DisplayName("a chain registered AFTER the wrap still migrates on the next read")
    void chainRegisteredAfterTheWrapStillMigrates() {
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");
        byte[] data = payload(inner, legacyTalisman());

        // the descriptor is already built and the codec already wrapped - the consumer registers late
        registerTalismanChain();

        Talisman decoded = wrapped.decode(data);

        assertEquals(3, decoded.getSchemaVersion());
        assertEquals(7, decoded.getMight(), "the renamed field followed the chain");
        assertEquals("neutral", decoded.getElement());
    }

    @Test
    @DisplayName("an upcast marks the IDirtyable flavor dirty, so the migrated shape gets re-persisted")
    void upcastMarksTheInterfaceFlavorDirty() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman decoded = wrapped.decode(payload(inner, legacyTalisman()));

        assertTrue(decoded.isDirty());
    }

    @Test
    @DisplayName("an upcast marks the @DirtyFlag flavor dirty too - the flavor instanceof cannot see")
    void upcastMarksTheAnnotationFlavorDirty() {
        EntitySchemaMigrations.register(Sigil.class, 1, node -> node.put("glyph", "rewritten"));
        Codec<Sigil> inner = new JacksonJsonCodec<>(Sigil.class);
        Codec<Sigil> wrapped = EntitySchemaMigratingCodec.wrap(Sigil.class, inner, "name");

        Sigil decoded = wrapped.decode(payload(inner, "{\"schemaVersion\":1,\"name\":\"ward\",\"glyph\":\"old\"}"));

        assertEquals(2, decoded.getSchemaVersion());
        assertEquals("rewritten", decoded.getGlyph());
        assertTrue(decoded.isTouched(), "@DirtyFlag must be set by the codec, not only IDirtyable");
    }

    @Test
    @DisplayName("a type with no dirty tracking still decodes migrated - it just re-migrates every read")
    void noDirtyTrackingStillDecodesMigrated() {
        EntitySchemaMigrations.register(Rune.class, 1, node -> node.put("inscription", "restored"));
        Codec<Rune> inner = new JacksonJsonCodec<>(Rune.class);
        Codec<Rune> wrapped = EntitySchemaMigratingCodec.wrap(Rune.class, inner, "id");

        Rune decoded = wrapped.decode(payload(inner, "{\"schemaVersion\":1,\"id\":\"r1\",\"inscription\":\"old\"}"));

        assertEquals(2, decoded.getSchemaVersion());
        assertEquals("restored", decoded.getInscription());
    }

    @Test
    @DisplayName("a payload already at the current version decodes clean")
    void currentPayloadDecodesClean() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman decoded = wrapped.decode(inner.encode(new Talisman(KEY, 7, "fire", 3)));

        assertFalse(decoded.isDirty());
        assertEquals(3, decoded.getSchemaVersion());
    }

    // ==================================================================
    //  Decode: protected fields
    // ==================================================================

    @Test
    @DisplayName("a step cannot touch the optimistic-lock field, whatever it is named")
    void theCustomNamedLockFieldSurvivesAHostileStep() {
        EntitySchemaMigrations.register(Talisman.class, 1, node -> {
            node.put("revision", 999);      // the @OptimisticLock field, discovered by name
            node.put("lockVersion", 999);   // the conventional name, always protected
            node.put("element", "neutral");
        });
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman decoded = wrapped.decode(payload(inner, legacyTalisman()));

        assertEquals(5L, decoded.getRevision(),
                "a step that bumped the lock counter would make the backend miss a concurrent write");
        assertEquals(2, decoded.getSchemaVersion());
    }

    @Test
    @DisplayName("a step cannot re-key a row: the declared identity field is restored")
    void theIdentityFieldSurvivesAHostileStep() {
        EntitySchemaMigrations.register(Talisman.class, 1,
                node -> node.put("uuid", UUID.randomUUID().toString()));
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman decoded = wrapped.decode(payload(inner, legacyTalisman()));

        assertEquals(KEY, decoded.getUuid());
    }

    // ==================================================================
    //  Decode: malformed payloads
    // ==================================================================

    @Test
    @DisplayName("an unreadable payload fails with the parse error kept as the cause")
    void unreadablePayloadKeepsItsCause() {
        registerTalismanChain();
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(
                Talisman.class, new JacksonJsonCodec<>(Talisman.class), "uuid");

        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> wrapped.decode("this is not json at all".getBytes(StandardCharsets.UTF_8)));

        assertTrue(error.getMessage().contains("not readable as a tree"), error.getMessage());
        assertNotNull(error.getCause(), "the parse position lives in the cause - do not swallow it");
    }

    @Test
    @DisplayName("a payload that is valid JSON but not an object is refused")
    void nonObjectPayloadIsRefused() {
        registerTalismanChain();
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(
                Talisman.class, new JacksonJsonCodec<>(Talisman.class), "uuid");

        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> wrapped.decode("[1,2,3]".getBytes(StandardCharsets.UTF_8)));

        assertTrue(error.getMessage().contains("not a JSON object"), error.getMessage());
    }

    @Test
    @DisplayName("a migrated tree that will not bind reports the bind failure, not a phantom step failure")
    void bindFailureIsReportedAsSuch() {
        EntitySchemaMigrations.register(Talisman.class, 1, node -> node.put("might", "not-a-number"));
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> wrapped.decode(payload(inner, legacyTalisman())));

        assertTrue(error.getMessage().contains("failed to bind"), error.getMessage());
        assertNotNull(error.getCause());
    }

    @Test
    @DisplayName("a stored version below the initial one is refused with a domain error")
    void versionBelowInitialIsRefused() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> wrapped.decode(payload(inner, "{\"schemaVersion\":0,\"uuid\":\"" + KEY + "\",\"power\":7}")));

        assertTrue(error.getMessage().contains("below the initial version"), error.getMessage());
    }

    // ==================================================================
    //  Encode
    // ==================================================================

    @Test
    @DisplayName("encode is pure delegation - the write path is never migrated")
    void encodeIsPureDelegation() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman stale = new Talisman(KEY, 7, "fire", 1);

        assertArrayEquals(inner.encode(stale), wrapped.encode(stale));
        assertEquals(1, stale.getSchemaVersion(), "encoding must not stamp or migrate the entity");
    }

    // ==================================================================
    //  YAML
    // ==================================================================

    @Test
    @DisplayName("a YAML codec migrates on decode just like JSON - the seam is the mapper, not the format")
    void yamlPayloadsMigrateToo() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonYamlCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman decoded = wrapped.decode(payload(inner, legacyTalisman()));

        assertEquals(3, decoded.getSchemaVersion());
        assertEquals(7, decoded.getMight());
        assertEquals("neutral", decoded.getElement());
        assertEquals(5L, decoded.getRevision());
        assertTrue(decoded.isDirty());
        assertFalse(wrapped.isJsonCodec(), "the wrapper reports the inner format, not JSON by assumption");
    }

    @Test
    @DisplayName("a migrated payload round-trips: decode, encode, decode again yields a stable shape")
    void migratedPayloadRoundTrips() {
        registerTalismanChain();
        Codec<Talisman> inner = new JacksonJsonCodec<>(Talisman.class);
        Codec<Talisman> wrapped = EntitySchemaMigratingCodec.wrap(Talisman.class, inner, "uuid");

        Talisman migrated = wrapped.decode(payload(inner, legacyTalisman()));
        Talisman reread = wrapped.decode(wrapped.encode(migrated));

        assertEquals(3, reread.getSchemaVersion());
        assertEquals(migrated.getMight(), reread.getMight());
        assertEquals(migrated.getElement(), reread.getElement());
        assertFalse(reread.isDirty(), "an already-current payload must not be re-migrated");
        assertNull(strayLegacyField(inner.encode(reread)), "the legacy field must be gone from disk");
    }

    /** The {@code power} node in an encoded payload, or {@code null} once the rename has landed. */
    private static JsonNode strayLegacyField(byte[] encoded) {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(encoded);
            return node.get("power");
        } catch (Exception e) {
            throw new IllegalStateException("unreadable payload", e);
        }
    }
}
