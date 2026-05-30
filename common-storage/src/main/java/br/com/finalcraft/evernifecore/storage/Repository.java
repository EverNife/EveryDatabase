package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.query.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Typed CRUD contract for a single "collection" of entities.
 *
 * <p>All I/O operations return {@link CompletableFuture}; there are no blocking
 * synchronous variants. Callers that need to block may call {@code .join()}.</p>
 *
 * <p>Errors propagate as exceptional completions of the returned futures
 * (wrapped in {@link RuntimeException} when the root cause is checked).</p>
 *
 * @param <K> the key (identifier) type
 * @param <V> the entity type
 */
public interface Repository<K, V> {

    /**
     *  Returns the entity for the given key, or {@link Optional#empty()} if absent.
     */
    CompletableFuture<Optional<V>> find(K key);

    /**
     * Returns entities for all the given keys; missing keys are simply omitted.
     */
    CompletableFuture<List<V>> findMany(Collection<K> keys);

    /** Upsert: inserts or replaces.
     *
     */
    CompletableFuture<Void> save(V entity);

    /**
     * Batch upsert. Backends should optimise (JDBC batch, Mongo insertMany).
     */
    CompletableFuture<Void> saveAll(Collection<V> entities);

    /**
     * Deletes the entity. Returns {@code true} if it existed.
     */
    CompletableFuture<Boolean> delete(K key);

    /**
     * Returns {@code true} if the entity exists.
     */
    CompletableFuture<Boolean> exists(K key);

    /**
     *  Total count of stored entities.
     */
    CompletableFuture<Long> count();

    /**
     * Returns all entities as a {@link Stream}.
     * Implementations should materialise results internally and paginate for large datasets.
     */
    CompletableFuture<Stream<V>> all();

    // ------------------------------------------------------------------
    //  Secondary-index queries
    // ------------------------------------------------------------------

    /**
     * Finds entities whose indexed field at {@code fieldPath} equals {@code value}.
     *
     * <p>{@code fieldPath} must have been declared on the {@link EntityDescriptor} via
     * {@code .index(IndexHint.string("..."))} (or another typed factory). Backends that
     * support real indexes (SQL, Mongo, InMemory) use them; LocalFile falls back to a
     * full scan.
     *
     * <p>Equivalent to {@link #query(Query)} with {@code Query.eq(fieldPath, value)} but
     * shorter for the common case.
     *
     * @throws IllegalArgumentException at execution time if {@code fieldPath} is not declared
     *         as an {@code IndexHint} (LocalFile excepted - it scans anyway)
     */
    CompletableFuture<List<V>> findBy(String fieldPath, Object value);

    /**
     * Executes a composite query against indexed fields.
     *
     * <p>All fields referenced by {@link Query.Condition} must be declared as
     * {@code IndexHint} on the descriptor. Conditions are intersected (AND).
     *
     * @see Query
     */
    CompletableFuture<List<V>> query(Query query);
}
