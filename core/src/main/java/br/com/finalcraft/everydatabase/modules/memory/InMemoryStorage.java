package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionScope;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;

import java.util.*;
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
 *
 * <p>Also implements {@link ChangeFeedStorage} as the <b>reference implementation</b>: local
 * writes emit {@link br.com.finalcraft.everydatabase.changefeed.ChangeEvent}s synchronously. Since
 * the store is per-instance there is no cross-process scenario to solve in production, but it makes
 * the capability real and fully testable without a database server.</p>
 */
public final class InMemoryStorage implements Storage, TransactionalStorage, SchemaAwareStorage, ChangeFeedStorage {

    private final ConcurrentHashMap<String, InMemoryRepository<?, ?>> repositories = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /** Stable per-instance origin id, stamped on emitted change events. */
    private final String originId = "mem-" + UUID.randomUUID();
    /** In-process change-feed dispatcher; repositories of this storage emit through it. */
    private final ChangeFeedSupport changeFeed = new ChangeFeedSupport();

    /** Registered migrations, kept sorted by version. Mutated only before {@link #migrate()}. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    /** Ephemeral ledger of applied migrations - lives only for this instance's lifetime. */
    private final List<AppliedEntry> appliedLedger = new ArrayList<>();

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
        changeFeed.closeAll();
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
            k -> new InMemoryRepository<>(descriptor, log, changeFeed, originId)
        );
    }

    // ------------------------------------------------------------------
    //  ChangeFeedStorage
    // ------------------------------------------------------------------

    @Override
    public String originId() {
        return originId;
    }

    @Override
    public ChangeSubscription subscribe(ChangeListener listener) {
        return changeFeed.subscribe(listener);
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
    //  SchemaAwareStorage
    // ------------------------------------------------------------------

    @Override
    public SchemaAwareStorage register(List<Migration> migrations) {
        registeredMigrations.addAll(migrations);
        Collections.sort(registeredMigrations, Comparator.comparing(Migration::version));
        return this;
    }

    @Override
    public CompletableFuture<SchemaVersion> currentVersion() {
        if (appliedLedger.isEmpty()) {
            return CompletableFuture.completedFuture(SchemaVersion.none());
        }
        AppliedEntry latest = appliedLedger.get(appliedLedger.size() - 1);
        return CompletableFuture.completedFuture(new SchemaVersion(latest.version, latest.appliedAt));
    }

    @Override
    public CompletableFuture<List<Migration>> pending() {
        Set<String> applied = appliedVersionSet();
        List<Migration> pending = new ArrayList<>();
        for (Migration m : registeredMigrations) {
            if (!applied.contains(m.version())) pending.add(m);
        }
        return CompletableFuture.completedFuture(pending);
    }

    @Override
    public CompletableFuture<Void> migrate() {
        try {
            Set<String> applied = appliedVersionSet();
            MigrationContext ctx = new InMemoryMigrationContext(this);

            int pendingCount = 0;
            for (Migration m : registeredMigrations) {
                if (!applied.contains(m.version())) pendingCount++;
            }
            log.migrationPending(pendingCount);

            int appliedCount = 0;
            int skippedCount = 0;
            String lastVersion = null;

            for (Migration migration : registeredMigrations) {
                if (applied.contains(migration.version())) {
                    log.migrationSkipped(migration.version());
                    skippedCount++;
                    continue;
                }

                long startMs = System.currentTimeMillis();
                try {
                    migration.execute(ctx);
                } catch (Exception e) {
                    throw log.errored(StorageOp.MIGRATION_APPLY, null,
                        new RuntimeException(
                            "InMemory migration " + migration.version()
                            + " [" + migration.description() + "] failed", e));
                }

                appliedLedger.add(new AppliedEntry(
                    migration.version(), migration.description(), System.currentTimeMillis()));
                applied.add(migration.version());
                log.migrationApplied(migration.version(), migration.description(),
                    System.currentTimeMillis() - startMs);
                appliedCount++;
                lastVersion = migration.version();
            }

            String target = lastVersion != null ? lastVersion
                : (registeredMigrations.isEmpty() ? "none"
                   : registeredMigrations.get(registeredMigrations.size() - 1).version());
            log.migrationComplete(appliedCount, skippedCount, target);

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    private Set<String> appliedVersionSet() {
        Set<String> applied = new HashSet<>();
        for (AppliedEntry e : appliedLedger) applied.add(e.version);
        return applied;
    }

    // ------------------------------------------------------------------
    //  Private: migration ledger entry (in-memory, never serialized)
    // ------------------------------------------------------------------

    private static final class AppliedEntry {
        final String version;
        final String description;
        final long   appliedAt;

        AppliedEntry(String version, String description, long appliedAt) {
            this.version     = version;
            this.description = description;
            this.appliedAt   = appliedAt;
        }
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext
    // ------------------------------------------------------------------

    private static final class InMemoryMigrationContext implements MigrationContext {

        private final InMemoryStorage storage;

        InMemoryMigrationContext(InMemoryStorage storage) {
            this.storage = storage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(storage))      return (T) storage;
            if (type == InMemoryStorage.class) return (T) storage;
            throw new IllegalArgumentException(
                "InMemoryStorage migration context does not provide: " + type.getName()
                + " (available: InMemoryStorage)"
            );
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
