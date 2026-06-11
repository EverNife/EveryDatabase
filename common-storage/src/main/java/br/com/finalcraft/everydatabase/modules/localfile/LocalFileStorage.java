package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.*;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
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
 *   _schema_migrations.json
 *   playerdata/
 *     550e8400-e29b-41d4-a716-446655440000.json
 *     ...
 * </pre>
 *
 * <p>Each collection is a sub-directory; each entity is a file whose name is
 * {@code key.toString()} (with path-separator characters sanitised to {@code _}).
 *
 * <p>Does <em>not</em> implement {@link TransactionalStorage}.
 * Implements {@link SchemaAwareStorage}.
 */
public final class LocalFileStorage implements Storage, SchemaAwareStorage {

    static final String MIGRATIONS_FILE = "_schema_migrations.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final LocalFileConfig config;
    private final ConcurrentHashMap<String, LocalFileRepository<?, ?>> repositories = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /** Registered migrations, kept sorted by version. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    // ------------------------------------------------------------------
    //  Logging
    // ------------------------------------------------------------------

    private volatile StorageLogConfig logConfig;
    private final StorageLog log;

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    public LocalFileStorage(LocalFileConfig config) {
        this(config, StorageLogConfig.defaults());
    }

    public LocalFileStorage(LocalFileConfig config, StorageLogConfig logConfig) {
        this.config    = config;
        this.logConfig = logConfig;
        this.log       = new StorageLog("localfile", () -> this.logConfig);
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
            } catch (IOException e) {
                throw log.errored(StorageOp.INIT, null,
                    new RuntimeException("LocalFileStorage: failed to create base directory '"
                        + config.baseDirectory() + "'", e));
            }
            log.initialized("dir=" + config.baseDirectory());
            return null;
        }, StorageExecutors.async());
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
        boolean ok = initialized && Files.isDirectory(config.baseDirectory());
        if (!ok) {
            log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                b -> b.detail("base directory not accessible: " + config.baseDirectory()));
        } else {
            log.emit(StorageOp.HEALTH, StorageLogLevel.DEBUG,
                b -> b.detail("dir=" + config.baseDirectory()));
        }
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
                    new LocalFileRepository<>(descriptor, config.baseDirectory(), log);
                try {
                    repo.initDirectory();
                } catch (IOException e) {
                    throw log.errored(StorageOp.TABLE_CREATE, descriptor.collection(),
                        new RuntimeException(
                            "LocalFileStorage: failed to create directory for collection '"
                            + descriptor.collection() + "'", e));
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

    @Override
    public CompletableFuture<Void> migrate() {
        try {
            Set<String> applied = loadAppliedVersionSet();
            MigrationContext ctx = new LocalFileMigrationContext(this);

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
                            "LocalFile migration " + migration.version()
                            + " [" + migration.description() + "] failed", e));
                }

                recordApplied(migration);
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
            throw log.errored(StorageOp.MIGRATION_COMPLETE, null,
                new RuntimeException("LocalFile: failed to write migration tracking file", e));
        }
    }

    // ------------------------------------------------------------------
    //  Private: migration tracking POJO
    // ------------------------------------------------------------------

    static final class AppliedEntry {
        public String version;
        public String description;
        public long   applied_at;

        public AppliedEntry() {}

        AppliedEntry(String version, String description, long applied_at) {
            this.version     = version;
            this.description = description;
            this.applied_at  = applied_at;
        }
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext
    // ------------------------------------------------------------------

    private static final class LocalFileMigrationContext implements MigrationContext {

        private final LocalFileStorage storage;

        LocalFileMigrationContext(LocalFileStorage storage) {
            this.storage = storage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(storage))       return (T) storage;
            if (type == Path.class)             return (T) storage.baseDirectory();
            if (type == LocalFileStorage.class) return (T) storage;
            throw new IllegalArgumentException(
                "LocalFileStorage migration context does not provide: " + type.getName()
                + " (available: LocalFileStorage, Path)"
            );
        }
    }
}
