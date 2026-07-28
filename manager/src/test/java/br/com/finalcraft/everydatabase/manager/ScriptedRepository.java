package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A minimal in-memory {@link Repository} for tests, with scripted per-key save failures - so a
 * batch write-back can be driven into the optimistic-lock and transient-error paths without a real
 * versioned backend (none of the no-Docker backends enforce optimistic locking).
 *
 * <p>Reads are scriptable too ({@link #failFind}, {@link #throwOnFind}, {@link #beforeFind}), because a conflict
 * resolution re-reads the winner: that read is both a failure path of its own and the window in
 * which a racing thread gets to touch the live instance. {@link #deferFind} holds a read open and
 * {@link #findCount} counts them, which is how a test observes whether two readers of one key
 * produced one backend call or two.
 */
public class ScriptedRepository<K, V> implements Repository<K, V> {

    private final Map<K, V> data = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> saveFailures = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> saveThrows = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> deleteFailures = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> findFailures = new ConcurrentHashMap<>();
    private final Map<K, Supplier<? extends RuntimeException>> findThrows = new ConcurrentHashMap<>();
    private final Map<K, Runnable> findCallbacks = new ConcurrentHashMap<>();
    private final Map<K, CompletableFuture<Optional<V>>> deferredFinds = new ConcurrentHashMap<>();
    private final Map<K, AtomicInteger> findCalls = new ConcurrentHashMap<>();
    private final Function<V, K> keyOf;

    public ScriptedRepository(Function<V, K> keyOf) {
        this.keyOf = keyOf;
    }

    /** Makes {@code save}/{@code saveAll} fail for {@code key} with the supplied exception. */
    public void failSave(K key, Supplier<? extends RuntimeException> exception) {
        saveFailures.put(key, exception);
    }

    /**
     * Makes {@code save} THROW for {@code key} instead of returning a failed future - a repository
     * breaking the async contract, which is what drives a manager into rethrowing from a batch write
     * back at its caller.
     */
    public void throwOnSave(K key, Supplier<? extends RuntimeException> exception) {
        saveThrows.put(key, exception);
    }

    /** Makes {@code delete} fail for {@code key} with the supplied exception. */
    public void failDelete(K key, Supplier<? extends RuntimeException> exception) {
        deleteFailures.put(key, exception);
    }

    /** Makes {@code find} fail for {@code key} with the supplied exception. */
    public void failFind(K key, Supplier<? extends RuntimeException> exception) {
        findFailures.put(key, exception);
    }

    /**
     * Makes the next {@code find} of {@code key} THROW instead of returning a failed future - a
     * repository breaking the async contract on the read side, which is what tells a manager's
     * in-flight bookkeeping apart from one that only survives well-behaved failures. Runs once.
     */
    public void throwOnFind(K key, Supplier<? extends RuntimeException> exception) {
        findThrows.put(key, exception);
    }

    /** Runs {@code action} on the next {@code find} of {@code key}, before it answers - the hook a
     *  test uses to land a concurrent change while the winner is being re-read. Runs once. */
    public void beforeFind(K key, Runnable action) {
        findCallbacks.put(key, action);
    }

    /**
     * Makes the next {@code find} of {@code key} answer only when the returned handle is completed,
     * holding the read open - the window in which a second read of the same key either joins the
     * load already in flight or issues one of its own.
     */
    public CompletableFuture<Optional<V>> deferFind(K key) {
        CompletableFuture<Optional<V>> gate = new CompletableFuture<>();
        deferredFinds.put(key, gate);
        return gate;
    }

    /** How many {@code find} calls this repository has answered for {@code key}. */
    public int findCount(K key) {
        AtomicInteger calls = findCalls.get(key);
        return calls == null ? 0 : calls.get();
    }

    /** Stores {@code entity} directly, bypassing the scripted failures - seeds the stored winner. */
    public void put(V entity) {
        data.put(keyOf.apply(entity), entity);
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = keyOf.apply(entity);
        Supplier<? extends RuntimeException> thrown = saveThrows.get(key);
        if (thrown != null) {
            throw thrown.get();
        }
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
            K key = keyOf.apply(entity);
            if (saveFailures.containsKey(key) || saveThrows.containsKey(key)) {
                return failed(new RuntimeException("scripted batch failure"));   // forces the per-entity retry
            }
        }
        for (V entity : entities) {
            data.put(keyOf.apply(entity), entity);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities, WriteMode mode) {
        if (mode == null || mode == WriteMode.UPSERT) {
            return saveAll(entities);
        }
        throw new UnsupportedOperationException();   // this fake has no update-only maintenance path
    }

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        Supplier<? extends RuntimeException> thrown = findThrows.remove(key);
        if (thrown != null) {
            throw thrown.get();   // never answered, so it is not a counted call
        }
        findCalls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        Runnable callback = findCallbacks.remove(key);
        if (callback != null) {
            callback.run();
        }
        Supplier<? extends RuntimeException> failure = findFailures.get(key);
        if (failure != null) {
            return failed(failure.get());
        }
        CompletableFuture<Optional<V>> deferred = deferredFinds.remove(key);
        if (deferred != null) {
            return deferred;
        }
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
        Supplier<? extends RuntimeException> failure = deleteFailures.get(key);
        if (failure != null) {
            return failed(failure.get());
        }
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
    public CompletableFuture<List<V>> query(Query query, QueryOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Slice<ScanRow<V>>> scanAll(Cursor cursor, int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Slice<V>> queryAfter(Query query, Cursor cursor, int limit) {
        throw new UnsupportedOperationException();
    }
}
