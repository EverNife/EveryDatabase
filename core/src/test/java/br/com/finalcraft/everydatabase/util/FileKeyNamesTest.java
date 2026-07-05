package br.com.finalcraft.everydatabase.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FileKeyNames#safeStem(String)}.
 */
@DisplayName("FileKeyNames")
class FileKeyNamesTest {

    @Test
    @DisplayName("short lower-case keys keep their verbatim stem")
    void shortKey_isVerbatim() {
        String key = "00000000-0000-0000-0000-000000000001";
        assertEquals(key, FileKeyNames.safeStem(key), "A short UUID key must stay byte-identical");
    }

    @Test
    @DisplayName("two distinct near-limit keys map to two different, length-bounded stems")
    void veryLongKeys_areHashTruncated_andDistinct() {
        // Both keys share a long common prefix and differ only near the very end - a plain
        // truncation would collide them; the hash suffix must keep them apart.
        StringBuilder common = new StringBuilder();
        for (int i = 0; i < 250; i++) common.append('a');
        String keyA = common.toString() + "X";
        String keyB = common.toString() + "Y";

        String stemA = FileKeyNames.safeStem(keyA);
        String stemB = FileKeyNames.safeStem(keyB);

        assertNotEquals(stemA, stemB, "Distinct long keys must not collide on one file");
        assertTrue(stemA.length() <= 200, "Stem must stay within the safe length bound, was " + stemA.length());
        assertTrue(stemB.length() <= 200, "Stem must stay within the safe length bound, was " + stemB.length());
        // Determinism: same key always yields the same stem.
        assertEquals(stemA, FileKeyNames.safeStem(keyA), "safeStem must be a pure function of the key");
    }
}
