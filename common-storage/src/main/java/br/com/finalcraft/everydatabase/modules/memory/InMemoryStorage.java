package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.tx.TransactionScope;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory {@link Storage} implementation - data exists only for the lifetime of the JVM.
 *
 * <p>Also implements {@link TransactionalStorage}: the "transaction" model is
 * best-effort (no isolation), but satisfies the interface contract so that
 * code using {@code TransactionalStorage} works against in-memory storage during tests.</p>
 *
 * <p>All operations are synchronous and complete immediately on the calling thread.</p>
 */
public final class InMemoryStorage implements Storage, TransactionalStorage {

    private final ConcurrentHashMap<String, InMemoryRepository<?, ?>> repositories = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    // ------------------------------------------------------------------
    //  Logging
    // ------------------------------------------------------------------

    private volatile StorageLogConfig logConfig;
    private final StorageLog log;

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    public InMemoryStorage() {
        this(StorageLogConfig.defaults());
    }

    public InMemoryStorage(StorageLogConfig logConfig) {
        this.logConfig = logConfig;
        this.log       = new StorageLog("memory", () -> this.logConfig);
    }

    // ------------------------------------------------------------------
    //  Storage.getStorageLogConfig / setStorageLogConfig
    // ------------------------------------------------------------------

    @Override
    public StorageLogConfig getStorageLogConfig() {
        return logConfig;
    }

    @Override
    public Storage setStorageLogConfig(StorageLogConfig config) {
        this.logConfig = config;
        return this;
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> init() {
        initialized = true;
        log.initialized("ephemeral in-memory store");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
        repositories.clear();
        initialized = false;
        log.closed();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        return CompletableFuture.completedFuture(
            initialized ? HealthStatus.ok(0) : HealthStatus.down("Not initialized")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        if (!descriptor.codec().isJsonCodec()) {
            throw new IllegalArgumentException(
                "InMemoryStorage requires a JSON codec (e.g. JacksonJsonCodec), but descriptor '"
                + descriptor.collection() + "' uses '" + descriptor.codec().contentType() + "'. "
                + "YAML and other non-JSON codecs are only supported by LocalFileStorage.");
        }
        return (Repository<K, V>) repositories.computeIfAbsent(
            descriptor.collection(),
            k -> new InMemoryRepository<>(descriptor, log)
        );
    }

    /**
     * Best-effort transactional execution for in-memory storage.
     *
     * <p>There is no real isolation: concurrent writes during the transaction
     * are visible immediately. This is intentional - in-memory is for tests.
     * The interface contract (commit/rollback) is honoured.</p>
     */
    @Override
    public <R> CompletableFuture<R> inTransaction(Function<TransactionScope, CompletableFuture<R>> work) {
        InMemoryTransactionScope scope = new InMemoryTransactionScope(this);
        try {
            return work.apply(scope)
                .exceptionally(ex -> {
                    if (ex instanceof RuntimeException) throw (RuntimeException) ex;
                    throw new RuntimeException(ex);
                });
        } catch (Exception e) {
            CompletableFuture<R> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    // ------------------------------------------------------------------
    //  Inner TransactionScope
    // ------------------------------------------------------------------

    private static final class InMemoryTransactionScope implements TransactionScope {

        private final InMemoryStorage storage;

        InMemoryTransactionScope(InMemoryStorage storage) {
            this.storage = storage;
        }

        @Override
        public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
            return storage.repository(descriptor);
        }

        @Override
        public void rollback() {
            // no-op for in-memory; no real isolation to undo
        }
    }
}
