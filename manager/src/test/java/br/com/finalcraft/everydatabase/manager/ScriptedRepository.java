package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.query.Query;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A minimal in-memory {@link Repository} for tests, with scripted per-key save failures - so a
 * batch write-back can be driven into the optimistic-lock and transient-error paths without a real
 * versioned backend (none of the no-Docker backends enforce optimistic locking).
 */
class ScriptedRepository<K, V> implements Repository<K, V> {

    private final Map<K, V> data = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> saveFailures = new ConcurrentHashMap<>();
    private final Function<V, K> keyOf;

    ScriptedRepository(Function<V, K> keyOf) {
        this.keyOf = keyOf;
    }

    /** Makes {@code save}/{@code saveAll} fail for {@code key} with the supplied exception. */
    void failSave(K key, Supplier<? extends RuntimeException> exception) {
        saveFailures.put(key, exception);
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = keyOf.apply(entity);
        Supplier<? extends RuntimeException> failure = saveFailures.get(key);
        if (failure != null) {
            return failed(failure.get());
        }
        data.put(key, entity);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        for (V entity : entities) {
            if (saveFailures.containsKey(keyOf.apply(entity))) {
                return failed(new RuntimeException("scripted batch failure"));   // forces the per-entity retry
            }
        }
        for (V entity : entities) {
            data.put(keyOf.apply(entity), entity);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(data.get(key)));
    }

    @Override
    public CompletableFuture<List<V>> findMany(Collection<K> keys) {
        List<V> found = new ArrayList<>();
        for (K key : keys) {
            V value = data.get(key);
            if (value != null) {
                found.add(value);
            }
        }
        return CompletableFuture.completedFuture(found);
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        return CompletableFuture.completedFuture(data.remove(key) != null);
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.completedFuture(data.containsKey(key));
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.completedFuture((long) data.size());
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        Map<K, Long> result = new HashMap<>();
        for (K key : keys) {
            if (data.containsKey(key)) result.put(key, 0L);
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        return CompletableFuture.completedFuture(new ArrayList<>(data.values()).stream());
    }

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<V>> query(Query query) {
        throw new UnsupportedOperationException();
    }
}
