package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A {@link Storage} decorator that fails chosen writes on chosen collections while everything else -
 * the scan, the codec, the migration, the reads - runs for real against the store underneath.
 *
 * <p>It exists because the sweep's failure paths cannot be reached any other way from a test: no
 * backend that runs without a server enforces optimistic locking, and none of them can be told to
 * drop a marker write at the exact moment the sweep is finishing. Faulting a real store keeps
 * everything the sweep depends on honest and scripts only the one write under test.
 */
class ScriptedStorage implements Storage {

    /**
     * Decides whether one write fails, and with what. Receives the entities being written (a single
     * one for {@code save}), and returns the exception to fail with, or {@code null} to let the
     * write reach the store.
     */
    @FunctionalInterface
    interface WriteScript {
        RuntimeException failureFor(Collection<?> entities);
    }

    private final Storage delegate;
    private final Map<String, WriteScript> scripts = new ConcurrentHashMap<>();

    ScriptedStorage(Storage delegate) {
        this.delegate = delegate;
    }

    /** Installs (replacing) the script that governs writes to {@code collection}. */
    ScriptedStorage script(String collection, WriteScript script) {
        scripts.put(collection, script);
        return this;
    }

    @Override
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        return new ScriptedRepo<>(delegate.repository(descriptor), descriptor.collection());
    }

    @Override
    public CompletableFuture<Void> init() {
        return delegate.init();
    }

    @Override
    public CompletableFuture<Void> close() {
        return delegate.close();
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        return delegate.health();
    }

    @Override
    public StorageLogConfig getStorageLogConfig() {
        return delegate.getStorageLogConfig();
    }

    @Override
    public Storage setStorageLogConfig(StorageLogConfig config) {
        delegate.setStorageLogConfig(config);
        return this;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    /** Reads pass straight through; writes are offered to the collection's script first. */
    private final class ScriptedRepo<K, V> implements Repository<K, V> {

        private final Repository<K, V> inner;
        private final String collection;

        private ScriptedRepo(Repository<K, V> inner, String collection) {
            this.inner = inner;
            this.collection = collection;
        }

        private RuntimeException failureFor(Collection<?> entities) {
            WriteScript script = scripts.get(collection);
            return script == null ? null : script.failureFor(entities);
        }

        @Override
        public CompletableFuture<Void> save(V entity) {
            RuntimeException failure = failureFor(Collections.singletonList(entity));
            return failure != null ? failed(failure) : inner.save(entity);
        }

        @Override
        public CompletableFuture<Void> saveAll(Collection<V> entities) {
            RuntimeException failure = failureFor(entities);
            return failure != null ? failed(failure) : inner.saveAll(entities);
        }

        @Override
        public CompletableFuture<Void> saveAll(Collection<V> entities, WriteMode mode) {
            RuntimeException failure = failureFor(entities);
            return failure != null ? failed(failure) : inner.saveAll(entities, mode);
        }

        @Override
        public CompletableFuture<Optional<V>> find(K key) {
            return inner.find(key);
        }

        @Override
        public CompletableFuture<List<V>> findMany(Collection<K> keys) {
            return inner.findMany(keys);
        }

        @Override
        public CompletableFuture<Boolean> delete(K key) {
            return inner.delete(key);
        }

        @Override
        public CompletableFuture<Boolean> exists(K key) {
            return inner.exists(key);
        }

        @Override
        public CompletableFuture<Long> count() {
            return inner.count();
        }

        @Override
        public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
            return inner.versions(keys);
        }

        @Override
        public CompletableFuture<Stream<V>> all() {
            return inner.all();
        }

        @Override
        public CompletableFuture<Slice<ScanRow<V>>> scanAll(Cursor cursor, int limit) {
            return inner.scanAll(cursor, limit);
        }

        @Override
        public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
            return inner.findBy(fieldPath, value);
        }

        @Override
        public CompletableFuture<List<V>> query(Query query, QueryOptions options) {
            return inner.query(query, options);
        }

        @Override
        public CompletableFuture<Slice<V>> queryAfter(Query query, Cursor cursor, int limit) {
            return inner.queryAfter(query, cursor, limit);
        }
    }
}
