package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Account;
import br.com.finalcraft.everydatabase.manager.testdata.Bank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-flight loading: misses on the same key collapse into one backend read, a load that fails
 * leaves the key free for the next reader to retry, and the in-flight bookkeeping survives both a
 * repository that throws and a load whose continuation resolves another key re-entrantly.
 */
class CachingManagerSingleFlightTest {

    private RefRegistry registry;
    private ScriptedRepository<UUID, Bank> repo;
    private CachingManager<UUID, Bank> manager;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        repo = new ScriptedRepository<>(Bank::getId);
        EntityDescriptor<UUID, Bank> descriptor = EntityDescriptor.builder(UUID.class, Bank.class)
                .collection("single_flight_accounts")
                .keyExtractor(Bank::getId)
                .codec(registry.codec(Bank.class))
                .build();
        manager = new CachingManager<>(descriptor, storageReturning(repo),
                CacheOptions.of(CachePolicy.always()), registry);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void a_second_miss_joins_the_load_already_in_flight() {
        UUID id = UUID.randomUUID();
        Bank stored = new Bank(id, 100);
        repo.put(stored);
        CompletableFuture<Optional<Bank>> heldRead = repo.deferFind(id);

        CompletableFuture<Optional<Bank>> first = manager.resolve(id);
        CompletableFuture<Optional<Bank>> second = manager.resolve(id);

        assertFalse(first.isDone(), "the read is still open");
        assertFalse(second.isDone(), "...and the second caller is waiting on that same read");
        assertEquals(1, repo.findCount(id), "the second miss must not issue a read of its own");

        heldRead.complete(Optional.of(stored));

        assertSame(first.join().orElse(null), second.join().orElse(null),
                "both callers converge on the identity map's single instance");
        assertEquals(1, repo.findCount(id), "and the backend was read exactly once");
    }

    @Test
    void a_failed_load_leaves_the_key_free_for_the_next_reader() {
        UUID id = UUID.randomUUID();
        Bank stored = new Bank(id, 42);
        repo.put(stored);
        CompletableFuture<Optional<Bank>> heldRead = repo.deferFind(id);

        CompletableFuture<Optional<Bank>> failing = manager.resolve(id);
        heldRead.completeExceptionally(new IllegalStateException("backend down"));

        assertThrows(CompletionException.class, failing::join);
        assertEquals(42L, manager.resolve(id).join().map(Bank::getCoins).orElse(-1L).longValue(),
                "the retry reads the row that is actually stored");
        assertEquals(2, repo.findCount(id), "a failed load is not remembered as still in flight");
    }

    /**
     * A repository that throws instead of returning a failed future: the read has to fail, and the
     * key has to stay usable. Publishing the shared handle before the load is started means a throw
     * would otherwise strand it - every later reader joining a future nobody is left to complete.
     */
    @Test
    void a_find_that_throws_fails_the_read_instead_of_wedging_the_key() throws Exception {
        UUID id = UUID.randomUUID();
        repo.put(new Bank(id, 7));
        repo.throwOnFind(id, () -> new IllegalStateException("driver blew up synchronously"));

        CompletableFuture<Optional<Bank>> broken = manager.resolve(id);

        assertTrue(broken.isCompletedExceptionally(), "the throw becomes this read's failure");
        assertThrows(CompletionException.class, broken::join);
        assertEquals(1, manager.stats().loadFailureCount(), "and it is counted as a failed load");

        assertEquals(7L, manager.resolve(id).get(5, TimeUnit.SECONDS)
                        .map(Bank::getCoins).orElse(-1L).longValue(),
                "the next reader gets a load of its own, not a promise nobody will complete");
    }

    /**
     * A synchronous backend runs the load's continuation inline, so a continuation that resolves
     * another key - an account alias hop - re-enters the manager from inside the first load. The
     * two keys here share a {@code ConcurrentHashMap} bin (equal hash codes), which is the case that
     * answers a re-entrant {@code compute*} with {@code IllegalStateException: Recursive update}.
     */
    @Test
    void an_alias_hop_resolved_from_inside_a_load_does_not_re_enter_the_in_flight_map() {
        assertEquals("Aa".hashCode(), "BB".hashCode(), "the two keys must share a bin for this to bite");

        ScriptedRepository<String, Account> accountRepo = new ScriptedRepository<>(Account::getName);
        EntityDescriptor<String, Account> descriptor = EntityDescriptor.builder(String.class, Account.class)
                .collection("single_flight_aliases")
                .keyExtractor(Account::getName)
                .codec(registry.codec(Account.class))
                .build();
        CachingManager<String, Account> accounts = new CachingManager<>(descriptor,
                storageReturning(accountRepo), CacheOptions.of(CachePolicy.always()), registry);

        Account root = new Account("Aa", UUID.randomUUID());
        Account alias = new Account("BB", UUID.randomUUID());
        accountRepo.put(root);
        accountRepo.put(alias);
        accountRepo.beforeFind("Aa", () -> accounts.resolve("BB").join());

        assertSame(root, accounts.resolve("Aa").join().orElse(null));
        assertSame(alias, accounts.resolve("BB").join().orElse(null),
                "the hop resolved during the first load, and stayed cached");
        assertEquals(1, accountRepo.findCount("BB"), "the hop read the backend once");
    }

    /**
     * The racing shape the single-threaded cases cannot reach: several threads released together on
     * one cold key, so some of them lose the publish race and have to adopt the winner's handle.
     * Whichever way each round lands, the invariant is the same - one backend read, one instance.
     */
    @Test
    void racing_misses_on_one_key_still_read_the_backend_once() throws Exception {
        final int threads = 8;
        pool = Executors.newFixedThreadPool(threads);
        for (int round = 0; round < 100; round++) {
            UUID id = UUID.randomUUID();
            repo.put(new Bank(id, round));
            CyclicBarrier gate = new CyclicBarrier(threads);
            List<Future<Bank>> racers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                racers.add(pool.submit(() -> {
                    gate.await();
                    return manager.resolve(id).join().orElse(null);
                }));
            }
            Bank first = racers.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(first, "round " + round + ": the row exists");
            for (Future<Bank> racer : racers) {
                assertSame(first, racer.get(10, TimeUnit.SECONDS), "round " + round + ": one instance for all");
            }
            assertEquals(1, repo.findCount(id), "round " + round + ": the racing misses collapsed into one read");
        }
    }

    /** A storage that vends a single pre-built repository - lets a test inject a {@link ScriptedRepository}. */
    private static Storage storageReturning(Repository<?, ?> repo) {
        return new Storage() {
            @Override public CompletableFuture<Void> init() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<HealthStatus> health() { return CompletableFuture.completedFuture(HealthStatus.ok(0)); }
            @Override @SuppressWarnings("unchecked")
            public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> d) { return (Repository<K, V>) repo; }
            @Override public StorageLogConfig getStorageLogConfig() { return StorageLogConfig.defaults(); }
            @Override public Storage setStorageLogConfig(StorageLogConfig config) { return this; }
        };
    }
}
