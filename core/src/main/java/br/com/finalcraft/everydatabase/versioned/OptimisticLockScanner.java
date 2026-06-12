package br.com.finalcraft.everydatabase.versioned;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Scans entity classes for the {@link OptimisticLock} annotation and validates the
 * annotated field. Used internally by {@link EntityDescriptor.Builder#build()};
 * not meant to be called by application code.
 *
 * <p>Scanning walks the entire class hierarchy (from most-derived to least-derived,
 * stopping before {@link Object}), mirroring the {@code @Indexed} scanner, so an
 * annotated field on a superclass is also picked up.
 */
public final class OptimisticLockScanner {

    private OptimisticLockScanner() {}

    /**
     * Returns the single field annotated with {@link OptimisticLock} in {@code clazz}
     * or any of its superclasses, already validated and made accessible
     * ({@code setAccessible(true)}), or {@code null} when no field is annotated.
     *
     * @throws IllegalStateException    if more than one field carries the annotation
     * @throws IllegalArgumentException if the annotated field is not {@code long}/{@code Long},
     *                                  or is {@code static} or {@code final}
     */
    public static Field findLockField(Class<?> clazz) {
        Field found = null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getAnnotation(OptimisticLock.class) == null) continue;
                if (found != null) {
                    throw new IllegalStateException(
                        "@OptimisticLock: only one field per entity may carry the annotation, but "
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
        if (field.getType() != long.class && field.getType() != Long.class) {
            throw new IllegalArgumentException(
                "@OptimisticLock on '" + location(field) + "': the field must be of type "
                + "long or Long, found '" + field.getType().getName() + "'.");
        }
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException(
                "@OptimisticLock on '" + location(field) + "': the field must not be static - "
                + "the lock version belongs to each entity instance.");
        }
        if (Modifier.isFinal(modifiers)) {
            throw new IllegalArgumentException(
                "@OptimisticLock on '" + location(field) + "': the field must not be final - "
                + "the backend writes the new version back after every successful save.");
        }
    }

    private static String location(Field field) {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
