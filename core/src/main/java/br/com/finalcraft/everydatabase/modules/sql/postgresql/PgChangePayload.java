package br.com.finalcraft.everydatabase.modules.sql.postgresql;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.changefeed.ChangePayload;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PostgreSQL change-feed binding: just the {@code NOTIFY}/{@code LISTEN} channel name. The payload
 * format itself is the backend-neutral {@link ChangePayload}, to which encode/decode delegate.
 */
final class PgChangePayload {

    /** The single channel all entity collections publish to. */
    static final String CHANNEL = "everydatabase_changes";

    private PgChangePayload() {}

    static String encode(ObjectMapper mapper, String collection, String key,
                         ChangeOp op, long version, String originId) {
        return ChangePayload.encode(mapper, collection, key, op, version, originId);
    }

    /** Parses a payload back to a {@link ChangeEvent}, or {@code null} if it is malformed. */
    static ChangeEvent decode(ObjectMapper mapper, String payload) {
        return ChangePayload.decode(mapper, payload);
    }
}
