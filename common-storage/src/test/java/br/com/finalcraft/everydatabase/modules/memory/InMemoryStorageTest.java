package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete test suite for {@link InMemoryStorage}.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} and adds
 * InMemory-specific tests (transactional interface, lifecycle idempotency, etc.).
 */
@DisplayName("InMemoryStorage")
class InMemoryStorageTest extends AbstractStorageTest {

    @Override
    protected Storage createStorage(String testMethodName) {
        return new InMemoryStorage(); // in-memory has no persistent database to name
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: TransactionalStorage capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("InMemoryStorage implements TransactionalStorage")
    void inMemoryStorage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            "InMemoryStorage must implement TransactionalStorage");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: transaction commit
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("inTransaction() - saves inside scope are visible after completion")
    void inTransaction_commit_savesAreVisible() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenCompose(__ -> txRepo.save(bob()));
        }).join();

        // Both writes must be visible through the normal (non-tx) repo
        assertTrue(repo.find(UUID_ALICE).join().isPresent(), "Alice should be visible after tx commit");
        assertTrue(repo.find(UUID_BOB).join().isPresent(),   "Bob should be visible after tx commit");
        assertEquals(2L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: transaction exception propagation
    // ------------------------------------------------------------------

    @Test
    @Order(1011)
    @DisplayName("inTransaction() - work that throws propagates exception, no crash")
    void inTransaction_exceptionInWork_propagatesAndDoesNotCrash() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        RuntimeException boom = new RuntimeException("intentional failure");

        CompletableFuture<Void> result = tx.inTransaction(scope -> {
            throw boom;
        });

        ExecutionException ex = assertThrows(ExecutionException.class, result::get);
        assertSame(boom, ex.getCause(), "Original exception should be the cause");
    }

    @Test
    @Order(1012)
    @DisplayName("inTransaction() - failed future propagates exception")
    void inTransaction_failedFuture_propagatesException() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        RuntimeException boom = new RuntimeException("async failure");

        CompletableFuture<String> result = tx.inTransaction(scope ->
            CompletableFuture.supplyAsync(() -> { throw boom; })
        );

        ExecutionException ex = assertThrows(ExecutionException.class, result::get);
        assertNotNull(ex.getCause());
    }

    @Test
    @Order(1013)
    @DisplayName("inTransaction() - scope.rollback() is a no-op (best-effort InMemory semantics)")
    void inTransaction_rollback_isNoOp_writesRemain() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        // In InMemory there is no isolation, so writes made before rollback() remain visible.
        // This test documents the known limitation rather than asserting isolation.
        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(carol())
                .thenRun(scope::rollback);   // rollback() is a no-op
        }).join();

        // Writes survive because InMemory has no real undo mechanism
        assertTrue(repo.find(UUID_CAROL).join().isPresent(),
            "InMemory rollback is a no-op - writes survive (documented limitation)");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: lifecycle idempotency
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("init() is idempotent - calling it twice does not throw")
    void init_calledTwice_isIdempotent() {
        assertDoesNotThrow(() -> storage.init().join(),
            "Second init() must not throw");
        assertTrue(storage.health().join().isConnected(),
            "Storage must remain healthy after double init()");
    }

    @Test
    @Order(1021)
    @DisplayName("close() is idempotent - calling it twice does not throw")
    void close_calledTwice_isIdempotent() {
        // first close happens in @AfterEach; here we call it once early
        assertDoesNotThrow(() -> storage.close().join(),
            "First explicit close() must not throw");
        assertDoesNotThrow(() -> storage.close().join(),
            "Second close() must not throw");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: health after close
    // ------------------------------------------------------------------

    @Test
    @Order(1030)
    @DisplayName("health() after close() reports not connected")
    void health_afterClose_isNotConnected() {
        storage.close().join();
        HealthStatus h = storage.health().join();
        assertFalse(h.isConnected(), "Storage should report DOWN after close()");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: multiple independent collections
    // ------------------------------------------------------------------

    @Test
    @Order(1040)
    @DisplayName("Two repositories with different collection names are independent")
    void twoRepositories_differentCollections_areIndependent() {
        EntityDescriptor<UUID, TestPlayer> altDescriptor =
            EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("other_players")
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build();

        Repository<UUID, TestPlayer> altRepo = storage.repository(altDescriptor);

        repo.save(alice()).join();

        // altRepo is a different collection - alice must NOT be visible there
        assertFalse(altRepo.find(UUID_ALICE).join().isPresent(),
            "Entity saved in 'test_players' must not appear in 'other_players'");
        assertEquals(0L, altRepo.count().join());
        assertEquals(1L, repo.count().join());
    }

    @Test
    @Order(1041)
    @DisplayName("repository() called twice with same descriptor returns same instance")
    void repository_sameDescriptor_returnsSameInstance() {
        Repository<UUID, TestPlayer> r1 = storage.repository(DESCRIPTOR);
        Repository<UUID, TestPlayer> r2 = storage.repository(DESCRIPTOR);
        assertSame(r1, r2, "Repeated calls with same descriptor must return the same Repository instance");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: close clears data
    // ------------------------------------------------------------------

    @Test
    @Order(1050)
    @DisplayName("close() then init() starts with a clean slate")
    void closeAndReInit_startsEmpty() {
        repo.save(alice()).join();
        assertEquals(1L, repo.count().join());

        // Simulate restart
        storage.close().join();
        storage.init().join();

        // Obtain a fresh repository handle after re-init
        Repository<UUID, TestPlayer> freshRepo = storage.repository(DESCRIPTOR);
        assertEquals(0L, freshRepo.count().join(),
            "Repository must be empty after close() + init()");
    }

    // ------------------------------------------------------------------
    //  InMemory-specific: saveAll inside transaction
    // ------------------------------------------------------------------

    @Test
    @Order(1060)
    @DisplayName("inTransaction() - saveAll() inside scope commits all entities")
    void inTransaction_saveAll_commitsAllEntities() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        List<TestPlayer> players = Arrays.asList(alice(), bob(), carol());

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).saveAll(players)
        ).join();

        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertEquals(3, all.size());
        assertTrue(all.containsAll(players));
    }
}
