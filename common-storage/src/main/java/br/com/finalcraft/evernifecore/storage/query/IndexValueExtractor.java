package br.com.finalcraft.evernifecore.storage.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Reads the value at an {@link IndexHint} field path from an entity, coercing it to
 * the declared {@link IndexHint.FieldType}.
 *
 * <p>The entity is converted to a Jackson tree (via {@code valueToTree}) once per
 * {@code save()} call; per-hint extractions then walk the tree by field-path segments.
 *
 * <p>This is the central piece shared by SQL, Mongo, and InMemory backends so they all
 * use the same path-resolution and type-coercion semantics. A missing path produces
 * {@code null} (the entity simply has no value for that field).
 */
public final class IndexValueExtractor {

    /** Shared mapper used only for {@code valueToTree}; safe for concurrent use. */
    private static final ObjectMapper TREE_MAPPER = JsonMapper.builder().build();

    private IndexValueExtractor() {}

    /**
     * Converts {@code entity} to a Jackson {@link JsonNode} tree.
     * Throws {@link RuntimeException} only if Jackson cannot inspect the entity at all.
     */
    public static JsonNode toTree(Object entity) {
        if (entity == null) return null;
        try {
            return TREE_MAPPER.valueToTree(entity);
        } catch (Exception e) {
            throw new RuntimeException(
                "IndexValueExtractor: cannot inspect entity of type "
                + entity.getClass().getName() + " via Jackson tree", e);
        }
    }

    /**
     * Walks {@code tree} along the dot-separated path in {@code hint} and returns the
     * value coerced to a Java type matching the hint's {@link IndexHint.FieldType}.
     *
     * <p>Returns {@code null} if any segment of the path is missing or the leaf node is
     * itself null.
     *
     * @param tree the entity as a Jackson tree (from {@link #toTree(Object)}); may be {@code null}
     * @param hint the index hint with its field path and target type
     * @return the coerced value, or {@code null} if absent
     */
    public static Object extract(JsonNode tree, IndexHint hint) {
        if (tree == null) return null;

        // Walk the path segment by segment.
        JsonNode current = tree;
        for (String segment : hint.fieldPath().split("\\.")) {
            if (current == null || !current.isObject()) return null;
            current = current.get(segment);
        }
        if (current == null || current.isNull()) return null;

        switch (hint.fieldType()) {
            case STRING:
                // For non-scalars (objects/arrays) we fall back to their JSON representation,
                // which gives a deterministic indexable string. For scalars Jackson's asText()
                // is the natural representation.
                return current.isValueNode() ? current.asText() : current.toString();
            case INT:
                return current.isNumber() ? Integer.valueOf(current.asInt())
                                          : tryParseInt(current.asText());
            case LONG:
                return current.isNumber() ? Long.valueOf(current.asLong())
                                          : tryParseLong(current.asText());
            case DOUBLE:
                return current.isNumber() ? Double.valueOf(current.asDouble())
                                          : tryParseDouble(current.asText());
            case BOOLEAN:
                if (current.isBoolean()) return current.asBoolean();
                String s = current.asText();
                return "true".equalsIgnoreCase(s) ? Boolean.TRUE
                     : "false".equalsIgnoreCase(s) ? Boolean.FALSE
                     : null;
            case TIMESTAMP:
                // Handles three common Jackson serialisation forms:
                //   1. JSON number      -> treat as epoch millis (long field, or Instant via JavaTimeModule)
                //   2. JSON string      -> parse ISO-8601 Instant ("...Z") or LocalDateTime ("...") -> epoch millis
                //   3. JSON object with "epochSecond" field -> Jackson default Instant without JavaTimeModule
                if (current.isNumber()) {
                    // If the number is a decimal (e.g. from Jackson's float representation), convert carefully.
                    return current.isFloatingPointNumber()
                        ? (long) (current.asDouble() * 1000)   // seconds.fraction -> millis
                        : current.asLong();
                }
                if (current.isTextual()) {
                    return tryParseTimestamp(current.asText());
                }
                if (current.isObject()) {
                    // Jackson default: {"epochSecond": X, "nano": Y}
                    JsonNode epochSec = current.get("epochSecond");
                    JsonNode nano     = current.get("nano");
                    if (epochSec != null && epochSec.isNumber()) {
                        long nanos = (nano != null && nano.isNumber()) ? nano.asLong() : 0L;
                        return epochSec.asLong() * 1000L + nanos / 1_000_000L;
                    }
                }
                return null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    //  TIMESTAMP query-parameter normalisation
    // ------------------------------------------------------------------

    /**
     * Converts a query parameter value to epoch-milliseconds ({@code Long}) when the
     * hint's field type is {@link IndexHint.FieldType#TIMESTAMP}.
     *
     * <p>Accepted types: {@link Instant}, {@link LocalDateTime} (treated as UTC),
     * {@link Long}, {@link Integer}, any {@link Number}, or an ISO-8601 {@link String}.
     *
     * <p>For non-TIMESTAMP hints the value is returned unchanged.
     */
    public static Object normalizeQueryValue(Object value, IndexHint hint) {
        if (value == null || hint.fieldType() != IndexHint.FieldType.TIMESTAMP) return value;
        return toEpochMilli(value);
    }

    /**
     * Converts {@code value} to epoch-milliseconds regardless of the concrete type.
     * Returns {@code null} if conversion is not possible.
     */
    public static Long toEpochMilli(Object value) {
        if (value == null)               return null;
        if (value instanceof Long)       return (Long) value;
        if (value instanceof Instant)    return ((Instant) value).toEpochMilli();
        if (value instanceof LocalDateTime) return ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toEpochMilli();
        if (value instanceof Number)     return ((Number) value).longValue();
        if (value instanceof String)     return tryParseTimestamp((String) value);
        return null;
    }

    private static Long tryParseTimestamp(String s) {
        if (s == null || s.isEmpty()) return null;
        // Try Instant (has 'Z' or offset) first, then bare LocalDateTime (no offset = UTC assumed).
        try { return Instant.parse(s).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        return null;
    }

    private static Integer tryParseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static Long tryParseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double tryParseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}
