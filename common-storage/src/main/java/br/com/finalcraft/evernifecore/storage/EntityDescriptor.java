package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.codec.Codec;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Immutable metadata that describes an entity to a {@link Storage} backend.
 *
 * <p>Contains:
 * <ul>
 *   <li>{@code collection} - logical name of the table / collection / directory</li>
 *   <li>{@code type} / {@code keyType} - Java types for type-safe generics</li>
 *   <li>{@code keyExtractor} - extracts the key from an entity instance</li>
 *   <li>{@code codec} - serialises/deserialises the entity</li>
 *   <li>{@code indexes} - hints for the backend (optional)</li>
 * </ul>
 *
 * <p>Use the fluent {@link #builder(Class, Class)} factory to construct instances.
 *
 * <p><b>Collection naming rules</b> (enforced at {@link Builder#build()} time):
 * <ul>
 *   <li>Must start with a letter (a-z / A-Z)</li>
 *   <li>Remaining characters may be letters, digits (0-9), or underscores ({@code _})</li>
 *   <li>No spaces, hyphens, dots, backticks, quotes, or any other special character</li>
 * </ul>
 * These rules produce identifiers that are safe across all supported backends without
 * any additional quoting or escaping: MySQL/MariaDB, PostgreSQL, H2, SQLite, MongoDB,
 * and the local file-system.
 *
 * Example:
 *
 *   EntityDescriptor<UUID, SomeKindOfData> altDescriptor =
 *       EntityDescriptor.builder(UUID.class, SomeKindOfData.class)
 *           .collection("some_kind_of_data")
 *           .keyExtractor(SomeKindOfData::getUuid)
 *           .codec(new JacksonJsonCodec<>(SomeKindOfData.class))
 *           .build();
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public final class EntityDescriptor<K, V> {

    /**
     * Regex that every {@code collection} name must satisfy.
     * Starts with a letter; remaining chars are letters, digits, or underscores.
     * This is the intersection of safe identifiers for SQL (all dialects),
     * MongoDB collection names, and file-system directory names.
     */
    static final Pattern VALID_COLLECTION = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final String collection;
    private final Class<V> type;
    private final Class<K> keyType;
    private final Function<V, K> keyExtractor;
    private final Codec<V> codec;
    private final List<IndexHint> indexes;

    private EntityDescriptor(Builder<K, V> b) {
        this.collection   = b.collection;
        this.type         = b.type;
        this.keyType      = b.keyType;
        this.keyExtractor = b.keyExtractor;
        this.codec        = b.codec;
        this.indexes      = Collections.unmodifiableList(new ArrayList<>(b.indexes));
    }

    public String collection()          { return collection; }
    public Class<V> type()              { return type; }
    public Class<K> keyType()           { return keyType; }
    public Function<V, K> keyExtractor(){ return keyExtractor; }
    public Codec<V> codec()             { return codec; }
    public List<IndexHint> indexes()    { return indexes; }

    @Override
    public String toString() {
        return "EntityDescriptor{collection='" + collection + "', type=" + type.getSimpleName() + "}";
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    public static <K, V> Builder<K, V> builder(Class<K> keyType, Class<V> type) {
        return new Builder<>(keyType, type);
    }

    public static final class Builder<K, V> {

        private final Class<K> keyType;
        private final Class<V> type;
        private String collection;
        private Function<V, K> keyExtractor;
        private Codec<V> codec;
        private final List<IndexHint> indexes = new ArrayList<>();

        private Builder(Class<K> keyType, Class<V> type) {
            this.keyType = keyType;
            this.type    = type;
        }

        public Builder<K, V> collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder<K, V> keyExtractor(Function<V, K> keyExtractor) {
            this.keyExtractor = keyExtractor;
            return this;
        }

        public Builder<K, V> codec(Codec<V> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<K, V> index(IndexHint hint) {
            this.indexes.add(hint);
            return this;
        }

        public EntityDescriptor<K, V> build() {
            if (collection == null || collection.isEmpty())
                throw new IllegalStateException("EntityDescriptor.collection must be set");
            if (!VALID_COLLECTION.matcher(collection).matches())
                throw new IllegalStateException(
                    "EntityDescriptor.collection '" + collection + "' is not a valid identifier. " +
                    "Must start with a letter and contain only letters, digits, or underscores " +
                    "(no spaces, hyphens, dots, backticks, or other special characters). " +
                    "This rule is enforced for all backends: SQL, MongoDB, and local file storage.");
            if (keyExtractor == null)
                throw new IllegalStateException("EntityDescriptor.keyExtractor must be set");
            if (codec == null)
                throw new IllegalStateException("EntityDescriptor.codec must be set");

            // Reject duplicate index hints on the same field path - keeping two indexes
            // on the same field has no benefit and confuses some backends.
            Set<String> seenPaths = new HashSet<>();
            for (IndexHint hint : indexes) {
                if (!seenPaths.add(hint.fieldPath())) {
                    throw new IllegalStateException(
                        "EntityDescriptor: duplicate index hint on field '" + hint.fieldPath()
                        + "'. Each field may only be indexed once."
                    );
                }
            }

            return new EntityDescriptor<>(this);
        }
    }
}
