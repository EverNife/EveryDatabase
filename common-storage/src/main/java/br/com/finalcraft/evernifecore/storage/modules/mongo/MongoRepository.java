package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.codec.CodecException;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.IndexValueExtractor;
import br.com.finalcraft.evernifecore.storage.query.Query;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * MongoDB-backed {@link Repository}.
 *
 * <p>Each entity is stored as a MongoDB document of the form:
 * <pre>
 * {
 *   "_sk":   "key-as-string",
 *   "_data": "{\"field\":\"value\",...}",
 *   "_idx_type":     "ENABLED",          // present for each declared IndexHint
 *   "_idx_location_world": "world_nether"
 * }
 * </pre>
 * <p>Each declared {@link IndexHint} produces a sibling field {@code _idx_<field>}
 * populated from the entity at {@code save()} time, plus a real Mongo index over it
 * via {@code createIndex}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
final class MongoRepository<K, V> implements Repository<K, V> {

    /** Field storing the serialised entity key. */
    static final String FIELD_KEY  = "_sk";
    /** Field storing the JSON-encoded entity. */
    static final String FIELD_DATA = "_data";

    private final EntityDescriptor<K, V> descriptor;
    private final MongoCollection<Document> collection;
    /** Non-null only when operating inside a transaction. */
    private final ClientSession session;

    /** Indexed field paths declared on the descriptor; key = fieldPath. */
    private final Map<String, IndexHint> hintsByPath;

    MongoRepository(EntityDescriptor<K, V> descriptor, MongoCollection<Document> collection, ClientSession session) {
        this.descriptor = descriptor;
        this.collection = collection;
        this.session    = session;

        this.hintsByPath = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) {
            this.hintsByPath.put(hint.fieldPath(), hint);
        }
    }

    /**
     * Creates the unique key index plus every {@link IndexHint} declared on the descriptor.
     * Called once by {@code MongoStorage.repository()} when the repo is first obtained.
     */
    void ensureIndexes() {
        // Unique index on the storage key.
        collection.createIndex(Indexes.ascending(FIELD_KEY), new IndexOptions().unique(true));

        // One Mongo index per declared IndexHint.
        for (IndexHint hint : hintsByPath.values()) {
            String column = hint.indexColumnName();
            Bson def = hint.order() == IndexHint.Order.DESCENDING
                ? Indexes.descending(column)
                : Indexes.ascending(column);
            collection.createIndex(def, new IndexOptions().unique(hint.unique()));
        }
    }

    // ------------------------------------------------------------------
    //  CRUD
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = session != null
                ? collection.find(session, Filters.eq(FIELD_KEY, key.toString())).first()
                : collection.find(Filters.eq(FIELD_KEY, key.toString())).first();

            if (found == null) return Optional.empty();
            try {
                byte[] data = found.getString(FIELD_DATA).getBytes(StandardCharsets.UTF_8);
                return Optional.of(descriptor.codec().decode(data));
            } catch (CodecException e) {
                throw new RuntimeException("Mongo codec error for key=" + key, e);
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<List<V>> findMany(Collection<K> keys) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> keyStrings = new ArrayList<>(keys.size());
            for (K k : keys) keyStrings.add(k.toString());

            FindIterable<Document> found = session != null
                ? collection.find(session, Filters.in(FIELD_KEY, keyStrings))
                : collection.find(Filters.in(FIELD_KEY, keyStrings));

            return decodeAll(found);
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = descriptor.codec().encode(entity);
                Document doc = new Document()
                    .append(FIELD_KEY,  key.toString())
                    .append(FIELD_DATA, new String(data, StandardCharsets.UTF_8));

                // Populate _idx_* sibling fields for every declared IndexHint.
                if (!hintsByPath.isEmpty()) {
                    JsonNode tree = IndexValueExtractor.toTree(entity);
                    for (IndexHint hint : hintsByPath.values()) {
                        Object value = IndexValueExtractor.extract(tree, hint);
                        // Store TIMESTAMP as BSON Date so Compass shows human-readable values.
                        doc.append(hint.indexColumnName(), toMongoValue(value, hint));
                    }
                }

                ReplaceOptions opts = new ReplaceOptions().upsert(true);
                if (session != null)
                    collection.replaceOne(session, Filters.eq(FIELD_KEY, key.toString()), doc, opts);
                else
                    collection.replaceOne(Filters.eq(FIELD_KEY, key.toString()), doc, opts);
                return null;
            } catch (CodecException e) {
                throw new RuntimeException("Mongo codec error saving key=" + key, e);
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(entities.size());
        for (V entity : entities) futures.add(save(entity));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        return CompletableFuture.supplyAsync(() -> {
            long count = session != null
                ? collection.deleteOne(session, Filters.eq(FIELD_KEY, key.toString())).getDeletedCount()
                : collection.deleteOne(Filters.eq(FIELD_KEY, key.toString())).getDeletedCount();
            return count > 0;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = session != null
                ? collection.find(session, Filters.eq(FIELD_KEY, key.toString())).first()
                : collection.find(Filters.eq(FIELD_KEY, key.toString())).first();
            return found != null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.supplyAsync(
            () -> session != null ? collection.countDocuments(session) : collection.countDocuments(),
            StorageExecutors.async()
        );
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.supplyAsync(() -> {
            FindIterable<Document> all = session != null ? collection.find(session) : collection.find();
            return decodeAll(all).stream();
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  Index queries
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        return query(Query.eq(fieldPath, value));
    }

    @Override
    public CompletableFuture<List<V>> query(Query query) {
        // Validate synchronously so callers get IllegalArgumentException directly,
        // not wrapped in CompletionException - consistent with all other backends.
        List<Bson> filters = new ArrayList<>(query.conditions().size());
        for (Query.Condition c : query.conditions()) {
            IndexHint hint = hintsByPath.get(c.fieldPath());
            if (hint == null) {
                throw new IllegalArgumentException(
                    "Mongo: field '" + c.fieldPath() + "' is not indexed. "
                    + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
            }
            filters.add(toFilter(c, hint));
        }
        Bson combined = filters.size() == 1 ? filters.get(0) : Filters.and(filters);
        return CompletableFuture.supplyAsync(() -> {
            FindIterable<Document> found = session != null
                ? collection.find(session, combined)
                : collection.find(combined);
            return decodeAll(found);
        }, StorageExecutors.async());
    }

    private Bson toFilter(Query.Condition c, IndexHint hint) {
        String column = hint.indexColumnName();
        switch (c.op()) {
            case EQ:
                return Filters.eq(column, toMongoValue(c.value(), hint));
            case IN: {
                List<Object> values = new ArrayList<>(c.inValues().size());
                for (Object v : c.inValues()) values.add(toMongoValue(v, hint));
                return Filters.in(column, values);
            }
            case RANGE:
                List<Bson> parts = new ArrayList<>(2);
                if (c.rangeFrom() != null) parts.add(Filters.gte(column, toMongoValue(c.rangeFrom(), hint)));
                if (c.rangeTo()   != null) parts.add(Filters.lte(column, toMongoValue(c.rangeTo(),   hint)));
                if (parts.isEmpty()) return Filters.exists(column);
                return parts.size() == 1 ? parts.get(0) : Filters.and(parts);
            default:
                throw new IllegalStateException("Unknown op: " + c.op());
        }
    }

    /**
     * Converts a value to the appropriate MongoDB/BSON type for the given hint.
     * {@link IndexHint.FieldType#TIMESTAMP} values are stored and queried as BSON
     * {@code Date} ({@link java.util.Date}) so MongoDB Compass shows human-readable dates.
     * All other types are passed through as-is.
     */
    private static Object toMongoValue(Object value, IndexHint hint) {
        if (value == null || hint.fieldType() != IndexHint.FieldType.TIMESTAMP) return value;
        Long epoch = IndexValueExtractor.toEpochMilli(value);
        return epoch != null ? new java.util.Date(epoch) : null;
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private List<V> decodeAll(FindIterable<Document> docs) {
        List<V> result = new ArrayList<>();
        for (Document doc : docs) {
            try {
                byte[] data = doc.getString(FIELD_DATA).getBytes(StandardCharsets.UTF_8);
                result.add(descriptor.codec().decode(data));
            } catch (CodecException ignored) {}
        }
        return result;
    }
}
