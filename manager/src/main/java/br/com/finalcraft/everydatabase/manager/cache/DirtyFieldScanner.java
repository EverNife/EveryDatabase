package br.com.finalcraft.everydatabase.manager.cache;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Scans entity classes for the {@link DirtyFlag} annotation and validates the annotated field. Used
 * internally by {@link DirtyAccessor}; not meant to be called by application code.
 *
 * <p>Scanning walks the entire class hierarchy (from most-derived to least-derived, stopping before
 * {@link Object}), mirroring the {@code @OptimisticLock} scanner, so an annotated field on a
 * superclass is also picked up.
 */
public class DirtyFieldScanner {

    protected DirtyFieldScanner() {}

    /**
     * Returns the single field annotated with {@link DirtyFlag} in {@code clazz} or any of its
     * superclasses, already validated and made accessible ({@code setAccessible(true)}), or
     * {@code null} when no field is annotated.
     *
     * @throws IllegalStateException    if more than one field carries the annotation
     * @throws IllegalArgumentException if the annotated field is not {@code boolean}/{@code Boolean},
     *                                  or is {@code static} or {@code final}
     */
    public static Field findDirtyField(Class<?> clazz) {
        Field found = null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getAnnotation(DirtyFlag.class) == null) continue;
                if (found != null) {
                    throw new IllegalStateException(
                        "@DirtyFlag: only one field per entity may carry the annotation, but "
                        + clazz.getSimpleName() + " has it on both '" + location(found)
                        + "' and '" + location(field) + "'.");
                }
                validate(field);
                field.setAccessible(true);
                found = field;
            }
            current = current.getSuperclass();
        }
        return found;
    }

    private static void validate(Field field) {
        if (field.getType() != boolean.class && field.getType() != Boolean.class) {
            throw new IllegalArgumentException(
                "@DirtyFlag on '" + location(field) + "': the field must be of type "
                + "boolean or Boolean, found '" + field.getType().getName() + "'.");
        }
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException(
                "@DirtyFlag on '" + location(field) + "': the field must not be static - "
                + "the dirty flag belongs to each entity instance.");
        }
        if (Modifier.isFinal(modifiers)) {
            throw new IllegalArgumentException(
                "@DirtyFlag on '" + location(field) + "': the field must not be final - "
                + "the manager clears and re-sets it around a flush.");
        }
    }

    private static String location(Field field) {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
