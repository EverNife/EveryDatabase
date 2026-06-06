package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.codec.Codec;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.versioned.OptimisticLockException;
import br.com.finalcraft.evernifecore.storage.versioned.Versioned;

import java.util.*;
import java.util.function.BiConsumer;
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
    /** Nullable. When non-null (together with {@link #versionSetter}), optimistic locking is active. */
    private final Function<V, Long> versionGetter;
    /** Nullable. When non-null (together with {@link #versionGetter}), optimistic locking is active. */
    private final BiConsumer<V, Long> versionSetter;

    private EntityDescriptor(Builder<K, V> b) {
        this.collection     = b.collection;
        this.type           = b.type;
        this.keyType        = b.keyType;
        this.keyExtractor   = b.keyExtractor;
        this.codec          = b.codec;
        this.indexes        = Collections.unmodifiableList(new ArrayList<>(b.indexes));
        this.versionGetter  = b.versionGetter;
        this.versionSetter  = b.versionSetter;
    }

    public String collection()                 { return collection; }
    public Class<V> type()                     { return type; }
    public Class<K> keyType()                  { return keyType; }
    public Function<V, K> keyExtractor()       { return keyExtractor; }
    public Codec<V> codec()                    { return codec; }
    public List<IndexHint> indexes()           { return indexes; }

    /**
     * Returns the version-getter function, or {@code null} if this descriptor is not versioned.
     * Use {@link #isVersioned()} to check before calling.
     */
    public Function<V, Long> versionGetter()   { return versionGetter; }

    /**
     * Returns the version-setter consumer, or {@code null} if this descriptor is not versioned.
     * Use {@link #isVersioned()} to check before calling.
     */
    public BiConsumer<V, Long> versionSetter() { return versionSetter; }

    /**
     * Returns {@code true} when optimistic locking is active for this descriptor, i.e. both
     * {@link #versionGetter()} and {@link #versionSetter()} are non-null.
     * Descriptors without a {@code .version(...)} call on the builder always return {@code false},
     * and their repositories use plain upsert semantics (optimistic locking is opt-in).
     */
    public boolean isVersioned() { return versionGetter != null && versionSetter != null; }

    @Override
    public String toString() {
        return "EntityDescriptor{collection='" + collection + "', type=" + type.getSimpleName()
            + (isVersioned() ? ", versioned=true" : "") + "}";
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
        private Function<V, Long>    versionGetter;
        private BiConsumer<V, Long>  versionSetter;

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

        /**
         * Activates optimistic locking for this descriptor.
         *
         * <p>The {@code getter} is called before every save to read the entity's current
         * version. The {@code setter} is called after a successful insert or update so that
         * the in-memory entity reflects the version now held by the backend.
         *
         * <p>Backends that see this descriptor will:
         * <ul>
         *   <li>Add a {@code lock_version} column / field to storage (SQL: only for new tables,
         *       existing tables need a migration).</li>
         *   <li>On INSERT: store version 0 and call {@code setter(entity, 0)}.</li>
         *   <li>On UPDATE: only update if stored version matches {@code getter(entity)};
         *       on success call {@code setter(entity, storedVersion + 1)};
         *       on mismatch throw {@link OptimisticLockException}.</li>
         * </ul>
         *
         * <p>Descriptors without a {@code .version(...)} call keep the current plain upsert
         * behaviour - this method is entirely opt-in.
         *
         * @param getter extracts the current lock version from an entity
         * @param setter sets the lock version on an entity after a successful save
         * @return this builder
         */
        public Builder<K, V> version(Function<V, Long> getter, BiConsumer<V, Long> setter) {
            this.versionGetter = getter;
            this.versionSetter = setter;
            return this;
        }

        /**
         * Convenience overload that wires the {@link Versioned} interface methods as the
         * version accessors. Equivalent to:
         * <pre>
         * .version(v -> ((Versioned) v).getLockVersion(),
         *          (v, ver) -> ((Versioned) v).setLockVersion(ver))
         * </pre>
         *
         * <p>The descriptor's entity type {@code V} must implement {@link Versioned} at
         * runtime; a {@link ClassCastException} will be thrown by the lambdas otherwise.
         *
         * @return this builder
         */
        public Builder<K, V> versioned() {
            this.versionGetter = v -> ((Versioned) v).getLockVersion();
            this.versionSetter = (v, ver) -> ((Versioned) v).setLockVersion(ver);
            return this;
        }

        /**
         * Builds the immutable {@link EntityDescriptor}.
         *
         * <p>As part of building, the entity type {@code V} is scanned for
         * {@link br.com.finalcraft.evernifecore.storage.query.Indexed} annotations. Any annotated
         * field produces an {@link IndexHint} that is merged with manually declared hints.
         * If the same field path appears in both, an {@link IllegalStateException} is thrown
         * (duplicate index). This allows mixing annotation-driven and manual declarations
         * when needed, but prevents accidental double-indexing.
         *
         * @throws IllegalStateException    if required fields are missing or duplicate index paths exist
         * @throws IllegalArgumentException if an {@code @Indexed} field has an unsupported Java type
         */
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

            // Scan entity class for @Indexed annotations and merge with manually declared hints.
            indexes.addAll(IndexHint.fromAnnotations(type));

            // Reject duplicate index hints on the same field path - keeping two indexes
            // on the same field has no benefit and confuses some backends.
            Set<String> seenPaths = new HashSet<>();
            for (IndexHint hint : indexes) {
                if (!seenPaths.add(hint.fieldPath())) {
                    throw new IllegalStateException(
                        "EntityDescriptor: duplicate index hint on field '" + hint.fieldPath()
                        + "'. Each field may only be indexed once. "
                        + "(Check both manual .index() calls and @Indexed annotations on "
                        + type.getSimpleName() + ".)");
                }
            }

            return new EntityDescriptor<>(this);
        }
    }
}
