package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageExecutors;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.codec.TreeCodec;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.query.QueryResultOrdering;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Function;
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
 * <p><b>Scan consistency.</b> The scans ({@code count}, {@code all}, {@code query}) read key files
 * without taking the per-key write lock. On the atomic {@code ATOMIC_MOVE} write path each individual
 * file read is safe (a reader sees either the old or the new file, never a partial one). On the
 * {@code REPLACE_EXISTING} fallback (filesystems that cannot move atomically) a scan racing a
 * concurrent write of the same key may read a truncated file; such a file is skipped-and-logged, so
 * the effect is a transient undercount, never a crash. Even on the atomic path, though, a scan lists
 * the directory and then reads each key file separately, all without the per-key lock: a key
 * created or deleted between the listing and the read is simply missed or omitted. A scan is
 * therefore only point-in-time consistent (a key may be transiently missing), never a
 * guaranteed-consistent snapshot of the whole key-set. Take a maintenance window (or otherwise
 * quiesce writes) for scans that must be exact under concurrent create/delete.
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
    /**
     * The codec's tree fast-path, or {@code null} when it only speaks bytes. Resolved once here
     * rather than probed per row: every read and write of this repository crosses the tree boundary.
     */
    private final TreeCodec<V>           treeCodec;

    @SuppressWarnings("unchecked")
    GroupedFileRepository(EntityDescriptor<K, V> descriptor, KeyFileStore store, StorageLog log) {
        this.descriptor  = descriptor;
        this.store       = store;
        this.log         = log;
        this.collection  = descriptor.collection();
        this.hintsByPath = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) this.hintsByPath.put(hint.fieldPath(), hint);
        this.treeCodec   = descriptor.codec() instanceof TreeCodec
            ? (TreeCodec<V>) descriptor.codec()
            : null;
    }

    // ------------------------------------------------------------------
    //  Codec boundary
    //
    //  An entity lives in the aggregate document as a sub-node, so both directions cross a tree.
    //  A codec that speaks trees crosses it directly; one that only speaks bytes needs the document
    //  serialised and re-parsed around it, which is what these two hide.
    // ------------------------------------------------------------------

    private V decodeSub(JsonNode sub) throws IOException {
        return treeCodec != null
            ? treeCodec.decodeTree(sub)
            : descriptor.codec().decode(store.mapper().writeValueAsBytes(sub));
    }

    private JsonNode encodeSub(V entity) throws IOException {
        return treeCodec != null
            ? treeCodec.encodeTree(entity)
            : store.mapper().readTree(descriptor.codec().encode(entity));
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

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.readLock().lock();
            try {
                // Addressing one key: the aggregate document is memoized, because the collections
                // that share this key are the next thing anyone asks for.
                ObjectNode root = store.cachedRoot(fileFor(key));
                JsonNode sub = root == null ? null : root.get(collection);
                if (sub == null) return Optional.empty();
                return Optional.of(decodeSub(sub));
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
                return store.hasSubNode(fileFor(key), collection);
            } catch (IOException e) {
                throw log.errored(StorageOp.EXISTS, collection,
                    new RuntimeException("GroupedFile: failed to check key=" + key, e));
            } finally {
                lock.readLock().unlock();
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        if (keys.isEmpty()){
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        return CompletableFuture.supplyAsync(() -> {
            Map<K, Long> result = new HashMap<>();
            for (K key : keys) {
                ReadWriteLock lock = lockFor(key);
                lock.readLock().lock();
                try {
                    // GroupedFile does not enforce optimistic locking, so existing keys always
                    // report version 0 - matching H2 and keeping the polling substrate uniform
                    // across non-enforcing backends. A presence probe is all this costs.
                    if (store.hasSubNode(fileFor(key), collection)) result.put(key, 0L);
                } catch (IOException e) {
                    // skip unreadable/corrupt entries
                } finally {
                    lock.readLock().unlock();
                }
            }
            return result;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long n = 0;
                for (Path file : store.keyFiles()) {
                    try {
                        // Presence, not decodability: a sub-node this collection owns is a row even if
                        // its payload is poisoned, and all() is what skips it. A key file too broken to
                        // parse cannot be attributed to any collection, so it is skipped-and-logged
                        // instead - counting it here would inflate every collection in the directory.
                        if (store.hasSubNode(file, collection)) n++;
                    } catch (Exception e) {
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
                        JsonNode sub = store.readSubNode(file, collection);
                        if (sub != null) results.add(decodeSub(sub));
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
        K key;
        try {
            key = descriptor.keyExtractor().apply(entity);
        } catch (RuntimeException e) {
            return StorageKeys.failedFuture(e);
        }
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
            K key;
            try {
                key = descriptor.keyExtractor().apply(entity);
            } catch (RuntimeException e) {
                return StorageKeys.failedFuture(e);
            }
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, collection);
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

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities, WriteMode mode) {
        if (mode == null || mode == WriteMode.UPSERT) {
            return saveAll(entities);
        }
        for (V entity : entities) {
            K key;
            try {
                key = descriptor.keyExtractor().apply(entity);
            } catch (RuntimeException e) {
                return StorageKeys.failedFuture(e);
            }
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, collection);
            if (reject != null) return reject;
        }
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
        for (V entity : entities) {
            K key = descriptor.keyExtractor().apply(entity);
            futures.add(CompletableFuture.runAsync(() -> updateEntityOnly(key, entity), StorageExecutors.get()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.savedBatch(collection, count, System.currentTimeMillis() - startMs));
    }

    /**
     * {@code UPDATE_ONLY} read-modify-write: rewrite this collection's sub-node only if it already exists
     * in the key file. When the sub-node is absent (a concurrent delete, or a key that only holds other
     * collections) it is a no-op, so the maintenance pass never resurrects a deleted entity.
     */
    private void updateEntityOnly(K key, V entity) {
        ReadWriteLock lock = lockFor(key);
        lock.writeLock().lock();
        try {
            Path file = fileFor(key);
            ObjectNode root = store.mutableRoot(file);
            if (root == null || !root.has(collection)) return;   // absent - never inserts
            root.set(collection, encodeSub(entity));
            store.writeAtomic(file, root);
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
            ObjectNode root = store.mutableRoot(file);
            if (root == null) root = store.mapper().createObjectNode();
            // The sub-node keeps the codec's own representation of the entity; the document around
            // it is re-emitted in the storage's format.
            root.set(collection, encodeSub(entity));
            store.writeAtomic(file, root);
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
                ObjectNode root = store.mutableRoot(file);
                if (root == null || !root.has(collection)) {
                    log.deleted(collection, key, false);
                    return false;
                }
                root.remove(collection);
                if (root.size() == 0) {
                    // Last collection for this key - drop the now-empty file rather than leave a "{}".
                    store.delete(file);
                } else {
                    store.writeAtomic(file, root);
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

    /**
     * Key-ordered scan. Like {@link LocalFileRepository}, GroupedFile is single-instance and holds modest
     * collections, so this returns the whole collection in one page (ordered by key file name); {@code limit}
     * is advisory. A key file that is unreadable, or holds an undecodable sub-node for this collection, is
     * surfaced as a failed {@link ScanRow} (never silently dropped). A non-start cursor returns an empty
     * final page.
     */
    @Override
    public CompletableFuture<Slice<ScanRow<V>>> scanAll(Cursor cursor, int limit) {
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        if (!cursor.isStart()) {
            return CompletableFuture.completedFuture(Slice.ofCursor(new ArrayList<>(), QueryOptions.none(), false, null));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Path> files = new ArrayList<>();
                for (Path file : store.keyFiles()) files.add(file);
                files.sort(Comparator.comparing(p -> p.getFileName().toString()));
                List<ScanRow<V>> rows = new ArrayList<>();
                for (Path file : files) {
                    String fileName = file.getFileName().toString();
                    try {
                        JsonNode sub = store.readSubNode(file, collection);
                        if (sub == null) continue;   // key holds only other collections
                        V value = decodeSub(sub);
                        // Carry the real storage key (from the decoded entity), not the key file name, so
                        // ScanRow.key() matches the other backends for sanitized/hashed keys.
                        rows.add(ScanRow.ok(descriptor.keyExtractor().apply(value).toString(), value));
                    } catch (Exception e) {
                        log.skippedCorruptedRow(collection, fileName, e);
                        rows.add(ScanRow.failed(fileName, e));   // undecodable: best-effort identifier is the file name
                    }
                }
                return Slice.ofCursor(rows, QueryOptions.none(), false, null);
            } catch (IOException e) {
                throw log.errored(StorageOp.SCAN_ALL, collection,
                    new RuntimeException("GroupedFile: failed to scan all entities", e));
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

    /**
     * Scans the key files and returns the entities matching {@code query}.
     *
     * <p>The stored sub-node <em>is</em> the codec's own output ({@link #writeEntity} embeds
     * {@code codec.encode(entity)} verbatim), so a condition can be tested against the tree read from
     * disk and only the matches ever reach the codec. A sub-node that is present but undecodable is
     * therefore reported (skipped-and-logged) only when it matches the query - one that does not match
     * is filtered out before its decode would have failed.
     */
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
        QueryResultOrdering.validateOrderField(finalOptions, hintsByPath, "GroupedFile");

        long startMs = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<V> filtered = new ArrayList<>();
                for (Path file : store.keyFiles()) {
                    try {
                        JsonNode sub = store.readSubNode(file, collection);
                        if (sub == null || !IndexValueExtractor.matchesAll(sub, query, hintsByPath)) continue;
                        filtered.add(decodeSub(sub));
                    } catch (Exception e) {
                        log.skippedCorruptedRow(collection, file.getFileName().toString(), e);
                    }
                }
                List<V> result = QueryResultOrdering.apply(filtered, finalOptions, hintsByPath, descriptor.keyExtractor(), descriptor.codec());
                log.queried(collection, query, result.size(), System.currentTimeMillis() - startMs);
                return result;
            } catch (IOException e) {
                throw log.errored(StorageOp.QUERY, collection,
                    new RuntimeException("GroupedFile: failed to query entities", e));
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Slice<V>> queryAfter(Query query, Cursor cursor, int limit) {
        if (query == null)  throw new IllegalArgumentException("query cannot be null");
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        IndexHint hint = hintsByPath.get(cursor.orderBy());
        if (hint == null) {
            throw new IllegalArgumentException(
                "GroupedFile: order field '" + cursor.orderBy() + "' is not declared as an IndexHint. "
                + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
        }
        QueryOptions order = QueryOptions.builder().orderBy(cursor.orderBy(), cursor.direction()).build();
        return query(query, order).thenApply(ordered ->
            QueryResultOrdering.keysetSlice(ordered, cursor, limit, hint, descriptor.keyExtractor(), descriptor.codec()));
    }

}
