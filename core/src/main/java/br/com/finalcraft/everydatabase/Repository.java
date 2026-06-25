package br.com.finalcraft.everydatabase;

import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.Page;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Slice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Upsert: inserts or replaces.
     *
     * <p>The key is persisted by its {@code toString()}; a key whose {@code toString()} exceeds
     * {@link StorageKeys#MAX_KEY_LENGTH} characters is rejected and the returned future completes
     * exceptionally with an {@link IllegalArgumentException} (the key never reaches storage, so it
     * cannot be silently truncated into a collision). See {@link StorageKeys} for the full key
     * contract.
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
     * Returns the current optimistic-lock version of each given key that exists in storage; absent
     * keys are omitted from the result.
     *
     * <p>For a versioned descriptor the value is the stored {@code lock_version}. For a non-versioned
     * descriptor - or a backend that does not enforce versioning (H2) - it is {@code 0} for every
     * existing key, so a poller can still detect <em>deletions</em> (a cached key missing from the
     * result) but not in-place <em>updates</em>.
     *
     * <p>This is a cheap, content-free read (key + version only, never the entity body), used by the
     * manager's {@code PollingCacheSync} to invalidate caches on backends without a native change
     * feed (MySQL/MariaDB). On backends that do have a change feed it still works, but the push feed
     * is preferred.
     */
    CompletableFuture<Map<K, Long>> versions(Collection<K> keys);

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
     * support real indexes (SQL, Mongo, InMemory) use them; LocalFile answers with a
     * full scan - correct, but O(N) per call.
     *
     * <p>Equivalent to {@link #query(Query)} with {@code Query.eq(fieldPath, value)} but
     * shorter for the common case.
     *
     * @throws IllegalArgumentException at execution time if {@code fieldPath} is not declared
     *         as an {@code IndexHint} - every backend validates this, including LocalFile
     *         (which could scan undeclared fields, but rejects them to keep behavior
     *         consistent across backends)
     */
    CompletableFuture<List<V>> findBy(String fieldPath, Object value);

    /**
     * Executes a composite query against indexed fields.
     *
     * <p>All fields referenced by {@link Query.Condition} must be declared as
     * {@code IndexHint} on the descriptor. Conditions are intersected (AND).
     *
     * <p>Convenience overload equivalent to {@link #query(Query, QueryOptions)} with
     * {@link QueryOptions#none()}.
     *
     * @see Query
     */
    default CompletableFuture<List<V>> query(Query query) {
        return query(query, QueryOptions.none());
    }

    /**
     * Executes a composite query with optional result ordering and pagination.
     *
     * <p>This is the single query primitive every backend implements; {@link #query(Query)}
     * delegates here with {@link QueryOptions#none()}.
     *
     * <p>Ordering fields must be declared as {@link IndexHint}s, matching the same
     * cross-backend validation used for query conditions. Ordering and pagination are
     * consistent across backends - see {@link QueryOptions} for the NULL-ordering and
     * tie-breaking contract.
     *
     * @param options result controls; never {@code null} - pass {@link QueryOptions#none()}
     *        for plain queries
     * @see Query
     * @see QueryOptions
     */
    CompletableFuture<List<V>> query(Query query, QueryOptions options);

    /**
     * Counts entities matching {@code query}, ignoring pagination. Validates index fields like
     * {@link #query(Query)}. The default fetches and counts; SQL/Mongo/InMemory override it to count
     * without materializing rows, so a custom implementation is correct but should override for
     * efficiency on large collections.
     */
    default CompletableFuture<Long> count(Query query) {
        return query(query).thenApply(list -> (long) list.size());
    }

    /**
     * One page of results with next/previous navigation but <em>no</em> total count - the cheap
     * pagination result. Fetches one extra row to decide {@link Slice#hasNext()}; never runs a count.
     *
     * @see Slice
     */
    default CompletableFuture<Slice<V>> querySlice(Query query, QueryOptions options) {
        QueryOptions opts = options == null ? QueryOptions.none() : options;
        if (!opts.hasLimit()) {
            return query(query, opts).thenApply(rows -> Slice.of(rows, opts, false));
        }
        int probeLimit = opts.limit() == Integer.MAX_VALUE ? Integer.MAX_VALUE : opts.limit() + 1;
        return query(query, opts.withLimit(probeLimit)).thenApply(rows -> {
            boolean hasNext = rows.size() > opts.limit();
            List<V> content = hasNext ? new ArrayList<>(rows.subList(0, opts.limit())) : rows;
            return Slice.of(content, opts, hasNext);
        });
    }

    /**
     * One page of results <em>with</em> the total match count, so {@link Page#totalElements()} and
     * {@link Page#totalPages()} are available. Runs {@link #count(Query)} in addition to the page
     * query - prefer {@link #querySlice(Query, QueryOptions)} when you don't need the total.
     *
     * <p>Sequential (count then query) so it stays correct inside a transaction, where the two share
     * one connection and must not run concurrently.
     *
     * @see Page
     */
    default CompletableFuture<Page<V>> queryPage(Query query, QueryOptions options) {
        QueryOptions opts = options == null ? QueryOptions.none() : options;
        return count(query).thenCompose(total ->
               query(query, opts).thenApply(content -> Page.of(content, opts, total)));
    }
}
