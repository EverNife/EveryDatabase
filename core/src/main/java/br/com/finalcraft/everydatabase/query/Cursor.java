package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.codec.JacksonConfig;
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

    /**
     * Reserved {@code orderBy} used by {@link br.com.finalcraft.everydatabase.Repository#scanAll(Cursor, int)}:
     * it means "order by the storage key itself", which every backend can page cheaply without a declared
     * index. Not a real field name, so it never collides with an {@link IndexHint}.
     */
    public static final String STORAGE_KEY_ORDER = "__storage_key__";

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

    /**
     * Begins a full-collection scan ordered by the storage key ascending, from the first row - the start
     * cursor for {@link br.com.finalcraft.everydatabase.Repository#scanAll(Cursor, int)}.
     */
    public static Cursor scan() {
        return start(STORAGE_KEY_ORDER, IndexHint.Order.ASCENDING);
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
     * Rebuilds a cursor from {@link #encode()}. Numeric values come back as {@code Long}/{@code BigDecimal};
     * the repository coerces them to the order field's type before comparing, so the drift is harmless.
     * A fractional value is read as a {@code BigDecimal} rather than a {@code double} so a cursor over a
     * {@link IndexHint.FieldType#DECIMAL} index resumes at the exact row it left off.
     */
    public static Cursor decode(String token) {
        try {
            JsonNode n = JacksonConfig.exactTreeReader(MAPPER).readTree(Base64.getUrlDecoder().decode(token));
            String f = n.get("f").asText();
            IndexHint.Order d = IndexHint.Order.valueOf(n.get("d").asText());
            if (n.get("s").asBoolean()) {
                return start(f, d);
            }
            JsonNode v = n.get("v");
            Object value = (v == null || v.isNull()) ? null
                : v.isNumber() ? (v.isIntegralNumber() ? (Object) v.asLong() : (Object) v.decimalValue())
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
