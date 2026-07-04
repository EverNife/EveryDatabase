package br.com.finalcraft.everydatabase.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Construction-time validation of {@link Query} factories. Null values are rejected
 * eagerly (a sync throw at build time) so no backend ever sees them - SQL {@code = NULL}
 * matches nothing and the map-based backends would NPE on the lookup.
 */
class QueryTest {

    @Test
    @DisplayName("eq(field, null) throws IllegalArgumentException")
    void eq_nullValue_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> Query.eq("name", null));
        assertTrue(ex.getMessage().contains("name"), "message should carry the field path");
    }

    @Test
    @DisplayName("in(field, null collection) throws IllegalArgumentException")
    void in_nullCollection_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Query.in("name", (Collection<?>) null));
    }

    @Test
    @DisplayName("in(field, values...) with a null element throws IllegalArgumentException")
    void in_nullElement_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Query.in("name", "Alice", null, "Bob"));
        assertThrows(IllegalArgumentException.class,
            () -> Query.in("name", Arrays.asList("Alice", null)));
    }

    @Test
    @DisplayName("valid factories still build: eq, in, range (open ends allowed), and()")
    void validFactories_build() {
        assertEquals(1, Query.eq("name", "Alice").conditions().size());
        assertEquals(1, Query.in("name", "Alice", "Bob").conditions().size());
        assertEquals(1, Query.in("name", Collections.singletonList("Alice")).conditions().size());
        // range keeps nullable bounds: null = open end, both-null = match-all on the field
        assertEquals(1, Query.range("score", null, 10).conditions().size());
        assertEquals(1, Query.range("score", 10, null).conditions().size());
        assertEquals(2, Query.eq("a", 1).and(Query.eq("b", 2)).conditions().size());
        assertTrue(Query.all().conditions().isEmpty());
    }
}
