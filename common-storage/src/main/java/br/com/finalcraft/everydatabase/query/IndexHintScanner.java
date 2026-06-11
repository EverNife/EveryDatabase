package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

        IndexHint hint = createBaseHint(path, fieldType);
        if (ann.order() == IndexHint.Order.DESCENDING) hint = hint.asDescending();
        return hint;
    }

    private static IndexHint createBaseHint(String path, IndexHint.FieldType type) {
        switch (type) {
            case STRING:    return IndexHint.string(path);
            case INT:       return IndexHint.integer(path);
            case LONG:      return IndexHint.bigInt(path);
            case DOUBLE:    return IndexHint.decimal(path);
            case BOOLEAN:   return IndexHint.bool(path);
            case TIMESTAMP: return IndexHint.timestamp(path);
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
        if (javaType == int.class     || javaType == Integer.class)    return IndexHint.FieldType.INT;
        if (javaType == long.class    || javaType == Long.class)       return IndexHint.FieldType.LONG;
        if (javaType == float.class   || javaType == Float.class
         || javaType == double.class  || javaType == Double.class)     return IndexHint.FieldType.DOUBLE;
        if (javaType == boolean.class || javaType == Boolean.class)    return IndexHint.FieldType.BOOLEAN;
        if (javaType == Instant.class || javaType == LocalDateTime.class) return IndexHint.FieldType.TIMESTAMP;

        String location = field.getDeclaringClass().getSimpleName() + "." + field.getName();
        throw new IllegalArgumentException(
            "@Indexed on '" + location + "' (path=\"" + path + "\"): "
            + "cannot auto-detect IndexHint type for Java type '" + javaType.getName() + "'. "
            + "Supported types: String, int/Integer, long/Long, float/Float, double/Double, "
            + "boolean/Boolean, Instant, LocalDateTime. "
            + "For custom object types, specify @Indexed(type = String.class) or another "
            + "supported primitive wrapper, or declare the IndexHint manually on the "
            + "EntityDescriptor builder with .index(IndexHint.string(\"" + path + "\")).");
    }
}
