package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Sigil;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Talisman;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration registry and the raw-node runner, with no storage in sight: registration rules,
 * version arithmetic, and what {@code migrateNode} guarantees about the version stamp and the
 * fields a step may not touch.
 */
@DisplayName("EntitySchemaMigrations - registry + raw-node runner")
class EntitySchemaMigrationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A step that does nothing - for tests that only care about registration/arithmetic. */
    private static final EntitySchemaStep NO_OP = node -> { };

    @BeforeEach
    @AfterEach
    void resetTheGlobalRegistry() {
        EntitySchemaMigrations.clear();   // registration is static and process-wide
    }

    private static ObjectNode node(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("malformed test payload", e);
        }
    }

    // ==================================================================
    //  Registration
    // ==================================================================

    @Test
    @DisplayName("a contiguous chain registers, and each step lands at its version's slot")
    void contiguousChainRegisters() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 2, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 3, NO_OP);

        assertEquals(3, EntitySchemaMigrations.steps(Talisman.class).size());
        assertEquals(4, EntitySchemaMigrations.currentVersion(Talisman.class));
    }

    @Test
    @DisplayName("a gap in the chain throws")
    void gapThrows() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> EntitySchemaMigrations.register(Talisman.class, 3, NO_OP));
        assertTrue(error.getMessage().contains("contiguously"), error.getMessage());
    }

    @Test
    @DisplayName("registering the same fromVersion twice throws")
    void duplicateFromVersionThrows() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);

        assertThrows(IllegalStateException.class,
                () -> EntitySchemaMigrations.register(Talisman.class, 1, NO_OP));
    }

    @Test
    @DisplayName("a fromVersion below the initial version throws")
    void fromVersionBelowInitialThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> EntitySchemaMigrations.register(Talisman.class, 0, NO_OP));
    }

    @Test
    @DisplayName("registerChain replaces the whole chain; an empty or null one is a no-op")
    void registerChainReplacesWholesale() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 2, NO_OP);

        EntitySchemaMigrations.registerChain(Talisman.class, Collections.singletonList(
                new EntitySchemaMigrations.Step(NO_OP, EntitySchemaMigrationMode.EAGER)));
        assertEquals(1, EntitySchemaMigrations.steps(Talisman.class).size());
        assertEquals(2, EntitySchemaMigrations.currentVersion(Talisman.class));

        EntitySchemaMigrations.registerChain(Talisman.class, Collections.emptyList());
        EntitySchemaMigrations.registerChain(Talisman.class, null);
        assertEquals(1, EntitySchemaMigrations.steps(Talisman.class).size(),
                "an empty/null chain must not wipe an existing registration");
    }

    @Test
    @DisplayName("checkContiguous states the ordering rule uniformly for an external chain builder")
    void checkContiguousIsReusable() {
        EntitySchemaMigrations.checkContiguous(Talisman.class, 2, 2);   // matching: silent

        assertThrows(IllegalStateException.class,
                () -> EntitySchemaMigrations.checkContiguous(Talisman.class, 2, 5));
    }

    @Test
    @DisplayName("steps() is an immutable snapshot, not a live view")
    void stepsIsAnImmutableSnapshot() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        List<EntitySchemaMigrations.Step> snapshot = EntitySchemaMigrations.steps(Talisman.class);

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new EntitySchemaMigrations.Step(NO_OP, EntitySchemaMigrationMode.LAZY)));

        EntitySchemaMigrations.register(Talisman.class, 2, NO_OP);
        assertEquals(1, snapshot.size(), "the snapshot must not follow later registrations");
        assertEquals(2, EntitySchemaMigrations.steps(Talisman.class).size());
    }

    @Test
    @DisplayName("steps() of an unregistered type is empty, not null")
    void stepsOfUnregisteredTypeIsEmpty() {
        assertTrue(EntitySchemaMigrations.steps(Sigil.class).isEmpty());
    }

    // ==================================================================
    //  Version arithmetic
    // ==================================================================

    @Test
    @DisplayName("with no steps, current == initial and there is no chain")
    void noStepsMeansInitial() {
        assertEquals(EntitySchema.INITIAL_SCHEMA_VERSION, EntitySchemaMigrations.currentVersion(Talisman.class));
        assertFalse(EntitySchemaMigrations.hasChain(Talisman.class));
        assertEquals(EntitySchema.INITIAL_SCHEMA_VERSION, EntitySchemaMigrations.eagerTargetVersion(Talisman.class));
    }

    @Test
    @DisplayName("an all-lazy chain has a current version but no eager target")
    void allLazyChainHasNoEagerTarget() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 2, NO_OP);

        assertTrue(EntitySchemaMigrations.hasChain(Talisman.class));
        assertEquals(3, EntitySchemaMigrations.currentVersion(Talisman.class));
        assertEquals(EntitySchema.INITIAL_SCHEMA_VERSION, EntitySchemaMigrations.eagerTargetVersion(Talisman.class));
    }

    @Test
    @DisplayName("an eager step in the middle sets the sweep target below current - the cascade")
    void eagerStepInTheMiddleSetsTheTarget() {
        EntitySchemaMigrations.register(Talisman.class, 1, EntitySchemaMigrationMode.LAZY, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 2, EntitySchemaMigrationMode.EAGER, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 3, EntitySchemaMigrationMode.LAZY, NO_OP);

        assertEquals(4, EntitySchemaMigrations.currentVersion(Talisman.class));
        assertEquals(3, EntitySchemaMigrations.eagerTargetVersion(Talisman.class),
                "the target is what the LAST eager step upgrades TO");
    }

    // ==================================================================
    //  The runner: version stamping
    // ==================================================================

    @Test
    @DisplayName("a multi-step run re-stamps the version before every step")
    void multiStepRunReStampsEachPass() {
        List<Integer> observed = new ArrayList<>();
        EntitySchemaStep record = n -> observed.add(n.get(EntitySchemaMigrations.SCHEMA_VERSION_FIELD).asInt());
        EntitySchemaMigrations.register(Talisman.class, 1, record);
        EntitySchemaMigrations.register(Talisman.class, 2, record);

        ObjectNode node = node("{\"schemaVersion\":1,\"might\":7}");
        assertTrue(EntitySchemaMigrations.migrateNode(Talisman.class, node));

        assertEquals(Arrays.asList(1, 2), observed, "each step must see its own fromVersion");
        assertEquals(3, node.get(EntitySchemaMigrations.SCHEMA_VERSION_FIELD).asInt());
    }

    @Test
    @DisplayName("a missing version field is a pre-schema payload and migrates from the initial version")
    void missingVersionFieldMigratesFromInitial() {
        EntitySchemaMigrations.register(Talisman.class, 1, n -> n.put("element", "neutral"));

        ObjectNode node = node("{\"might\":7}");
        assertTrue(EntitySchemaMigrations.migrateNode(Talisman.class, node));

        assertEquals(2, node.get(EntitySchemaMigrations.SCHEMA_VERSION_FIELD).asInt());
        assertEquals("neutral", node.get("element").asText());
    }

    @Test
    @DisplayName("a payload at (or ahead of) the current version is left completely untouched")
    void currentOrAheadPayloadIsUntouched() {
        EntitySchemaMigrations.register(Talisman.class, 1, n -> n.put("tampered", true));

        ObjectNode current = node("{\"schemaVersion\":2,\"might\":7}");
        assertFalse(EntitySchemaMigrations.migrateNode(Talisman.class, current));
        assertFalse(current.has("tampered"));

        ObjectNode ahead = node("{\"schemaVersion\":9,\"might\":7}");
        assertFalse(EntitySchemaMigrations.migrateNode(Talisman.class, ahead));
        assertFalse(ahead.has("tampered"));
        assertEquals(9, ahead.get(EntitySchemaMigrations.SCHEMA_VERSION_FIELD).asInt());
    }

    // ==================================================================
    //  The runner: protected fields
    // ==================================================================

    @Test
    @DisplayName("a step cannot re-key or unlock a row: protected fields are restored after it")
    void protectedFieldsAreRestored() {
        EntitySchemaMigrations.register(Talisman.class, 1, n -> {
            n.put("uuid", "hijacked");
            n.put("lockVersion", 999);
            n.put("might", 42);   // not protected: the step owns it
        });

        ObjectNode node = node("{\"schemaVersion\":1,\"uuid\":\"the-key\",\"lockVersion\":3,\"might\":7}");
        assertTrue(EntitySchemaMigrations.migrateNode(Talisman.class, node, "uuid"));

        assertEquals("the-key", node.get("uuid").asText());
        assertEquals(3, node.get("lockVersion").asInt(), "the literal lockVersion is always protected");
        assertEquals(42, node.get("might").asInt(), "an unprotected field is the step's to change");
    }

    @Test
    @DisplayName("a protected field the payload never had stays absent")
    void absentProtectedFieldStaysAbsent() {
        EntitySchemaMigrations.register(Talisman.class, 1, n -> {
            n.put("uuid", "invented");
            n.put("lockVersion", 999);
        });

        ObjectNode node = node("{\"schemaVersion\":1,\"might\":7}");
        assertTrue(EntitySchemaMigrations.migrateNode(Talisman.class, node, "uuid"));

        assertFalse(node.has("uuid"), "absent must stay absent, not become the step's invention");
        assertFalse(node.has("lockVersion"));
    }

    @Test
    @DisplayName("a protected field stored as JSON null is restored as null, not dropped")
    void nullProtectedFieldIsRestoredAsNull() {
        EntitySchemaMigrations.register(Talisman.class, 1, n -> n.put("uuid", "invented"));

        ObjectNode node = node("{\"schemaVersion\":1,\"uuid\":null,\"might\":7}");
        assertTrue(EntitySchemaMigrations.migrateNode(Talisman.class, node, "uuid"));

        assertTrue(node.has("uuid"));
        assertTrue(node.get("uuid").isNull(), "an explicit JSON null is a value, not an absence");
    }

    // ==================================================================
    //  The runner: malformed input
    // ==================================================================

    @Test
    @DisplayName("a stored version of 0 is refused with a domain error, never an index blow-up")
    void versionZeroIsRefused() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);

        ObjectNode node = node("{\"schemaVersion\":0,\"might\":7}");
        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> EntitySchemaMigrations.migrateNode(Talisman.class, node));

        assertTrue(error.getMessage().contains("below the initial version"), error.getMessage());
    }

    @Test
    @DisplayName("a negative stored version is refused the same way")
    void negativeVersionIsRefused() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);

        ObjectNode node = node("{\"schemaVersion\":-1,\"might\":7}");
        assertThrows(EntitySchemaMigrationException.class,
                () -> EntitySchemaMigrations.migrateNode(Talisman.class, node));
    }

    @Test
    @DisplayName("a non-integral version field is refused")
    void nonIntegralVersionIsRefused() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);

        ObjectNode node = node("{\"schemaVersion\":\"x\",\"might\":7}");
        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> EntitySchemaMigrations.migrateNode(Talisman.class, node));

        assertTrue(error.getMessage().contains("non-integral"), error.getMessage());
    }

    @Test
    @DisplayName("a throwing step fails the migration with its cause and leaves the version stamp alone")
    void throwingStepKeepsTheOriginalVersion() {
        IllegalArgumentException boom = new IllegalArgumentException("bad legacy data");
        EntitySchemaMigrations.register(Talisman.class, 1, n -> { throw boom; });

        ObjectNode node = node("{\"schemaVersion\":1,\"might\":7}");
        EntitySchemaMigrationException error = assertThrows(EntitySchemaMigrationException.class,
                () -> EntitySchemaMigrations.migrateNode(Talisman.class, node));

        assertSame(boom, error.getCause());
        assertEquals(1, node.get(EntitySchemaMigrations.SCHEMA_VERSION_FIELD).asInt(),
                "a failed step must not bless the row with a version it never reached");
    }

    // ==================================================================
    //  Guards
    // ==================================================================

    @Test
    @DisplayName("isAhead/isBehind compare the entity's stamp against the registered chain")
    void aheadAndBehindGuards() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Talisman.class, 2, NO_OP);   // current == 3

        assertTrue(EntitySchemaMigrations.isAhead(talismanAt(4)));
        assertFalse(EntitySchemaMigrations.isAhead(talismanAt(3)));
        assertTrue(EntitySchemaMigrations.isBehind(talismanAt(2)));
        assertFalse(EntitySchemaMigrations.isBehind(talismanAt(3)));

        assertFalse(EntitySchemaMigrations.isAhead(null));
        assertFalse(EntitySchemaMigrations.isBehind(null));
    }

    @Test
    @DisplayName("firstStaleWarning fires once per type")
    void firstStaleWarningIsOneShot() {
        assertTrue(EntitySchemaMigrations.firstStaleWarning(Talisman.class));
        assertFalse(EntitySchemaMigrations.firstStaleWarning(Talisman.class));
        assertTrue(EntitySchemaMigrations.firstStaleWarning(Sigil.class), "another type warns on its own");
    }

    // ==================================================================
    //  Lifecycle
    // ==================================================================

    @Test
    @DisplayName("clear(type) drops one type's chain and its warning latch, leaving the others")
    void clearOneType() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Sigil.class, 1, NO_OP);
        EntitySchemaMigrations.firstStaleWarning(Talisman.class);

        EntitySchemaMigrations.clear(Talisman.class);

        assertFalse(EntitySchemaMigrations.hasChain(Talisman.class));
        assertTrue(EntitySchemaMigrations.hasChain(Sigil.class));
        assertTrue(EntitySchemaMigrations.firstStaleWarning(Talisman.class), "the latch was cleared too");
    }

    @Test
    @DisplayName("clear() drops every chain")
    void clearEverything() {
        EntitySchemaMigrations.register(Talisman.class, 1, NO_OP);
        EntitySchemaMigrations.register(Sigil.class, 1, NO_OP);

        EntitySchemaMigrations.clear();

        assertFalse(EntitySchemaMigrations.hasChain(Talisman.class));
        assertFalse(EntitySchemaMigrations.hasChain(Sigil.class));
    }

    @Test
    @DisplayName("a Step keeps the step and mode it was built with, and rejects nulls")
    void stepHoldsItsModeAndRejectsNulls() {
        EntitySchemaMigrations.Step step = new EntitySchemaMigrations.Step(NO_OP, EntitySchemaMigrationMode.EAGER);

        assertSame(NO_OP, step.step());
        assertEquals(EntitySchemaMigrationMode.EAGER, step.mode());
        assertNotNull(assertThrows(NullPointerException.class,
                () -> new EntitySchemaMigrations.Step(null, EntitySchemaMigrationMode.LAZY)));
        assertNotNull(assertThrows(NullPointerException.class,
                () -> new EntitySchemaMigrations.Step(NO_OP, null)));
    }

    private static Talisman talismanAt(int schemaVersion) {
        Talisman talisman = new Talisman();
        talisman.setSchemaVersion(schemaVersion);
        return talisman;
    }
}
