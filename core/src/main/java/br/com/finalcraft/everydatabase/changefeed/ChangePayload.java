package br.com.finalcraft.everydatabase.changefeed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

/**
 * Compact JSON wire format for a {@link ChangeEvent}, shared by every change-feed transport (a
 * PostgreSQL {@code NOTIFY} payload, a Redis/Valkey pub/sub message, ...). It carries only the
 * event's metadata - collection, key, op, version, origin - never entity content, the same privacy
 * posture as the rest of the change feed.
 *
 * <p>Kept tiny (some transports cap the payload, e.g. a PostgreSQL {@code NOTIFY} is ~8000 bytes):
 * the field names are single letters - {@code c} collection, {@code k} key, {@code op} operation,
 * {@code v} version, {@code o} origin id, {@code b} backend identity ({@code o} and {@code b} are
 * omitted when null).
 *
 * <p>Reading is tolerant of a missing {@code b}: a payload written before the field existed decodes
 * to a {@link ChangeEvent} with no backend identity, which consumers treat as "applies everywhere" -
 * exactly what such a producer meant.
 */
public final class ChangePayload {

    private ChangePayload() {}

    /** Encodes the event's fields to the compact JSON form, without a backend identity. */
    public static String encode(ObjectMapper mapper, String collection, String key,
                                ChangeOp op, long version, String originId) {
        return write(mapper, node(mapper, collection, key, op, version, originId, null));
    }

    /**
     * Convenience overload that encodes a whole {@link ChangeEvent}, including its backend identity
     * when the event carries one.
     */
    public static String encode(ObjectMapper mapper, ChangeEvent event) {
        return write(mapper, node(mapper, event.collection(), event.key(), event.op(),
                                  event.version(), event.originId(), event.backendId()));
    }

    private static ObjectNode node(ObjectMapper mapper, String collection, String key,
                                   ChangeOp op, long version, String originId, String backendId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("c", collection);
        node.put("k", key);
        node.put("op", op.name());
        node.put("v", version);
        if (originId != null) {
            node.put("o", originId);
        }
        if (backendId != null) {
            node.put("b", backendId);
        }
        return node;
    }

    private static String write(ObjectMapper mapper, ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode change payload", e);
        }
    }

    /** Parses a payload back to a {@link ChangeEvent}, or {@code null} if it is malformed. */
    public static ChangeEvent decode(ObjectMapper mapper, String payload) {
        return decode(mapper, payload, null);
    }

    /**
     * Parses a payload back to a {@link ChangeEvent}, or {@code null} if it is malformed. When
     * {@code onInvalid} is non-null it receives a short reason for a dropped payload, so a transport
     * can log it - a silent drop hides a channel collision with another application publishing to the
     * same channel/{@code NOTIFY}.
     */
    public static ChangeEvent decode(ObjectMapper mapper, String payload, Consumer<String> onInvalid) {
        try {
            JsonNode node = mapper.readTree(payload);
            String collection = node.path("c").asText(null);
            String key        = node.path("k").asText(null);
            String opName     = node.path("op").asText(null);
            if (collection == null || key == null || opName == null) {
                if (onInvalid != null) onInvalid.accept("missing a required field (c/k/op)");
                return null;
            }
            ChangeOp op = ChangeOp.valueOf(opName);
            long version = node.path("v").asLong(ChangeEvent.UNKNOWN_VERSION);
            String origin = node.path("o").asText(null);
            String backendId = node.path("b").asText(null);
            return new ChangeEvent(collection, key, op, version, origin, backendId);
        } catch (Exception e) {
            if (onInvalid != null) {
                onInvalid.accept(e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
            return null;
        }
    }
}
