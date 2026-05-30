package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.codec.CodecException;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.IndexValueExtractor;
import br.com.finalcraft.evernifecore.storage.query.Query;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();
    /** Declared index hints indexed by field path - used for query dispatch. */
    private final Map<String, IndexHint> hintsByPath;

    LocalFileRepository(EntityDescriptor<K, V> descriptor, Path baseDirectory) {
        this.descriptor    = descriptor;
        this.collectionDir = baseDirectory.resolve(descriptor.collection());
        this.hintsByPath   = new HashMap<>();
        for (IndexHint hint : descriptor.indexes()) this.hintsByPath.put(hint.fieldPath(), hint);
    }

    /** Called once by the owning {@link LocalFileStorage} at repository creation time. */
    void initDirectory() throws IOException {
        Files.createDirectories(collectionDir);
    }

    // ------------------------------------------------------------------
    //  Path helpers
    // ------------------------------------------------------------------

    private String keyToString(K key) {
        // sanitise: replace path separators to avoid directory traversal
        return key.toString().replace("/", "_").replace("\\", "_").replace(":", "_");
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
                throw new RuntimeException("LocalFile: failed to read key=" + key, e);
            } catch (CodecException e) {
                throw new RuntimeException("LocalFile: codec error reading key=" + key, e);
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
            ReadWriteLock lock = lockFor(key);
            lock.writeLock().lock();
            try {
                byte[] data = descriptor.codec().encode(entity);
                Files.write(keyToPath(key), data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
                return null;
            } catch (IOException e) {
                throw new RuntimeException("LocalFile: failed to write key=" + key, e);
            } catch (CodecException e) {
                throw new RuntimeException("LocalFile: codec error writing key=" + key, e);
            } finally {
                lock.writeLock().unlock();
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
            ReadWriteLock lock = lockFor(key);
            lock.writeLock().lock();
            try {
                Path path = keyToPath(key);
                if (!Files.exists(path)) return false;
                Files.delete(path);
                locks.remove(keyToString(key));
                return true;
            } catch (IOException e) {
                throw new RuntimeException("LocalFile: failed to delete key=" + key, e);
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
                throw new RuntimeException("LocalFile: failed to count entities", e);
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(collectionDir)) return Stream.empty();

                String ext = "." + fileExtension();
                List<Path> jsonFiles;
                try (java.util.stream.Stream<Path> paths = Files.walk(collectionDir, 1)) {
                    jsonFiles = paths
                        .filter(p -> p.toString().endsWith(ext) && !p.equals(collectionDir))
                        .collect(Collectors.toList());
                }

                List<V> results = new ArrayList<>(jsonFiles.size());
                for (Path path : jsonFiles) {
                    try {
                        byte[] data = Files.readAllBytes(path);
                        results.add(descriptor.codec().decode(data));
                    } catch (Exception e) {
                        // skip corrupted files
                    }
                }
                return results.stream();
            } catch (IOException e) {
                throw new RuntimeException("LocalFile: failed to stream all entities", e);
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
        // Unlike SQL/Mongo we tolerate non-declared fields (we have to scan anyway).
        // But to keep behaviour predictable across backends we still validate.
        for (Query.Condition c : query.conditions()) {
            if (!hintsByPath.containsKey(c.fieldPath())) {
                throw new IllegalArgumentException(
                    "LocalFile: field '" + c.fieldPath() + "' is not declared as an IndexHint. "
                    + "Add .index(IndexHint.<type>(\"...\")) on the EntityDescriptor.");
            }
        }

        return all().thenApply(stream -> {
            List<V> result = new ArrayList<>();
            stream.forEach(entity -> {
                JsonNode tree = IndexValueExtractor.toTree(entity);
                if (matchesAll(tree, query)) result.add(entity);
            });
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
