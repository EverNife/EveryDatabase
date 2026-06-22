package br.com.finalcraft.everydatabase.modules.sql.postgresql;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compact JSON payload carried on the PostgreSQL {@code NOTIFY} channel for the change feed. Kept
 * tiny (a {@code NOTIFY} payload is capped at ~8000 bytes): collection, key, op, version, origin.
 */
final class PgChangePayload {

    /** The single channel all entity collections publish to. */
    static final String CHANNEL = "everydatabase_changes";

    private PgChangePayload() {}

    static String encode(ObjectMapper mapper, String collection, String key,
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

    /** Parses a payload back to a {@link ChangeEvent}, or {@code null} if it is malformed. */
    static ChangeEvent decode(ObjectMapper mapper, String payload) {
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
