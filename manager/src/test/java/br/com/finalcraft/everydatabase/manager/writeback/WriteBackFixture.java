package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.ScriptedRepository;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.GuildBank;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The wiring the write-back suites share: a scriptable manager, and the two setups a flush needs. */
final class WriteBackFixture {

    private WriteBackFixture() {
    }

    /**
     * A storage vending one pre-built repository, so a manager can be driven by a script. The conflict
     * paths need a backend that enforces optimistic locking, and none of the embedded ones do.
     */
    static Storage storageReturning(Repository<?, ?> repo) {
        return new Storage() {
            @Override public CompletableFuture<Void> init() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<HealthStatus> health() { return CompletableFuture.completedFuture(HealthStatus.ok(0)); }
            @Override @SuppressWarnings("unchecked")
            public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) { return (Repository<K, V>) repo; }
            @Override public StorageLogConfig getStorageLogConfig() { return StorageLogConfig.defaults(); }
            @Override public Storage setStorageLogConfig(StorageLogConfig config) { return this; }
        };
    }

    static EntityDescriptor<UUID, GuildBank> bankDescriptor(RefRegistry registry) {
        return EntityDescriptor.builder(UUID.class, GuildBank.class)
                .collection("guild_banks")
                .keyExtractor(GuildBank::getId)
                .codec(registry.codec(GuildBank.class))
                .build();
    }

    static CachingManager<UUID, GuildBank> bankManagerOver(RefRegistry registry, Storage storage) {
        return new CachingManager<>(bankDescriptor(registry), storage, CacheOptions.of(CachePolicy.always()), registry);
    }

    /** The winner another instance already wrote, and the lost race our next save runs into. */
    static void scriptLostRace(ScriptedRepository<UUID, GuildBank> repo, UUID key, GuildBank winner) {
        repo.put(winner);
        repo.failSave(key, () -> new OptimisticLockException(GuildBank.class, key, 0L, 7L));
    }

    /**
     * The caller's collection pass: the flusher's input contract is that entities arrive already
     * collected AND mark-cleaned, so that a change landing mid-flush re-marks them instead of being
     * swallowed.
     */
    @SafeVarargs
    static <V extends IDirtyable> List<V> collected(V... entities) {
        for (V entity : entities) {
            entity.markClean();
        }
        return Arrays.asList(entities);
    }
}
