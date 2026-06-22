package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Thread-safe in-memory repository backed by a {@link ConcurrentHashMap}.
 * All operations complete immediately on the calling thread.
 *
 * <p>Read operations ({@code find}, {@code findMany}, {@code query}, {@code all}) return
 * defensive deep copies so that callers cannot mutate stored state through returned references.
 * Write operations ({@code save}, {@code saveAll}) likewise copy the incoming entity before
 * storing it, ensuring full isolation between the store and the caller.
 *
 * <p>Secondary indexes are kept as auxiliary maps
 * {@code Map<fieldPath, Map<value, Set<key>>>}, maintained synchronously on
 * {@code save}/{@code delete}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
final class InMemoryRepository<K, V> implements Repository<K, V> {

    private final EntityDescriptor<K, V> descriptor;
    private final StorageLog log;
    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();

    /** Change-feed dispatcher of the owning storage; {@code null} when the storage has none. */
    private final ChangeFeedSupport changeFeed;
    /** Origin id of the owning storage, stamped on emitted events. */
    private final String originId;

    /** Indexed field paths (declared in the descriptor). */
    private final Map<String, IndexHint> hintsByPath;

    /**
     * indexes[fieldPath] -> indexValue -> set of keys.
     * Synchronised on the outer map for cross-field consistency between save and query.
     */
    private final Map<String, Map<Object, Set<K>>> indexes;

    InMemoryRepository(EntityDescriptor<K, V> descriptor, StorageLog log) {
        this(descriptor, log, null, null);
    }

    InMemoryRepository(EntityDescriptor<K, V> descriptor, StorageLog log,
                       ChangeFeedSupport changeFeed, String originId) {
        this.descriptor = descriptor;
        this.log        = log;
        this.changeFeed = changeFeed;
        this.originId   = originId;

        this.hintsByPath = new HashMap<>();
        this.indexes     = new ConcurrentHashMap<>();
        for (IndexHint hint : descriptor.indexes()) {
            this.hintsByPath.put(hint.fieldPath(), hint);
            this.indexes.put(hint.fieldPath(), new ConcurrentHashMap<>());
        }
    }

    // ------------------------------------------------------------------
    //  CRUD
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        V v = store.get(key);
        return CompletableFuture.completedFuture(v == null ? Optional.empty() : Optional.of(deepCopy(v)));
    }

    @Override
    public CompletableFuture<List<V>> findMany(Collection<K> keys) {
        List<V> result = new ArrayList<>(keys.size());
        for (K key : keys) {
            V v = store.get(key);
            if (v != null) result.add(deepCopy(v));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, descriptor.collection());
        if (reject != null) return reject;
        synchronized (this) {
            V copy = deepCopy(entity);
            V existing = store.get(key);
            store.put(key, copy);
            if (existing != null) removeFromIndexes(key, existing);
            addToIndexes(key, copy);
        }
        log.saved(descriptor.collection(), key, entity);
        emitSave(key, entity);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        for (V entity : entities) {
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(descriptor.keyExtractor().apply(entity), descriptor.collection());
            if (reject != null) return reject;
        }
        long startMs = System.currentTimeMillis();
        long count = entities.size();
        synchronized (this) {
            for (V entity : entities) {
                K key = descriptor.keyExtractor().apply(entity);
                V copy = deepCopy(entity);
                V existing = store.get(key);
                store.put(key, copy);
                if (existing != null) removeFromIndexes(key, existing);
                addToIndexes(key, copy);
            }
        }
        log.savedBatch(descriptor.collection(), count, System.currentTimeMillis() - startMs);
        for (V entity : entities) {
            emitSave(descriptor.keyExtractor().apply(entity), entity);
        }
        return CompletableFuture.completedFuture(null);
    }

    private V deepCopy(V entity) {
        return descriptor.codec().decode(descriptor.codec().encode(entity));
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        boolean existed;
        synchronized (this) {
            V previous = store.remove(key);
            if (previous != null) removeFromIndexes(key, previous);
            existed = previous != null;
        }
        log.deleted(descriptor.collection(), key, existed);
        if (existed) emitDelete(key);
        return CompletableFuture.completedFuture(existed);
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.completedFuture(store.containsKey(key));
    }

    @Override
    public CompletableFuture<Long> count() {
        return CompletableFuture.completedFuture((long) store.size());
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        Map<K, Long> result = new HashMap<>();
        Function<V, Long> getter = descriptor.versionGetter();
        for (K key : keys) {
            V v = store.get(key);
            if (v == null) continue;
            long version = 0L;
            if (getter != null) {
                Long got = getter.apply(v);
                version = got != null ? got : 0L;
            }
            result.put(key, version);
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        List<V> snapshot = new ArrayList<>(store.size());
        for (V v : store.values()) snapshot.add(deepCopy(v));
        return CompletableFuture.completedFuture(snapshot.stream());
    }

    // ------------------------------------------------------------------
    //  Index queries
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
        // Intersect candidate-key sets across all conditions.
        long startMs = System.currentTimeMillis();

        Set<K> candidates = null;
        for (Query.Condition condition : query.conditions()) {
            IndexHint hint = hintsByPath.get(condition.fieldPath());
            if (hint == null) {
                throw new IllegalArgumentException(
                    "InMemory: field '" + condition.fieldPath() + "' is not indexed. "
                    + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
            }
            Set<K> hits = evaluateCondition(condition, hint);
            candidates = (candidates == null) ? new LinkedHashSet<>(hits)
                                              : intersect(candidates, hits);
            if (candidates.isEmpty()) break; // short-circuit
        }
        validateQueryOptions(options);

        List<V> result = new ArrayList<>(query.conditions().isEmpty() ? store.size() : (candidates == null ? 0 : candidates.size()));
        if (query.conditions().isEmpty()) {
            for (V v : store.values()) {
                if (v != null) result.add(deepCopy(v));
            }
        } else if (candidates != null) {
            for (K k : candidates) {
                V v = store.get(k);
                if (v != null) result.add(deepCopy(v));
            }
        }
        result = applyQueryOptions(result, options);

        log.queried(descriptor.collection(), query, result.size(), System.currentTimeMillis() - startMs);
        return CompletableFuture.completedFuture(result);
    }

    private void validateQueryOptions(QueryOptions options) {
        if (!options.hasOrder()) {
            return;
        }
        if (!hintsByPath.containsKey(options.orderBy())) {
            throw new IllegalArgumentException(
                "InMemory: order field '" + options.orderBy() + "' is not indexed. "
                + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
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

    // ------------------------------------------------------------------
    //  Internal: maintain indexes
    // ------------------------------------------------------------------

    private void addToIndexes(K key, V entity) {
        if (indexes.isEmpty()) return;
        JsonNode tree = IndexValueExtractor.toTree(entity);
        for (IndexHint hint : hintsByPath.values()) {
            Object value = IndexValueExtractor.extract(tree, hint);
            if (value == null) continue;
            indexes.get(hint.fieldPath())
                .computeIfAbsent(value, __ -> ConcurrentHashMap.newKeySet())
                .add(key);
        }
    }

    private void removeFromIndexes(K key, V entity) {
        if (indexes.isEmpty()) return;
        JsonNode tree = IndexValueExtractor.toTree(entity);
        for (IndexHint hint : hintsByPath.values()) {
            Object value = IndexValueExtractor.extract(tree, hint);
            if (value == null) continue;
            Map<Object, Set<K>> byValue = indexes.get(hint.fieldPath());
            Set<K> keys = byValue.get(value);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) byValue.remove(value);
            }
        }
    }

    private Set<K> evaluateCondition(Query.Condition condition, IndexHint hint) {
        Map<Object, Set<K>> byValue = indexes.get(hint.fieldPath());
        switch (condition.op()) {
            case EQ: {
                Object key = IndexValueExtractor.normalizeQueryValue(condition.value(), hint);
                Set<K> hit = byValue.get(key);
                return hit == null ? Collections.emptySet() : new LinkedHashSet<>(hit);
            }
            case IN: {
                Set<K> union = new LinkedHashSet<>();
                for (Object v : condition.inValues()) {
                    Object key = IndexValueExtractor.normalizeQueryValue(v, hint);
                    Set<K> hit = byValue.get(key);
                    if (hit != null) union.addAll(hit);
                }
                return union;
            }
            case RANGE: {
                // No sorted-map optimisation; do a linear pass since the InMemory map is unsorted.
                Set<K> union = new LinkedHashSet<>();
                Object from = IndexValueExtractor.normalizeQueryValue(condition.rangeFrom(), hint);
                Object to   = IndexValueExtractor.normalizeQueryValue(condition.rangeTo(),   hint);
                for (Map.Entry<Object, Set<K>> e : byValue.entrySet()) {
                    if (inRange(e.getKey(), from, to)) union.addAll(e.getValue());
                }
                return union;
            }
            default:
                return Collections.emptySet();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean inRange(Object value, Object from, Object to) {
        if (!(value instanceof Comparable)) return false;
        Comparable cmp = (Comparable) value;
        if (from != null && cmp.compareTo(from) < 0) return false;
        if (to   != null && cmp.compareTo(to)   > 0) return false;
        return true;
    }

    private Set<K> intersect(Set<K> a, Set<K> b) {
        Set<K> out = new LinkedHashSet<>(Math.min(a.size(), b.size()));
        Set<K> small = a.size() <= b.size() ? a : b;
        Set<K> big   = small == a ? b : a;
        for (K k : small) if (big.contains(k)) out.add(k);
        return out;
    }

    // ------------------------------------------------------------------
    //  Change feed
    // ------------------------------------------------------------------

    private void emitSave(K key, V entity) {
        if (changeFeed == null) return;
        changeFeed.emit(new ChangeEvent(
            descriptor.collection(), key.toString(), ChangeOp.SAVE, versionOf(entity), originId));
    }

    private void emitDelete(K key) {
        if (changeFeed == null) return;
        changeFeed.emit(new ChangeEvent(
            descriptor.collection(), key.toString(), ChangeOp.DELETE, ChangeEvent.UNKNOWN_VERSION, originId));
    }

    /** The entity's optimistic-lock version, or {@code -1} when the descriptor is not versioned. */
    private long versionOf(V entity) {
        Function<V, Long> getter = descriptor.versionGetter();
        if (getter == null) return ChangeEvent.UNKNOWN_VERSION;
        Long v = getter.apply(entity);
        return v != null ? v : 0L;
    }
}
