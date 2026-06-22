package br.com.finalcraft.everydatabase.manager.cache;

import java.lang.reflect.Field;

/**
 * Unifies the two opt-in dirty-tracking forms - implementing {@link IDirtyable} or annotating a
 * {@code boolean} field with {@link DirtyFlag @DirtyFlag} - behind a single accessor the
 * {@code CachingManager} uses without caring which form an entity chose.
 *
 * <p>Resolve one per entity type with {@link #forType(Class)}: it returns the accessor for whichever
 * form the type opts into, or {@code null} when the type is not dirty-trackable (the manager then
 * treats it as a plain read-through / write-through entity). Declaring <b>both</b> forms on one type
 * is a configuration error and fails fast from {@code forType}.
 */
public class DirtyAccessor {

    protected enum Mode { INTERFACE, FIELD }

    protected final Mode mode;
    protected final Field field;

    private DirtyAccessor(Mode mode, Field field) {
        this.mode  = mode;
        this.field = field;
    }

    /**
     * Resolves the dirty-tracking accessor for {@code type}:
     * <ul>
     *   <li>{@code null} if the type is neither an {@link IDirtyable} nor carries a
     *       {@link DirtyFlag @DirtyFlag} field (not dirty-trackable - treated as plain);</li>
     *   <li>an interface-mode accessor if it implements {@link IDirtyable};</li>
     *   <li>a field-mode accessor if it has a {@code @DirtyFlag} field.</li>
     * </ul>
     *
     * @throws IllegalStateException    if the type declares both forms at once
     * @throws IllegalArgumentException if the {@code @DirtyFlag} field is not {@code boolean}/{@code Boolean},
     *                                  or is {@code static} or {@code final}
     */
    public static DirtyAccessor forType(Class<?> type) {
        boolean iface = IDirtyable.class.isAssignableFrom(type);
        Field field = DirtyFieldScanner.findDirtyField(type);
        if (iface && field != null) {
            throw new IllegalStateException(
                type.getSimpleName() + " implements IDirtyable AND has a @DirtyFlag field ('"
                + field.getDeclaringClass().getSimpleName() + "." + field.getName()
                + "') - use one, not both.");
        }
        if (iface) {
            return new DirtyAccessor(Mode.INTERFACE, null);
        }
        if (field != null) {
            return new DirtyAccessor(Mode.FIELD, field);
        }
        return null;
    }

    /** Whether {@code entity} has in-memory changes not yet persisted (a null {@code Boolean} reads as {@code false}). */
    public boolean isDirty(Object entity) {
        if (mode == Mode.INTERFACE) {
            return ((IDirtyable) entity).isDirty();
        }
        try {
            Object value = field.get(entity);
            return value != null && (Boolean) value;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@DirtyFlag: cannot read '" + location() + "'.", e);
        }
    }

    /** Clears the dirty flag on {@code entity}. */
    public void markClean(Object entity) {
        if (mode == Mode.INTERFACE) {
            ((IDirtyable) entity).markClean();
            return;
        }
        set(entity, false);
    }

    /** (Re)sets the dirty flag on {@code entity}. */
    public void markDirty(Object entity) {
        if (mode == Mode.INTERFACE) {
            ((IDirtyable) entity).markDirty();
            return;
        }
        set(entity, true);
    }

    private void set(Object entity, boolean dirty) {
        try {
            field.set(entity, dirty);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@DirtyFlag: cannot write '" + location() + "'.", e);
        }
    }

    private String location() {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
