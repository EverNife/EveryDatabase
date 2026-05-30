package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-file-system {@link Storage} backend.
 *
 * <p>Directory structure on disk:
 * <pre>
 * &lt;baseDirectory&gt;/
 *   _schema_migrations.json      ← migration tracking (this class manages it)
 *   playerdata/
 *     550e8400-e29b-41d4-a716-446655440000.json
 *     ...
 *   economy/
 *     ...
 * </pre>
 *
 * <p>Each collection is a sub-directory; each entity is a JSON file whose name
 * is {@code key.toString()} (with path-separator characters sanitised to {@code _}).
 *
 * <p>Does <em>not</em> implement
 * {@link br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage} - local
 * files have no native transaction support.
 *
 * <p>Implements {@link SchemaAwareStorage}: applied migrations are recorded in
 * {@value #MIGRATIONS_FILE} inside the base directory.
 * Register migrations with {@link #register(List)} and call {@link #migrate()}
 * before performing CRUD operations.
 */
public final class LocalFileStorage implements Storage, SchemaAwareStorage {

    /** File used to record applied migration versions. */
    static final String MIGRATIONS_FILE = "_schema_migrations.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final LocalFileConfig config;
    private final ConcurrentHashMap<String, LocalFileRepository<?, ?>> repositories = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /** Registered migrations, kept sorted by version. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    public LocalFileStorage(LocalFileConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    //  Package-visible config accessor (used by LocalFileMigration context)
    // ------------------------------------------------------------------

    Path baseDirectory() {
        return config.baseDirectory();
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> init() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(config.baseDirectory());
                initialized = true;
                return null;
            } catch (IOException e) {
                throw new RuntimeException("LocalFileStorage: failed to create base directory '"
                    + config.baseDirectory() + "'", e);
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> close() {
        repositories.clear();
        initialized = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        boolean ok = initialized && Files.isDirectory(config.baseDirectory());
        return CompletableFuture.completedFuture(
            ok ? HealthStatus.ok(0)
               : HealthStatus.down("Base directory not accessible: " + config.baseDirectory())
        );
    }

    // ------------------------------------------------------------------
    //  Repository factory
    // ------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        return (Repository<K, V>) repositories.computeIfAbsent(
            descriptor.collection(),
            k -> {
                LocalFileRepository<K, V> repo =
                    new LocalFileRepository<>(descriptor, config.baseDirectory());
                try {
                    repo.initDirectory();
                } catch (IOException e) {
                    throw new RuntimeException(
                        "LocalFileStorage: failed to create directory for collection '"
                        + descriptor.collection() + "'", e);
                }
                return repo;
            }
        );
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
            List<AppliedEntry> entries = loadTrackingFile();
            if (entries.isEmpty()) return SchemaVersion.none();
            // Entries are stored in insertion order (already version-sorted)
            AppliedEntry latest = entries.get(entries.size() - 1);
            return new SchemaVersion(latest.version, latest.applied_at);
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<List<Migration>> pending() {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> applied = loadAppliedVersionSet();
            List<Migration> pending = new ArrayList<>();
            for (Migration m : registeredMigrations) {
                if (!applied.contains(m.version())) pending.add(m);
            }
            return pending;
        }, StorageExecutors.async());
    }

    /**
     * Applies all pending migrations in version order.
     *
     * <p>Runs synchronously on the calling thread so that migrations can freely
     * dispatch I/O to {@link FCScheduler} without risk of thread starvation.
     * The returned {@link CompletableFuture} is always already completed when returned.
     */
    @Override
    public CompletableFuture<Void> migrate() {
        try {
            Set<String> applied = loadAppliedVersionSet();
            MigrationContext ctx = new LocalFileMigrationContext(this);

            for (Migration migration : registeredMigrations) {
                if (applied.contains(migration.version())) continue;

                try {
                    migration.execute(ctx);
                } catch (Exception e) {
                    throw new RuntimeException(
                        "LocalFile migration " + migration.version()
                        + " [" + migration.description() + "] failed", e
                    );
                }

                // Record the successful application
                recordApplied(migration);
                applied.add(migration.version());
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    // ------------------------------------------------------------------
    //  Migration tracking helpers
    // ------------------------------------------------------------------

    private Path trackingFilePath() {
        return config.baseDirectory().resolve(MIGRATIONS_FILE);
    }

    private List<AppliedEntry> loadTrackingFile() {
        Path path = trackingFilePath();
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(path);
            CollectionType listType =
                MAPPER.getTypeFactory().constructCollectionType(List.class, AppliedEntry.class);
            return MAPPER.readValue(bytes, listType);
        } catch (Exception e) {
            // Corrupted tracking file → treat as empty; will re-apply if needed
            return new ArrayList<>();
        }
    }

    private Set<String> loadAppliedVersionSet() {
        Set<String> applied = new HashSet<>();
        for (AppliedEntry e : loadTrackingFile()) applied.add(e.version);
        return applied;
    }

    private void recordApplied(Migration migration) {
        List<AppliedEntry> entries = loadTrackingFile();
        entries.add(new AppliedEntry(
            migration.version(),
            migration.description(),
            System.currentTimeMillis()
        ));
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(trackingFilePath(), bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (Exception e) {
            throw new RuntimeException("LocalFile: failed to write migration tracking file", e);
        }
    }

    // ------------------------------------------------------------------
    //  Private: migration tracking POJO
    // ------------------------------------------------------------------

    /** Jackson-serialisable entry in {@value #MIGRATIONS_FILE}. */
    static final class AppliedEntry {
        public String version;
        public String description;
        public long   applied_at;

        /** Required by Jackson. */
        public AppliedEntry() {}

        AppliedEntry(String version, String description, long applied_at) {
            this.version     = version;
            this.description = description;
            this.applied_at  = applied_at;
        }
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext implementation for LocalFile
    // ------------------------------------------------------------------

    private static final class LocalFileMigrationContext implements MigrationContext {

        private final LocalFileStorage storage;

        LocalFileMigrationContext(LocalFileStorage storage) {
            this.storage = storage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(storage))                    return (T) storage;
            if (type == Path.class)                          return (T) storage.baseDirectory();
            if (type == LocalFileStorage.class)              return (T) storage;
            throw new IllegalArgumentException(
                "LocalFileStorage migration context does not provide: " + type.getName()
                + " (available: LocalFileStorage, Path)"
            );
        }
    }
}
