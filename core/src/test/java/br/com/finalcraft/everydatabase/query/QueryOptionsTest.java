package br.com.finalcraft.everydatabase.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryOptions - validation and normalization")
class QueryOptionsTest {

    @Test
    @DisplayName("none() has no order, limit or offset")
    void none_isEmpty() {
        QueryOptions none = QueryOptions.none();
        assertFalse(none.hasOrder());
        assertFalse(none.hasLimit());
        assertFalse(none.hasOffset());
        assertTrue(none.isNone());
    }

    @Test
    @DisplayName("empty builder is equivalent to none()")
    void emptyBuilder_isNone() {
        assertTrue(QueryOptions.builder().build().isNone());
    }

    @Test
    @DisplayName("negative limit is rejected")
    void negativeLimit_throws() {
        assertThrows(IllegalArgumentException.class, () -> QueryOptions.builder().limit(-1));
    }

    @Test
    @DisplayName("negative offset is rejected")
    void negativeOffset_throws() {
        assertThrows(IllegalArgumentException.class, () -> QueryOptions.builder().offset(-1));
    }

    @Test
    @DisplayName("limit 0 means unbounded (hasLimit() is false)")
    void zeroLimit_isUnbounded() {
        QueryOptions opts = QueryOptions.builder().limit(0).build();
        assertFalse(opts.hasLimit());
        assertEquals(0, opts.limit());
    }

    @Test
    @DisplayName("blank or empty orderBy normalizes to no-order")
    void blankOrderBy_isNoOrder() {
        assertFalse(QueryOptions.builder().ascending("   ").build().hasOrder());
        assertFalse(QueryOptions.builder().ascending("").build().hasOrder());
        assertFalse(QueryOptions.builder().orderBy(null, IndexHint.Order.ASCENDING).build().hasOrder());
    }

    @Test
    @DisplayName("orderBy is trimmed; direction defaults to ascending when null")
    void orderBy_trimmedAndDefaulted() {
        QueryOptions opts = QueryOptions.builder().orderBy("  score  ", null).build();
        assertTrue(opts.hasOrder());
        assertEquals("score", opts.orderBy());
        assertEquals(IndexHint.Order.ASCENDING, opts.order());
    }

    @Test
    @DisplayName("descending(field) sets order to DESCENDING")
    void descending_setsOrder() {
        QueryOptions opts = QueryOptions.builder().descending("score").build();
        assertEquals(IndexHint.Order.DESCENDING, opts.order());
        assertTrue(opts.hasOrder());
    }

    @Test
    @DisplayName("positive limit/offset are reported by hasLimit()/hasOffset()")
    void positiveLimitOffset_reported() {
        QueryOptions opts = QueryOptions.builder().limit(10).offset(5).build();
        assertTrue(opts.hasLimit());
        assertTrue(opts.hasOffset());
        assertEquals(10, opts.limit());
        assertEquals(5, opts.offset());
        assertFalse(opts.isNone());
    }
}
