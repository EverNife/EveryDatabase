package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.manager.cache.DirtyAccessor;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Codec} decorator that runs the registered {@link EntitySchemaStep} chain on the raw tree
 * before binding it to the POJO. Wrap an entity's codec once at descriptor-build time; every read
 * path (cache resolve, preload, detached query, transfer, in-memory deep-copy) then migrates
 * uniformly - the codec is the single universal read seam of every backend.
 *
 * <p>The write path is untouched: {@link #encode} pure-delegates. On decode, an actually-upcast
 * entity is marked dirty (whichever dirty-tracking form it opts into - see {@link DirtyAccessor})
 * so the migrated shape is re-persisted on the next flush - the same "changed -&gt; re-persist"
 * channel a manual on-read upcast would need, just moved into the codec so no consumer has to
 * remember it. An entity type with no dirty tracking still decodes correctly, but its migrated
 * shape is never re-persisted: every read re-migrates it.
 *
 * @param <V> the entity type (unbounded: {@link #wrap} passes a non-{@link EntitySchema} type
 *            straight through, so it composes with install sites that also bind non-versioned
 *            entities)
 */
public final class EntitySchemaMigratingCodec<V> implements Codec<V>, ObjectMapperAware {

    private final Class<V> type;
    private final Codec<V> inner;
    private final ObjectMapper mapper;
    private final DirtyAccessor dirtyAccessor;
    private final String[] protectedIdentityFields;

    private EntitySchemaMigratingCodec(Class<V> type, Codec<V> inner, ObjectMapper mapper,
                                       DirtyAccessor dirtyAccessor, String[] protectedIdentityFields) {
        this.type = type;
        this.inner = inner;
        this.mapper = mapper;
        this.dirtyAccessor = dirtyAccessor;
        this.protectedIdentityFields = protectedIdentityFields;
    }

    /**
     * Wraps {@code inner} so a registered migration chain runs on the raw tree before binding.
     * Returns {@code inner} unchanged only when {@code type} is not an {@link EntitySchema}.
     *
     * <p>An {@link EntitySchema} type is ALWAYS decorated, even with no chain registered yet:
     * a chain registered after the descriptor was built would otherwise never run, silently
     * leaving every row un-migrated. A wrapper with no chain costs one map lookup per decode
     * before delegating straight to {@code inner}.
     *
     * <p>Fails fast when {@code inner} does not expose an {@link ObjectMapper}
     * ({@link ObjectMapperAware}) - tree ops need one (works for both JSON and YAML codecs), and a
     * chain may be registered at any time after this call.
     *
     * @param protectedIdentityFields the identity key field name(s) the runner must protect across
     *                                every step (e.g. {@code "uuid"} or {@code "accountId"}); the
     *                                entity's optimistic-lock field, whatever it is named, is added
     *                                automatically
     */
    public static <V> Codec<V> wrap(Class<V> type, Codec<V> inner, String... protectedIdentityFields) {
        if (!EntitySchema.class.isAssignableFrom(type)) {
            return inner; // not a versioned entity - nothing to migrate
        }
        if (!(inner instanceof ObjectMapperAware)) {
            throw new IllegalArgumentException(type.getName() + " is an EntitySchema but its codec "
                    + inner.getClass().getName() + " does not expose an ObjectMapper (implement"
                    + " ObjectMapperAware) - raw-tree migration needs one.");
        }
        ObjectMapper mapper = ((ObjectMapperAware) inner).objectMapper();
        return new EntitySchemaMigratingCodec<>(type, inner, mapper, DirtyAccessor.forType(type),
                withLockField(type, protectedIdentityFields));
    }

    /**
     * Adds the entity's {@code @OptimisticLock} field - which may carry any name - to the fields the
     * runner restores after every step, so a step can never mutate the lock counter the backend
     * uses to detect a concurrent write.
     */
    private static String[] withLockField(Class<?> type, String[] protectedIdentityFields) {
        Field lockField = OptimisticLockScanner.findLockField(type);
        if (lockField == null) {
            return protectedIdentityFields.clone();
        }
        List<String> fields = new ArrayList<>(protectedIdentityFields.length + 1);
        for (String field : protectedIdentityFields) {
            fields.add(field);
        }
        if (!fields.contains(lockField.getName())) {
            fields.add(lockField.getName());
        }
        return fields.toArray(new String[0]);
    }

    @Override
    public V decode(byte[] data) {
        if (!EntitySchemaMigrations.hasChain(type)) {
            return inner.decode(data); // nothing to migrate: same bytes, same result, one pass
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(data);
        } catch (Exception e) {
            throw new EntitySchemaMigrationException(type, "stored payload is not readable as a tree", e);
        }
        if (!(tree instanceof ObjectNode)) {
            throw new EntitySchemaMigrationException(type, "stored payload is not a JSON object");
        }
        ObjectNode node = (ObjectNode) tree;
        boolean upcast = EntitySchemaMigrations.migrateNode(type, node, protectedIdentityFields);
        if (!upcast) {
            return inner.decode(data); // already latest: the original bytes serve, inner owns decode (cheapest path)
        }
        byte[] migrated;
        try {
            migrated = mapper.writeValueAsBytes(node); // re-encode only the actually-migrated row
        } catch (Exception e) {
            throw new EntitySchemaMigrationException(type, "failed to re-encode the migrated payload", e);
        }
        // Delegate to inner so a custom-decode codec (lifecycle hooks, re-binding) survives the migrated path;
        // binding the migrated tree straight to the POJO would bypass it, dropping anything the inner does
        // beyond a plain readValue.
        V value;
        try {
            value = inner.decode(migrated);
        } catch (Exception e) {
            throw new EntitySchemaMigrationException(type, "failed to bind the migrated payload to the entity type", e);
        }
        if (dirtyAccessor != null) {
            dirtyAccessor.markDirty(value); // re-persist the migrated shape on the next flush
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
