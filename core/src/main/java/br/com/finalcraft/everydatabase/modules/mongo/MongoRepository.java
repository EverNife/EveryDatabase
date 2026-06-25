package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageExecutors;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

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
 *   "_id":          "key-as-string",             // the entity key IS the document _id
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

    /**
     * Field storing the serialised entity key - this <b>is</b> MongoDB's primary key ({@code _id}),
     * so the entity key is the document identity. Lookups use the automatic unique {@code _id} index,
     * and a change-stream <b>delete</b> event's {@code documentKey._id} carries the key directly, so
     * deletes propagate without pre-images.
     */
    static final String COL_KEY     = "_id";
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
    private final StorageLog log;

    /** Indexed field paths declared on the descriptor; key = fieldPath. */
    private final Map<String, IndexHint> hintsByPath;

    MongoRepository(EntityDescriptor<K, V> descriptor, MongoCollection<Document> collection,
                    ClientSession session, StorageLog log) {
        this.descriptor = descriptor;
        this.collection = collection;
        this.session    = session;
        this.log        = log;

        this.hintsByPath = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) {
            this.hintsByPath.put(hint.fieldPath(), hint);
        }
    }

    /**
     * Reconciles this collection's indexes with the descriptor's declared {@link IndexHint}s:
     * creates the unique key index, creates (non-unique) indexes for every declared hint,
     * drops any {@code _idx_*} index that is no longer declared, and auto-populates freshly
     * added {@code _idx_*} fields on existing documents.
     */
    void ensureIndexes() {
        // The entity key is the document _id (COL_KEY), so its unique index is automatic - there is
        // nothing to create for identity. Only the secondary _idx_* indexes are reconciled below.
        long reconcileStart = System.currentTimeMillis();

        // Snapshot existing _idx_ indexes before modifying
        Map<String, String> existing = existingIndexFields();

        Set<String> declaredFields = new HashSet<>();
        List<IndexHint> newHints = new ArrayList<>();
        List<String> createdFields = new ArrayList<>();

        for (IndexHint hint : hintsByPath.values()) {
            String field = hint.indexColumnName();
            declaredFields.add(field);
            Bson def = hint.order() == IndexHint.Order.DESCENDING
                ? Indexes.descending(field)
                : Indexes.ascending(field);
            collection.createIndex(def);

            if (!existing.containsKey(field)) {
                newHints.add(hint);
                createdFields.add(hint.fieldPath());
                log.indexCreated(descriptor.collection(), hint);
            }
        }

        // Enforcement: drop _idx_ indexes that are no longer declared.
        List<String> droppedColumns = new ArrayList<>();
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (!declaredFields.contains(entry.getKey())) {
                collection.dropIndex(entry.getValue());
                droppedColumns.add(entry.getKey());
                log.indexDropped(descriptor.collection(), entry.getKey());
            }
        }

        // Auto-populate _idx_ fields on existing documents for newly created indexes
        long backfilled = 0L;
        if (!newHints.isEmpty()) {
            backfilled = backfillIndexFields(newHints);
        }

        long elapsed = System.currentTimeMillis() - reconcileStart;
        log.reconcileSummary(descriptor.collection(), createdFields, droppedColumns, backfilled, elapsed);
    }

    /** Maps each existing {@code _idx_*} index's backing field to its Mongo index name. */
    private Map<String, String> existingIndexFields() {
        Map<String, String> result = new HashMap<>();
        for (Document index : collection.listIndexes()) {
            String name = index.getString("name");
            Document key = index.get("key", Document.class);
            if (name == null || key == null) continue;
            for (String field : key.keySet()) {
                if (field.startsWith("_idx_")) result.put(field, name);
            }
        }
        return result;
    }

    /**
     * Auto-populates freshly created {@code _idx_*} fields for existing documents.
     * Reads each document's stored entity, extracts the index value via the same
     * {@link IndexValueExtractor} used at {@code save()} time, and {@code $set}s it.
     * Documents whose data cannot be decoded are skipped.
     */
    private long backfillIndexFields(List<IndexHint> newHints) {
        // Count total documents for progress tracking only when progress would be visible
        // (mirrors the gating in SqlRepository.backfillIndexColumns - no extra round-trip
        // when DEBUG progress is disabled).
        long total = 0L;
        StorageLog.ProgressTracker tracker = null;
        if (log.isEnabled(StorageOp.INDEX_BACKFILL, StorageLogLevel.DEBUG)) {
            total = collection.countDocuments();
            tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, descriptor.collection());
        }
        long updated = 0L;

        for (Document doc : collection.find()) {
            Object key = doc.get(COL_KEY);
            if (key == null) continue;
            V entity;
            try {
                entity = decodeEntity(doc);
            } catch (CodecException e) {
                log.skippedCorruptedRow(descriptor.collection(), String.valueOf(key), e);
                continue;
            }
            JsonNode tree = IndexValueExtractor.toTree(entity);
            Document set = new Document();
            for (IndexHint hint : newHints) {
                Object value = IndexValueExtractor.extract(tree, hint);
                set.append(hint.indexColumnName(), toMongoValue(value, hint));
            }
            collection.updateOne(Filters.eq(COL_KEY, key), new Document("$set", set));
            updated++;
            if (tracker != null) tracker.tick(updated, total);
        }

        if (tracker != null) tracker.finish(updated);
        return updated;
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
                throw log.errored(StorageOp.FIND, descriptor.collection(),
                    new RuntimeException("Mongo codec error for key=" + key, e));
            }
        }, StorageExecutors.get());
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
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, descriptor.collection());
        if (reject != null) return reject;
        if (descriptor.isVersioned()) {
            return saveVersioned(entity);
        }

        return CompletableFuture.supplyAsync(() -> {
            replaceDocument(key, entity);
            log.saved(descriptor.collection(), key, entity);
            return null;
        }, StorageExecutors.get());
    }

    /**
     * Builds the full Mongo document for {@code entity}: the key, the {@code storage_data}
     * sub-document and the {@code _idx_*} sibling fields for every declared {@link IndexHint}.
     * Shared by the single {@code replaceOne} path and {@link #saveAll}'s {@code bulkWrite} path.
     */
    private Document buildDocument(K key, V entity) throws CodecException {
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
        return doc;
    }

    /**
     * Encodes {@code entity} and upserts it via {@code replaceOne} (non-versioned path).
     * {@link #saveAll} does not use this - it batches the same documents through
     * {@code bulkWrite} instead of paying one round-trip per entity.
     */
    private void replaceDocument(K key, V entity) {
        try {
            Document doc = buildDocument(key, entity);
            ReplaceOptions opts = new ReplaceOptions().upsert(true);
            if (session != null)
                collection.replaceOne(session, Filters.eq(COL_KEY, key.toString()), doc, opts);
            else
                collection.replaceOne(Filters.eq(COL_KEY, key.toString()), doc, opts);
        } catch (CodecException e) {
            throw log.errored(StorageOp.SAVE, descriptor.collection(),
                new RuntimeException("Mongo codec error saving key=" + key, e));
        }
    }

    /**
     * Versioned save using optimistic locking.
     *
     * <ol>
     *   <li>Attempt {@code updateOne({_id:key, lock_version:incomingVersion},
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
                        try {
                            if (session != null) collection.insertOne(session, insertDoc);
                            else                  collection.insertOne(insertDoc);
                        } catch (MongoWriteException mwe) {
                            if (ErrorCategory.fromErrorCode(mwe.getError().getCode()) != ErrorCategory.DUPLICATE_KEY) {
                                throw mwe;
                            }
                            // Lost the first-insert race: another writer created this key between
                            // our countDocuments check and the insert. Surface it as the same
                            // OptimisticLockException a version mismatch produces.
                            Document raced = session != null
                                ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                                : collection.find(Filters.eq(COL_KEY, key.toString())).first();
                            long actualVersion = raced != null && raced.get(COL_VERSION) instanceof Number
                                ? ((Number) raced.get(COL_VERSION)).longValue()
                                : 0L;
                            log.optimisticLockConflict(descriptor.collection(), key, incomingVersion, actualVersion);
                            throw new OptimisticLockException(
                                descriptor.type(), key, incomingVersion, actualVersion);
                        }
                        // entity version is already 0 (set above)
                    } else {
                        // Document exists but version did not match - conflict.
                        Document found = session != null
                            ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                            : collection.find(Filters.eq(COL_KEY, key.toString())).first();
                        long actualVersion = found != null
                            ? ((Number) found.get(COL_VERSION)).longValue()
                            : -1L;
                        log.optimisticLockConflict(descriptor.collection(), key, incomingVersion, actualVersion);
                        throw new OptimisticLockException(
                            descriptor.type(), key, incomingVersion, actualVersion);
                    }
                }
                // If matchedCount > 0 the entity already has newVersion set (we set it before the update).
                log.saved(descriptor.collection(), key, entity);
                return null;
            } catch (OptimisticLockException ole) {
                throw ole;
            } catch (CodecException e) {
                throw log.errored(StorageOp.SAVE, descriptor.collection(),
                    new RuntimeException("Mongo codec error saving key=" + key, e));
            }
        }, StorageExecutors.get());
    }

    /**
     * Max models per {@code bulkWrite} call. Chunking keeps each wire message comfortably
     * under MongoDB's 16MB/message limit even for large entities.
     */
    private static final int BULK_WRITE_CHUNK = 1000;

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        if (entities.isEmpty()) return CompletableFuture.completedFuture(null);

        for (V entity : entities) {
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(descriptor.keyExtractor().apply(entity), descriptor.collection());
            if (reject != null) return reject;
        }

        long startMs = System.currentTimeMillis();
        long count = entities.size();

        if (descriptor.isVersioned()) {
            // Optimistic-lock check-then-act per entity is inherent to versioning - reuse save().
            List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
            for (V entity : entities) futures.add(save(entity));
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> log.savedBatch(descriptor.collection(), count, System.currentTimeMillis() - startMs));
        }

        // Non-versioned path: upsert everything through chunked unordered bulkWrite calls
        // (one round-trip per chunk instead of one replaceOne round-trip per entity).
        return CompletableFuture.supplyAsync(() -> {
            ReplaceOptions upsert = new ReplaceOptions().upsert(true);
            BulkWriteOptions unordered = new BulkWriteOptions().ordered(false);
            List<ReplaceOneModel<Document>> models = new ArrayList<>(Math.min((int) count, BULK_WRITE_CHUNK));

            for (V entity : entities) {
                K key = descriptor.keyExtractor().apply(entity);
                try {
                    models.add(new ReplaceOneModel<>(
                        Filters.eq(COL_KEY, key.toString()), buildDocument(key, entity), upsert));
                } catch (CodecException e) {
                    throw log.errored(StorageOp.SAVE_BATCH, descriptor.collection(),
                        new RuntimeException("Mongo codec error saving key=" + key, e));
                }
                if (models.size() >= BULK_WRITE_CHUNK) {
                    bulkWriteChunk(models, unordered);
                    models.clear();
                }
            }
            if (!models.isEmpty()) bulkWriteChunk(models, unordered);

            log.savedBatch(descriptor.collection(), count, System.currentTimeMillis() - startMs);
            return null;
        }, StorageExecutors.get());
    }

    private void bulkWriteChunk(List<ReplaceOneModel<Document>> models, BulkWriteOptions opts) {
        if (session != null) collection.bulkWrite(session, models, opts);
        else                 collection.bulkWrite(models, opts);
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        return CompletableFuture.supplyAsync(() -> {
            long count = session != null
                ? collection.deleteOne(session, Filters.eq(COL_KEY, key.toString())).getDeletedCount()
                : collection.deleteOne(Filters.eq(COL_KEY, key.toString())).getDeletedCount();
            boolean existed = count > 0;
            log.deleted(descriptor.collection(), key, existed);
            return existed;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = session != null
                ? collection.find(session, Filters.eq(COL_KEY, key.toString())).first()
                : collection.find(Filters.eq(COL_KEY, key.toString())).first();
            return found != null;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.supplyAsync(
            () -> session != null ? collection.countDocuments(session) : collection.countDocuments(),
            StorageExecutors.get()
        );
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyMap());
        return CompletableFuture.supplyAsync(() -> {
            List<String> keyStrings = new ArrayList<>(keys.size());
            Map<String, K> byString = new HashMap<>();
            for (K k : keys) {
                String s = k.toString();
                keyStrings.add(s);
                byString.put(s, k);
            }
            FindIterable<Document> found = (session != null
                    ? collection.find(session, Filters.in(COL_KEY, keyStrings))
                    : collection.find(Filters.in(COL_KEY, keyStrings)))
                    .projection(new Document(COL_VERSION, 1));   // _id (the key) is always returned
            Map<K, Long> result = new HashMap<>();
            for (Document doc : found) {
                K key = byString.get(String.valueOf(doc.get(COL_KEY)));
                if (key == null) continue;
                Object v = doc.get(COL_VERSION);
                result.put(key, v instanceof Number ? ((Number) v).longValue() : 0L);
            }
            return result;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.supplyAsync(() -> {
            FindIterable<Document> all = session != null ? collection.find(session) : collection.find();
            return decodeAll(all).stream();
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Index queries
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        return query(Query.eq(fieldPath, value));
    }

    @Override
    public CompletableFuture<List<V>> query(Query query, QueryOptions options) {
        if (query == null) {
            throw new IllegalArgumentException("query cannot be null");
        }
        if (options == null) {
            options = QueryOptions.none();
        }
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
        final QueryOptions finalOptions = options;
        validateQueryOptions(finalOptions);
        Bson combined = filters.isEmpty() ? new Document() : (filters.size() == 1 ? filters.get(0) : Filters.and(filters));
        long startMs = System.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            FindIterable<Document> found = session != null
                ? collection.find(session, combined)
                : collection.find(combined);
            boolean paginating = finalOptions.hasOffset() || finalOptions.hasLimit();
            if (finalOptions.hasOrder()) {
                IndexHint hint = hintsByPath.get(finalOptions.orderBy());
                Bson valueSort = finalOptions.order() == IndexHint.Order.DESCENDING
                    ? Sorts.descending(hint.indexColumnName())
                    : Sorts.ascending(hint.indexColumnName());
                // Mongo sorts null/missing as the smallest value (first ascending, last descending),
                // matching the other backends; _id (the entity key) breaks ties so paging is stable.
                found = found.sort(Sorts.orderBy(valueSort, Sorts.ascending(COL_KEY)));
            } else if (paginating) {
                // Stable pagination needs a deterministic order even without an explicit sort field.
                found = found.sort(Sorts.ascending(COL_KEY));
            }
            if (finalOptions.hasOffset()) {
                found = found.skip(finalOptions.offset());
            }
            if (finalOptions.hasLimit()) {
                found = found.limit(finalOptions.limit());
            }
            List<V> result = decodeAll(found);
            log.queried(descriptor.collection(), query, result.size(), System.currentTimeMillis() - startMs);
            return result;
        }, StorageExecutors.get());
    }

    private void validateQueryOptions(QueryOptions options) {
        if (!options.hasOrder()) {
            return;
        }
        IndexHint hint = hintsByPath.get(options.orderBy());
        if (hint == null) {
            throw new IllegalArgumentException(
                "Mongo: order field '" + options.orderBy() + "' is not indexed. "
                + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
        }
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

    /**
     * Decodes all documents in the iterable. Documents that cannot be decoded emit a WARN
     * log entry and are skipped (consistent with the "skip corrupted" contract).
     */
    private List<V> decodeAll(FindIterable<Document> docs) {
        List<V> result = new ArrayList<>();
        for (Document doc : docs) {
            Object key = doc.get(COL_KEY);
            try {
                result.add(decodeEntity(doc));
            } catch (CodecException e) {
                log.skippedCorruptedRow(descriptor.collection(), String.valueOf(key), e);
            }
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
