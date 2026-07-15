package br.com.finalcraft.everydatabase.manager.writeback;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reflective copy of an entity's persisted state - the canonical building block of a
 * {@link ConflictHooks#adoptStoredState} implementation, so every type that adopts a stored winner
 * does it the same way and none of them drifts when a persisted field is added.
 */
public final class PersistedState {

    private PersistedState() {
    }

    /**
     * Copies every persisted (Jackson-visible) field from {@code stored} into {@code live}: every
     * non-static, non-{@code transient}, non-{@code @JsonIgnore} field declared across the whole
     * class hierarchy. Runtime wiring (transient/{@code @JsonIgnore} fields: locks, dirty flags,
     * attached references) is untouched. Both instances must be the same concrete type.
     */
    public static void copyInto(Object live, Object stored) {
        Class<?> type = live.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isTransient(modifiers)
                        || field.isAnnotationPresent(JsonIgnore.class)) {
                    continue;   //runtime-only fields are never carried over
                }
                try {
                    field.setAccessible(true);
                    field.set(live, field.get(stored));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to adopt stored state of ["
                            + live.getClass().getName() + "] field '" + field.getName() + "'", e);
                }
            }
            type = type.getSuperclass();
        }
    }
}
