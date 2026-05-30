package br.com.finalcraft.evernifecore.storage.query;

import java.util.*;

/**
 * Composable query expression used with
 * {@link br.com.finalcraft.evernifecore.storage.Repository#query(Query)}.
 *
 * <p>Queries are built via static factory methods and combined with {@link #and(Query)}:
 * <pre>{@code
 * // Single equality
 * repo.query(Query.eq("type", "ENABLED"))
 *
 * // Composite (intersection - AND)
 * repo.query(Query.eq("owner", uuid).and(Query.eq("world", "world_nether")))
 *
 * // Range (inclusive)
 * repo.query(Query.range("score", 100, 200))
 *
 * // IN-list
 * repo.query(Query.in("status", Arrays.asList("ENABLED", "PENDING")))
 *
 * // Composite of different operators
 * repo.query(Query.eq("world", "world").and(Query.range("y", 0, 256)))
 * }</pre>
 *
 * <p>Backends translate the {@link #conditions()} list to native operations:
 * <ul>
 *   <li>SQL → {@code WHERE _idx_field = ?} / {@code BETWEEN} / {@code IN}, joined by {@code AND}</li>
 *   <li>MongoDB → {@code Filters.and(Filters.eq(...), Filters.gte(...), ...)}</li>
 *   <li>InMemory → intersection of per-field key sets</li>
 *   <li>LocalFile → full scan + tree-walk filter</li>
 * </ul>
 *
 * <p>All fields referenced by a {@code Query} must be declared as
 * {@link IndexHint} on the {@code EntityDescriptor}, otherwise the backend will
 * throw {@link IllegalArgumentException} at execution time. (LocalFile excepted -
 * it always scans and never throws.)
 */
public final class Query {

    /** Comparison operator for a single condition. */
    public enum Op { EQ, RANGE, IN }

    /** A single field condition inside a query. */
    public static final class Condition {
        private final String        fieldPath;
        private final Op            op;
        private final Object        value;        // for EQ
        private final Object        rangeFrom;    // for RANGE (inclusive, nullable = open lower)
        private final Object        rangeTo;      // for RANGE (inclusive, nullable = open upper)
        private final List<Object>  inValues;     // for IN

        private Condition(String fieldPath, Op op, Object value,
                          Object rangeFrom, Object rangeTo, List<Object> inValues) {
            this.fieldPath = fieldPath;
            this.op        = op;
            this.value     = value;
            this.rangeFrom = rangeFrom;
            this.rangeTo   = rangeTo;
            this.inValues  = inValues;
        }

        public String        fieldPath() { return fieldPath; }
        public Op            op()        { return op; }
        public Object        value()     { return value; }
        public Object        rangeFrom() { return rangeFrom; }
        public Object        rangeTo()   { return rangeTo; }
        public List<Object>  inValues()  { return inValues; }

        @Override
        public String toString() {
            switch (op) {
                case EQ:    return fieldPath + " = " + value;
                case RANGE: return fieldPath + " BETWEEN " + rangeFrom + " AND " + rangeTo;
                case IN:    return fieldPath + " IN " + inValues;
                default:    return "?";
            }
        }
    }

    private final List<Condition> conditions;

    private Query(List<Condition> conditions) {
        this.conditions = Collections.unmodifiableList(conditions);
    }

    /** Returns the list of conditions; all are joined by {@code AND}. */
    public List<Condition> conditions() {
        return conditions;
    }

    // ------------------------------------------------------------------
    //  Factories
    // ------------------------------------------------------------------

    /** {@code fieldPath = value}. */
    public static Query eq(String fieldPath, Object value) {
        return new Query(Collections.singletonList(
            new Condition(fieldPath, Op.EQ, value, null, null, null)
        ));
    }

    /**
     * {@code fieldPath BETWEEN from AND to} (inclusive on both ends).
     * Pass {@code null} for an open end (e.g. {@code range("score", 100, null)} = "score >= 100").
     */
    public static Query range(String fieldPath, Object fromInclusive, Object toInclusive) {
        return new Query(Collections.singletonList(
            new Condition(fieldPath, Op.RANGE, null, fromInclusive, toInclusive, null)
        ));
    }

    /** {@code fieldPath IN (values...)}. */
    public static Query in(String fieldPath, Collection<?> values) {
        List<Object> copy = new ArrayList<>(values);
        return new Query(Collections.singletonList(
            new Condition(fieldPath, Op.IN, null, null, null, Collections.unmodifiableList(copy))
        ));
    }

    /** {@code fieldPath IN (values...)} - varargs convenience. */
    public static Query in(String fieldPath, Object... values) {
        return in(fieldPath, Arrays.asList(values));
    }

    // ------------------------------------------------------------------
    //  Composition - intersection (AND)
    // ------------------------------------------------------------------

    /**
     * Returns a new {@code Query} containing the conditions of {@code this} followed by
     * the conditions of {@code other}, all joined by {@code AND}.
     *
     * <p>No native OR support yet - if you need it, run two queries and union the results
     * client-side.
     */
    public Query and(Query other) {
        List<Condition> merged = new ArrayList<>(this.conditions.size() + other.conditions.size());
        merged.addAll(this.conditions);
        merged.addAll(other.conditions);
        return new Query(merged);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Query{");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(conditions.get(i));
        }
        return sb.append('}').toString();
    }
}
