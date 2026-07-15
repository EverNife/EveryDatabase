package br.com.finalcraft.everydatabase.manager.entityschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The framework-side registry of payload-schema migration chains for {@link EntitySchema} entities.
 * A consumer registers one upcasting step per version gap; the framework runs the chain on the RAW
 * stored payload before it is bound to the POJO (see {@link EntitySchemaMigratingCodec}), upcasting
 * a stale payload one version at a time up to {@link #currentVersion}, and reports whether anything
 * changed so the caller re-persists only after a real upcast.
 *
 * <p>Steps operate on a Jackson {@link ObjectNode} ({@link EntitySchemaStep}), so a consumer's POJO
 * no longer has to keep legacy fields around just to migrate them. A step may be
 * {@link EntitySchemaMigrationMode#EAGER}, which additionally drives a boot-time full-collection
 * sweep (see {@link EntitySchemaSweeper}).
 *
 * <p>Registration must be a contiguous chain starting at {@link EntitySchema#INITIAL_SCHEMA_VERSION},
 * and must happen BEFORE the entities load. The chain is consulted only when a payload is decoded,
 * so a chain grown after a row was decoded never re-runs on it. Registration is static and survives
 * a manager reload.
 */
public final class EntitySchemaMigrations {

    private EntitySchemaMigrations() {
    }

    /** The JSON key the version lives under (the {@code schemaVersion} field of every {@link EntitySchema} POJO). */
    public static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    /** Always protected across a step, on top of the caller-supplied identity key(s). */
    private static final String LOCK_VERSION_FIELD = "lockVersion";

    /** Per-entity-type chain: the entry at index i upgrades version {@code (i + 1)} to {@code (i + 2)}. */
    private static final Map<Class<?>, List<Step>> STEPS = new ConcurrentHashMap<>();

    /** Types for which a "decoded behind current" diagnostic has already been logged (one-shot). */
    private static final Set<Class<?>> WARNED_STALE = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    /** Appends a {@link EntitySchemaMigrationMode#LAZY} step. See {@link #register(Class, int, EntitySchemaMigrationMode, EntitySchemaStep)}. */
    public static <T extends EntitySchema> void register(Class<T> type, int fromVersion, EntitySchemaStep step) {
        register(type, fromVersion, EntitySchemaMigrationMode.LAZY, step);
    }

    /**
     * Appends the step that upcasts an instance of {@code type} FROM {@code fromVersion} to
     * {@code fromVersion + 1}. Steps must be registered as a contiguous chain starting at
     * {@link EntitySchema#INITIAL_SCHEMA_VERSION} - registering out of order, or twice for the same
     * {@code fromVersion}, is a programming error and throws.
     */
    public static <T extends EntitySchema> void register(Class<T> type, int fromVersion,
                                                         EntitySchemaMigrationMode mode, EntitySchemaStep step) {
        if (fromVersion < EntitySchema.INITIAL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Entity-schema migration fromVersion must be >= "
                    + EntitySchema.INITIAL_SCHEMA_VERSION + " (got " + fromVersion + " for "
                    + type.getName() + ")");
        }
        List<Step> steps = STEPS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        synchronized (steps) {
            checkContiguous(type, EntitySchema.INITIAL_SCHEMA_VERSION + steps.size(), fromVersion);
            steps.add(new Step(step, mode));
        }
    }

    /**
     * Validates that a migration step for {@code type} is declared contiguously - its
     * {@code gotFrom} must equal the {@code expectedFrom} that follows the previous step. Throws a
     * uniform {@link IllegalStateException} otherwise. Shared by the direct {@link #register} path
     * and any external chain-builder, so the ordering rule reads identically everywhere.
     */
    public static void checkContiguous(Class<?> type, int expectedFrom, int gotFrom) {
        if (gotFrom != expectedFrom) {
            throw new IllegalStateException("Entity-schema migrations for " + type.getName()
                    + " must be declared contiguously: expected fromVersion " + expectedFrom
                    + " but got " + gotFrom + ".");
        }
    }

    /**
     * Installs (replacing wholesale) the whole chain for {@code type}. The list is ordered: entry i
     * upgrades version {@code (i + 1)} to {@code (i + 2)}. An empty/null list is a no-op (it does
     * NOT clear a chain registered via {@link #register}), so a consumer with no migrations never
     * disturbs another registration.
     */
    public static void registerChain(Class<? extends EntitySchema> type, List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        STEPS.put(type, new CopyOnWriteArrayList<>(steps));
    }

    // ------------------------------------------------------------------
    //  Version queries
    // ------------------------------------------------------------------

    /**
     * The schema version the current code expects for {@code type}: one above the last registered
     * step ({@link EntitySchema#INITIAL_SCHEMA_VERSION} when none are registered). A freshly
     * created entity should be stamped with this.
     */
    public static int currentVersion(Class<?> type) {
        List<Step> steps = STEPS.get(type);
        return EntitySchema.INITIAL_SCHEMA_VERSION + (steps == null ? 0 : steps.size());
    }

    /**
     * Whether any migration step is registered for {@code type} - i.e. whether a decode of it can
     * ever upcast. Sugar over {@link #currentVersion(Class)}; the read path uses it to skip the
     * tree round-trip entirely for a type whose payload never evolved.
     */
    public static boolean hasChain(Class<?> type) {
        return currentVersion(type) > EntitySchema.INITIAL_SCHEMA_VERSION;
    }

    /**
     * The highest version an {@link EntitySchemaMigrationMode#EAGER} step upgrades TO, or
     * {@link EntitySchema#INITIAL_SCHEMA_VERSION} when no step is eager. The eager sweep drives the
     * whole collection up to this version; because a decode always reaches {@link #currentVersion},
     * the sweep effectively brings touched rows fully current (the cascade cost documented on
     * {@link EntitySchemaMigrationMode}).
     */
    public static int eagerTargetVersion(Class<?> type) {
        List<Step> steps = STEPS.get(type);
        int target = EntitySchema.INITIAL_SCHEMA_VERSION;
        if (steps == null) return target;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).mode() == EntitySchemaMigrationMode.EAGER) {
                target = EntitySchema.INITIAL_SCHEMA_VERSION + i + 1; // step i upgrades (i+1) -> (i+2)
            }
        }
        return target;
    }

    // ------------------------------------------------------------------
    //  The runner (raw node)
    // ------------------------------------------------------------------

    /**
     * Upcasts {@code node} in place from its stored {@code "schemaVersion"} to the current code
     * version, running each registered step in order. The framework - never the step - owns the
     * version field and the protected fields: after each step the version is stamped and
     * {@code protectedIdentityFields} plus {@code "lockVersion"} are restored to their pre-step
     * values (absent stays absent).
     *
     * <p>A payload already at (or ahead of) the current version is left completely untouched and
     * returns {@code false}. A missing version field is treated as
     * {@link EntitySchema#INITIAL_SCHEMA_VERSION} (a pre-schema payload); a non-integral version
     * field, a version below the initial one, or a throwing step, raises
     * {@link EntitySchemaMigrationException}.
     *
     * @return {@code true} when at least one step ran (the decoded entity should be re-persisted)
     */
    public static boolean migrateNode(Class<?> type, ObjectNode node, String... protectedIdentityFields) {
        List<Step> steps = STEPS.get(type);
        int current = currentVersion(type);
        int version = readNodeVersion(type, node);
        if (version >= current) {
            return false; // current, or written by a newer schema (ahead) - never touch it
        }
        List<String> protectedFields = new ArrayList<>(Arrays.asList(protectedIdentityFields));
        protectedFields.add(LOCK_VERSION_FIELD);

        boolean changed = false;
        while (steps != null && version < current && version - EntitySchema.INITIAL_SCHEMA_VERSION < steps.size()) {
            Step entry = steps.get(version - EntitySchema.INITIAL_SCHEMA_VERSION);
            Map<String, JsonNode> snapshot = snapshot(node, protectedFields);
            try {
                entry.step().upgrade(node);
            } catch (Exception e) {
                throw new EntitySchemaMigrationException(type, version, e);
            }
            restore(node, protectedFields, snapshot);
            version++;
            node.put(SCHEMA_VERSION_FIELD, version);
            changed = true;
        }
        return changed;
    }

    private static int readNodeVersion(Class<?> type, ObjectNode node) {
        JsonNode v = node.get(SCHEMA_VERSION_FIELD);
        if (v == null || v.isNull()) {
            return EntitySchema.INITIAL_SCHEMA_VERSION; // pre-schema payload: field absent
        }
        if (!v.isIntegralNumber()) {
            throw new EntitySchemaMigrationException(type, "non-integral '" + SCHEMA_VERSION_FIELD + "': " + v);
        }
        int version = v.asInt();
        if (version < EntitySchema.INITIAL_SCHEMA_VERSION) {
            // A stored version below the initial one describes no shape the chain can start from
            // (0 is what an uninitialized 'int schemaVersion' field writes), and guessing which one
            // it really is would corrupt the row. Refusing keeps it readable once the entity stamps
            // its version correctly.
            throw new EntitySchemaMigrationException(type, "stored '" + SCHEMA_VERSION_FIELD + "' is "
                    + version + ", below the initial version " + EntitySchema.INITIAL_SCHEMA_VERSION
                    + " - the entity must stamp its schema version on construction");
        }
        return version;
    }

    private static Map<String, JsonNode> snapshot(ObjectNode node, List<String> fields) {
        Map<String, JsonNode> snap = new HashMap<>(fields.size() * 2);
        for (String field : fields) {
            snap.put(field, node.get(field)); // null == absent
        }
        return snap;
    }

    private static void restore(ObjectNode node, List<String> fields, Map<String, JsonNode> snap) {
        for (String field : fields) {
            JsonNode value = snap.get(field);
            if (value == null) {
                node.remove(field);
            } else {
                node.set(field, value);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Guards
    // ------------------------------------------------------------------

    /**
     * True when {@code entity} was written by a NEWER schema than this code knows. Such an entity
     * was decoded with the newer fields silently dropped (the codec ignores unknown properties), so
     * re-persisting it from this instance would permanently erase them while keeping the newer
     * version stamp - a well-behaved flush pipeline uses this to refuse exactly that write.
     */
    public static boolean isAhead(EntitySchema entity) {
        return entity != null && entity.getSchemaVersion() > currentVersion(entity.getClass());
    }

    /**
     * True when {@code entity} decoded at a version BELOW current - which should not happen,
     * because the codec migrates on decode. Reaching this state means the chain grew after the row
     * was decoded (a migration registered too late). The framework diagnoses it but does NOT
     * stamp/dirty the entity: stamping without running the steps would bless un-migrated data;
     * leaving the old version lets the row migrate correctly on its next decode.
     */
    public static boolean isBehind(EntitySchema entity) {
        return entity != null && entity.getSchemaVersion() < currentVersion(entity.getClass());
    }

    /** {@code true} the FIRST time a stale decode is seen for {@code type} - drives a one-shot warning. */
    public static boolean firstStaleWarning(Class<?> type) {
        return WARNED_STALE.add(type);
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /** Drops every registered chain (for tests). */
    public static void clear() {
        STEPS.clear();
        WARNED_STALE.clear();
    }

    /**
     * Drops the chain registered for ONE entity type - called when a consumer's entities are
     * unregistered at runtime, so the registry does not pin the consumer's classes (and their
     * classloader).
     */
    public static void clear(Class<?> type) {
        STEPS.remove(type);
        WARNED_STALE.remove(type);
    }

    /**
     * Read-only snapshot of the chain registered for {@code type} (empty when none) - lets a
     * consumer inspect or copy a chain it did not register, e.g. to mirror it onto another entity
     * type or to report the pending steps in admin tooling.
     */
    public static List<Step> steps(Class<?> type) {
        List<Step> steps = STEPS.get(type);
        return steps == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /**
     * One entry in a type's migration chain: the {@link EntitySchemaStep} plus its
     * {@link EntitySchemaMigrationMode}. Immutable. Position i in the chain upgrades version
     * {@code (i + 1)} to {@code (i + 2)}.
     */
    public static final class Step {

        private final EntitySchemaStep step;
        private final EntitySchemaMigrationMode mode;

        public Step(EntitySchemaStep step, EntitySchemaMigrationMode mode) {
            this.step = Objects.requireNonNull(step, "step");
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        public EntitySchemaStep step() {
            return step;
        }

        public EntitySchemaMigrationMode mode() {
            return mode;
        }
    }
}
