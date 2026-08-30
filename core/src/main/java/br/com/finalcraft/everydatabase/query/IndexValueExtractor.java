package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.JacksonConfig;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.codec.TreeCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
     * Converts {@code entity} to a tree the way {@code codec} itself would: through
     * {@link TreeCodec#encodeTree} when the codec builds trees directly, otherwise through the
     * mapper it serialises with (see {@link #mapperFor(Codec)}).
     *
     * <p>Asking the codec first matters for correctness, not speed: a codec that produces trees
     * without exposing an {@code ObjectMapper} would otherwise be indexed through the fallback
     * mapper, and the indexed form of a field could disagree with the persisted one.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JsonNode toTree(Object entity, Codec<?> codec) {
        if (entity == null) return null;
        if (codec instanceof TreeCodec) {
            try {
                return ((TreeCodec) codec).encodeTree(entity);
            } catch (Exception e) {
                throw new RuntimeException(
                    "IndexValueExtractor: cannot inspect entity of type "
                    + entity.getClass().getName() + " via its codec's tree form", e);
            }
        }
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
            case DECIMAL:
                return canonicalDecimal(current.isNumber() ? current.decimalValue() : current.asText());
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
            case DATE:
                // Jackson writes a LocalDate as ISO text; a number in that slot names no day.
                return current.isTextual() ? canonicalDate(current.asText()) : null;
            case UUID:
                // Jackson writes a UUID as text; anything else in that slot is not a UUID.
                return current.isTextual() ? canonicalUuid(current.asText()) : null;
            default:
                return null;
        }
    }

    /**
     * Returns {@code value} as the canonical 36-character lowercase UUID string, or {@code null}
     * when it neither is a {@link UUID} nor spells one.
     *
     * <p>Canonicalising is what lets a {@code UUID} and its many textual spellings (uppercase,
     * or the abbreviated groups {@link UUID#fromString} accepts) reach the same stored value on
     * every backend, and what keeps the stored form's lexicographic order equal to the byte-wise
     * order PostgreSQL's native {@code uuid} type compares by.
     */
    public static String canonicalUuid(Object value) {
        if (value instanceof UUID) return value.toString();
        if (value instanceof String) {
            try {
                return UUID.fromString((String) value).toString();
            } catch (IllegalArgumentException notAUuid) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns {@code value} as the canonical ISO-8601 day ({@code "2026-08-29"}) a
     * {@link IndexHint.FieldType#DATE} index stores, or {@code null} when it names no day.
     *
     * <p>Accepts a {@link LocalDate}, anything that carries one in a zone of its own
     * ({@link LocalDateTime}, {@link ZonedDateTime}, {@link OffsetDateTime}), and a string spelling
     * an ISO date or date-time. An {@link Instant} and a {@link Date} are deliberately refused: the
     * day they fall on depends on a zone they do not carry, and picking one here would silently
     * shift a row by a day for half the world.
     */
    public static String canonicalDate(Object value) {
        if (value instanceof LocalDate)      return value.toString();
        if (value instanceof LocalDateTime)  return ((LocalDateTime) value).toLocalDate().toString();
        if (value instanceof ZonedDateTime)  return ((ZonedDateTime) value).toLocalDate().toString();
        if (value instanceof OffsetDateTime) return ((OffsetDateTime) value).toLocalDate().toString();
        if (value instanceof String) {
            String text = ((String) value).trim();
            try {
                return LocalDate.parse(text).toString();
            } catch (DateTimeParseException notADate) {
                // A date-time in that slot names a day too - the entity may have been written when
                // the field still held one.
                try {
                    return LocalDateTime.parse(text).toLocalDate().toString();
                } catch (DateTimeParseException notADateTime) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code value} as the canonical {@link BigDecimal} every backend stores and compares a
     * {@link IndexHint.FieldType#DECIMAL} index by, or {@code null} when it neither is a number nor
     * spells one.
     *
     * <p>Canonical means trailing zeros stripped ({@code 2.50} and {@code 2.5} become one value,
     * zero collapses to {@link BigDecimal#ZERO}), which is what makes the map/scan backends - whose
     * buckets are keyed by {@code equals} - agree with the SQL and Mongo numeric columns, where
     * {@code 2.50 = 2.5} holds by definition. The <em>entity</em> keeps the scale it was saved with;
     * this canonical form exists only inside the index.
     *
     * <p>A {@code double} converts through its shortest decimal rendering ({@code 0.1} stays
     * {@code 0.1}, not the exact binary expansion), so a DECIMAL index answers a query written with
     * a {@code double} literal the way the caller reads it.
     */
    public static BigDecimal canonicalDecimal(Object value) {
        if (value instanceof BigDecimal) return stripZeros((BigDecimal) value);
        if (value instanceof BigInteger) return stripZeros(new BigDecimal((BigInteger) value));
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return stripZeros(BigDecimal.valueOf(d));
        }
        // Any other Number - Integer, Long, AtomicLong, a custom one - through its own text, so a
        // fractional type this method does not know by name is not truncated to its long value.
        if (value instanceof Number) return parseOrNull(value.toString());
        if (value instanceof String) return parseOrNull((String) value);
        return null;
    }

    /** {@code text} as a canonical decimal, or {@code null} when it does not spell a number. */
    private static BigDecimal parseOrNull(String text) {
        try {
            return stripZeros(new BigDecimal(text.trim()));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** {@code stripTrailingZeros} with the zero case pinned, so {@code 0.00} and {@code 0} are one value. */
    private static BigDecimal stripZeros(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    /**
     * Parses stored JSON into the tree {@code codec} wrote, keeping every digit of a decimal number
     * (see {@link JacksonConfig#exactTreeReader(ObjectMapper)}).
     *
     * <p>The scan backends filter and order on this tree instead of on the decoded entity. Parsing
     * it the default way would answer a {@link IndexHint.FieldType#DECIMAL} query from a number
     * rounded to a {@code double}, while SQL and Mongo answer from the exact one - the same query
     * returning different rows depending on where the collection happens to live.
     */
    public static JsonNode readTree(byte[] json, Codec<?> codec) throws IOException {
        return JacksonConfig.exactTreeReader(mapperFor(codec)).readTree(json);
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
     * {@link IndexHint.FieldType} stores, mirroring {@link #extract}. Every backend runs its
     * query values through this, which is what makes one {@link Query} mean the same thing on
     * all of them: the map/scan backends (InMemory, LocalFile, GroupedFile) look up their
     * buckets with {@code equals}, so {@code eq("score", 100L)} against an INT field must look
     * up {@code Integer 100}, not {@code Long 100L}; SQL and Mongo hand the value to a driver
     * that coerces numbers natively but not, say, a {@link UUID} against a stored string.
     *
     * <p>TIMESTAMP accepts {@link Instant}, {@link LocalDateTime} (treated as UTC),
     * {@link ZonedDateTime}, {@link OffsetDateTime}, {@link Date}, any {@link Number}, or an
     * ISO-8601 {@link String}, and converts to epoch-milliseconds. DATE accepts a {@link LocalDate}
     * or anything carrying one in a zone of its own, canonicalised by {@link #canonicalDate}.
     * INT/LONG only convert when the conversion is lossless: for {@code eq}/{@code in} a
     * fractional or out-of-range number can never equal a stored integral value, so it is
     * returned unchanged and simply matches nothing - the same result SQL produces. For
     * {@code range} the bound is left as-is and compared numerically by {@link #rangeContains}
     * (so e.g. an upper bound wider than {@code int} still correctly matches every stored
     * value), mirroring SQL's {@code BETWEEN}.
     *
     * <p>UUID accepts a {@link UUID} or any string spelling of one, canonicalised by
     * {@link #canonicalUuid} so it meets the equally canonicalised stored form. DECIMAL likewise
     * accepts any {@link Number} or a string spelling one, canonicalised by
     * {@link #canonicalDecimal}, so {@code eq("balance", 2.5)} finds a stored {@code 2.50}.
     *
     * <p>A value that cannot be coerced is returned unchanged, never {@code null}ed - it then
     * simply matches nothing, on every backend.
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
            case DECIMAL: {
                BigDecimal canonical = canonicalDecimal(value);
                return canonical != null ? canonical : value;
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
            case DATE: {
                String canonical = canonicalDate(value);
                return canonical != null ? canonical : value;
            }
            case UUID: {
                String canonical = canonicalUuid(value);
                return canonical != null ? canonical : value;
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
     * stored {@code Integer} against a {@code Long} bound wider than the {@code int} range, or a
     * {@link BigDecimal} against a plain {@code double} bound - they are compared <b>numerically</b>
     * instead of through {@code Comparable.compareTo}, which throws a {@code ClassCastException} on
     * mismatched boxed types (the SQL path never hits this because JDBC compares numerically). A
     * value that is not {@link Comparable}, and a bound of a type the value cannot be compared with
     * at all, both match nothing.
     */
    public static boolean rangeContains(Object value, Object from, Object to) {
        if (!(value instanceof Comparable)) return false;
        if (from != null) {
            Integer c = compareForRange(value, from);
            if (c == null || c < 0) return false;
        }
        if (to != null) {
            Integer c = compareForRange(value, to);
            if (c == null || c > 0) return false;
        }
        return true;
    }

    /**
     * Compares a stored value with a range bound, or {@code null} when the two cannot be compared at
     * all - a bound that did not coerce to the hint's type (an unparseable string against a numeric
     * index, say). The caller reads that as "no match", which is what {@link #normalizeQueryValue}
     * promises such a value everywhere: SQL binds it as {@code NULL} and Mongo meets no document, so
     * a scan raising {@code ClassCastException} would be the one backend to answer differently.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Integer compareForRange(Object value, Object bound) {
        if (value instanceof Number && bound instanceof Number && value.getClass() != bound.getClass()) {
            Number a = (Number) value, b = (Number) bound;
            if (a instanceof BigDecimal || b instanceof BigDecimal) {
                BigDecimal x = canonicalDecimal(a), y = canonicalDecimal(b);
                return (x == null || y == null) ? null : x.compareTo(y);
            }
            boolean floating = a instanceof Double || a instanceof Float
                    || b instanceof Double || b instanceof Float;
            return floating ? Double.compare(a.doubleValue(), b.doubleValue())
                            : Long.compare(a.longValue(), b.longValue());
        }
        if (!value.getClass().isInstance(bound)) return null;
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
        if (value instanceof ZonedDateTime)  return ((ZonedDateTime) value).toInstant().toEpochMilli();
        if (value instanceof OffsetDateTime) return ((OffsetDateTime) value).toInstant().toEpochMilli();
        if (value instanceof Date)       return ((Date) value).getTime();
        if (value instanceof Number)     return ((Number) value).longValue();
        if (value instanceof String)     return tryParseTimestamp((String) value);
        return null;
    }

    private static Long tryParseTimestamp(String s) {
        if (s == null || s.isEmpty()) return null;
        // Try Instant (has 'Z' or offset) first, then a zoned/offset date-time - which is what a
        // ZonedDateTime field looks like, zone id and all - then bare LocalDateTime (UTC assumed).
        try { return Instant.parse(s).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return ZonedDateTime.parse(s).toInstant().toEpochMilli(); }
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
