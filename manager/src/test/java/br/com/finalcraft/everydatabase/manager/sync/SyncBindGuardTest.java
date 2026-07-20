package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.SyncParticipation;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryConfig;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.manager.testdata.Bank;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link SyncBindGuard} decision matrix over its three inputs (versioned descriptor, enforcing
 * storage, multi-instance intent).
 *
 * <p>Exactly one of the eight combinations is fatal - a versioned entity bound to a backend that
 * degrades the version check to last-write-wins, while several instances are meant to write. The
 * other seven are legitimate configurations and must bind without a word: refusing any of them would
 * reject setups that are perfectly safe, which is why the matrix is asserted whole rather than only
 * on its failing corner.</p>
 */
class SyncBindGuardTest {

    /** Versioned: {@code Quest} carries an {@code @OptimisticLock} field, which build() scans. */
    private static EntityDescriptor<UUID, Quest> versionedDescriptor() {
        return EntityDescriptor.builder(UUID.class, Quest.class)
                .collection("quests")
                .keyExtractor(Quest::getId)
                .codec(new JacksonJsonCodec<>(Quest.class))
                .build();
    }

    /** Plain: {@code Bank} has no lock field, so the descriptor is not versioned. */
    private static EntityDescriptor<UUID, Bank> plainDescriptor() {
        return EntityDescriptor.builder(UUID.class, Bank.class)
                .collection("banks")
                .keyExtractor(Bank::getId)
                .codec(new JacksonJsonCodec<>(Bank.class))
                .build();
    }

    /** A real backend that does not enforce the lock - no stub needed for this half of the axis. */
    private static Storage nonEnforcingStorage() {
        return Storages.createInMemory();
    }

    /**
     * A storage that answers the capability and refuses everything else. The refusal is the point:
     * these tests pass only while the guard decides from {@code enforcesOptimisticLock()} alone,
     * without opening a connection or touching a repository at bind time.
     */
    private static Storage enforcingStorage() {
        return new Storage() {
            @Override public boolean enforcesOptimisticLock() { return true; }
            @Override public CompletableFuture<Void> init() { throw new UnsupportedOperationException(); }
            @Override public CompletableFuture<Void> close() { throw new UnsupportedOperationException(); }
            @Override public CompletableFuture<HealthStatus> health() { throw new UnsupportedOperationException(); }
            @Override public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> d) { throw new UnsupportedOperationException(); }
            @Override public StorageLogConfig getStorageLogConfig() { throw new UnsupportedOperationException(); }
            @Override public Storage setStorageLogConfig(StorageLogConfig config) { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    void the_fixtures_are_what_the_matrix_assumes() {
        // Without this the matrix could go vacuously green: a fixture that silently stopped being
        // versioned (or enforcing) would turn the fatal combination into a legitimate one.
        assertTrue(versionedDescriptor().isVersioned(), "the Quest descriptor must be versioned");
        assertFalse(plainDescriptor().isVersioned(), "the Bank descriptor must not be versioned");
        assertFalse(nonEnforcingStorage().enforcesOptimisticLock(), "InMemory must not enforce the lock");
        assertTrue(enforcingStorage().enforcesOptimisticLock(), "the stub must enforce the lock");
    }

    @Test
    void a_versioned_entity_on_a_non_enforcing_backend_under_multi_instance_intent_is_refused() {
        IllegalStateException fatal = assertThrows(IllegalStateException.class, () ->
                SyncBindGuard.check("quests", versionedDescriptor(), nonEnforcingStorage(), true));

        assertTrue(fatal.getMessage().contains("quests"),
                "the message must name the entity being bound, so the admin knows what to re-route");
        assertTrue(fatal.getMessage().contains("last-write-wins"),
                "the message must name the failure mode, not just declare the binding invalid");
    }

    @Test
    void a_versioned_entity_on_a_non_enforcing_backend_binds_when_a_single_instance_writes() {
        // The common case: versioning on a file/memory backend is safe while one process writes.
        assertDoesNotThrow(() ->
                SyncBindGuard.check("quests", versionedDescriptor(), nonEnforcingStorage(), false));
    }

    @Test
    void a_versioned_entity_on_an_enforcing_backend_binds_under_any_intent() {
        assertDoesNotThrow(() ->
                SyncBindGuard.check("quests", versionedDescriptor(), enforcingStorage(), true));
        assertDoesNotThrow(() ->
                SyncBindGuard.check("quests", versionedDescriptor(), enforcingStorage(), false));
    }

    @Test
    void a_plain_entity_binds_anywhere_under_any_intent() {
        // Nothing to lose: with no version to check, there is no check to degrade. This covers the
        // whole non-versioned half of the matrix - the other two inputs cannot make it fatal.
        assertDoesNotThrow(() ->
                SyncBindGuard.check("banks", plainDescriptor(), nonEnforcingStorage(), true));
        assertDoesNotThrow(() ->
                SyncBindGuard.check("banks", plainDescriptor(), nonEnforcingStorage(), false));
        assertDoesNotThrow(() ->
                SyncBindGuard.check("banks", plainDescriptor(), enforcingStorage(), true));
        assertDoesNotThrow(() ->
                SyncBindGuard.check("banks", plainDescriptor(), enforcingStorage(), false));
    }

    // ------------------------------------------------------------------
    //  checkParticipation: the ALWAYS + machine-local fail-fast
    // ------------------------------------------------------------------

    /** A storage answering only the two participation signals; everything else refuses. */
    private static Storage participationStorage(SyncParticipation participation, boolean machineLocal) {
        return new Storage() {
            @Override public SyncParticipation syncParticipation() { return participation; }
            @Override public boolean isMachineLocalIdentity() { return machineLocal; }
            @Override public CompletableFuture<Void> init() { throw new UnsupportedOperationException(); }
            @Override public CompletableFuture<Void> close() { throw new UnsupportedOperationException(); }
            @Override public CompletableFuture<HealthStatus> health() { throw new UnsupportedOperationException(); }
            @Override public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> d) { throw new UnsupportedOperationException(); }
            @Override public StorageLogConfig getStorageLogConfig() { throw new UnsupportedOperationException(); }
            @Override public Storage setStorageLogConfig(StorageLogConfig config) { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    void always_on_a_machine_local_backend_without_shared_identity_is_refused() {
        IllegalStateException fatal = assertThrows(IllegalStateException.class, () ->
                SyncBindGuard.checkParticipation("guilds", participationStorage(SyncParticipation.ALWAYS, true)));

        assertTrue(fatal.getMessage().contains("guilds"),
                "the message must name the collection being bound");
        assertTrue(fatal.getMessage().contains("sharedIdentity"),
                "the message must point at the fix - declaring a shared identity");
    }

    @Test
    void always_on_a_machine_local_backend_with_shared_identity_binds() {
        // A real InMemory with a sharedIdentity reports isMachineLocalIdentity() == false, so ALWAYS
        // is legitimate - the operator declared the store shareable.
        Storage shared = Storages.createInMemory(new InMemoryConfig("shared-x", SyncParticipation.ALWAYS));
        assertFalse(shared.isMachineLocalIdentity(), "a shared identity must make the store shareable");
        assertDoesNotThrow(() -> SyncBindGuard.checkParticipation("guilds", shared));
    }

    @Test
    void always_on_a_shareable_backend_binds() {
        assertDoesNotThrow(() ->
                SyncBindGuard.checkParticipation("guilds", participationStorage(SyncParticipation.ALWAYS, false)));
    }

    @Test
    void recommended_and_never_never_fail_regardless_of_machine_local_ness() {
        // The fail-fast is exclusive to ALWAYS: every other participation binds under either identity.
        for (SyncParticipation p : new SyncParticipation[]{SyncParticipation.RECOMMENDED, SyncParticipation.NEVER}) {
            assertDoesNotThrow(() -> SyncBindGuard.checkParticipation("guilds", participationStorage(p, true)));
            assertDoesNotThrow(() -> SyncBindGuard.checkParticipation("guilds", participationStorage(p, false)));
        }
    }
}
