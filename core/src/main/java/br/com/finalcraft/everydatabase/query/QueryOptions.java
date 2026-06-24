package br.com.finalcraft.everydatabase.query;

/**
 * Optional result controls for {@link br.com.finalcraft.everydatabase.Repository#query(Query, QueryOptions)}.
 *
 * <p>Query options affect the returned result set, not which entities match the query.
 * Backends should validate that {@link #orderBy()} references a declared {@link IndexHint}.</p>
 */
public final class QueryOptions {
    private static final QueryOptions NONE = new QueryOptions(null, IndexHint.Order.ASCENDING, 0, 0);

    private final String orderBy;
    private final IndexHint.Order order;
    private final int limit;
    private final int offset;

    private QueryOptions(String orderBy, IndexHint.Order order, int limit, int offset) {
        this.orderBy = normalizeOrderBy(orderBy);
        this.order = order == null ? IndexHint.Order.ASCENDING : order;
        this.limit = Math.max(0, limit);
        this.offset = Math.max(0, offset);
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

    public int limit() {
        return limit;
    }

    public int offset() {
        return offset;
    }

    public boolean hasOrder() {
        return orderBy != null;
    }

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

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public QueryOptions build() {
            return new QueryOptions(orderBy, order, limit, offset);
        }
    }
}
