package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageExecutors;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Stream;

/**
 * Key-major {@link Repository}: one file per key under the base directory, each file an aggregate
 * document mapping {@code collection -> entity}. A repository owns one collection name; it reads and
 * writes only its own sub-node of each key file, sharing the file (and its lock) with the repositories
 * of the other collections via the storage-wide {@link KeyFileStore}.
 *
 * <p>Writes are read-modify-write of the whole key file, guarded by a global per-key write lock so two
 * collections of the same key never lose each other's update; the atomic {@code .tmp}+move keeps the
 * file from ever being truncated.
 *
 * <p>Entities are (de)serialized through the descriptor's {@code Codec}: the codec's bytes are parsed
 * into a sub-node with the storage's format-matched mapper, embedded in the aggregate document, and
 * re-emitted on read. The codec also decides the container format (JSON vs YAML) for the whole storage
 * (see {@link KeyFileStore#resolveFormat}).
 *
 * @param <K> the key type (its {@code toString()} names the file)
 * @param <V> the entity type
 */
final class GroupedFileRepository<K, V> implements Repository<K, V> {

    private final EntityDescriptor<K, V> descriptor;
    private final KeyFileStore           store;
    private final StorageLog             log;
    private final String                 collection;
    /** Declared index hints indexed by field path - used for query dispatch. */
    private final Map<String, IndexHint> hintsByPath;

    GroupedFileRepository(EntityDescriptor<K, V> descriptor, KeyFileStore store, StorageLog log) {
        this.descriptor  = descriptor;
        this.store       = store;
        this.log         = log;
        this.collection  = descriptor.collection();
        this.hintsByPath = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) this.hintsByPath.put(hint.fieldPath(), hint);
    }

    // ------------------------------------------------------------------
    //  Path / lock helpers
    // ------------------------------------------------------------------

    private Path fileFor(K key) {
        return store.keyFile(KeyFileStore.sanitize(key));
    }

    private ReadWriteLock lockFor(K key) {
        return store.lockFor(KeyFileStore.sanitize(key));
    }

    /** Reads the aggregate root for a key file, or {@code null} if the file is absent / not an object. */
    private ObjectNode readRoot(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        JsonNode node = store.mapper().readTree(Files.readAllBytes(file));
        return (node != null && node.isObject()) ? (ObjectNode) node : null;
    }

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.readLock().lock();
            try {
                ObjectNode root = readRoot(fileFor(key));
                if (root == null || !root.has(collection)) return Optional.empty();
                byte[] bytes = store.mapper().writeValueAsBytes(root.get(collection));
                return Optional.of(descriptor.codec().decode(bytes));
            } catch (IOException e) {
                throw log.errored(StorageOp.FIND, collection,
                    new RuntimeException("GroupedFile: failed to read key=" + key, e));
            } catch (CodecException e) {
                throw log.errored(StorageOp.FIND, collection,
                    new RuntimeException("GroupedFile: codec error reading key=" + key, e));
            } finally {
                lock.readLock().unlock();
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<List<V>> findMany(Collection<K> keys) {
        List<CompletableFuture<Optional<V>>> futures = new ArrayList<>(keys.size());
        for (K key : keys) futures.add(find(key));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(__ -> {
                List<V> result = new ArrayList<>(keys.size());
                for (CompletableFuture<Optional<V>> f : futures) f.join().ifPresent(result::add);
                return result;
            });
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.readLock().lock();
            try {
                ObjectNode root = readRoot(fileFor(key));
                return root != null && root.has(collection);
            } catch (IOException e) {
                throw log.errored(StorageOp.EXISTS, collection,
                    new RuntimeException("GroupedFile: failed to check key=" + key, e));
            } finally {
                lock.readLock().unlock();
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long n = 0;
                for (Path file : store.keyFiles()) {
                    try {
                        // count() never materialises entities - parse the envelope and test presence.
                        ObjectNode root = readRoot(file);
                        if (root != null && root.has(collection)) n++;
                    } catch (IOException e) {
                        log.skippedCorruptedRow(collection, file.getFileName().toString(), e);
                    }
                }
                return n;
            } catch (IOException e) {
                throw log.errored(StorageOp.COUNT, collection,
                    new RuntimeException("GroupedFile: failed to count entities", e));
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<V> results = new ArrayList<>();
                for (Path file : store.keyFiles()) {
                    try {
                        ObjectNode root = readRoot(file);
                        if (root != null && root.has(collection)) {
                            results.add(descriptor.codec().decode(store.mapper().writeValueAsBytes(root.get(collection))));
                        }
                    } catch (Exception e) {
                        // A corrupt key file drops the whole key from the scan; log a WARN, don't fail.
                        log.skippedCorruptedRow(collection, file.getFileName().toString(), e);
                    }
                }
                return results.stream();
            } catch (IOException e) {
                throw log.errored(StorageOp.SCAN_ALL, collection,
                    new RuntimeException("GroupedFile: failed to stream all entities", e));
            }
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Writes
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, collection);
        if (reject != null) return reject;
        return CompletableFuture.supplyAsync(() -> {
            writeEntity(key, entity);
            log.saved(collection, key, entity);
            return null;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        for (V entity : entities) {
            CompletableFuture<Void> reject =
                StorageKeys.rejectIfTooLong(descriptor.keyExtractor().apply(entity), collection);
            if (reject != null) return reject;
        }
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        // Each entity is a distinct key here (one collection), so distinct files and distinct locks -
        // safe to write in parallel. Same-key clashes (rare in one batch) serialise on the global lock.
        List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
        for (V entity : entities) {
            K key = descriptor.keyExtractor().apply(entity);
            futures.add(CompletableFuture.runAsync(() -> writeEntity(key, entity), StorageExecutors.get()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.savedBatch(collection, count, System.currentTimeMillis() - startMs));
    }

    /**
     * Read-modify-write of the key file under the global per-key write lock: load the aggregate root
     * (or a fresh one), set this collection's sub-node to the encoded entity, and atomically rewrite the
     * whole document. Shared by {@link #save} (one SAVE event) and {@link #saveAll} (one SAVE_BATCH).
     */
    private void writeEntity(K key, V entity) {
        ReadWriteLock lock = lockFor(key);
        lock.writeLock().lock();
        try {
            Path file = fileFor(key);
            ObjectNode root = readRoot(file);
            if (root == null) root = store.mapper().createObjectNode();
            // Parse the codec's bytes into a sub-node so the entity keeps its codec representation,
            // then re-emit the whole aggregate document in the storage's format.
            root.set(collection, store.mapper().readTree(descriptor.codec().encode(entity)));
            store.writeAtomic(file, store.mapper().writeValueAsBytes(root));
        } catch (IOException e) {
            throw log.errored(StorageOp.SAVE, collection,
                new RuntimeException("GroupedFile: failed to write key=" + key, e));
        } catch (CodecException e) {
            throw log.errored(StorageOp.SAVE, collection,
                new RuntimeException("GroupedFile: codec error writing key=" + key, e));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.writeLock().lock();
            try {
                Path file = fileFor(key);
                ObjectNode root = readRoot(file);
                if (root == null || !root.has(collection)) {
                    log.deleted(collection, key, false);
                    return false;
                }
                root.remove(collection);
                if (root.size() == 0) {
                    // Last collection for this key - drop the now-empty file rather than leave a "{}".
                    store.delete(file);
                } else {
                    store.writeAtomic(file, store.mapper().writeValueAsBytes(root));
                }
                log.deleted(collection, key, true);
                return true;
            } catch (IOException e) {
                throw log.errored(StorageOp.DELETE, collection,
                    new RuntimeException("GroupedFile: failed to delete key=" + key, e));
            } finally {
                lock.writeLock().unlock();
            }
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Index queries
    //
    //  Like LocalFile, GroupedFile has no real index: each query walks every key file, extracts this
    //  collection's sub-node and filters in memory via the shared Jackson-tree extractor. Correct but
    //  O(total keys) per call - the scan reads files of unrelated collections too.
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
        final QueryOptions finalOptions = options;
        // Reject undeclared fields so a query that works here keeps working when swapped for SQL/Mongo.
        for (Query.Condition c : query.conditions()) {
            if (!hintsByPath.containsKey(c.fieldPath())) {
                throw new IllegalArgumentException(
                    "GroupedFile: field '" + c.fieldPath() + "' is not declared as an IndexHint. "
                    + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
            }
        }
        validateQueryOptions(finalOptions);

        long startMs = System.currentTimeMillis();
        return all().thenApply(stream -> {
            List<V> filtered = new ArrayList<>();
            stream.forEach(entity -> {
                JsonNode tree = IndexValueExtractor.toTree(entity);
                if (matchesAll(tree, query)) filtered.add(entity);
            });
            List<V> result = applyQueryOptions(filtered, finalOptions);
            log.queried(collection, query, result.size(), System.currentTimeMillis() - startMs);
            return result;
        });
    }

    private void validateQueryOptions(QueryOptions options) {
        if (!options.hasOrder()) {
            return;
        }
        if (!hintsByPath.containsKey(options.orderBy())) {
            throw new IllegalArgumentException(
                "GroupedFile: order field '" + options.orderBy() + "' is not declared as an IndexHint. "
                + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
        }
    }

    private List<V> applyQueryOptions(List<V> result, QueryOptions options) {
        if (options.hasOrder()) {
            final IndexHint hint = hintsByPath.get(options.orderBy());
            final int direction = options.order() == IndexHint.Order.DESCENDING ? -1 : 1;
            result.sort((left, right) -> direction * compareIndexedValue(left, right, hint));
        }
        if (!options.hasOffset() && !options.hasLimit()) {
            return result;
        }
        int from = Math.min(options.offset(), result.size());
        int to = options.hasLimit() ? Math.min(from + options.limit(), result.size()) : result.size();
        return new ArrayList<>(result.subList(from, to));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareIndexedValue(V left, V right, IndexHint hint) {
        Object leftValue = IndexValueExtractor.extract(IndexValueExtractor.toTree(left), hint);
        Object rightValue = IndexValueExtractor.extract(IndexValueExtractor.toTree(right), hint);
        if (leftValue == rightValue) return 0;
        if (leftValue == null) return 1;
        if (rightValue == null) return -1;
        if (leftValue instanceof Comparable && rightValue instanceof Comparable) {
            return ((Comparable) leftValue).compareTo(rightValue);
        }
        return String.valueOf(leftValue).compareTo(String.valueOf(rightValue));
    }

    private boolean matchesAll(JsonNode tree, Query query) {
        for (Query.Condition c : query.conditions()) {
            IndexHint hint = hintsByPath.get(c.fieldPath());
            Object actual = IndexValueExtractor.extract(tree, hint);
            if (!matchesCondition(actual, c, hint)) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matchesCondition(Object actual, Query.Condition c, IndexHint hint) {
        switch (c.op()) {
            case EQ: {
                Object expected = IndexValueExtractor.normalizeQueryValue(c.value(), hint);
                return Objects.equals(actual, expected);
            }
            case IN:
                for (Object v : c.inValues()) {
                    Object normalized = IndexValueExtractor.normalizeQueryValue(v, hint);
                    if (Objects.equals(actual, normalized)) return true;
                }
                return false;
            case RANGE: {
                Object from = IndexValueExtractor.normalizeQueryValue(c.rangeFrom(), hint);
                Object to   = IndexValueExtractor.normalizeQueryValue(c.rangeTo(),   hint);
                if (!(actual instanceof Comparable)) return false;
                Comparable cmp = (Comparable) actual;
                if (from != null && cmp.compareTo(from) < 0) return false;
                if (to   != null && cmp.compareTo(to)   > 0) return false;
                return true;
            }
            default:
                return false;
        }
    }
}
