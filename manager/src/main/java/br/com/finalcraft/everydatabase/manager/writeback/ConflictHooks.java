package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-type seams {@link WriteBackFlusher}'s conflict resolution drives: the implementation knows
 * the concrete entity (where its key lives, which lock guards it, how a stored winner is taken on),
 * the flusher knows the protocol. One implementation per entity type, usually a singleton.
 *
 * <p>Conflict resolution calls every method with the entity's lock held - including
 * {@link #mergesOnConflict}, which is queried inside the resolution to pick the branch. The only
 * exceptions are {@link #storageKey}, called while the batch is being keyed, and {@link #lock}
 * itself, which is what hands the lock over to be taken.
 *
 * @param <K> the key type
 * @param <V> the entity type; dirty-trackable, because the resolution decides on the dirty flag
 */
public interface ConflictHooks<K, V extends IDirtyable> {

    /** The key {@code live} is stored under - the same key its manager's descriptor extracts. */
    K storageKey(V live);

    /**
     * The lock guarding {@code live}'s mutable state. The resolution decides and mutates under it, so
     * it must be the very lock the entity's own mutators take.
     */
    ReentrantLock lock(V live);

    /**
     * Copies the winner's persisted state into the live instance, keeping the live instance's identity
     * so plugin-held references stay valid - see {@link PersistedState#copyInto}.
     *
     * <p>When {@link #mergesOnConflict()} is true this method instead encapsulates the WHOLE
     * resolution (combine both sides, re-mark dirty, adopt the winner's lock version).
     */
    void adoptStoredState(V live, V stored);

    /**
     * Adopts ONLY the winner's lock version, discarding the winner's values - used when the live
     * instance was modified again while the conflict was being resolved, so the local values must
     * survive and win the next flush cleanly.
     */
    void adoptStoredLockVersion(V live, V stored);

    /**
     * Resets the optimistic lock so the next flush re-creates a row that vanished mid-conflict
     * (deleted by another instance between the failed save and the re-read).
     */
    void resetLockForRecreate(V live);

    /** Post-adopt callback on the clean-adopt branch: the winner is a detached decode that may need
     *  re-checking (e.g. it came from an instance running an older payload schema). */
    default void afterAdopt(V live) { }

    /**
     * True when this type resolves a conflict by MERGING the winner into the live state instead of
     * adopting it - the right answer when a whole-row adopt would silently drop what the other
     * instance wrote. {@link #adoptStoredState} then runs regardless of the dirty flag and owns the
     * entire resolution.
     */
    default boolean mergesOnConflict() { return false; }
}
