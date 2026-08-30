package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Scans entity classes for {@link Indexed} annotations and produces the corresponding
 * {@link IndexHint} list. Used internally by
 * {@link EntityDescriptor.Builder#build()}.
 *
 * <p>Scanning walks the entire class hierarchy (from most-derived to least-derived,
 * stopping before {@link Object}), so annotations on superclass fields are also picked up.
 */
final class IndexHintScanner {

    private IndexHintScanner() {}

    /**
     * Returns one {@link IndexHint} for each field annotated with {@link Indexed}
     * in {@code clazz} or any of its superclasses.
     *
     * @throws IllegalArgumentException if an annotated field's type cannot be mapped
     *         to a supported {@link IndexHint.FieldType} and no explicit {@link Indexed#type()}
     *         was provided.
     */
    static List<IndexHint> scan(Class<?> clazz) {
        List<IndexHint> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Indexed ann = field.getAnnotation(Indexed.class);
                if (ann == null) continue;
                result.add(buildHint(field, ann));
            }
            current = current.getSuperclass();
        }
        return result;
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private static IndexHint buildHint(Field field, Indexed ann) {
        String path = ann.path().isEmpty() ? field.getName() : ann.path();

        // ann.type() == void.class means "auto-detect from the field's Java type"
        Class<?> javaType = (ann.type() == void.class) ? field.getType() : ann.type();
        IndexHint.FieldType fieldType = resolveFieldType(javaType, field, path);
        if (ann.path().isEmpty()) rejectUnreadableCombination(field, fieldType);

        IndexHint hint = createBaseHint(path, fieldType);
        if (ann.order() == IndexHint.Order.DESCENDING) hint = hint.asDescending();
        return hint;
    }

    /**
     * Refuses an explicit {@code type} the annotated field can never produce a value for.
     *
     * <p>Only one family of mistake is caught, and it is caught because it is otherwise invisible: a
     * temporal type indexed as the wrong <em>kind</em> of temporal. A {@link LocalDate} declared
     * {@code type = Instant.class} compiles, saves, and indexes {@code null} on every row - the
     * query then returns nothing, forever, with no error to explain why. Every other pairing is left
     * alone: a {@code String} indexed as INT is a value that may well hold digits, and a
     * {@link BigDecimal} indexed as DOUBLE is a deliberate (if lossy) choice.
     *
     * <p>Checked only when the hint reads this field itself. With a {@code path}, the index reads a
     * <em>nested</em> value whose type the annotated field does not describe. A manually declared
     * {@code .index(...)} is not checked either, and that is the way out for the rare entity whose
     * codec writes a field as something its Java type does not suggest - a custom serialiser
     * emitting a {@code LocalDate} as epoch millis, say.
     */
    private static void rejectUnreadableCombination(Field field, IndexHint.FieldType fieldType) {
        Class<?> declared = field.getType();
        String location = field.getDeclaringClass().getSimpleName() + "." + field.getName();

        if (fieldType == IndexHint.FieldType.TIMESTAMP && isTemporal(declared) && !isMoment(declared)) {
            throw new IllegalArgumentException(
                "@Indexed(type = ...) on '" + location + "' asks for a TIMESTAMP index, but a "
                + declared.getSimpleName() + " does not name a moment in time, so every row would "
                + "index as null and the query would match nothing. "
                + (declared == LocalDate.class
                    ? "Drop the type= and let it index as a DATE (that is what a LocalDate is), "
                    : "Index it as text with @Indexed(type = String.class), ")
                + "or store the moment itself in an Instant/LocalDateTime/ZonedDateTime field.");
        }
        if (fieldType == IndexHint.FieldType.DATE && isTemporal(declared) && !carriesADay(declared)) {
            throw new IllegalArgumentException(
                "@Indexed(type = LocalDate.class) on '" + location + "' asks for a DATE index, but a "
                + declared.getSimpleName() + " does not name a calendar day, so every row would "
                + "index as null and the query would match nothing. Index it as text with "
                + "@Indexed(type = String.class), or store the day in a LocalDate field.");
        }
    }

    /** Whether {@code type} is one of the {@code java.time}/{@code java.util} date-ish types. */
    private static boolean isTemporal(Class<?> type) {
        return type.getName().startsWith("java.time.") || type == Date.class;
    }

    /** Whether {@code type} pins an absolute moment (possibly via an assumed UTC, as LocalDateTime does). */
    private static boolean isMoment(Class<?> type) {
        return MOMENT_TYPES.contains(type);
    }

    /** Whether {@code type} carries a calendar day without inventing a zone for it. */
    private static boolean carriesADay(Class<?> type) {
        return DAY_TYPES.contains(type);
    }

    private static final List<Class<?>> MOMENT_TYPES = Arrays.asList(
        Instant.class, LocalDateTime.class, ZonedDateTime.class, OffsetDateTime.class, Date.class);

    private static final List<Class<?>> DAY_TYPES = Arrays.asList(
        LocalDate.class, LocalDateTime.class, ZonedDateTime.class, OffsetDateTime.class);

    private static IndexHint createBaseHint(String path, IndexHint.FieldType type) {
        switch (type) {
            case STRING:    return IndexHint.string(path);
            case INT:       return IndexHint.integer(path);
            case LONG:      return IndexHint.bigInt(path);
            case DOUBLE:    return IndexHint.decimal(path);
            case DECIMAL:   return IndexHint.bigDecimal(path);
            case BOOLEAN:   return IndexHint.bool(path);
            case TIMESTAMP: return IndexHint.timestamp(path);
            case DATE:      return IndexHint.date(path);
            case UUID:      return IndexHint.uuid(path);
            default: throw new IllegalStateException("Unknown FieldType: " + type);
        }
    }

    /**
     * Maps a Java type to its corresponding {@link IndexHint.FieldType}.
     *
     * @param javaType  the Java type to map (from field declaration or {@link Indexed#type()})
     * @param field     the annotated field (used only for error messages)
     * @param path      the resolved index path (used only for error messages)
     * @throws IllegalArgumentException if the type is not supported
     */
    private static IndexHint.FieldType resolveFieldType(Class<?> javaType, Field field, String path) {
        if (javaType == String.class)                                  return IndexHint.FieldType.STRING;
        if (javaType == char.class    || javaType == Character.class)  return IndexHint.FieldType.STRING;
        if (javaType.isEnum())                                         return IndexHint.FieldType.STRING;
        if (javaType == int.class     || javaType == Integer.class
         || javaType == short.class   || javaType == Short.class
         || javaType == byte.class    || javaType == Byte.class)       return IndexHint.FieldType.INT;
        if (javaType == long.class    || javaType == Long.class)       return IndexHint.FieldType.LONG;
        if (javaType == float.class   || javaType == Float.class
         || javaType == double.class  || javaType == Double.class)     return IndexHint.FieldType.DOUBLE;
        if (javaType == BigDecimal.class || javaType == BigInteger.class) return IndexHint.FieldType.DECIMAL;
        if (javaType == boolean.class || javaType == Boolean.class)    return IndexHint.FieldType.BOOLEAN;
        if (MOMENT_TYPES.contains(javaType))                           return IndexHint.FieldType.TIMESTAMP;
        if (javaType == LocalDate.class)                               return IndexHint.FieldType.DATE;
        if (javaType == UUID.class)                                    return IndexHint.FieldType.UUID;

        String location = field.getDeclaringClass().getSimpleName() + "." + field.getName();
        throw new IllegalArgumentException(
            "@Indexed on '" + location + "' (path=\"" + path + "\"): "
            + "cannot auto-detect IndexHint type for Java type '" + javaType.getName() + "'. "
            + "Supported types: String, char/Character, any enum, byte/short/int (and their "
            + "wrappers), long/Long, float/Float, double/Double, BigDecimal, BigInteger, "
            + "boolean/Boolean, Instant, LocalDateTime, ZonedDateTime, OffsetDateTime, "
            + "java.util.Date, LocalDate, UUID. "
            + "A type that serialises as text (URI, Currency, Locale, ZoneId, Duration, ...) is "
            + "indexable with @Indexed(type = String.class); for a value inside a nested object, "
            + "name it with @Indexed(path = \"" + path + ".<field>\", type = ...). Or declare the "
            + "IndexHint manually on the EntityDescriptor builder with "
            + ".index(IndexHint.string(\"" + path + "\")).");
    }
}
