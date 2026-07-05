package br.com.finalcraft.everydatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the {@link Storages} factory dispatch.
 */
@DisplayName("Storages")
class StoragesTest {

    @Test
    @DisplayName("create(null) throws the documented IllegalArgumentException, not an NPE")
    void create_null_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Storages.create(null));
    }
}
