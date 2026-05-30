package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import br.com.finalcraft.evernifecore.storage.tx.TransactionScope;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
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
 * { "_sk": "key-as-string", "_data": "{\"field\":\"value\",...}" }
 * </pre>
 */
public final class MongoStorage implements Storage, TransactionalStorage, SchemaAwareStorage {

    /** Reserved collection used to record applied migration versions. */
    static final String MIGRATIONS_COLLECTION = "_schema_migrations";

    private final MongoConfig config;
    private MongoClient mongoClient;
    private MongoDatabase database;

    /** Registered migrations, sorted by version. Mutated only before migrate() is called. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    public MongoStorage(MongoConfig config) {
        this.config = config;
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

            mongoClient = MongoClients.create(builder.build());
            database    = mongoClient.getDatabase(config.database());
            database.runCommand(new Document("ping", 1));  // verify connection
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.supplyAsync(() -> {
            if (mongoClient != null) {
                mongoClient.close();
                mongoClient = null;
                database    = null;
            }
            repositories.clear();
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        return CompletableFuture.supplyAsync(() -> {
            if (database == null) return HealthStatus.down("Not initialized");
            try {
                long start = System.currentTimeMillis();
                database.runCommand(new Document("ping", 1));
                return HealthStatus.ok(System.currentTimeMillis() - start);
            } catch (Exception e) {
                return HealthStatus.down(e.getMessage());
            }
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  Repository factory
    // ------------------------------------------------------------------

    /** Cache of repositories per collection so {@code ensureIndexes()} runs only once. */
    private final Map<String, MongoRepository<?, ?>> repositories = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        return (Repository<K, V>) repositories.computeIfAbsent(
            descriptor.collection(),
            __ -> {
                MongoRepository<K, V> repo = new MongoRepository<>(
                    descriptor,
                    database.getCollection(descriptor.collection()),
                    null  // no transaction session
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
            MongoTransactionScope scope = new MongoTransactionScope(database, session);

            try {
                R result = work.apply(scope).join();

                if (scope.isRolledBack()) session.abortTransaction();
                else                      session.commitTransaction();

                return result;
            } catch (Exception e) {
                try { session.abortTransaction(); } catch (Exception ignored) {}
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("Mongo transaction failed", e);
            } finally {
                session.close();
            }
        }, StorageExecutors.async());
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
        }, StorageExecutors.async());
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
        }, StorageExecutors.async());
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

            MigrationContext ctx = new MongoMigrationContext(database);

            for (Migration migration : registeredMigrations) {
                if (applied.contains(migration.version())) continue; // already applied

                try {
                    migration.execute(ctx);
                } catch (Exception e) {
                    throw new RuntimeException(
                        "Mongo migration " + migration.version()
                        + " [" + migration.description() + "] failed", e
                    );
                }

                // Record successful application
                migrationsCol.insertOne(new Document()
                    .append("version",     migration.version())
                    .append("description", migration.description())
                    .append("applied_at",  System.currentTimeMillis())
                );
            }
            return null;
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext implementation for Mongo
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
