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

    @Test
    @DisplayName("page(n, size) maps to offset = n*size, limit = size (0-based)")
    void page_mapsToOffsetAndLimit() {
        QueryOptions first = QueryOptions.builder().descending("score").page(0, 20).build();
        assertEquals(0, first.offset());
        assertEquals(20, first.limit());

        QueryOptions third = QueryOptions.builder().page(2, 20).build();
        assertEquals(40, third.offset());
        assertEquals(20, third.limit());
    }

    @Test
    @DisplayName("page() rejects negative page number and non-positive page size")
    void page_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> QueryOptions.builder().page(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> QueryOptions.builder().page(0, 0));
        assertThrows(IllegalArgumentException.class, () -> QueryOptions.builder().page(0, -5));
    }

    @Test
    @DisplayName("withLimit/withOffset return copies keeping the other fields")
    void withLimitOffset_copy() {
        QueryOptions base = QueryOptions.builder().descending("score").page(1, 10).build();
        QueryOptions probe = base.withLimit(11);
        assertEquals(11, probe.limit());
        assertEquals(base.offset(), probe.offset());
        assertEquals("score", probe.orderBy());
        assertEquals(IndexHint.Order.DESCENDING, probe.order());

        QueryOptions advanced = base.withOffset(base.offset() + base.limit());
        assertEquals(20, advanced.offset());
        assertEquals(10, advanced.limit());
    }
}
