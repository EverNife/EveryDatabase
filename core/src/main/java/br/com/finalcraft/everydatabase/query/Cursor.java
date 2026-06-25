package br.com.finalcraft.everydatabase.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An opaque continuation token for keyset (seek) pagination via
 * {@link br.com.finalcraft.everydatabase.Repository#queryAfter(Query, Cursor, int)}.
 *
 * <p>Unlike offset pagination (which scans and discards the first N rows), a cursor remembers the
 * <em>position</em> of the last row seen - the pair {@code (order value, entity key)} - and the next
 * page is "the rows strictly after this position" in the total order (order field + key tie-break).
 * Because that order is total and stable, the position is unambiguous.
 *
 * <p>The order field and direction are baked into the cursor, so a cursor cannot be misused with a
 * different ordering and {@code queryAfter} needs no separate {@link QueryOptions}. Start a sequence
 * with {@link #start(String, IndexHint.Order)}; each returned {@link Slice} yields the next cursor via
 * {@link Slice#nextCursor()}. {@link #encode()}/{@link #decode(String)} make a cursor transportable
 * (a command argument, a GUI button payload) for stateless paging.
 */
public final class Cursor {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String orderBy;
    private final IndexHint.Order direction;
    private final boolean start;
    private final Object lastValue;   // order-field value of the last row seen (may be null)
    private final String lastKey;     // entity key (as String) of the last row seen

    private Cursor(String orderBy, IndexHint.Order direction, boolean start, Object lastValue, String lastKey) {
        this.orderBy = Objects.requireNonNull(orderBy, "orderBy");
        this.direction = direction == null ? IndexHint.Order.ASCENDING : direction;
        this.start = start;
        this.lastValue = lastValue;
        this.lastKey = lastKey;
    }

    /** Begins a keyset sequence ordered by {@code orderBy}/{@code direction}, from the first row. */
    public static Cursor start(String orderBy, IndexHint.Order direction) {
        return new Cursor(orderBy, direction, true, null, null);
    }

    /** A cursor positioned right after the row {@code (lastValue, lastKey)}; built by the repository. */
    public static Cursor after(String orderBy, IndexHint.Order direction, Object lastValue, String lastKey) {
        return new Cursor(orderBy, direction, false, lastValue, lastKey);
    }

    public String orderBy()            { return orderBy; }
    public IndexHint.Order direction() { return direction; }
    /** {@code true} for a fresh sequence with no position yet (no keyset predicate is applied). */
    public boolean isStart()           { return start; }
    public Object lastValue()          { return lastValue; }
    public String lastKey()            { return lastKey; }

    /** A URL-safe base64 token that round-trips through {@link #decode(String)} for stateless transport. */
    public String encode() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("f", orderBy);
            m.put("d", direction.name());
            m.put("s", start);
            m.put("v", lastValue);
            m.put("k", lastKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(m));
        } catch (Exception e) {
            throw new IllegalStateException("Cursor.encode failed", e);
        }
    }

    /**
     * Rebuilds a cursor from {@link #encode()}. Numeric values come back as {@code Long}/{@code Double};
     * the repository coerces them to the order field's type before comparing, so the drift is harmless.
     */
    public static Cursor decode(String token) {
        try {
            JsonNode n = MAPPER.readTree(Base64.getUrlDecoder().decode(token));
            String f = n.get("f").asText();
            IndexHint.Order d = IndexHint.Order.valueOf(n.get("d").asText());
            if (n.get("s").asBoolean()) {
                return start(f, d);
            }
            JsonNode v = n.get("v");
            Object value = (v == null || v.isNull()) ? null
                : v.isNumber() ? (v.isIntegralNumber() ? (Object) v.asLong() : (Object) v.asDouble())
                : v.isBoolean() ? (Object) v.asBoolean()
                : v.asText();
            JsonNode k = n.get("k");
            return after(f, d, value, (k == null || k.isNull()) ? null : k.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cursor.decode failed for token: " + token, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cursor)) return false;
        Cursor c = (Cursor) o;
        return start == c.start && direction == c.direction
            && orderBy.equals(c.orderBy)
            && Objects.equals(lastValue, c.lastValue)
            && Objects.equals(lastKey, c.lastKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderBy, direction, start, lastValue, lastKey);
    }

    @Override
    public String toString() {
        return start
            ? "Cursor{start " + direction + " '" + orderBy + "'}"
            : "Cursor{after (" + lastValue + ", " + lastKey + ") " + direction + " '" + orderBy + "'}";
    }
}
