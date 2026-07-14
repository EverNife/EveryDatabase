package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A {@link Codec} decorator that runs the registered {@link EntitySchemaStep} chain on the raw tree
 * before binding it to the POJO. Wrap an entity's codec once at descriptor-build time; every read
 * path (cache resolve, preload, detached query, transfer, in-memory deep-copy) then migrates
 * uniformly - the codec is the single universal read seam of every backend.
 *
 * <p>The write path is untouched: {@link #encode} pure-delegates. On decode, an actually-upcast
 * entity is marked dirty ({@link IDirtyable}) so the migrated shape is re-persisted on the next
 * flush - the same "changed -&gt; re-persist" channel a manual on-read upcast would need, just
 * moved into the codec so no consumer has to remember it.
 *
 * @param <V> the entity type (unbounded: {@link #wrap} passes a non-{@link EntitySchema} type
 *            straight through, so it composes with install sites that also bind non-versioned
 *            entities)
 */
public final class EntitySchemaMigratingCodec<V> implements Codec<V>, ObjectMapperAware {

    private final Class<V> type;
    private final Codec<V> inner;
    private final ObjectMapper mapper;
    private final String[] protectedIdentityFields;

    private EntitySchemaMigratingCodec(Class<V> type, Codec<V> inner, ObjectMapper mapper,
                                       String[] protectedIdentityFields) {
        this.type = type;
        this.inner = inner;
        this.mapper = mapper;
        this.protectedIdentityFields = protectedIdentityFields;
    }

    /**
     * Wraps {@code inner} so a registered migration chain runs on the raw tree before binding.
     * Returns {@code inner} unchanged when {@code type} is not {@link EntitySchema}, or when no
     * chain is registered for it (nothing to migrate - the wrapper would be pure overhead). Fails
     * fast at bind time if a chain IS registered but {@code inner} does not expose an
     * {@link ObjectMapper} ({@link ObjectMapperAware}) - tree ops need one (works for both JSON and
     * YAML codecs).
     *
     * @param protectedIdentityFields the identity key field name(s) the runner must protect across
     *                                every step (e.g. {@code "uuid"} or {@code "accountId"})
     */
    public static <V> Codec<V> wrap(Class<V> type, Codec<V> inner, String... protectedIdentityFields) {
        if (!EntitySchema.class.isAssignableFrom(type)) {
            return inner; // not a versioned entity - nothing to migrate
        }
        if (EntitySchemaMigrations.currentVersion(type) <= EntitySchema.INITIAL_SCHEMA_VERSION) {
            return inner; // no chain registered now - skip the wrapper entirely
        }
        if (!(inner instanceof ObjectMapperAware)) {
            throw new IllegalArgumentException("An entity-schema migration chain is registered for "
                    + type.getName() + " but its codec " + inner.getClass().getName()
                    + " does not expose an ObjectMapper (implement ObjectMapperAware) -"
                    + " raw-tree migration needs one.");
        }
        ObjectMapper mapper = ((ObjectMapperAware) inner).objectMapper();
        return new EntitySchemaMigratingCodec<>(type, inner, mapper, protectedIdentityFields.clone());
    }

    @Override
    public V decode(byte[] data) {
        JsonNode tree;
        try {
            tree = mapper.readTree(data);
        } catch (Exception e) {
            throw new EntitySchemaMigrationException(type, "stored payload is not readable as a tree");
        }
        if (!(tree instanceof ObjectNode)) {
            throw new EntitySchemaMigrationException(type, "stored payload is not a JSON object");
        }
        ObjectNode node = (ObjectNode) tree;
        boolean upcast = EntitySchemaMigrations.migrateNode(type, node, protectedIdentityFields);
        V value;
        try {
            value = mapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new EntitySchemaMigrationException(type, 0, e);
        }
        if (upcast && value instanceof IDirtyable) {
            ((IDirtyable) value).markDirty(); // re-persist the migrated shape on the next flush
        }
        return value;
    }

    @Override
    public byte[] encode(V value) {
        return inner.encode(value); // the write path is never migrated
    }

    @Override
    public String contentType() {
        return inner.contentType();
    }

    @Override
    public ObjectMapper objectMapper() {
        return mapper;
    }
}
