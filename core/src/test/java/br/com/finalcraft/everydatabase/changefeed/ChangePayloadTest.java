package br.com.finalcraft.everydatabase.changefeed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-format tests for the compact JSON a change event travels as. */
class ChangePayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("round-trip keeps every field, backend identity included")
    void roundTrip_withBackendId() {
        ChangeEvent event = new ChangeEvent("players", "alice", ChangeOp.SAVE, 7L, "origin-1", "sql:db/mc");

        ChangeEvent decoded = ChangePayload.decode(mapper, ChangePayload.encode(mapper, event));

        assertEquals(event, decoded);
        assertEquals("sql:db/mc", decoded.backendId());
    }

    @Test
    @DisplayName("round-trip of an event without a backend identity keeps it absent")
    void roundTrip_withoutBackendId() {
        ChangeEvent event = new ChangeEvent("players", "alice", ChangeOp.DELETE,
                ChangeEvent.UNKNOWN_VERSION, null);

        String payload = ChangePayload.encode(mapper, event);
        ChangeEvent decoded = ChangePayload.decode(mapper, payload);

        assertFalse(payload.contains("\"b\""), payload);
        assertEquals(event, decoded);
        assertNull(decoded.backendId());
    }

    @Test
    @DisplayName("a payload written before the backend-identity field decodes with none")
    void decode_legacyPayloadWithoutBackendId() {
        String legacy = "{\"c\":\"players\",\"k\":\"alice\",\"op\":\"SAVE\",\"v\":3,\"o\":\"origin-1\"}";

        ChangeEvent decoded = ChangePayload.decode(mapper, legacy);

        assertNull(decoded.backendId(), "an absent field must not become an identity nobody matches");
        assertEquals("players", decoded.collection());
        assertEquals("alice", decoded.key());
        assertEquals(ChangeOp.SAVE, decoded.op());
        assertEquals(3L, decoded.version());
        assertEquals("origin-1", decoded.originId());
    }

    @Test
    @DisplayName("the positional encode overload never emits a backend identity")
    void positionalEncode_hasNoBackendId() {
        String payload = ChangePayload.encode(mapper, "players", "alice", ChangeOp.SAVE, 1L, "origin-1");

        assertFalse(payload.contains("\"b\""), payload);
        assertNull(ChangePayload.decode(mapper, payload).backendId());
    }

    @Test
    @DisplayName("a malformed payload is reported, not thrown")
    void decode_malformedPayload() {
        List<String> reasons = new ArrayList<>();

        assertNull(ChangePayload.decode(mapper, "{\"c\":\"players\"}", reasons::add));
        assertNull(ChangePayload.decode(mapper, "not json at all", reasons::add));
        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).contains("c/k/op"), reasons.get(0));
    }
}
