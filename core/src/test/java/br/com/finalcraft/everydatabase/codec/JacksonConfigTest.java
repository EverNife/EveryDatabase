package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the three {@link JacksonConfig} profiles against one DTO that touches every
 * tricky serialisation case: {@code Instant} (absolute time -&gt; number/ISO),
 * {@code LocalDate}/{@code LocalDateTime} (zoneless -&gt; array/ISO), {@code Duration},
 * legacy {@code java.util.Date} and {@code java.sql.Timestamp} (-&gt; epoch millis/ISO),
 * present/empty {@code Optional}, {@code OptionalInt}, a {@code null} property, and a
 * {@code Map} whose insertion order is non-alphabetical (to prove insertion order survives).
 *
 * <p>All three profiles share the same foundation: the read contract, and <b>no map
 * reordering</b> (entries are written in iteration order). They differ only in date form
 * (base = Jackson numeric/array defaults; storageSafe/compact = ISO-8601) and in whether
 * null/absent properties are dropped (only compact, via {@code NON_ABSENT}).
 *
 * <p>The expected JSON for each profile is written in a text block so the desired shape
 * is visible at a glance; comparison is structural (whitespace- and number-format
 * tolerant) and the order-/null-sensitive details are pinned by separate assertions on
 * the raw compact output.
 */
@DisplayName("JacksonConfig - profile serialisation matrix")
class JacksonConfigTest {

    @JsonPropertyOrder({
        "name", "id", "count",
        "instant", "localDate", "localDateTime", "duration",
        "date", "timestamp",
        "optionalPresent", "optionalEmpty", "optInt",
        "nullName", "counts"
    })
    static class EventDTO {
        public String name;
        public UUID id;
        public int count;
        public Instant instant;
        public LocalDate localDate;
        public LocalDateTime localDateTime;
        public Duration duration;
        public Date date;
        public Timestamp timestamp;
        public Optional<String> optionalPresent;
        public Optional<String> optionalEmpty;
        public OptionalInt optInt;
        public String nullName;
        public Map<String, Integer> counts;
    }

    /** 2026-06-25T14:30:00Z == 1_782_397_800 epoch-seconds == 1_782_397_800_000 epoch-millis. */
    static EventDTO sample() {
        EventDTO d = new EventDTO();
        d.name = "Deploy";
        d.id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        d.count = 7;
        d.instant = Instant.parse("2026-06-25T14:30:00Z");
        d.localDate = LocalDate.of(2026, 6, 25);
        d.localDateTime = LocalDateTime.of(2026, 6, 25, 14, 30, 0);
        d.duration = Duration.ofSeconds(90);
        d.date = Date.from(Instant.parse("2026-06-25T14:30:00Z"));
        d.timestamp = Timestamp.from(Instant.parse("2026-06-25T14:30:00Z"));
        d.optionalPresent = Optional.of("yes");
        d.optionalEmpty = Optional.empty();
        d.optInt = OptionalInt.of(5);
        d.nullName = null;
        d.counts = new LinkedHashMap<>();
        d.counts.put("ok", 40);      // non-alphabetical insertion proves the order is not sorted away
        d.counts.put("errors", 2);
        return d;
    }

    private static final ObjectMapper PARSER = new JsonMapper();

    /** Structural (order-/whitespace-/number-format-tolerant) JSON comparison. */
    private static void assertJsonEquals(String expected, String actual) throws Exception {
        assertEquals(PARSER.readTree(expected), PARSER.readTree(actual),
            () -> "\nexpected: " + expected + "\nactual:   " + actual);
    }

    // ================================================================================
    //  baseReadContract - read contract only; Jackson's date defaults
    // ================================================================================

    @Test
    @DisplayName("baseReadContract -> numeric/array dates, nulls kept, map in insertion order")
    void baseReadContract_writesJacksonDateDefaultsAndKeepsMapOrder() throws Exception {
        String json = JacksonConfig.baseReadContract(new JsonMapper()).writeValueAsString(sample());

        assertJsonEquals("""
            {
              "name": "Deploy",
              "id": "00000000-0000-0000-0000-000000000001",
              "count": 7,
              "instant": 1782397800.000000000,
              "localDate": [2026, 6, 25],
              "localDateTime": [2026, 6, 25, 14, 30],
              "duration": 90.000000000,
              "date": 1782397800000,
              "timestamp": 1782397800000,
              "optionalPresent": "yes",
              "optionalEmpty": null,
              "optInt": 5,
              "nullName": null,
              "counts": { "ok": 40, "errors": 2 }
            }
            """, json);

        // An Instant is a NUMBER here (epoch seconds.nanos), NOT an array - arrays are only
        // for the zoneless local types (LocalDate/LocalDateTime).
        assertTrue(json.contains("\"instant\":1782397800.000000000"), json);
        // No profile reorders maps: the LinkedHashMap insertion order (ok, errors) survives
        // verbatim - alphabetical order would have put errors first.
        assertTrue(json.indexOf("\"ok\"") < json.indexOf("\"errors\""), json);
        // base keeps nulls (no NON_ABSENT).
        assertTrue(json.contains("\"nullName\":null"), json);
    }

    // ================================================================================
    //  storageSafe (the default) - ISO dates, nulls kept, map in insertion order
    // ================================================================================

    @Test
    @DisplayName("storageSafe -> ISO-8601 dates/duration, nulls kept, map in insertion order")
    void storageSafe_isoDatesKeepsMapOrder() throws Exception {
        String json = JacksonConfig.storageSafe(new JsonMapper()).writeValueAsString(sample());

        assertJsonEquals("""
            {
              "name": "Deploy",
              "id": "00000000-0000-0000-0000-000000000001",
              "count": 7,
              "instant": "2026-06-25T14:30:00Z",
              "localDate": "2026-06-25",
              "localDateTime": "2026-06-25T14:30:00",
              "duration": "PT1M30S",
              "date": "2026-06-25T14:30:00.000+00:00",
              "timestamp": "2026-06-25T14:30:00.000+00:00",
              "optionalPresent": "yes",
              "optionalEmpty": null,
              "optInt": 5,
              "nullName": null,
              "counts": { "ok": 40, "errors": 2 }
            }
            """, json);

        assertTrue(json.contains("\"instant\":\"2026-06-25T14:30:00Z\""), json);
        assertTrue(json.contains("\"duration\":\"PT1M30S\""), json);
        assertTrue(json.indexOf("\"ok\"") < json.indexOf("\"errors\""), json);
        assertTrue(json.contains("\"nullName\":null"), json);
    }

    // ================================================================================
    //  compact = storageSafe minus null/absent (same ISO dates + same map order)
    // ================================================================================

    @Test
    @DisplayName("compact -> identical to storageSafe but drops null and absent (Optional.empty) properties")
    void compact_isoDatesDropsNullAndAbsent() throws Exception {
        String json = JacksonConfig.compact(new JsonMapper()).writeValueAsString(sample());

        // Same ISO dates and map order as storageSafe; "nullName" and "optionalEmpty" are gone.
        assertJsonEquals("""
            {
              "name": "Deploy",
              "id": "00000000-0000-0000-0000-000000000001",
              "count": 7,
              "instant": "2026-06-25T14:30:00Z",
              "localDate": "2026-06-25",
              "localDateTime": "2026-06-25T14:30:00",
              "duration": "PT1M30S",
              "date": "2026-06-25T14:30:00.000+00:00",
              "timestamp": "2026-06-25T14:30:00.000+00:00",
              "optionalPresent": "yes",
              "optInt": 5,
              "counts": { "ok": 40, "errors": 2 }
            }
            """, json);

        assertTrue(json.contains("\"instant\":\"2026-06-25T14:30:00Z\""), json);
        assertTrue(json.indexOf("\"ok\"") < json.indexOf("\"errors\""), json);
    }

    // ================================================================================
    //  Focused special cases
    // ================================================================================

    @Test
    @DisplayName("compact drops both a null property and an empty Optional (NON_ABSENT)")
    void compact_dropsNullAndEmptyOptional() throws Exception {
        String json = JacksonConfig.compact(new JsonMapper()).writeValueAsString(sample());
        assertFalse(json.contains("nullName"), "null String must be dropped: " + json);
        assertFalse(json.contains("optionalEmpty"), "empty Optional must be dropped: " + json);
        // Present values are obviously kept.
        assertTrue(json.contains("\"optionalPresent\":\"yes\""), json);
        assertTrue(json.contains("\"optInt\":5"), json);
    }

    @Test
    @DisplayName("Instant: numeric only in base; ISO in storageSafe AND compact (interchange-compatible)")
    void instant_numericInBaseOnly_isoInStorageSafeAndCompact() throws Exception {
        String base    = JacksonConfig.baseReadContract(new JsonMapper()).writeValueAsString(sample());
        String safe    = JacksonConfig.storageSafe(new JsonMapper()).writeValueAsString(sample());
        String compact = JacksonConfig.compact(new JsonMapper()).writeValueAsString(sample());
        assertTrue(base.contains("\"instant\":1782397800.000000000"), base);
        assertTrue(safe.contains("\"instant\":\"2026-06-25T14:30:00Z\""), safe);
        assertTrue(compact.contains("\"instant\":\"2026-06-25T14:30:00Z\""), compact);
    }

    @Test
    @DisplayName("present Optional serialises as the raw value, not its wrapper shape")
    void optionalPresent_unwrapsToRawValue() throws Exception {
        String json = JacksonConfig.storageSafe(new JsonMapper()).writeValueAsString(sample());
        assertTrue(json.contains("\"optionalPresent\":\"yes\""), json);
        assertFalse(json.contains("\"present\":true"), "Optional internals must not leak: " + json);
    }

    @Test
    @DisplayName("every profile preserves map insertion order by default")
    void everyProfilePreservesMapInsertionOrder() throws Exception {
        for (ObjectMapper m : new ObjectMapper[]{
                JacksonConfig.baseReadContract(new JsonMapper()),
                JacksonConfig.storageSafe(new JsonMapper()),
                JacksonConfig.compact(new JsonMapper())}) {
            String json = m.writeValueAsString(sample());
            assertTrue(json.indexOf("\"ok\"") < json.indexOf("\"errors\""),
                "map must keep its insertion order (ok before errors): " + json);
        }
    }

    /** A key type that is deliberately NOT Comparable; serialises as its toString(). */
    static class NonComparableKey {
        final String v;
        NonComparableKey(String v) { this.v = v; }
        @Override public String toString() { return v; }
    }

    static class NonComparableMapHolder {
        public Map<NonComparableKey, Integer> m;
    }

    @Test
    @DisplayName("a Map with a non-Comparable key type serialises under every write profile")
    void nonComparableMapKey_serialisesWithoutFailing() throws Exception {
        NonComparableMapHolder h = new NonComparableMapHolder();
        h.m = new LinkedHashMap<>();
        h.m.put(new NonComparableKey("b"), 1);
        h.m.put(new NonComparableKey("a"), 2);

        // Key ordering is never attempted, so the incomparable key type is a non-event: were a
        // profile to enable it, this would throw InvalidDefinitionException ("Cannot order Map
        // entries by key of incomparable type"), which encode() surfaces as a CodecException on save.
        for (ObjectMapper m : new ObjectMapper[]{
                JacksonConfig.storageSafe(new JsonMapper()),
                JacksonConfig.compact(new JsonMapper())}) {
            String json = m.writeValueAsString(h);
            assertTrue(json.contains("\"a\":2") && json.contains("\"b\":1"),
                "both entries must be present (order not asserted): " + json);
        }
    }

    @Test
    @DisplayName("unknown properties are tolerated on read (schema evolution)")
    void unknownProperties_tolerated() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        EventDTO back = m.readValue("{\"name\":\"Deploy\",\"sinceRemovedField\":123}", EventDTO.class);
        assertEquals("Deploy", back.name);
    }

    @Test
    @DisplayName("storageSafe round-trips every field back to byte-identical JSON")
    void storageSafe_roundTripsByteIdentical() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        String json = m.writeValueAsString(sample());
        EventDTO back = m.readValue(json, EventDTO.class);
        assertEquals(json, m.writeValueAsString(back),
            "re-encoding the decoded DTO must reproduce the exact same JSON");
    }

    @Test
    @DisplayName("every profile shares the read contract: all read what any other wrote")
    void allProfilesReadEachOther() throws Exception {
        ObjectMapper base    = JacksonConfig.baseReadContract(new JsonMapper());
        ObjectMapper safe    = JacksonConfig.storageSafe(new JsonMapper());
        ObjectMapper compact = JacksonConfig.compact(new JsonMapper());

        // A document written with numeric dates (base) and one written with ISO dates
        // (storageSafe/compact) must both deserialise on any reader to the same Instant.
        Instant expected = sample().instant;
        for (ObjectMapper writer : new ObjectMapper[]{base, safe, compact}) {
            String json = writer.writeValueAsString(sample());
            for (ObjectMapper reader : new ObjectMapper[]{base, safe, compact}) {
                EventDTO back = reader.readValue(json, EventDTO.class);
                assertEquals(expected, back.instant,
                    "reader must recover the Instant from any writer's output");
            }
        }
    }

    // ================================================================================
    //  Map order survives a full encode -> decode round trip (the default profile)
    // ================================================================================

    static class StringMapHolder {
        public Map<String, String> segments = new LinkedHashMap<>();
    }

    static class IntMapHolder {
        public Map<Integer, String> byId = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("numeric-looking String keys keep their sequence through encode -> decode")
    void numericStringKeys_keepSequenceThroughRoundTrip() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        StringMapHolder h = new StringMapHolder();
        List<String> inserted = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String key = String.valueOf(i);
            inserted.add(key);
            h.segments.put(key, "line " + i);
        }

        StringMapHolder back = m.readValue(m.writeValueAsString(h), StringMapHolder.class);

        // Sorted-by-key would give 1, 10, 11, 12, 2, ... - the classic silent corruption.
        assertEquals(inserted, new ArrayList<>(back.segments.keySet()));
        assertEquals("line 11", back.segments.get("11"));
    }

    @Test
    @DisplayName("alphabetic String keys inserted out of alphabetical order keep that order")
    void alphabeticKeys_keepInsertionOrderThroughRoundTrip() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        StringMapHolder h = new StringMapHolder();
        List<String> inserted = List.of("zulu", "alpha", "mike", "bravo");
        for (String key : inserted) h.segments.put(key, key.toUpperCase());

        StringMapHolder back = m.readValue(m.writeValueAsString(h), StringMapHolder.class);

        assertEquals(inserted, new ArrayList<>(back.segments.keySet()));
    }

    @Test
    @DisplayName("Integer keys inserted in descending order keep that order")
    void integerKeys_keepDescendingInsertionOrderThroughRoundTrip() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        IntMapHolder h = new IntMapHolder();
        List<Integer> inserted = List.of(30, 20, 10, 5);
        for (Integer key : inserted) h.byId.put(key, "slot " + key);

        IntMapHolder back = m.readValue(m.writeValueAsString(h), IntMapHolder.class);

        assertEquals(inserted, new ArrayList<>(back.byId.keySet()));
    }

    /** Extra properties flattened into the enclosing object by {@link JsonAnyGetter}. */
    static class AnyGetterHolder {
        public String name;
        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnyGetter
        public Map<String, Object> getExtras() { return extras; }

        @JsonAnySetter
        public void put(String key, Object value) { extras.put(key, value); }
    }

    @Test
    @DisplayName("@JsonAnyGetter entries keep their insertion order too")
    void anyGetter_keepsInsertionOrder() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());
        AnyGetterHolder h = new AnyGetterHolder();
        h.name = "sign";
        h.put("3", "third");
        h.put("1", "first");
        h.put("2", "second");

        String json = m.writeValueAsString(h);

        assertTrue(json.indexOf("\"3\"") < json.indexOf("\"1\""), json);
        assertTrue(json.indexOf("\"1\"") < json.indexOf("\"2\""), json);
        assertEquals(List.of("3", "1", "2"),
            new ArrayList<>(m.readValue(json, AnyGetterHolder.class).getExtras().keySet()));
    }

    // ================================================================================
    //  Legacy data (written while the old default sorted keys) still reads back
    // ================================================================================

    @Test
    @DisplayName("a payload whose keys were already sorted still deserialises, values intact")
    void alphabeticallySortedPayload_stillDeserialises() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new JsonMapper());

        // Hand-written exactly as the key-sorting default used to emit it.
        String legacy = "{\"segments\":{\"1\":\"line 1\",\"10\":\"line 10\",\"11\":\"line 11\",\"2\":\"line 2\"}}";
        StringMapHolder back = m.readValue(legacy, StringMapHolder.class);

        assertEquals(4, back.segments.size());
        assertEquals("line 2", back.segments.get("2"));
        assertEquals("line 10", back.segments.get("10"));
        // The order it was written in is what comes back - the original insertion order was
        // never persisted, so it is not recoverable; only the values are.
        assertEquals(List.of("1", "10", "11", "2"), new ArrayList<>(back.segments.keySet()));
    }
}
