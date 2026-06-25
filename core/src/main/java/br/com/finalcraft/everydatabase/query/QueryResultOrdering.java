package br.com.finalcraft.everydatabase.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Applies {@link QueryOptions} ordering and pagination to an already-matched, in-memory result
 * list. Shared by the scan-based backends (InMemory, LocalFile, GroupedFile) so they order and
 * paginate with the same, cross-backend-consistent semantics the SQL and Mongo backends produce:
 *
 * <ul>
 *   <li>NULL / missing order values sort as the smallest value - first ascending, last descending.</li>
 *   <li>Ties on the order field are broken by the entity key (always ascending), so a paged result
 *       is stable and identical across backends. Pagination without an order field is ordered by
 *       key as well, so {@code limit}/{@code offset} alone is still deterministic.</li>
 * </ul>
 *
 * <p>The order value and the key are each extracted once per entity (decorate-sort-undecorate),
 * so a full scan does not re-serialise every entity on each comparison.
 *
 * <p>Internal support class; not part of the stable public API.
 */
public final class QueryResultOrdering {

    private QueryResultOrdering() {}

    /**
     * Validates that an ordering field, if requested, is a declared index.
     *
     * @throws IllegalArgumentException if {@code options} orders by a field absent from {@code hintsByPath}
     */
    public static void validateOrderField(QueryOptions options, Map<String, IndexHint> hintsByPath, String backend) {
        if (options.hasOrder() && !hintsByPath.containsKey(options.orderBy())) {
            throw new IllegalArgumentException(
                backend + ": order field '" + options.orderBy() + "' is not indexed. "
                + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
        }
    }

    /**
     * Orders (when requested) and paginates {@code matched}, returning an independent list.
     *
     * @param matched      the entities that already matched the query
     * @param options      ordering and pagination controls
     * @param hintsByPath  declared index hints, keyed by field path
     * @param keyExtractor reads an entity's key (compared by its {@code toString()} to mirror how
     *                     SQL/Mongo persist the key) for stable tie-breaking
     */
    public static <V> List<V> apply(List<V> matched, QueryOptions options,
                                    Map<String, IndexHint> hintsByPath,
                                    Function<? super V, ?> keyExtractor) {
        boolean paginating = options.hasOffset() || options.hasLimit();
        List<V> ordered;
        if (options.hasOrder()) {
            ordered = sorted(matched, hintsByPath.get(options.orderBy()),
                options.order() == IndexHint.Order.DESCENDING, keyExtractor);
        } else if (paginating) {
            // Stable pagination needs a deterministic order even without an explicit sort field.
            ordered = sorted(matched, null, false, keyExtractor);
        } else {
            return matched;
        }
        if (!paginating) {
            return ordered;
        }
        int from = Math.min(options.offset(), ordered.size());
        int to = options.hasLimit() ? Math.min(from + options.limit(), ordered.size()) : ordered.size();
        return new ArrayList<>(ordered.subList(from, to));
    }

    private static <V> List<V> sorted(List<V> matched, IndexHint hint, boolean descending,
                                      Function<? super V, ?> keyExtractor) {
        List<Decorated<V>> decorated = new ArrayList<>(matched.size());
        for (V v : matched) {
            Object orderValue = hint == null ? null : IndexValueExtractor.extract(IndexValueExtractor.toTree(v), hint);
            Object key = keyExtractor.apply(v);
            decorated.add(new Decorated<>(v, orderValue, key == null ? null : key.toString()));
        }
        decorated.sort((a, b) -> {
            int c = compareNullLeast(a.orderValue, b.orderValue);
            if (descending) c = -c;
            if (c != 0) return c;
            return compareNullLeast(a.key, b.key);   // tie-break is always ascending, independent of direction
        });
        List<V> out = new ArrayList<>(matched.size());
        for (Decorated<V> d : decorated) out.add(d.value);
        return out;
    }

    /** Natural-order comparison treating {@code null} as the smallest value. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareNullLeast(Object left, Object right) {
        if (left == right) return 0;       // includes both-null
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof Comparable && right instanceof Comparable) {
            return ((Comparable) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static final class Decorated<V> {
        final V value;
        final Object orderValue;
        final String key;

        Decorated(V value, Object orderValue, String key) {
            this.value = value;
            this.orderValue = orderValue;
            this.key = key;
        }
    }
}
