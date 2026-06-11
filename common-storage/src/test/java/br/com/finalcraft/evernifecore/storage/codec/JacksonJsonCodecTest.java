package br.com.finalcraft.evernifecore.storage.codec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class JacksonJsonCodecTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Sample {
        private String name;
        private int score;
        private java.util.List<String> tags;
    }

    private static final Sample SAMPLE = new Sample("Petrus", 42, Arrays.asList("a", "b"));

    @Test
    @DisplayName("default codec emits compact JSON (no indentation whitespace)")
    void defaultEncode_isCompact() {
        String json = new String(new JacksonJsonCodec<>(Sample.class).encode(SAMPLE), StandardCharsets.UTF_8);
        assertFalse(json.contains("\n"), "compact output must not contain newlines: " + json);
        assertFalse(json.contains("  "), "compact output must not contain indentation: " + json);
    }

    @Test
    @DisplayName("pretty() codec emits indented JSON")
    void prettyEncode_isIndented() {
        String json = new String(JacksonJsonCodec.pretty(Sample.class).encode(SAMPLE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\n"), "pretty output must contain newlines: " + json);
    }

    @Test
    @DisplayName("compact and pretty round-trip to the same entity, interchangeably")
    void compactAndPretty_roundTripEquivalent() {
        JacksonJsonCodec<Sample> compact = new JacksonJsonCodec<>(Sample.class);
        JacksonJsonCodec<Sample> pretty  = JacksonJsonCodec.pretty(Sample.class);

        assertEquals(SAMPLE, compact.decode(compact.encode(SAMPLE)));
        assertEquals(SAMPLE, pretty.decode(pretty.encode(SAMPLE)));
        // Cross-decode: indentation is cosmetic, both parse the other's output.
        assertEquals(SAMPLE, compact.decode(pretty.encode(SAMPLE)));
        assertEquals(SAMPLE, pretty.decode(compact.encode(SAMPLE)));
    }

    @Test
    @DisplayName("content type and file extension are stable")
    void contentTypeAndExtension() {
        JacksonJsonCodec<Sample> codec = new JacksonJsonCodec<>(Sample.class);
        assertEquals("application/json", codec.contentType());
        assertTrue(codec.isJsonCodec());
    }
}
