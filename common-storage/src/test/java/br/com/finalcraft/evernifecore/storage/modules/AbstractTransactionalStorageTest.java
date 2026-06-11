package br.com.finalcraft.evernifecore.storage.modules;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Query;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
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
 * Shared suite for backends with <b>real rollback semantics</b> ({@link TransactionalStorage}
 * implementations backed by an actual transaction: MariaDB/MySQL, PostgreSQL, H2).
 *
 * <p>Extends the base contract suite with the tests that used to be copied verbatim between
 * the three SQL dialect suites: transaction commit/rollback/exception semantics, lifecycle
 * idempotency, health reporting, repository identity, and schema evolution.
 *
 * <p>Display names are prefixed {@code [tx]}. {@code InMemoryStorage} deliberately does NOT
 * extend this suite (its {@code rollback()} is a documented no-op), and {@code MongoStorage}
 * only joins when running against a replica set (standalone Mongo cannot open transactions).
 *
 * <p>Concrete classes implement {@link #createStorage(String)} (inherited) and
 * {@link #openExtraStorageOnSameDatabase()} (used by the schema-evolution test to reopen
 * the current test database with a second storage instance).
 */
public abstract class AbstractTransactionalStorageTest extends AbstractStorageTest {

    /**
     * Opens a NEW {@link Storage} instance pointing at the same database that
     * {@link #createStorage(String)} created for the current test method. The caller
     * {@code init()}s and {@code close()}s it. Used by the schema-evolution test, which
     * needs two storage "generations" against one database.
     */
    protected abstract Storage openExtraStorageOnSameDatabase();

    // ------------------------------------------------------------------
    //  Capability
    // ------------------------------------------------------------------

    @Test
    @Order(1001)
    @DisplayName("[tx] storage implements TransactionalStorage")
    void storage_implementsTransactionalStorage() {
        assertInstanceOf(TransactionalStorage.class, storage,
            storage.getClass().getSimpleName() + " must implement TransactionalStorage");
    }

    // ------------------------------------------------------------------
    //  Transaction commit
    // ------------------------------------------------------------------

    @Test
    @Order(1010)
    @DisplayName("[tx] inTransaction() - saves inside scope are visible after commit")
    void inTransaction_commit_savesAreVisible() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenCompose(__ -> txRepo.save(bob()));
        }).join();

        assertTrue(repo.find(UUID_ALICE).join().isPresent(), "Alice should be visible after tx commit");
        assertTrue(repo.find(UUID_BOB).join().isPresent(),   "Bob should be visible after tx commit");
        assertEquals(2L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  Real transaction rollback
    // ------------------------------------------------------------------

    @Test
    @Order(1011)
    @DisplayName("[tx] inTransaction() - scope.rollback() actually undoes writes")
    void inTransaction_rollback_actuallyUndoesWrites() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .thenRun(scope::rollback);
        }).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent(),
            "ROLLBACK must undo the save - Alice should not be visible");
        assertEquals(0L, repo.count().join(),
            "count() must be 0 after a rolled-back transaction");
    }

    @Test
    @Order(1012)
    @DisplayName("[tx] inTransaction() - rollback only affects the rolled-back transaction")
    void inTransaction_rollback_doesNotAffectCommittedData() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        // First tx: commits Alice
        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(alice())
        ).join();

        // Second tx: saves Bob then rolls back
        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(bob())
                .thenRun(scope::rollback);
        }).join();

        assertTrue(repo.find(UUID_ALICE).join().isPresent(), "Alice (committed) must survive");
        assertFalse(repo.find(UUID_BOB).join().isPresent(),  "Bob (rolled back) must not be visible");
        assertEquals(1L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  Exception propagation from transactional work
    // ------------------------------------------------------------------

    @Test
    @Order(1013)
    @DisplayName("[tx] inTransaction() - work that throws propagates exception")
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
    @Order(1014)
    @DisplayName("[tx] inTransaction() - exception rolls back any writes made before the throw")
    void inTransaction_exceptionAfterSave_rollsBackWrite() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope -> {
            Repository<UUID, TestPlayer> txRepo = scope.repository(DESCRIPTOR);
            return txRepo.save(alice())
                .<Void>thenApply(__ -> { throw new RuntimeException("forced failure"); });
        }).exceptionally(e -> null).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent(),
            "Exception in tx work must trigger rollback - Alice should not be visible");
    }

    @Test
    @Order(1015)
    @DisplayName("[tx] inTransaction() - saveAll() inside scope commits all entities")
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

    // ------------------------------------------------------------------
    //  Upsert semantics inside a transaction
    // ------------------------------------------------------------------

    @Test
    @Order(1020)
    @DisplayName("[tx] save() inside a transaction upserts correctly")
    void inTransaction_save_upsertIsCorrect() {
        TransactionalStorage tx = (TransactionalStorage) storage;

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(alice())
        ).join();

        tx.inTransaction(scope ->
            scope.repository(DESCRIPTOR).save(new TestPlayer(UUID_ALICE, "Alice", 999))
        ).join();

        TestPlayer found = repo.find(UUID_ALICE).join().orElseThrow(AssertionError::new);
        assertEquals(999, found.getScore(), "Score must reflect last upserted value");
        assertEquals(1L,  repo.count().join(), "count() must not grow after upsert");
    }

    // ------------------------------------------------------------------
    //  Lifecycle idempotency
    // ------------------------------------------------------------------

    @Test
    @Order(1030)
    @DisplayName("[tx] init() is idempotent - calling it twice does not throw")
    void init_calledTwice_isIdempotent() {
        assertDoesNotThrow(() -> storage.init().join(),
            "Second init() must not throw");
        assertTrue(storage.health().join().isConnected(),
            "Storage must remain healthy after double init()");
    }

    @Test
    @Order(1031)
    @DisplayName("[tx] close() is idempotent - calling it twice does not throw")
    void close_calledTwice_isIdempotent() {
        assertDoesNotThrow(() -> storage.close().join(), "First explicit close() must not throw");
        assertDoesNotThrow(() -> storage.close().join(), "Second close() must not throw");
    }

    // ------------------------------------------------------------------
    //  Health reporting
    // ------------------------------------------------------------------

    @Test
    @Order(1040)
    @DisplayName("[tx] health() after init() reports connected=true and a valid ping")
    void health_afterInit_isConnectedWithPing() {
        HealthStatus h = storage.health().join();
        assertTrue(h.isConnected(), "Storage must be connected after init()");
        assertTrue(h.pingMs() >= 0, "pingMs must be non-negative");
    }

    @Test
    @Order(1041)
    @DisplayName("[tx] health() after close() reports connected=false")
    void health_afterClose_isNotConnected() {
        storage.close().join();
        HealthStatus h = storage.health().join();
        assertFalse(h.isConnected(), "Storage must report DOWN after close()");
    }

    // ------------------------------------------------------------------
    //  Repository identity
    // ------------------------------------------------------------------

    @Test
    @Order(1050)
    @DisplayName("[tx] repository() called twice with same descriptor returns same instance")
    void repository_sameDescriptor_returnsSameInstance() {
        Repository<UUID, TestPlayer> r1 = storage.repository(DESCRIPTOR);
        Repository<UUID, TestPlayer> r2 = storage.repository(DESCRIPTOR);
        assertSame(r1, r2, "Repeated calls with same descriptor must return the same Repository instance");
    }

    @Test
    @Order(1051)
    @DisplayName("[tx] Two repositories with different collection names are independent")
    void twoRepositories_differentCollections_areIndependent() {
        EntityDescriptor<UUID, TestPlayer> altDescriptor =
            EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("other_players")
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build();

        Repository<UUID, TestPlayer> altRepo = storage.repository(altDescriptor);

        repo.save(alice()).join();

        assertFalse(altRepo.find(UUID_ALICE).join().isPresent(),
            "Entity saved in 'test_players' must not appear in 'other_players'");
        assertEquals(0L, altRepo.count().join());
        assertEquals(1L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  Schema drift (new IndexHint on existing table)
    // ------------------------------------------------------------------

    @Test
    @Order(1060)
    @DisplayName("[tx] ensureIndexColumn + auto-populate: new IndexHint is ALTERed in and backfilled")
    void schemaEvolution_newIndexHint_columnAddedAndBackfilled() {
        // V1 and V2 run against the same database as the main storage for this test method.
        // They use a distinct collection name ("schema_evolution") to avoid colliding with
        // the "test_players" table used by the inherited contract tests.

        // --- V1 descriptor: only "name" indexed ---
        EntityDescriptor<UUID, TestPlayer> v1 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        // Create the table via V1 and save Alice (score column does NOT exist yet).
        Storage storageV1 = openExtraStorageOnSameDatabase();
        storageV1.init().join();
        storageV1.repository(v1).save(alice()).join();
        storageV1.close().join();

        // --- V2 descriptor: "name" + "score" indexed (score is the new hint) ---
        EntityDescriptor<UUID, TestPlayer> v2 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_evolution")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))   // added vs. the V1 descriptor above
            .build();

        // Open a NEW storage on the SAME database with V2.
        // ensureIndexColumn() must add _idx_score via ALTER TABLE without throwing.
        Storage storageV2 = openExtraStorageOnSameDatabase();
        storageV2.init().join();
        Repository<UUID, TestPlayer> repoV2 = assertDoesNotThrow(
            () -> storageV2.repository(v2),
            "repository() with a new IndexHint must not throw - ensureIndexColumn should ADD the column");

        // Alice is still readable, and basic CRUD works.
        assertTrue(repoV2.find(UUID_ALICE).join().isPresent(), "Alice must still be readable after ALTER");
        assertEquals(1L, repoV2.count().join());

        // Auto-populate: opening V2 backfilled _idx_score for the pre-existing Alice row, so she is
        // findable via the brand-new score index WITHOUT being re-saved first.
        List<TestPlayer> found = repoV2.query(Query.eq("score", 100)).join();
        assertEquals(1, found.size(),
            "Auto-populate must backfill the new score index so Alice is found without a re-save");
        assertEquals(UUID_ALICE, found.get(0).getUuid());

        storageV2.close().join();
    }
}
