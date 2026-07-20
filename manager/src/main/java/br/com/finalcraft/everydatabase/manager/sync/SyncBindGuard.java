package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.SyncParticipation;

/**
 * Fail-fast guard for the one misrouting optimistic locking cannot catch by itself: a versioned
 * descriptor bound to a backend that does not enforce the lock, while writes from several
 * instances are intended.
 *
 * <p>On such a backend the version check silently degrades to last-write-wins, so under two
 * writers one edit vanishes with no error and no log line - the failure mode is invisible until
 * someone notices missing data. Declared multi-instance intent is what turns that from "risky but
 * possibly deliberate" into "certainly wrong", which is why the guard needs the caller to state
 * the intent instead of guessing it: a versioned entity on a non-enforcing backend is perfectly
 * safe while a single process writes, and that is the common case.</p>
 *
 * <p>Call it at bind time, once per entity, so a misconfiguration fails at boot rather than
 * corrupting data hours later.</p>
 */
public final class SyncBindGuard {

    private SyncBindGuard() {
    }

    /**
     * Throws when {@code descriptor} is versioned, {@code storage} does not enforce the optimistic
     * lock, and the caller declared multi-instance intent. Otherwise a no-op.
     *
     * @param what                a human-readable id of the entity being bound (for the message)
     * @param descriptor          the descriptor being bound (source of the versioned signal)
     * @param storage             the backend it is being bound to
     * @param multiInstanceIntent whether the caller intends writes from several instances
     * @throws IllegalStateException on the one fatal combination described above
     */
    public static void check(String what, EntityDescriptor<?, ?> descriptor, Storage storage,
                             boolean multiInstanceIntent) {
        if (!descriptor.isVersioned() || storage.enforcesOptimisticLock() || !multiInstanceIntent) {
            return;
        }
        throw new IllegalStateException(what + " is optimistic-locked (versioned descriptor) but is"
                + " bound to a storage that does NOT enforce the version check (it degrades to"
                + " last-write-wins). With multi-instance writes intended, concurrent writes would"
                + " silently drop one side. Route it to an enforcing backend (MySQL/MariaDB,"
                + " PostgreSQL, MongoDB), or drop the multi-instance intent.");
    }

    /**
     * Throws when a store asks to {@link SyncParticipation#ALWAYS} publish on a transport but its
     * identity is machine-local - it would publish onto a per-store channel no other machine
     * subscribes to, so the signal is silently lost. Otherwise a no-op.
     *
     * <p>Unlike {@link #check}, this is not a check the consumer opts into: {@code CacheSync} calls
     * it automatically while wiring the publish hook, because the fatal combination is entirely about
     * what {@code CacheSync} is about to do (publish or not) and all the inputs are available exactly
     * there. A machine-local store with an explicit {@code sharedIdentity} is not machine-local by
     * this test (the operator declared it shareable), so that combination is allowed through.
     *
     * @param what    a human-readable id of the entity/collection being bound (for the message)
     * @param storage the backend it is being bound to
     * @throws IllegalStateException on {@code ALWAYS} + machine-local identity
     */
    public static void checkParticipation(String what, Storage storage) {
        if (storage.syncParticipation() != SyncParticipation.ALWAYS || !storage.isMachineLocalIdentity()) {
            return;
        }
        throw new IllegalStateException(what + " sets syncParticipation=ALWAYS but its backend is"
                + " machine-local (a loopback database, a file directory, or an in-memory store with no"
                + " sharedIdentity), so it would publish onto a per-store channel no other machine can"
                + " subscribe to - the signal would be silently lost. Give the backend an explicit"
                + " sharedIdentity to declare it shared, or use RECOMMENDED/NEVER.");
    }
}
