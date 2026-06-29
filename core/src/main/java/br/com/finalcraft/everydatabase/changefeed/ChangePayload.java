package br.com.finalcraft.everydatabase.changefeed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compact JSON wire format for a {@link ChangeEvent}, shared by every change-feed transport (a
 * PostgreSQL {@code NOTIFY} payload, a Redis/Valkey pub/sub message, ...). It carries only the
 * event's metadata - collection, key, op, version, origin - never entity content, the same privacy
 * posture as the rest of the change feed.
 *
 * <p>Kept tiny (some transports cap the payload, e.g. a PostgreSQL {@code NOTIFY} is ~8000 bytes):
 * the field names are single letters - {@code c} collection, {@code k} key, {@code op} operation,
 * {@code v} version, {@code o} origin id ({@code o} is omitted when null).
 */
public final class ChangePayload {

    private ChangePayload() {}

    /** Encodes the event's fields to the compact JSON form. */
    public static String encode(ObjectMapper mapper, String collection, String key,
                                ChangeOp op, long version, String originId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("c", collection);
        node.put("k", key);
        node.put("op", op.name());
        node.put("v", version);
        if (originId != null) {
            node.put("o", originId);
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode change payload", e);
        }
    }

    /** Convenience overload that encodes a whole {@link ChangeEvent}. */
    public static String encode(ObjectMapper mapper, ChangeEvent event) {
        return encode(mapper, event.collection(), event.key(), event.op(), event.version(), event.originId());
    }

    /** Parses a payload back to a {@link ChangeEvent}, or {@code null} if it is malformed. */
    public static ChangeEvent decode(ObjectMapper mapper, String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            String collection = node.path("c").asText(null);
            String key        = node.path("k").asText(null);
            String opName     = node.path("op").asText(null);
            if (collection == null || key == null || opName == null) {
                return null;
            }
            ChangeOp op = ChangeOp.valueOf(opName);
            long version = node.path("v").asLong(ChangeEvent.UNKNOWN_VERSION);
            String origin = node.path("o").asText(null);
            return new ChangeEvent(collection, key, op, version, origin);
        } catch (Exception e) {
            return null;
        }
    }
}
