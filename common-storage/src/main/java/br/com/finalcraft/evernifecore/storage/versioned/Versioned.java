package br.com.finalcraft.evernifecore.storage.versioned;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;

/**
 * Opt-in interface for entities that support optimistic locking.
 *
 * <p>When an {@link EntityDescriptor} is configured with
 * {@link EntityDescriptor.Builder#version(java.util.function.Function, java.util.function.BiConsumer)}
 * (or the convenience {@link EntityDescriptor.Builder#versioned()} for implementors of this
 * interface), the storage backend will enforce optimistic locking on every {@code save()}.
 *
 * <p>The version starts at {@code 0} on the first insert and is incremented by the backend on
 * every successful update. The in-memory entity is updated to reflect the new version after a
 * successful save. If the in-memory version does not match the persisted version at save time,
 * the backend throws {@link OptimisticLockException}.
 *
 * <p>Entities that do not implement this interface (or whose descriptor does not declare version
 * accessors) continue to use plain upsert semantics - existing behaviour is completely unchanged.
 */
public interface Versioned {

    /**
     * Returns the current optimistic-lock version held by this instance.
     * A value of {@code 0} indicates the entity has not yet been persisted (or was just inserted).
     */
    long getLockVersion();

    /**
     * Sets the optimistic-lock version on this instance.
     * Called by the storage backend after a successful insert or update.
     *
     * @param version the new version (0 on first insert, incremented on each update)
     */
    void setLockVersion(long version);
}
