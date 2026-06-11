package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.data.VersionedTestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.evernifecore.storage.versioned.OptimisticLockException;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import br.com.finalcraft.evernifecore.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimistic-locking (versioned) tests for the MongoDB backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest} and adds a
 * Mongo-specific first-insert race test (duplicate-key on the unique {@code storage_key}
 * index must surface as {@link OptimisticLockException}, never as a raw driver exception).
 *
 * <p>Requires a running MongoDB 4.2+ server; skipped automatically otherwise.
 *
 * <h3>Configuration</h3>
 * Same env vars as {@link MongoStorageTest}:
 * {@code MONGO_USER}, {@code MONGO_PASS}, {@code MONGO_HOST}, {@code MONGO_PORT}.
 * Defaults: {@code root/root @ localhost:39308}.
 *
 * <pre>
 * docker compose up -d mongo
 * ./gradlew :common-storage:test --tests "*MongoVersionedStorageTest"
 * </pre>
 */
@DisplayName("MongoStorage - Optimistic Locking (versioned)")
class MongoVersionedStorageTest extends AbstractVersionedStorageTest {

    static final String MONGO_USER = DotEnvTestUtil.getOrDefault("MONGO_USER", "root");
    static final String MONGO_PASS = DotEnvTestUtil.getOrDefault("MONGO_PASS", "root");
    static final String MONGO_HOST = DotEnvTestUtil.getOrDefault("MONGO_HOST", "localhost");
    static final String MONGO_PORT = DotEnvTestUtil.getOrDefault("MONGO_PORT", "39308");
    static final String MONGO_URL  = "mongodb://" + MONGO_USER + ":" + MONGO_PASS
                                   + "@" + MONGO_HOST + ":" + MONGO_PORT;

    private static final ThrowawayDatabaseSupport DBS = ThrowawayDatabaseSupport.mongo(MONGO_URL, "mgv");

    @BeforeAll
    static void assumeMongoAvailable() {
        DBS.assumeAvailable("MongoVersionedStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MongoVersionedStorageTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        return new MongoStorage(new MongoConfig(MONGO_URL, DBS.newDatabase(testMethodName)));
    }

    // ------------------------------------------------------------------
    //  Mongo-specific: first-insert race
    // ------------------------------------------------------------------

    /**
     * Two writers insert the same brand-new key (version 0) simultaneously, repeatedly.
     * Legal outcomes per attempt: both succeed serialized (the second acts as an update of
     * version 0), or the loser of a true race throws {@link OptimisticLockException}.
     * What must NEVER escape is the raw driver {@code MongoWriteException} (E11000) that
     * the insert path used to leak before the duplicate-key conversion.
     */
    @Test
    @Order(900)
    @DisplayName("concurrent first insert: loser surfaces OptimisticLockException, never a raw driver error")
    void concurrentFirstInsert_loserGetsOptimisticLockException() throws Exception {
        final int ATTEMPTS = 25;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            UUID key = UUID.randomUUID();
            VersionedTestPlayer first  = new VersionedTestPlayer(key, "Racer", attempt);
            VersionedTestPlayer second = new VersionedTestPlayer(key, "Racer", attempt);

            CyclicBarrier start = new CyclicBarrier(2);
            CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
                await(start);
                vRepo.save(first).join();
            });
            CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
                await(start);
                vRepo.save(second).join();
            });

            int failures = 0;
            for (CompletableFuture<Void> f : new CompletableFuture[]{f1, f2}) {
                try {
                    f.join();
                } catch (CompletionException e) {
                    failures++;
                    Throwable root = rootCause(e);
                    assertInstanceOf(OptimisticLockException.class, root,
                        "racing writer must fail with OptimisticLockException, got: " + root);
                }
            }
            assertTrue(failures <= 1, "at most one of the two writers may fail per attempt");

            // Whatever the interleaving, the document must exist exactly once.
            assertEquals(1, vRepo.findMany(java.util.Collections.singletonList(key)).join().size(),
                "exactly one stored document for the raced key");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }
}
