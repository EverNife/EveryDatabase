package br.com.finalcraft.everydatabase.query;

/**
 * Optional result controls for {@link br.com.finalcraft.everydatabase.Repository#query(Query, QueryOptions)}.
 *
 * <p>Query options affect the returned result set, not which entities match the query.
 * Backends must validate that {@link #orderBy()} references a declared {@link IndexHint}.</p>
 *
 * <h3>Cross-backend ordering contract</h3>
 * Every backend produces the same order for the same options:
 * <ul>
 *   <li>NULL / missing order values sort as the smallest value - first when ascending,
 *       last when descending.</li>
 *   <li>Ties on the order field are broken by the entity key (ascending), so a paged
 *       result is stable and identical regardless of backend. Pagination with only
 *       {@link #limit()}/{@link #offset()} (no order field) is ordered by key as well.</li>
 * </ul>
 */
public final class QueryOptions {
    private static final QueryOptions NONE = new QueryOptions(null, IndexHint.Order.ASCENDING, 0, 0);

    private final String orderBy;
    private final IndexHint.Order order;
    private final int limit;
    private final int offset;

    private QueryOptions(String orderBy, IndexHint.Order order, int limit, int offset) {
        if (limit < 0)  throw new IllegalArgumentException("limit cannot be negative: " + limit);
        if (offset < 0) throw new IllegalArgumentException("offset cannot be negative: " + offset);
        this.orderBy = normalizeOrderBy(orderBy);
        this.order = order == null ? IndexHint.Order.ASCENDING : order;
        this.limit = limit;
        this.offset = offset;
    }

    public static QueryOptions none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String orderBy() {
        return orderBy;
    }

    public IndexHint.Order order() {
        return order;
    }

    /** Maximum number of results; {@code 0} means unbounded (no limit). */
    public int limit() {
        return limit;
    }

    public int offset() {
        return offset;
    }

    public boolean hasOrder() {
        return orderBy != null;
    }

    /** {@code true} when a positive limit was set; a limit of {@code 0} is treated as unbounded. */
    public boolean hasLimit() {
        return limit > 0;
    }

    public boolean hasOffset() {
        return offset > 0;
    }

    public boolean isNone() {
        return !hasOrder() && !hasLimit() && !hasOffset();
    }

    private static String normalizeOrderBy(String orderBy) {
        if (orderBy == null) {
            return null;
        }
        String trimmed = orderBy.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private String orderBy;
        private IndexHint.Order order = IndexHint.Order.ASCENDING;
        private int limit;
        private int offset;

        private Builder() {
        }

        public Builder orderBy(String fieldPath, IndexHint.Order order) {
            this.orderBy = fieldPath;
            this.order = order == null ? IndexHint.Order.ASCENDING : order;
            return this;
        }

        public Builder ascending(String fieldPath) {
            return orderBy(fieldPath, IndexHint.Order.ASCENDING);
        }

        public Builder descending(String fieldPath) {
            return orderBy(fieldPath, IndexHint.Order.DESCENDING);
        }

        /** @param limit maximum results; {@code 0} means unbounded. @throws IllegalArgumentException if negative. */
        public Builder limit(int limit) {
            if (limit < 0) throw new IllegalArgumentException("limit cannot be negative: " + limit);
            this.limit = limit;
            return this;
        }

        /** @param offset rows to skip; must be {@code >= 0}. @throws IllegalArgumentException if negative. */
        public Builder offset(int offset) {
            if (offset < 0) throw new IllegalArgumentException("offset cannot be negative: " + offset);
            this.offset = offset;
            return this;
        }

        public QueryOptions build() {
            return new QueryOptions(orderBy, order, limit, offset);
        }
    }
}
