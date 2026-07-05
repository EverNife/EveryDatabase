package br.com.finalcraft.everydatabase.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link IndexValueExtractor#rangeContains}, the type-tolerant inclusive range
 * test shared by the scan backends (InMemory, LocalFile, GroupedFile). It must compare numerically
 * across mismatched boxed types instead of {@code Comparable.compareTo(other)}, which throws
 * {@code ClassCastException} on e.g. {@code Integer.compareTo(Long)}.
 */
class IndexValueExtractorRangeTest {

    @Test
    @DisplayName("Integer value vs a Long bound wider than int does not throw and matches numerically")
    void integerValue_wideLongBound_matchesNumerically() {
        // score=200 (stored Integer) with an upper bound of 5_000_000_000L (out of int range, stays Long)
        assertTrue(IndexValueExtractor.rangeContains(200, 0, 5_000_000_000L),
            "a stored Integer must compare against a wider Long bound numerically, not crash");
        assertTrue(IndexValueExtractor.rangeContains(Integer.MAX_VALUE, 0, 5_000_000_000L));
        // Same cross-type on the lower bound.
        assertFalse(IndexValueExtractor.rangeContains(10, 5_000_000_000L, null),
            "10 is below a lower bound of 5e9");
    }

    @Test
    @DisplayName("mixed integral/floating bounds compare numerically")
    void mixedIntegralAndFloatingBounds() {
        assertTrue(IndexValueExtractor.rangeContains(100, 50L, 150.0));
        assertFalse(IndexValueExtractor.rangeContains(200, 50L, 150.0));
    }

    @Test
    @DisplayName("same-type comparisons keep natural ordering; open ends honoured")
    void sameTypeAndOpenEnds() {
        assertTrue(IndexValueExtractor.rangeContains(100, 100, 100));   // inclusive
        assertTrue(IndexValueExtractor.rangeContains("m", "a", "z"));   // String natural order
        assertTrue(IndexValueExtractor.rangeContains(100, null, null)); // both open = match
        assertFalse(IndexValueExtractor.rangeContains(100, 200, null)); // below open-upper lower bound
    }

    @Test
    @DisplayName("a non-Comparable value matches nothing (never throws)")
    void nonComparableValueMatchesNothing() {
        Object opaque = new Object();
        assertFalse(IndexValueExtractor.rangeContains(opaque, 0, 100));
    }
}
