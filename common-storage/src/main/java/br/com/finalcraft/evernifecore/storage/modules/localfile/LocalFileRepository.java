package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.codec.CodecException;
import br.com.finalcraft.evernifecore.storage.log.StorageLog;
import br.com.finalcraft.evernifecore.storage.log.StorageLogLevel;
import br.com.finalcraft.evernifecore.storage.log.StorageOp;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.IndexValueExtractor;
import br.com.finalcraft.evernifecore.storage.query.Query;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-system backed {@link Repository}: one file per entity, named
 * {@code <key>.<ext>} inside the collection directory, where {@code <ext>}
 * comes from {@link br.com.finalcraft.evernifecore.storage.codec.Codec#fileExtension()}.
 *
 * <p>The default codec ({@link br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec})
 * produces {@code .json} files; using
 * {@link br.com.finalcraft.evernifecore.storage.codec.JacksonYamlCodec} produces {@code .yml}
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
        // sanitise: replace path separators to avoid directory traversal
        String raw = key.toString();
        String sanitized = raw.replace("/", "_").replace("\\", "_").replace(":", "_");
        if (sanitized.equals(raw)) return raw;
        // Sanitisation changed the name, so distinct keys could now collide on disk
        // ("a/b" and "a_b" both sanitise to "a_b"). Suffix a short hash of the original
        // key to keep one file per key. String.hashCode() is specified by the JLS, so
        // the name is stable across JVM restarts.
        return sanitized + "_" + String.format("%08x", raw.hashCode());
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

    // ------------------------------------------------------------------
    //  Repository impl
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(key);
            lock.readLock().lock();
            try {
                Path path = keyToPath(key);
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
        }, StorageExecutors.async());
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
        K key = descriptor.keyExtractor().apply(entity);
        return CompletableFuture.supplyAsync(() -> {
            writeFile(key, entity);
            log.saved(descriptor.collection(), key, entity);
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>((int) count);
        for (V entity : entities) {
            K key = descriptor.keyExtractor().apply(entity);
            futures.add(CompletableFuture.runAsync(() -> writeFile(key, entity), StorageExecutors.async()));
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
                Path path = keyToPath(key);
                if (!Files.exists(path)) {
                    log.deleted(descriptor.collection(), key, false);
                    return false;
                }
                Files.delete(path);
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
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.supplyAsync(
            () -> Files.exists(keyToPath(key)),
            StorageExecutors.async()
        );
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(collectionDir)) return 0L;
                String ext = "." + fileExtension();
                try (java.util.stream.Stream<Path> paths = Files.walk(collectionDir, 1)) {
                    return paths
                        .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                        .count();
                }
            } catch (IOException e) {
                throw log.errored(StorageOp.COUNT, descriptor.collection(),
                    new RuntimeException("LocalFile: failed to count entities", e));
            }
        }, StorageExecutors.async());
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
        }, StorageExecutors.async());
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

    @Override
    public CompletableFuture<List<V>> query(Query query) {
        // The scan could answer undeclared fields, but we reject them so a query that
        // works here does not start throwing when the storage is swapped for SQL/Mongo.
        for (Query.Condition c : query.conditions()) {
            if (!hintsByPath.containsKey(c.fieldPath())) {
                throw new IllegalArgumentException(
                    "LocalFile: field '" + c.fieldPath() + "' is not declared as an IndexHint. "
                    + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
            }
        }

        long startMs = System.currentTimeMillis();
        return all().thenApply(stream -> {
            List<V> result = new ArrayList<>();
            stream.forEach(entity -> {
                JsonNode tree = IndexValueExtractor.toTree(entity);
                if (matchesAll(tree, query)) result.add(entity);
            });
            log.queried(descriptor.collection(), query, result.size(), System.currentTimeMillis() - startMs);
            return result;
        });
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
