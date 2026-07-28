package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.JacksonConfig;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Fallback mapper for entities whose codec does not expose its own (see
     * {@link #mapperFor(Codec)}). Configured with the same {@link JacksonConfig}
     * profile as the default codecs, so for the common case (default codec) the
     * indexed tree matches the persisted form without any coupling.
     */
    private static final ObjectMapper DEFAULT_MAPPER = JacksonConfig.storageSafe(new JsonMapper());

    private IndexValueExtractor() {}

    /**
     * Resolves the Jackson mapper to build the index tree for entities serialised by
     * {@code codec}: the codec's own mapper when it is {@link ObjectMapperAware} (so the
     * indexed form of a field can never disagree with the persisted form, even under
     * custom modules/serialisers), otherwise the shared {@link #DEFAULT_MAPPER}.
     */
    public static ObjectMapper mapperFor(Codec<?> codec) {
        return codec instanceof ObjectMapperAware ? ((ObjectMapperAware) codec).objectMapper() : DEFAULT_MAPPER;
    }

    /**
     * Converts {@code entity} to a Jackson {@link JsonNode} tree using the
     * {@link #DEFAULT_MAPPER}. Prefer {@link #toTree(Object, Codec)} where the codec is
     * known, so a custom codec mapper is honoured.
     */
    public static JsonNode toTree(Object entity) {
        return toTree(entity, DEFAULT_MAPPER);
    }

    /**
     * Converts {@code entity} to a tree using the mapper {@code codec} serialises with
     * (see {@link #mapperFor(Codec)}).
     */
    public static JsonNode toTree(Object entity, Codec<?> codec) {
        return toTree(entity, mapperFor(codec));
    }

    /**
     * Converts {@code entity} to a Jackson {@link JsonNode} tree using an explicit mapper.
     * Throws {@link RuntimeException} only if Jackson cannot inspect the entity at all.
     */
    public static JsonNode toTree(Object entity, ObjectMapper mapper) {
        if (entity == null) return null;
        try {
            return mapper.valueToTree(entity);
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
    //  In-memory matching (the scan backends' query engine)
    // ------------------------------------------------------------------

    /**
     * Whether {@code tree} satisfies every condition of {@code query} (conditions are ANDed).
     *
     * <p>This is the query engine of the backends that have no real index (LocalFile, GroupedFile):
     * they read the stored document, extract each queried field from its tree and compare with the
     * very same coercion rules {@link #extract} and {@link #normalizeQueryValue} apply on the indexed
     * backends, so a query answered by a scan agrees with the one answered by SQL or Mongo.
     *
     * <p>Every condition's field path must be present in {@code hintsByPath}: callers reject
     * undeclared fields up front so a query keeps working when the storage is swapped.
     *
     * @param tree        the stored entity as a Jackson tree; {@code null} satisfies no condition
     * @param query       the conditions to test
     * @param hintsByPath the declared index hints, by field path
     */
    public static boolean matchesAll(JsonNode tree, Query query, Map<String, IndexHint> hintsByPath) {
        for (Query.Condition c : query.conditions()) {
            IndexHint hint = hintsByPath.get(c.fieldPath());
            if (!matchesCondition(extract(tree, hint), c, hint)) return false;
        }
        return true;
    }

    private static boolean matchesCondition(Object actual, Query.Condition c, IndexHint hint) {
        switch (c.op()) {
            case EQ:
                return Objects.equals(actual, normalizeQueryValue(c.value(), hint));
            case IN:
                for (Object v : c.inValues()) {
                    if (Objects.equals(actual, normalizeQueryValue(v, hint))) return true;
                }
                return false;
            case RANGE:
                return rangeContains(actual,
                    normalizeQueryValue(c.rangeFrom(), hint),
                    normalizeQueryValue(c.rangeTo(),   hint));
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    //  Query-parameter normalisation
    // ------------------------------------------------------------------

    /**
     * Coerces a query parameter value to the Java type the hint's
     * {@link IndexHint.FieldType} stores, mirroring {@link #extract}. This is what lets
     * the map/scan backends (InMemory, LocalFile, GroupedFile) match SQL/Mongo semantics:
     * their index lookups compare with {@code equals}, so {@code eq("score", 100L)}
     * against an INT field must look up {@code Integer 100}, not {@code Long 100L}.
     * (SQL and Mongo don't need this - JDBC and BSON coerce natively.)
     *
     * <p>TIMESTAMP accepts {@link Instant}, {@link LocalDateTime} (treated as UTC), any
     * {@link Number}, or an ISO-8601 {@link String}, and converts to epoch-milliseconds.
     * INT/LONG only convert when the conversion is lossless: for {@code eq}/{@code in} a
     * fractional or out-of-range number can never equal a stored integral value, so it is
     * returned unchanged and simply matches nothing - the same result SQL produces. For
     * {@code range} the bound is left as-is and compared numerically by {@link #rangeContains}
     * (so e.g. an upper bound wider than {@code int} still correctly matches every stored
     * value), mirroring SQL's {@code BETWEEN}.
     *
     * <p>A value that cannot be coerced is returned unchanged, never {@code null}ed.
     */
    public static Object normalizeQueryValue(Object value, IndexHint hint) {
        if (value == null) return null;
        switch (hint.fieldType()) {
            case TIMESTAMP: {
                Long millis = toEpochMilli(value);
                return millis != null ? millis : value;
            }
            case INT: {
                if (value instanceof Integer) return value;
                Long integral = toIntegralOrNull(value);
                return integral != null && integral >= Integer.MIN_VALUE && integral <= Integer.MAX_VALUE
                    ? Integer.valueOf(integral.intValue()) : value;
            }
            case LONG: {
                if (value instanceof Long) return value;
                Long integral = toIntegralOrNull(value);
                return integral != null ? integral : value;
            }
            case DOUBLE: {
                if (value instanceof Double) return value;
                if (value instanceof Number) return ((Number) value).doubleValue();
                if (value instanceof String) {
                    Double parsed = tryParseDouble((String) value);
                    return parsed != null ? parsed : value;
                }
                return value;
            }
            case BOOLEAN: {
                if (value instanceof Boolean) return value;
                if (value instanceof String) {
                    String s = (String) value;
                    if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
                }
                return value;
            }
            case STRING:
                return value instanceof String ? value : String.valueOf(value);
            default:
                return value;
        }
    }

    /**
     * Inclusive range test used by the scan backends (InMemory, LocalFile, GroupedFile), matching
     * SQL's numeric {@code BETWEEN}. {@code value} is a stored index-bucket value; {@code from}/{@code to}
     * are normalised query bounds ({@code null} = open end).
     *
     * <p>When a value and a bound are both {@link Number}s of <b>different boxed types</b> - e.g. a
     * stored {@code Integer} against a {@code Long} bound wider than the {@code int} range - they are
     * compared <b>numerically</b> instead of through {@code Comparable.compareTo}, which throws a
     * {@code ClassCastException} on mismatched boxed types (the SQL path never hits this because JDBC
     * compares numerically). A non-{@link Comparable} value matches nothing.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean rangeContains(Object value, Object from, Object to) {
        if (!(value instanceof Comparable)) return false;
        if (from != null && compareForRange(value, from) < 0) return false;
        if (to   != null && compareForRange(value, to)   > 0) return false;
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareForRange(Object value, Object bound) {
        if (value instanceof Number && bound instanceof Number && value.getClass() != bound.getClass()) {
            Number a = (Number) value, b = (Number) bound;
            boolean floating = a instanceof Double || a instanceof Float
                    || b instanceof Double || b instanceof Float;
            return floating ? Double.compare(a.doubleValue(), b.doubleValue())
                            : Long.compare(a.longValue(), b.longValue());
        }
        return ((Comparable) value).compareTo(bound);
    }

    /**
     * Returns {@code value} as a {@code Long} when it is an integral number (or a string
     * of one) with no fractional part; {@code null} when the conversion would lose
     * information.
     */
    private static Long toIntegralOrNull(Object value) {
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return (d == Math.rint(d) && !Double.isInfinite(d)
                    && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE)
                ? Long.valueOf((long) d) : null;
        }
        if (value instanceof String) return tryParseLong((String) value);
        return null;
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
