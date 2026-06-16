package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Query;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Indexed field paths (declared in the descriptor). */
    private final Map<String, IndexHint> hintsByPath;

    /**
     * indexes[fieldPath] -> indexValue -> set of keys.
     * Synchronised on the outer map for cross-field consistency between save and query.
     */
    private final Map<String, Map<Object, Set<K>>> indexes;

    InMemoryRepository(EntityDescriptor<K, V> descriptor, StorageLog log) {
        this.descriptor = descriptor;
        this.log        = log;

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
        return CompletableFuture.completedFuture(null);
    }

    private V deepCopy(V entity) {
        return descriptor.codec().decode(descriptor.codec().encode(entity));
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        synchronized (this) {
            V previous = store.remove(key);
            if (previous != null) removeFromIndexes(key, previous);
            boolean existed = previous != null;
            log.deleted(descriptor.collection(), key, existed);
            return CompletableFuture.completedFuture(existed);
        }
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
    public CompletableFuture<List<V>> query(Query query) {
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

        List<V> result = new ArrayList<>(candidates == null ? 0 : candidates.size());
        if (candidates != null) {
            for (K k : candidates) {
                V v = store.get(k);
                if (v != null) result.add(deepCopy(v));
            }
        }

        log.queried(descriptor.collection(), query, result.size(), System.currentTimeMillis() - startMs);
        return CompletableFuture.completedFuture(result);
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
}
