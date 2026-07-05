package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.*;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;
import br.com.finalcraft.everydatabase.schema.Migrations;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionScope;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.*;
import org.bson.Document;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MongoDB {@link Storage} backend.
 *
 * <p>Implements {@link TransactionalStorage}: multi-document transactions require a
 * MongoDB replica set (MongoDB 4.0+). On standalone deployments, calling
 * {@link #inTransaction} will throw at runtime.
 *
 * <p>Implements {@link SchemaAwareStorage}: applied migrations are tracked in the
 * reserved {@value #MIGRATIONS_COLLECTION} collection as documents:
 * <pre>
 * { "version": "001", "description": "...", "applied_at": 1234567890 }
 * </pre>
 * Register migrations with {@link #register(List)} before calling {@link #migrate()}.
 *
 * <p>Each entity collection stores documents as:
 * <pre>
 * { "_id": "key-as-string", "storage_data": { "field": "value", ... } }
 * </pre>
 * where {@code storage_data} is a native BSON sub-document. See {@link MongoRepository}
 * for the full document shape (including {@code _idx_*} and {@code lock_version} fields).
 */
public final class MongoStorage implements Storage, TransactionalStorage, SchemaAwareStorage, ChangeFeedStorage {

    /** Reserved collection used to record applied migration versions. */
    static final String MIGRATIONS_COLLECTION = "_schema_migrations";

    private final MongoConfig config;
    /** Written by init()/close() on an executor thread, read everywhere - volatile for visibility. */
    private volatile MongoClient mongoClient;
    private volatile MongoDatabase database;

    /** Stable per-instance origin id (Mongo events carry no app identity, so it is not stamped). */
    private final String originId = "mongo-" + UUID.randomUUID();
    /** In-process change-feed dispatcher; fed by the change-stream listener thread. */
    private final ChangeFeedSupport changeFeed = new ChangeFeedSupport();
    /** Lazily started on first subscribe; the change-stream listener thread. */
    private volatile MongoChangeFeed changeFeedSource;

    /** Registered migrations, sorted by version. Mutated only before migrate() is called. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    /**
     * Marks the calling thread as inside an active {@link #inTransaction} scope, so a nested call is
     * rejected (a nested transaction would open a second session and commit independently).
     */
    private final ThreadLocal<Boolean> inTransactionOnThread = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ------------------------------------------------------------------
    //  Logging
    // ------------------------------------------------------------------

    private volatile StorageLogConfig logConfig;
    private final StorageLog log;

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    public MongoStorage(MongoConfig config) {
        this(config, StorageLogConfig.defaults());
    }

    public MongoStorage(MongoConfig config, StorageLogConfig logConfig) {
        this.config    = config;
        this.logConfig = logConfig;
        this.log       = new StorageLog("mongo", () -> this.logConfig);
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
        return CompletableFuture.supplyAsync(() -> {
            // Serialize the lifecycle transition: without the lock, two concurrent init() calls both
            // observe mongoClient==null, build two clients, and one assignment orphans a live
            // MongoClient (connection pool) that is never closed.
            synchronized (this) {
            if (mongoClient != null && database != null) {
                // Idempotent: a second init() without an intervening close() must not build a
                // new client over the live one (the old client's connections would leak).
                return null;
            }
            MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.connectionString()));

            config.connectTimeout().ifPresent(timeout ->
                builder.applyToSocketSettings(b ->
                    b.connectTimeout((int) timeout.toMillis(), TimeUnit.MILLISECONDS)
                )
            );

            try {
                mongoClient = MongoClients.create(builder.build());
                database    = mongoClient.getDatabase(config.database());
                database.runCommand(new Document("ping", 1));  // verify connection
            } catch (Exception e) {
                throw log.errored(StorageOp.INIT, null,
                    new RuntimeException("Mongo: failed to connect to " + config.connectionString(), e));
            }
            log.initialized("db=" + config.database() + " uri=" + config.connectionString());
            return null;
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {   // mutually exclusive with init()'s transition
                MongoChangeFeed source = changeFeedSource;
                if (source != null) {
                    source.stop();   // stop the change-stream thread before the client goes away
                    changeFeedSource = null;
                }
                changeFeed.closeAll();
                if (mongoClient != null) {
                    mongoClient.close();
                    mongoClient = null;
                    database    = null;
                }
                repositories.clear();
                log.closed();
                return null;
            }
        }, StorageExecutors.get());
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
        ensureChangeFeedStarted();
        return changeFeed.subscribe(listener);
    }

    /** Lazily starts the change-stream listener on first subscribe (requires {@link #init()}). */
    private synchronized void ensureChangeFeedStarted() {
        if (changeFeedSource != null) {
            return;
        }
        MongoDatabase db = database;
        if (db == null) {
            throw new IllegalStateException(
                "MongoStorage.subscribe() requires init() first (no database connection yet).");
        }
        MongoChangeFeed source = new MongoChangeFeed(db, changeFeed, log);
        source.start();
        changeFeedSource = source;
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        return CompletableFuture.supplyAsync(() -> {
            if (database == null) {
                log.emit(StorageOp.HEALTH, StorageLogLevel.WARN, b -> b.detail("not initialized"));
                return HealthStatus.down("Not initialized");
            }
            try {
                long start = System.currentTimeMillis();
                database.runCommand(new Document("ping", 1));
                long ping = System.currentTimeMillis() - start;
                log.emit(StorageOp.HEALTH, StorageLogLevel.DEBUG,
                    b -> b.durationMs(ping).detail("connected=true"));
                return HealthStatus.ok(ping);
            } catch (Exception e) {
                log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                    b -> b.detail("ping failed: " + e.getMessage()).error(e));
                return HealthStatus.down(e.getMessage());
            }
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Repository factory
    // ------------------------------------------------------------------

    /** Cache of repositories per collection so {@code ensureIndexes()} runs only once. */
    private final Map<String, MongoRepository<?, ?>> repositories = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        if (database == null) {
            throw new IllegalStateException(
                "MongoStorage.repository() called before init() (or after close()); call init() first. "
                + "Requested collection: '" + descriptor.collection() + "'.");
        }
        if (!descriptor.codec().isJsonCodec()) {
            throw new IllegalArgumentException(
                "MongoStorage requires a JSON codec (e.g. JacksonJsonCodec), but descriptor '"
                + descriptor.collection() + "' uses '" + descriptor.codec().contentType() + "'. "
                + "YAML and other non-JSON codecs are only supported by the file backends "
                + "(LocalFileStorage and GroupedFileStorage).");
        }
        return (Repository<K, V>) repositories.computeIfAbsent(
            descriptor.collection(),
            __ -> {
                MongoRepository<K, V> repo = new MongoRepository<>(
                    descriptor,
                    database.getCollection(descriptor.collection()),
                    null,  // no transaction session
                    log
                );
                repo.ensureIndexes();
                return repo;
            }
        );
    }

    // ------------------------------------------------------------------
    //  TransactionalStorage
    // ------------------------------------------------------------------

    @Override
    public <R> CompletableFuture<R> inTransaction(Function<TransactionScope, CompletableFuture<R>> work) {
        // Nesting check on the CALLER thread: a nested call comes from inside the outer work lambda,
        // which runs on the pooled thread that set this marker. A nested transaction would open a
        // second session and commit independently of the outer one, so reject it.
        if (inTransactionOnThread.get()) {
            CompletableFuture<R> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "Nested inTransaction() is not supported: this thread is already inside a transaction. "
                + "A nested call would open a separate session and commit independently of the outer "
                + "transaction. Do all the work inside one transaction scope."));
            return failed;
        }
        return CompletableFuture.supplyAsync(() -> {
            if (mongoClient == null || database == null) {
                throw new IllegalStateException(
                    "Mongo storage is not initialised - call init() before inTransaction().");
            }
            // Open the session BEFORE marking the thread in-transaction: if startSession() fails
            // (transient driver/connection error), the marker is never set, so a later legitimate
            // inTransaction() scheduled on this pooled thread is not falsely rejected as nested.
            // This mirrors SqlStorage, which sets its ThreadLocal only after acquiring the connection.
            // Everything set from here on is unwound by the finally.
            ClientSession session = mongoClient.startSession();
            long startMs = System.currentTimeMillis();
            MongoTransactionScope scope = new MongoTransactionScope(database, session, log);
            inTransactionOnThread.set(Boolean.TRUE);
            try {
                session.startTransaction();
                log.txBegin(null);

                R result = work.apply(scope).join();

                if (scope.isRolledBack()) {
                    session.abortTransaction();
                    log.txRollback(null, System.currentTimeMillis() - startMs, null);
                } else {
                    commitWithRetry(session);
                    log.txCommit(null, System.currentTimeMillis() - startMs);
                }

                return result;
            } catch (Exception e) {
                try {
                    session.abortTransaction();
                    log.txRollback(null, System.currentTimeMillis() - startMs, e);
                } catch (Exception ignored) {}
                if (looksLikeStandaloneTransactionError(e)) {
                    throw new IllegalStateException(
                        "inTransaction() requires a MongoDB replica set (or a mongos). This looks like a "
                        + "standalone deployment, which does not support multi-document transactions - "
                        + "run even a single-node replica set to enable them.", e);
                }
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("Mongo transaction failed", e);
            } finally {
                scope.markEnded();   // any retained-scope use after this fails fast instead of touching a closed session
                session.close();
                inTransactionOnThread.remove();
            }
        }, StorageExecutors.get());
    }

    /**
     * Commits, retrying while the driver cannot know whether the commit landed (the
     * {@code UnknownTransactionCommitResult} error label - e.g. a primary failover
     * mid-commit). Retrying the commit is the driver-documented safe response because the
     * server treats a repeated commit idempotently. A {@code TransientTransactionError} is
     * deliberately NOT retried here: recovering from it means re-running the caller's whole
     * lambda, and only the caller knows whether that work is idempotent.
     */
    private static void commitWithRetry(ClientSession session) {
        for (int attempt = 1; ; attempt++) {
            try {
                session.commitTransaction();
                return;
            } catch (MongoException e) {
                boolean retryable = e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                if (!retryable || attempt >= 3) throw e;
            }
        }
    }

    /**
     * Best-effort detection of the "this is a standalone server" transaction failure, so
     * {@link #inTransaction} can rewrap it with an actionable hint. Message-based, because the driver
     * surfaces this condition under a few different codes across versions.
     */
    private static boolean looksLikeStandaloneTransactionError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("replica set")
            || lower.contains("transaction numbers")
            || lower.contains("sessions are not supported");
    }

    // ------------------------------------------------------------------
    //  SchemaAwareStorage
    // ------------------------------------------------------------------

    @Override
    public SchemaAwareStorage register(List<Migration> migrations) {
        registeredMigrations.addAll(migrations);
        Collections.sort(registeredMigrations, Comparator.comparing(Migration::version));
        Migrations.requireUniqueVersions(registeredMigrations);
        return this;
    }

    @Override
    public CompletableFuture<SchemaVersion> currentVersion() {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> col = database.getCollection(MIGRATIONS_COLLECTION);
            Document latest = col.find()
                .sort(new Document("version", -1))
                .limit(1)
                .first();
            if (latest == null) return SchemaVersion.none();
            return new SchemaVersion(latest.getString("version"), latest.getLong("applied_at"));
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<List<Migration>> pending() {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> col = database.getCollection(MIGRATIONS_COLLECTION);
            // Collect all applied versions into a set for O(1) lookup
            Set<String> applied = new HashSet<>();
            for (Document doc : col.find()) {
                String v = doc.getString("version");
                if (v != null) applied.add(v);
            }
            List<Migration> pending = new ArrayList<>();
            for (Migration m : registeredMigrations) {
                if (!applied.contains(m.version())) pending.add(m);
            }
            return pending;
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> migrate() {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> migrationsCol = database.getCollection(MIGRATIONS_COLLECTION);

            // Snapshot applied versions up-front; avoids re-querying inside the loop
            Set<String> applied = new HashSet<>();
            for (Document doc : migrationsCol.find()) {
                String v = doc.getString("version");
                if (v != null) applied.add(v);
            }

            int pendingCount = 0;
            for (Migration m : registeredMigrations) {
                if (!applied.contains(m.version())) pendingCount++;
            }
            log.migrationPending(pendingCount);

            MigrationContext ctx = new MongoMigrationContext(database);
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
                            "Mongo migration " + migration.version()
                            + " [" + migration.description() + "] failed", e));
                }

                // Record successful application
                migrationsCol.insertOne(new Document()
                    .append("version",     migration.version())
                    .append("description", migration.description())
                    .append("applied_at",  System.currentTimeMillis())
                );
                log.migrationApplied(migration.version(), migration.description(),
                    System.currentTimeMillis() - startMs);
                appliedCount++;
                lastVersion = migration.version();
            }

            String target = lastVersion != null ? lastVersion
                : (registeredMigrations.isEmpty() ? "none"
                   : registeredMigrations.get(registeredMigrations.size() - 1).version());
            log.migrationComplete(appliedCount, skippedCount, target);
            return null;
        }, StorageExecutors.get());
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext
    // ------------------------------------------------------------------

    private static final class MongoMigrationContext implements MigrationContext {

        private final MongoDatabase database;

        MongoMigrationContext(MongoDatabase database) {
            this.database = database;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(database)) return (T) database;
            throw new IllegalArgumentException(
                "MongoStorage migration context does not provide: " + type.getName()
                + " (available: " + MongoDatabase.class.getName() + ")"
            );
        }
    }
}
