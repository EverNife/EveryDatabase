package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Bank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-flight loading: two misses on the same key collapse into one backend read, and a load that
 * fails leaves the key free for the next reader to retry.
 */
class CachingManagerSingleFlightTest {

    private RefRegistry registry;
    private ScriptedRepository<UUID, Bank> repo;
    private CachingManager<UUID, Bank> manager;

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
