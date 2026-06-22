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
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionScope;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
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
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.supplyAsync(() -> {
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
        if (!descriptor.codec().isJsonCodec()) {
            throw new IllegalArgumentException(
                "MongoStorage requires a JSON codec (e.g. JacksonJsonCodec), but descriptor '"
                + descriptor.collection() + "' uses '" + descriptor.codec().contentType() + "'. "
                + "YAML and other non-JSON codecs are only supported by LocalFileStorage.");
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
        return CompletableFuture.supplyAsync(() -> {
            ClientSession session = mongoClient.startSession();
            session.startTransaction();
            MongoTransactionScope scope = new MongoTransactionScope(database, session, log);
            long startMs = System.currentTimeMillis();
            log.txBegin(null);

            try {
                R result = work.apply(scope).join();

                if (scope.isRolledBack()) {
                    session.abortTransaction();
                    log.txRollback(null, System.currentTimeMillis() - startMs, null);
                } else {
                    session.commitTransaction();
                    log.txCommit(null, System.currentTimeMillis() - startMs);
                }

                return result;
            } catch (Exception e) {
                try {
                    session.abortTransaction();
                    log.txRollback(null, System.currentTimeMillis() - startMs, e);
                } catch (Exception ignored) {}
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("Mongo transaction failed", e);
            } finally {
                session.close();
            }
        }, StorageExecutors.get());
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
