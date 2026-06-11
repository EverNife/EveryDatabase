package br.com.finalcraft.evernifecore.storage.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorageLogLevel#passes(StorageLogLevel)} - the single gate every
 * emitted event goes through (specs/SPEC_storage_logging.md, secao 4.2).
 */
@DisplayName("StorageLogLevel.passes() - ordering and the ERROR floor")
class StorageLogLevelTest {

    @Test
    @DisplayName("rank order is OFF < ERROR < WARN < INFO < DEBUG < TRACE")
    void ordinals_followSpecOrder() {
        assertEquals(0, StorageLogLevel.OFF.ordinal());
        assertEquals(1, StorageLogLevel.ERROR.ordinal());
        assertEquals(2, StorageLogLevel.WARN.ordinal());
        assertEquals(3, StorageLogLevel.INFO.ordinal());
        assertEquals(4, StorageLogLevel.DEBUG.ordinal());
        assertEquals(5, StorageLogLevel.TRACE.ordinal());
    }

    @Test
    @DisplayName("ERROR floor: ERROR passes every threshold, including OFF")
    void errorFloor_alwaysPasses() {
        for (StorageLogLevel threshold : StorageLogLevel.values()) {
            assertTrue(StorageLogLevel.ERROR.passes(threshold),
                "ERROR must pass threshold " + threshold + " (floor cannot be disabled)");
        }
    }

    @Test
    @DisplayName("OFF threshold blocks WARN/INFO/DEBUG/TRACE but not ERROR")
    void offThreshold_blocksEverythingExceptError() {
        assertTrue(StorageLogLevel.ERROR.passes(StorageLogLevel.OFF));

        assertFalse(StorageLogLevel.WARN.passes(StorageLogLevel.OFF));
        assertFalse(StorageLogLevel.INFO.passes(StorageLogLevel.OFF));
        assertFalse(StorageLogLevel.DEBUG.passes(StorageLogLevel.OFF));
        assertFalse(StorageLogLevel.TRACE.passes(StorageLogLevel.OFF));
    }

    @Test
    @DisplayName("an event passes thresholds at its own level or more verbose")
    void passes_atOwnLevelOrMoreVerboseThreshold() {
        // Exactly at threshold
        assertTrue(StorageLogLevel.WARN.passes(StorageLogLevel.WARN));
        assertTrue(StorageLogLevel.INFO.passes(StorageLogLevel.INFO));
        assertTrue(StorageLogLevel.TRACE.passes(StorageLogLevel.TRACE));

        // Threshold more verbose than the event
        assertTrue(StorageLogLevel.WARN.passes(StorageLogLevel.INFO));
        assertTrue(StorageLogLevel.INFO.passes(StorageLogLevel.DEBUG));
        assertTrue(StorageLogLevel.DEBUG.passes(StorageLogLevel.TRACE));
    }

    @Test
    @DisplayName("an event is blocked by thresholds less verbose than itself")
    void passes_blockedByLessVerboseThreshold() {
        assertFalse(StorageLogLevel.INFO.passes(StorageLogLevel.WARN));
        assertFalse(StorageLogLevel.DEBUG.passes(StorageLogLevel.INFO));
        assertFalse(StorageLogLevel.TRACE.passes(StorageLogLevel.DEBUG));
        assertFalse(StorageLogLevel.WARN.passes(StorageLogLevel.ERROR));
    }
}
