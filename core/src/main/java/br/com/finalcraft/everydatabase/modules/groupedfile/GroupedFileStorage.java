package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.*;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.Migrations;
import br.com.finalcraft.everydatabase.schema.MigrationContext;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.schema.SchemaVersion;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import br.com.finalcraft.everydatabase.util.BackendIdentities;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Key-major local-file {@link Storage} backend: one file per key, each holding every collection that
 * shares that key (see {@link GroupedFileConfig} for the layout). The inverse of
 * {@link br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage}'s collection-major layout.
 *
 * <pre>
 * &lt;baseDirectory&gt;/
 *   _schema/migrations.json      (reserved ledger - isolated in a sub-directory, never a key file)
 *   &lt;key&gt;.yml                    (one file per key; a YAML/JSON map collection -&gt; entity)
 * </pre>
 *
 * <p>The per-key lock and the aggregate file primitives live in a single storage-wide
 * {@link KeyFileStore} shared by all repositories, because collections of the same key share one file.
 *
 * <p>Does <em>not</em> implement {@link TransactionalStorage}. Implements {@link SchemaAwareStorage};
 * the migration ledger lives under the reserved {@code _schema/} sub-directory, so it can never collide
 * with a key file (a key named {@code _schema} maps to {@code _schema.<ext>}, a file, not the directory).
 */
public final class GroupedFileStorage implements Storage, SchemaAwareStorage {

    static final String SCHEMA_DIR       = "_schema";
    static final String MIGRATIONS_FILE  = "migrations.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GroupedFileConfig config;
    private final KeyFileStore      keyFileStore;
    private final ConcurrentHashMap<String, GroupedFileRepository<?, ?>> repositories = new ConcurrentHashMap<>();
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

    public GroupedFileStorage(GroupedFileConfig config) {
        this(config, StorageLogConfig.defaults());
    }

    public GroupedFileStorage(GroupedFileConfig config, StorageLogConfig logConfig) {
        this.config       = config;
        this.logConfig    = logConfig;
        this.log          = new StorageLog("groupedfile", () -> this.logConfig);
        this.keyFileStore = new KeyFileStore(config.baseDirectory());
    }

    // ------------------------------------------------------------------
    //  Storage.getStorageLogConfig / setStorageLogConfig
    // ------------------------------------------------------------------

    /**
     * Derived from the canonical base directory plus a machine discriminator: the same absolute
     * path names a different store on every machine, so the path alone would make two servers with
     * an identical layout look like one. A genuinely shared directory (a network mount) is declared
     * through the config's explicit identity.
     */
    @Override
    public String backendIdentity() {
        String explicit = config.sharedIdentity();
        return explicit != null
                ? explicit
                : BackendIdentities.directory("groupedfile", config.baseDirectory(),
                                              BackendIdentities.localMachine());
    }

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
    //  Package-visible accessor (used by GroupedFileMigration context)
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
                    new RuntimeException("GroupedFileStorage: failed to create base directory '"
                        + config.baseDirectory() + "'", e));
            }
            log.initialized("dir=" + config.baseDirectory());
            return null;
        }, StorageExecutors.get());
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
                // Lock the container format (JSON/YAML) from the codec on first use; all collections
                // in this base directory share the files, so they must agree on one format.
                keyFileStore.resolveFormat(descriptor.codec());
                return new GroupedFileRepository<>(descriptor, keyFileStore, log);
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
        Migrations.requireUniqueVersions(registeredMigrations);
        return this;
    }

    @Override
    public CompletableFuture<SchemaVersion> currentVersion() {
        return CompletableFuture.supplyAsync(() -> {
            List<AppliedEntry> entries = loadTrackingFile();
            // The lexicographically greatest applied version, not the last one appended: a lower
            // version registered and applied after a higher one must not regress currentVersion(),
            // matching SQL's ORDER BY version DESC / Mongo's sort and the Migration contract.
            return entries.stream()
                    .max(Comparator.comparing(e -> e.version))
                    .map(e -> new SchemaVersion(e.version, e.applied_at))
                    .orElseGet(SchemaVersion::none);
        }, StorageExecutors.get());
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
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> migrate() {
        try {
            Set<String> applied = loadAppliedVersionSet();
            MigrationContext ctx = new GroupedFileMigrationContext(this);

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
                            "GroupedFile migration " + migration.version()
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
    //  Migration tracking helpers (ledger under reserved <base>/_schema/)
    // ------------------------------------------------------------------

    private Path schemaDir() {
        return config.baseDirectory().resolve(SCHEMA_DIR);
    }

    private Path trackingFilePath() {
        return schemaDir().resolve(MIGRATIONS_FILE);
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
            Files.createDirectories(schemaDir());
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            writeLedgerAtomically(trackingFilePath(), bytes);
        } catch (Exception e) {
            throw log.errored(StorageOp.MIGRATION_COMPLETE, null,
                new RuntimeException("GroupedFile: failed to write migration tracking file", e));
        }
    }

    /**
     * Writes the migration ledger via a temp file + atomic move (same crash-safety the entity
     * files get from KeyFileStore). A plain truncate-then-write could be interrupted mid-write,
     * and a truncated ledger reads back as "nothing applied" - the next boot would re-run every
     * migration over already-migrated data.
     */
    private static void writeLedgerAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static final class GroupedFileMigrationContext implements MigrationContext {

        private final GroupedFileStorage storage;

        GroupedFileMigrationContext(GroupedFileStorage storage) {
            this.storage = storage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(storage))         return (T) storage;
            if (type == Path.class)               return (T) storage.baseDirectory();
            if (type == GroupedFileStorage.class) return (T) storage;
            throw new IllegalArgumentException(
                "GroupedFileStorage migration context does not provide: " + type.getName()
                + " (available: GroupedFileStorage, Path)"
            );
        }
    }
}
