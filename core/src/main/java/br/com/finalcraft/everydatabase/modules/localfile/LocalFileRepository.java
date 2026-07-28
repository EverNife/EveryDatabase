package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageExecutors;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.util.FileKeyNames;
import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
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

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-system backed {@link Repository}: one file per entity, named
 * {@code <key>.<ext>} inside the collection directory, where {@code <ext>}
 * comes from {@link Codec#fileExtension()}.
 *
 * <p>The default codec ({@link JacksonJsonCodec})
 * produces {@code .json} files; using
 * {@link JacksonYamlCodec} produces {@code .yml}
 * files instead - no other change is needed.
 *
 * <p>Thread safety: per-key {@link ReadWriteLock}s guard concurrent access.</p>
 *
 * @param <K> the key type (its {@code toString()} is used as the file name)
 * @param <V> the entity type
 */
final class LocalFileRepository<K, V> implements Repository<K, V> {

    private final EntityDescriptor<K, V> descriptor;
    private final Path collectionDir;
    private final StorageLog log;
    private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();
    /** Declared index hints indexed by field path - used for query dispatch. */
    private final Map<String, IndexHint> hintsByPath;

    LocalFileRepository(EntityDescriptor<K, V> descriptor, Path baseDirectory, StorageLog log) {
        this.descriptor    = descriptor;
        this.collectionDir = baseDirectory.resolve(descriptor.collection());
        this.log           = log;
        this.hintsByPath   = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) this.hintsByPath.put(hint.fieldPath(), hint);
    }

    /** Called once by the owning {@link LocalFileStorage} at repository creation time. */
    void initDirectory() throws IOException {
        Files.createDirectories(collectionDir);
        log.emit(StorageOp.TABLE_CREATE, StorageLogLevel.INFO,
            b -> b.collection(descriptor.collection()).detail("dir=" + collectionDir));
    }

    // ------------------------------------------------------------------
    //  Path helpers
    // ------------------------------------------------------------------

    private String keyToString(K key) {
        // Path separators, case-differing names (case-insensitive file systems) and reserved
        // Windows device names all get a stable hash suffix - see FileKeyNames.
        return FileKeyNames.safeStem(key.toString());
    }

    private String fileExtension() {
        return descriptor.codec().fileExtension();
    }

    private Path keyToPath(K key) {
        return collectionDir.resolve(keyToString(key) + "." + fileExtension());
    }

    private ReadWriteLock lockFor(K key) {
        return locks.computeIfAbsent(keyToString(key), k -> new ReentrantReadWriteLock());
    }

    /**
     * Resolves the file to read for {@code key}: the current stem first, then the pre-guard
     * (legacy) stem, so entities written before the case/reserved-name guards renamed the
     * stems of affected keys remain readable. Writes always target the current stem, and
     * {@link #writeFile} removes a leftover legacy file so scans never see the entity twice.
     */
    private Path existingPathFor(K key) {
        Path primary = keyToPath(key);
        if (Files.exists(primary)) return primary;
        Path legacy = legacyKeyToPathIfReal(key);
        return legacy != null ? legacy : primary;
    }

    /**
     * The pre-guard file for {@code key}, or {@code null} when no such file exists with that
     * EXACT name. On case-insensitive file systems the legacy name may resolve to a different
     * key's file ({@code "Alice.json"} finding {@code "alice.json"}), so the directory entry's
     * real case is compared before the path is trusted for reads or deletes.
     */
    private Path legacyKeyToPathIfReal(K key) {
        Path legacy = collectionDir.resolve(FileKeyNames.legacyStem(key.toString()) + "." + fileExtension());
        try {
            if (!Files.exists(legacy)) return null;
            Path real = legacy.toRealPath();
            if (!real.getFileName().toString().equals(legacy.getFileName().toString())) return null;
            return legacy;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Repository impl
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.readLock().lock();
            try {
                Path path = existingPathFor(key);
                if (!Files.exists(path)) return Optional.empty();
                byte[] data = Files.readAllBytes(path);
                return Optional.of(descriptor.codec().decode(data));
            } catch (IOException e) {
                throw log.errored(StorageOp.FIND, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to read key=" + key, e));
            } catch (CodecException e) {
                throw log.errored(StorageOp.FIND, descriptor.collection(),
                    new RuntimeException("LocalFile: codec error reading key=" + key, e));
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
                for (CompletableFuture<Optional<V>> f : futures) {
                    f.join().ifPresent(result::add);
                }
                return result;
            });
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key;
        try {
            key = descriptor.keyExtractor().apply(entity);
        } catch (RuntimeException e) {
            return StorageKeys.failedFuture(e);
        }
        CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, descriptor.collection());
        if (reject != null) return reject;
        return CompletableFuture.supplyAsync(() -> {
            writeFile(key, entity);
            log.saved(descriptor.collection(), key, entity);
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
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, descriptor.collection());
            if (reject != null) return reject;
        }
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
        for (V entity : entities) {
            K key = descriptor.keyExtractor().apply(entity);
            futures.add(CompletableFuture.runAsync(() -> writeFile(key, entity), StorageExecutors.get()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.savedBatch(descriptor.collection(), count, System.currentTimeMillis() - startMs));
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
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, descriptor.collection());
            if (reject != null) return reject;
        }
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
        for (V entity : entities) {
            K key = descriptor.keyExtractor().apply(entity);
            futures.add(CompletableFuture.runAsync(() -> {
                // UPDATE_ONLY: write only if a file already exists, all under the per-key write lock so a
                // concurrent delete cannot be resurrected. The reentrant write lock lets writeFile re-take it.
                ReadWriteLock lock = lockFor(key);
                lock.writeLock().lock();
                try {
                    if (Files.exists(existingPathFor(key))) writeFile(key, entity);
                } finally {
                    lock.writeLock().unlock();
                }
            }, StorageExecutors.get()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.savedBatch(descriptor.collection(), count, System.currentTimeMillis() - startMs));
    }

    /**
     * Encodes and writes one entity to disk under its per-key lock. Shared by {@link #save}
     * (which logs a single {@code SAVE} event) and {@link #saveAll} (which logs one
     * {@code SAVE_BATCH} summary instead - logging here too would emit one event per entity).
     *
     * <p>The write is crash-safe: data goes to a sibling {@code .tmp} file first and is then
     * moved over the target with {@link StandardCopyOption#ATOMIC_MOVE}, so a crash mid-write
     * never leaves a truncated entity file behind (at worst an orphan {@code .tmp}, which
     * {@code all()}/{@code count()} ignore because they filter by codec extension).
     */
    private void writeFile(K key, V entity) {
        ReadWriteLock lock = lockFor(key);
        lock.writeLock().lock();
        try {
            byte[] data = descriptor.codec().encode(entity);
            Path target = keyToPath(key);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Exotic file system without atomic rename: plain replace is the best we can do.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Migrate-on-write: drop a pre-guard file for the same key so scans never
            // count the entity twice (reads prefer the new stem anyway).
            Path legacy = legacyKeyToPathIfReal(key);
            if (legacy != null && !legacy.equals(target)) Files.deleteIfExists(legacy);
        } catch (IOException e) {
            throw log.errored(StorageOp.SAVE, descriptor.collection(),
                new RuntimeException("LocalFile: failed to write key=" + key, e));
        } catch (CodecException e) {
            throw log.errored(StorageOp.SAVE, descriptor.collection(),
                new RuntimeException("LocalFile: codec error writing key=" + key, e));
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
                Path primary = keyToPath(key);
                Path legacy  = legacyKeyToPathIfReal(key);
                boolean existed = Files.deleteIfExists(primary);
                if (legacy != null && !legacy.equals(primary)) existed |= Files.deleteIfExists(legacy);
                if (!existed) {
                    log.deleted(descriptor.collection(), key, false);
                    return false;
                }
                // The lock deliberately stays in the map: removing it here would let another
                // thread mint a NEW lock for the same key while we still hold the old one,
                // breaking mutual exclusion. The map is bounded by the number of live keys.
                log.deleted(descriptor.collection(), key, true);
                return true;
            } catch (IOException e) {
                throw log.errored(StorageOp.DELETE, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to delete key=" + key, e));
            } finally {
                lock.writeLock().unlock();
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(
            () -> Files.exists(existingPathFor(key)),
            StorageExecutors.get()
        );
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyMap());
        return CompletableFuture.supplyAsync(() -> {
            Map<K, Long> result = new HashMap<>();
            for (K key : keys) {
                ReadWriteLock lock = lockFor(key);
                lock.readLock().lock();
                try {
                    // LocalFile does not enforce optimistic locking, so existing keys always
                    // report version 0 - matching H2 and keeping the polling substrate uniform
                    // across non-enforcing backends (and skipping a full decode per key).
                    if (Files.exists(existingPathFor(key))) result.put(key, 0L);
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
                if (!Files.exists(collectionDir)) return 0L;
                String ext = "." + fileExtension();
                List<Path> files;
                try (java.util.stream.Stream<Path> paths = Files.walk(collectionDir, 1)) {
                    files = paths
                        .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                        .collect(Collectors.toList());
                }
                long valid = 0L;
                for (Path path : files) {
                    try {
                        // Decode to stay consistent with all(): a corrupted file is skipped-and-logged
                        // there, so it must not inflate the count here (count() == all().count()).
                        descriptor.codec().decode(Files.readAllBytes(path));
                        valid++;
                    } catch (Exception e) {
                        log.skippedCorruptedRow(descriptor.collection(), path.getFileName().toString(), e);
                    }
                }
                return valid;
            } catch (IOException e) {
                throw log.errored(StorageOp.COUNT, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to count entities", e));
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(collectionDir)) return Stream.empty();

                String ext = "." + fileExtension();
                List<Path> files;
                try (java.util.stream.Stream<Path> paths = Files.walk(collectionDir, 1)) {
                    files = paths
                        .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                        .collect(Collectors.toList());
                }

                List<V> results = new ArrayList<>(files.size());
                for (Path path : files) {
                    String fileName = path.getFileName().toString();
                    try {
                        byte[] data = Files.readAllBytes(path);
                        results.add(descriptor.codec().decode(data));
                    } catch (Exception e) {
                        // skip corrupted files but log a WARN (not silently swallow)
                        log.skippedCorruptedRow(descriptor.collection(), fileName, e);
                    }
                }
                return results.stream();
            } catch (IOException e) {
                throw log.errored(StorageOp.SCAN_ALL, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to stream all entities", e));
            }
        }, StorageExecutors.get());
    }

    /**
     * Key-ordered scan. File backends are single-instance and hold modest collections, so this returns
     * the whole collection in one page (ordered by file stem), which keeps the scan complete and simple;
     * {@code limit} is advisory here. A file that cannot be read/decoded is surfaced as a failed
     * {@link ScanRow} (never silently dropped). A non-start cursor returns an empty final page, since the
     * first page already returned everything.
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
                if (!Files.exists(collectionDir)) {
                    return Slice.ofCursor(new ArrayList<ScanRow<V>>(), QueryOptions.none(), false, null);
                }
                String ext = "." + fileExtension();
                List<Path> files;
                try (java.util.stream.Stream<Path> paths = Files.walk(collectionDir, 1)) {
                    files = paths
                        .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList());
                }
                List<ScanRow<V>> rows = new ArrayList<>(files.size());
                for (Path path : files) {
                    String fileName = path.getFileName().toString();
                    String stem = fileName.substring(0, fileName.length() - ext.length());
                    try {
                        byte[] data = Files.readAllBytes(path);
                        V value = descriptor.codec().decode(data);
                        // Carry the real storage key (from the decoded entity), not the file stem, so
                        // ScanRow.key() matches the other backends for sanitized/hashed keys.
                        rows.add(ScanRow.ok(descriptor.keyExtractor().apply(value).toString(), value));
                    } catch (Exception e) {
                        log.skippedCorruptedRow(descriptor.collection(), fileName, e);
                        rows.add(ScanRow.failed(stem, e));   // undecodable: best-effort identifier is the file stem
                    }
                }
                return Slice.ofCursor(rows, QueryOptions.none(), false, null);
            } catch (IOException e) {
                throw log.errored(StorageOp.SCAN_ALL, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to scan all entities", e));
            }
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Index queries
    //
    //  LocalFile has no real index. Each query walks every file and filters in
    //  memory via the same Jackson-tree extractor the other backends use at save
    //  time. Correct but O(N) per call - acceptable for dev/embedded use, not
    //  for high-throughput production lookups.
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        return query(Query.eq(fieldPath, value));
    }

    /**
     * Scans the collection directory and returns the entities matching {@code query}.
     *
     * <p>A stored file <em>is</em> the codec's own output, so a condition can be tested against the
     * tree parsed straight from disk and only the matches ever reach the codec. A file that is present
     * but undecodable is therefore reported (skipped-and-logged) only when it matches the query - one
     * that does not match is filtered out before its decode would have failed. An opaque codec, whose
     * bytes are no tree at all, still decodes first (see {@link #payloadTree}).
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
        // The scan could answer undeclared fields, but we reject them so a query that
        // works here does not start throwing when the storage is swapped for SQL/Mongo.
        for (Query.Condition c : query.conditions()) {
            if (!hintsByPath.containsKey(c.fieldPath())) {
                throw new IllegalArgumentException(
                    "LocalFile: field '" + c.fieldPath() + "' is not declared as an IndexHint. "
                    + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
            }
        }
        QueryResultOrdering.validateOrderField(finalOptions, hintsByPath, "LocalFile");

        long startMs = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<V> filtered = new ArrayList<>();
                for (Path path : collectionFiles()) {
                    try {
                        byte[] data   = Files.readAllBytes(path);
                        V      entity = null;
                        JsonNode tree = payloadTree(data);
                        if (tree == null) {   // opaque codec: only the decoded entity yields a tree
                            entity = descriptor.codec().decode(data);
                            tree   = IndexValueExtractor.toTree(entity, descriptor.codec());
                        }
                        if (!IndexValueExtractor.matchesAll(tree, query, hintsByPath)) continue;
                        filtered.add(entity != null ? entity : descriptor.codec().decode(data));
                    } catch (Exception e) {
                        log.skippedCorruptedRow(descriptor.collection(), path.getFileName().toString(), e);
                    }
                }
                List<V> result = QueryResultOrdering.apply(filtered, finalOptions, hintsByPath, descriptor.keyExtractor(), descriptor.codec());
                log.queried(descriptor.collection(), query, result.size(), System.currentTimeMillis() - startMs);
                return result;
            } catch (IOException e) {
                throw log.errored(StorageOp.QUERY, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to query entities", e));
            }
        }, StorageExecutors.get());
    }

    /** The entity files of this collection (depth 1, filtered by the codec's extension). */
    private List<Path> collectionFiles() throws IOException {
        if (!Files.exists(collectionDir)) return Collections.emptyList();
        String ext = "." + fileExtension();
        try (Stream<Path> paths = Files.walk(collectionDir, 1)) {
            return paths
                .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                .collect(Collectors.toList());
        }
    }

    /**
     * The stored payload as the tree the codec wrote, so a query can filter a file before decoding it.
     * {@code null} for an opaque codec - one that is neither Jackson-backed nor JSON - whose bytes are
     * not a tree, and whose entity therefore has to be decoded before it can be matched.
     */
    private JsonNode payloadTree(byte[] data) throws IOException {
        Codec<V> codec = descriptor.codec();
        if (!(codec instanceof ObjectMapperAware) && !codec.isJsonCodec()) return null;
        return IndexValueExtractor.mapperFor(codec).readTree(data);
    }

    @Override
    public CompletableFuture<Slice<V>> queryAfter(Query query, Cursor cursor, int limit) {
        if (query == null)  throw new IllegalArgumentException("query cannot be null");
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        IndexHint hint = hintsByPath.get(cursor.orderBy());
        if (hint == null) {
            throw new IllegalArgumentException(
                "LocalFile: order field '" + cursor.orderBy() + "' is not declared as an IndexHint. "
                + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
        }
        QueryOptions order = QueryOptions.builder().orderBy(cursor.orderBy(), cursor.direction()).build();
        return query(query, order).thenApply(ordered ->
            QueryResultOrdering.keysetSlice(ordered, cursor, limit, hint, descriptor.keyExtractor(), descriptor.codec()));
    }

}
