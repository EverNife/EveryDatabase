package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.versioned.OptimisticLockException;
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
import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
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
 *   "storage_key":  "key-as-string",
 *   "storage_data": { "field": "value", ... },   // native BSON sub-document, not a string
 *   "_idx_type":    "ENABLED",                   // present for each declared IndexHint
 *   "_idx_location_world": "world_nether",
 *   "lock_version": 0                            // only present for versioned descriptors
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
    static final String COL_KEY     = "storage_key";
    /** Field storing the entity as a native BSON sub-document. */
    static final String COL_DATA    = "storage_data";
    /** Field storing the optimistic-lock version (only present in versioned collections). */
    static final String COL_VERSION = "lock_version";

    /**
     * Relaxed extended-JSON writer settings used when converting a stored BSON sub-document
     * back to a JSON string for the codec. Relaxed mode outputs plain JSON numbers and dates
     * instead of MongoDB extended-JSON wrappers ({@code $numberLong}, {@code $date}, etc.),
     * which is what Jackson expects.
     */
    private static final JsonWriterSettings RELAXED_JSON = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .build();

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
        collection.createIndex(Indexes.ascending(COL_KEY), new IndexOptions().unique(true));

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
                ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                : collection.find(Filters.eq(COL_KEY, key.toString())).first();

            if (found == null) return Optional.empty();
            try {
                return Optional.of(decodeEntity(found));
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
                ? collection.find(session, Filters.in(COL_KEY, keyStrings))
                : collection.find(Filters.in(COL_KEY, keyStrings));

            return decodeAll(found);
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        if (descriptor.isVersioned()) {
            return saveVersioned(entity);
        }

        K key = descriptor.keyExtractor().apply(entity);
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = descriptor.codec().encode(entity);
                Document doc = new Document()
                    .append(COL_KEY,  key.toString())
                    .append(COL_DATA, toDataDoc(data));

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
                    collection.replaceOne(session, Filters.eq(COL_KEY, key.toString()), doc, opts);
                else
                    collection.replaceOne(Filters.eq(COL_KEY, key.toString()), doc, opts);
                return null;
            } catch (CodecException e) {
                throw new RuntimeException("Mongo codec error saving key=" + key, e);
            }
        }, StorageExecutors.async());
    }

    /**
     * Versioned save using optimistic locking.
     *
     * <ol>
     *   <li>Attempt {@code updateOne({storage_key:key, lock_version:incomingVersion},
     *       {$set:{storage_data:...}, $inc:{lock_version:1}}, upsert=false)}.</li>
     *   <li>If {@code matchedCount == 0}: check whether the document exists.
     *       <ul>
     *         <li>If absent AND incoming version == 0: insert with {@code lock_version=0}.</li>
     *         <li>Otherwise: throw {@link OptimisticLockException}.</li>
     *       </ul>
     *   </li>
     *   <li>On success: update in-memory entity version via the descriptor's version setter.</li>
     * </ol>
     *
     * <p>The version is applied to the entity BEFORE encoding so that the {@code storage_data}
     * sub-document always carries the correct version - a subsequent {@code find()} does not
     * need to read the separate {@code lock_version} field.
     */
    private CompletableFuture<Void> saveVersioned(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        long incomingVersion = descriptor.versionGetter().apply(entity);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Determine the new version upfront so we can embed it in the JSON blob.
                long newVersion = incomingVersion + 1;

                // Build the $set document for _data and all index fields.
                // For INSERT path we'll re-encode with version 0.
                // For UPDATE path we encode with incomingVersion+1 before the update attempt.
                descriptor.versionSetter().accept(entity, newVersion);
                byte[] data = descriptor.codec().encode(entity);

                Document setDoc = new Document(COL_DATA, toDataDoc(data));
                if (!hintsByPath.isEmpty()) {
                    JsonNode tree = IndexValueExtractor.toTree(entity);
                    for (IndexHint hint : hintsByPath.values()) {
                        Object value = IndexValueExtractor.extract(tree, hint);
                        setDoc.append(hint.indexColumnName(), toMongoValue(value, hint));
                    }
                }

                Document update = new Document("$set", setDoc)
                    .append("$inc", new Document(COL_VERSION, 1L));

                // Filter: match on both key AND expected (incoming) version.
                Bson filter = Filters.and(
                    Filters.eq(COL_KEY, key.toString()),
                    Filters.eq(COL_VERSION, incomingVersion)
                );

                UpdateResult result = session != null
                    ? collection.updateOne(session, filter, update)
                    : collection.updateOne(filter, update);

                if (result.getMatchedCount() == 0) {
                    // Undo the setter - we haven't actually persisted anything yet.
                    descriptor.versionSetter().accept(entity, incomingVersion);

                    // Check whether the document exists at all.
                    long existCount = session != null
                        ? collection.countDocuments(session, Filters.eq(COL_KEY, key.toString()))
                        : collection.countDocuments(Filters.eq(COL_KEY, key.toString()));

                    if (existCount == 0 && incomingVersion == 0) {
                        // First insert: store with lock_version=0 (entity version already at 0
                        // because we undid the setter above; re-encode with v=0).
                        descriptor.versionSetter().accept(entity, 0L);
                        byte[] insertData = descriptor.codec().encode(entity);

                        Document insertDoc = new Document()
                            .append(COL_KEY, key.toString())
                            .append(COL_DATA, toDataDoc(insertData))
                            .append(COL_VERSION, 0L);
                        if (!hintsByPath.isEmpty()) {
                            JsonNode tree = IndexValueExtractor.toTree(entity);
                            for (IndexHint hint : hintsByPath.values()) {
                                Object value = IndexValueExtractor.extract(tree, hint);
                                insertDoc.append(hint.indexColumnName(), toMongoValue(value, hint));
                            }
                        }
                        if (session != null) collection.insertOne(session, insertDoc);
                        else                  collection.insertOne(insertDoc);
                        // entity version is already 0 (set above)
                    } else {
                        // Document exists but version did not match - conflict.
                        Document found = session != null
                            ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                            : collection.find(Filters.eq(COL_KEY, key.toString())).first();
                        long actualVersion = found != null
                            ? ((Number) found.get(COL_VERSION)).longValue()
                            : -1L;
                        throw new OptimisticLockException(
                            descriptor.type(), key, incomingVersion, actualVersion);
                    }
                }
                // If matchedCount > 0 the entity already has newVersion set (we set it before the update).
                return null;
            } catch (OptimisticLockException ole) {
                throw ole;
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
                ? collection.deleteOne(session, Filters.eq(COL_KEY, key.toString())).getDeletedCount()
                : collection.deleteOne(Filters.eq(COL_KEY, key.toString())).getDeletedCount();
            return count > 0;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = session != null
                ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                : collection.find(Filters.eq(COL_KEY, key.toString())).first();
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
                result.add(decodeEntity(doc));
            } catch (CodecException ignored) {}
        }
        return result;
    }

    /**
     * Encodes the entity bytes produced by the codec into a native BSON {@link Document}
     * for storage in the {@link #COL_DATA} sub-document field.
     *
     * <p>The codec always produces UTF-8 JSON; {@link Document#parse} turns that JSON into
     * a BSON document so Mongo stores it as a proper object (not an escaped string).
     */
    private Document toDataDoc(byte[] encodedEntity) {
        return Document.parse(new String(encodedEntity, StandardCharsets.UTF_8));
    }

    /**
     * Decodes an entity from the {@link #COL_DATA} sub-document field of a stored Mongo document.
     *
     * <p>The sub-document is serialised back to JSON using {@link #RELAXED_JSON} (plain numbers
     * and dates, no extended-JSON wrappers) so the codec (Jackson) can parse it correctly.
     */
    private V decodeEntity(Document outer) throws CodecException {
        Document dataDoc = outer.get(COL_DATA, Document.class);
        String json = dataDoc.toJson(RELAXED_JSON);
        return descriptor.codec().decode(json.getBytes(StandardCharsets.UTF_8));
    }
}
