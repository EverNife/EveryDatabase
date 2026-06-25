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

    // ------------------------------------------------------------------
    //  Keyset (cursor) pagination support
    // ------------------------------------------------------------------

    /**
     * Slices an already fully-ordered match set to the rows strictly after {@code cursor}, taking at
     * most {@code limit}. Used by the scan-based backends, whose {@code query} already returns the
     * full ordered list. Fetches the position by the same total order used everywhere, so it stays
     * consistent with the SQL/Mongo keyset predicates.
     */
    public static <V> Slice<V> keysetSlice(List<V> ordered, Cursor cursor, int limit,
                                           IndexHint hint, Function<? super V, ?> keyExtractor) {
        int from = 0;
        if (!cursor.isStart()) {
            Object cv = coerce(cursor.lastValue(), hint);
            String ck = cursor.lastKey();
            boolean descending = cursor.direction() == IndexHint.Order.DESCENDING;
            while (from < ordered.size()) {
                V row = ordered.get(from);
                Object rv = IndexValueExtractor.extract(IndexValueExtractor.toTree(row), hint);
                if (compareInOrder(rv, keyOf(keyExtractor, row), cv, ck, descending) > 0) break;
                from++;
            }
        }
        int to = Math.min(from + limit, ordered.size());
        List<V> content = new ArrayList<>(ordered.subList(from, to));
        boolean hasNext = to < ordered.size();
        Cursor next = (hasNext && !content.isEmpty())
            ? nextCursorFrom(content.get(content.size() - 1), hint, cursor.direction(), keyExtractor)
            : null;
        QueryOptions order = QueryOptions.builder()
            .orderBy(cursor.orderBy(), cursor.direction()).limit(limit).build();
        return Slice.ofCursor(content, order, hasNext, next);
    }

    /** Builds the cursor positioned right after {@code row} (its order value + key), for the next page. */
    public static <V> Cursor nextCursorFrom(V row, IndexHint hint, IndexHint.Order direction,
                                            Function<? super V, ?> keyExtractor) {
        Object value = IndexValueExtractor.extract(IndexValueExtractor.toTree(row), hint);
        return Cursor.after(hint.fieldPath(), direction, value, keyOf(keyExtractor, row));
    }

    /** Coerces a cursor's stored value (which may have drifted to Long/Double through encode/decode) to the hint's type. */
    public static Object coerce(Object value, IndexHint hint) {
        if (value == null) return null;
        switch (hint.fieldType()) {
            case INT:     return value instanceof Number ? ((Number) value).intValue()  : Integer.valueOf(value.toString());
            case LONG:    return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(value.toString());
            case DOUBLE:  return value instanceof Number ? ((Number) value).doubleValue(): Double.valueOf(value.toString());
            case BOOLEAN: return value instanceof Boolean ? value : Boolean.valueOf(value.toString());
            case TIMESTAMP: return IndexValueExtractor.toEpochMilli(value);
            case STRING:
            default:      return value.toString();
        }
    }

    /** Position of {@code (rv, rk)} relative to {@code (cv, ck)} in the total order (value dir, key asc, null=least). */
    private static int compareInOrder(Object rv, String rk, Object cv, String ck, boolean descending) {
        int c = compareNullLeast(rv, cv);
        if (descending) c = -c;
        if (c != 0) return c;
        return compareNullLeast(rk, ck);
    }

    private static <V> String keyOf(Function<? super V, ?> keyExtractor, V row) {
        Object k = keyExtractor.apply(row);
        return k == null ? null : k.toString();
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
