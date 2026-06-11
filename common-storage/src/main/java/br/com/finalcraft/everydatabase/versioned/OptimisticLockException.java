package br.com.finalcraft.everydatabase.versioned;

import br.com.finalcraft.everydatabase.Repository;

/**
 * Thrown by a versioned {@link Repository} when an optimistic-lock conflict is detected.
 *
 * <p>A conflict occurs when the version held by the in-memory entity does not match the
 * version currently stored in the backend at save time. This means another writer updated
 * the record between the caller's last read and the attempted save.
 *
 * <p>This is an unchecked exception. Callers that wish to recover (e.g. reload + merge)
 * must catch it explicitly.
 *
 * <p>Plain (non-versioned) descriptors never throw this exception.
 */
public class OptimisticLockException extends RuntimeException {

    private final Class<?> entityType;
    private final Object   key;
    private final long     expectedVersion;
    private final long     actualVersion;

    /**
     * Constructs a new {@code OptimisticLockException}.
     *
     * @param entityType      the Java type of the entity whose save failed
     * @param key             the entity's key (toString is used in the message)
     * @param expectedVersion the lock version the caller held in memory
     * @param actualVersion   the lock version found in the backend at save time
     */
    public OptimisticLockException(Class<?> entityType, Object key,
                                   long expectedVersion, long actualVersion) {
        super(String.format(
            "Optimistic lock conflict for %s[key=%s]: expected version=%d but backend has version=%d. "
            + "Reload the entity and retry.",
            entityType.getSimpleName(), key, expectedVersion, actualVersion
        ));
        this.entityType      = entityType;
        this.key             = key;
        this.expectedVersion = expectedVersion;
        this.actualVersion   = actualVersion;
    }

    /** The Java type of the entity whose save failed. */
    public Class<?> getEntityType() {
        return entityType;
    }

    /** The entity's key value. */
    public Object getKey() {
        return key;
    }

    /**
     * The lock version the caller held in memory at the time of the save attempt.
     * This is the version the backend was expected to have.
     */
    public long getExpectedVersion() {
        return expectedVersion;
    }

    /**
     * The lock version actually found in the backend at save time.
     * If {@code -1}, the row was absent when an update was expected.
     */
    public long getActualVersion() {
        return actualVersion;
    }
}
