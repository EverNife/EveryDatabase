package br.com.finalcraft.everydatabase.codec;

import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the enriched default codec configuration ({@link JacksonConfig#storageSafe}),
 * the {@link ObjectMapperAware} capability, and the {@code IndexValueExtractor} pickup of
 * the codec's own mapper.
 */
@DisplayName("codec defaults - JacksonConfig profile, ObjectMapperAware, java.time")
class JacksonCodecDefaultsTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Timey {
        private String name;
        private Instant instant;
        private LocalDate date;
        private Optional<String> nick;
    }

    private static Timey sample() {
        return new Timey("hero", Instant.parse("2026-06-25T10:15:30Z"),
            LocalDate.of(2026, 6, 25), Optional.of("the-one"));
    }

    @Test
    @DisplayName("JacksonJsonCodec exposes its mapper via ObjectMapperAware")
    void jsonCodecIsObjectMapperAware() {
        JacksonJsonCodec<Timey> codec = new JacksonJsonCodec<>(Timey.class);
        assertTrue(codec instanceof ObjectMapperAware);
        assertNotNull(((ObjectMapperAware) codec).objectMapper());
    }

    @Test
    @DisplayName("JacksonYamlCodec exposes its mapper via ObjectMapperAware")
    void yamlCodecIsObjectMapperAware() {
        JacksonYamlCodec<Timey> codec = new JacksonYamlCodec<>(Timey.class);
        assertTrue(codec instanceof ObjectMapperAware);
        assertNotNull(((ObjectMapperAware) codec).objectMapper());
    }

    @Test
    @DisplayName("default codec round-trips java.time and Optional")
    void javaTimeAndOptionalRoundTrip() {
        JacksonJsonCodec<Timey> codec = new JacksonJsonCodec<>(Timey.class);
        Timey original = sample();
        Timey back = codec.decode(codec.encode(original));
        assertEquals(original, back);
    }

    @Test
    @DisplayName("dates serialise as ISO-8601 text, not epoch numbers or {epochSecond,nano}")
    void datesAreIso() {
        JacksonJsonCodec<Timey> codec = new JacksonJsonCodec<>(Timey.class);
        String json = new String(codec.encode(sample()), StandardCharsets.UTF_8);
        assertTrue(json.contains("2026-06-25T10:15:30Z"), json);
        assertTrue(json.contains("\"date\":\"2026-06-25\""), json);
        assertFalse(json.contains("epochSecond"), json);
    }

    @Test
    @DisplayName("unknown properties are tolerated on decode (schema evolution)")
    void unknownPropertiesTolerated() {
        JacksonJsonCodec<Timey> codec = new JacksonJsonCodec<>(Timey.class);
        byte[] withExtra = "{\"name\":\"hero\",\"sinceRemovedField\":42}".getBytes(StandardCharsets.UTF_8);
        Timey back = codec.decode(withExtra);   // must not throw
        assertEquals("hero", back.getName());
    }

    @Test
    @DisplayName("IndexValueExtractor.mapperFor picks the codec's mapper, else the shared default")
    void mapperForPicksCodecMapper() {
        ObjectMapper custom = new ObjectMapper();
        JacksonJsonCodec<Timey> jacksonCodec = new JacksonJsonCodec<>(Timey.class, custom);
        assertSame(custom, IndexValueExtractor.mapperFor(jacksonCodec));

        // A non-Jackson codec is not ObjectMapperAware -> the shared default mapper.
        Codec<Timey> opaque = new Codec<Timey>() {
            @Override public byte[] encode(Timey value) { return new byte[0]; }
            @Override public Timey decode(byte[] data) { return null; }
            @Override public String contentType() { return "application/x-thing"; }
        };
        ObjectMapper fallback = IndexValueExtractor.mapperFor(opaque);
        assertNotNull(fallback);
        assertNotSame(custom, fallback);
    }

    @Test
    @DisplayName("LACUNA: java.time written WITHOUT the module ({epochSecond,nano}) no longer reads back")
    void legacyObjectFormInstantIsNotReadable() {
        // Documents the known migration caveat: the previous bare mapper emitted Instant as an
        // {epochSecond,nano} object, which JavaTimeModule cannot read. This is safe in practice
        // because that bare mapper could not deserialize the object form back either - no working
        // round-trip ever existed to produce such data.
        JacksonJsonCodec<Timey> codec = new JacksonJsonCodec<>(Timey.class);
        byte[] legacy = "{\"name\":\"hero\",\"instant\":{\"epochSecond\":1781000130,\"nano\":0}}"
            .getBytes(StandardCharsets.UTF_8);
        assertThrows(CodecException.class, () -> codec.decode(legacy));
    }
}
