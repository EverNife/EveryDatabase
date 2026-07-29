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
import br.com.finalcraft.everydatabase.keymajor.KeyBatch;
import br.com.finalcraft.everydatabase.keymajor.KeyBundle;
import br.com.finalcraft.everydatabase.keymajor.KeyMajorStorage;
import br.com.finalcraft.everydatabase.util.BackendIdentities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;

/**
 * Key-major local-file {@link Storage} backend: one file per key, each holding every collection that
 * shares that key (see {@link GroupedFileConfig} for the layout). The inverse of
 * {@link br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage}'s collection-major layout.
 *
 * <pre>
 * &lt;baseDirectory&gt;/
 *   _schema/layout.json          (reserved - what format the files below are written in)
 *   _schema/migrations.json      (reserved ledger - isolated in a sub-directory, never a key file)
 *   &lt;key&gt;.yml                    (one file per key; a YAML/JSON map collection -&gt; entity)
 * </pre>
 *
 * <p>The per-key lock and the aggregate file primitives live in a single storage-wide
 * {@link KeyFileStore} shared by all repositories, because collections of the same key share one file.
 *
 * <p>The directory describes itself: {@link GroupedFileLayout} records the container format under
 * {@code _schema/} and refuses to open a directory whose files were written in the other one, which
 * would otherwise read as an empty collection.
 *
 * <p>Implements {@link KeyMajorStorage}: one key's collections share one file, so they can be read
 * with one parse and written with one atomic move. That is atomicity <em>per key</em> and nothing
 * more - this backend still does <em>not</em> implement {@link TransactionalStorage}, and nothing
 * here spans two keys.
 *
 * <p>Implements {@link SchemaAwareStorage};
 * the migration ledger lives under the reserved {@code _schema/} sub-directory, so it can never collide
 * with a key file (a key named {@code _schema} maps to {@code _schema.<ext>}, a file, not the directory).
 */
public final class GroupedFileStorage implements Storage, SchemaAwareStorage, KeyMajorStorage {

    static final String SCHEMA_DIR       = "_schema";
    static final String MIGRATIONS_FILE  = "migrations.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GroupedFileConfig  config;
    private final ContainerFormat    format = new ContainerFormat();
    private final GroupedFileLayout  layout;
    /** The store for collections with no declared key space: the base directory itself. */
    private final KeyFileStore       rootStore;
    /** One store per declared key space, created on first use. Key: the key-space name. */
    private final ConcurrentHashMap<String, KeyFileStore> keySpaceStores = new ConcurrentHashMap<>();
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
        this.layout       = new GroupedFileLayout(config.baseDirectory());
        this.rootStore    = new KeyFileStore(config.baseDirectory(), format, config.rootCacheSize());
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
    public SyncParticipation syncParticipation() {
        return config.syncParticipation();
    }

    /** A directory is machine-local by definition unless an explicit identity declares it shared. */
    @Override
    public boolean isMachineLocalIdentity() {
        return config.sharedIdentity() == null;
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

    /** Package-visible so tests can assert how often an aggregate document is actually parsed. */
    KeyFileStore keyFileStore() {
        return rootStore;
    }

    /**
     * The store owning {@code collection}'s files: the one for its declared key space, or the base
     * directory's when it declared none. Two collections in different key spaces resolve to different
     * stores, hence different directories and different lock maps.
     */
    private KeyFileStore storeFor(String collection) {
        String keySpace = config.keySpaceOf(collection);
        if (keySpace == null) return rootStore;
        return keySpaceStores.computeIfAbsent(keySpace, name ->
            new KeyFileStore(config.baseDirectory().resolve(name), format, config.rootCacheSize(),
                             config.partitionerOf(name)));
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
        keySpaceStores.clear();
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
                format.resolve(descriptor.codec());
                // ...and the directory gets a say too: it may already hold this collection's files
                // in another format, or in another key space, either of which would silently hide
                // them from a repository opened this way.
                String keySpace = config.keySpaceOf(descriptor.collection());
                layout.reconcile(format, descriptor.collection(), keySpace,
                                 keySpace == null ? null : config.partitionerOf(keySpace));
                return new GroupedFileRepository<>(descriptor, storeFor(descriptor.collection()), log);
            }
        );
    }

    // ------------------------------------------------------------------
    //  KeyMajorStorage
    //
    //  This is the backend's own shape surfaced as API: every collection of one key already lives in
    //  one file behind one lock, so reading them together is one parse and writing them together is
    //  one atomic move. Doing it collection by collection pays N times for the same file - and, on
    //  the write side, is not even equivalent: a crash between two of the N saves leaves the key
    //  half-updated, which one batch cannot do.
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<KeyBundle> loadKey(Object key, EntityDescriptor<?, ?>... descriptors) {
        final List<GroupedFileRepository<?, ?>> repositories;
        final KeyFileStore store;
        try {
            repositories = repositoriesOf("loadKey", key, descriptors);
            store        = sharedStore("loadKey", descriptors);
        } catch (RuntimeException e) {
            return failed(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            String sanitized = KeyFileStore.sanitize(key);
            ReadWriteLock lock = store.lockFor(sanitized);
            lock.readLock().lock();
            try {
                // One read for the whole bundle - the point of the capability.
                ObjectNode root = store.cachedRoot(store.keyFile(sanitized));
                Map<String, Object> loaded = new LinkedHashMap<>();
                for (GroupedFileRepository<?, ?> repository : repositories) {
                    JsonNode sub = root == null ? null : root.get(repository.collection());
                    loaded.put(repository.collection(), sub == null ? null : repository.decodeSub(sub));
                }
                return (KeyBundle) new LoadedKeyBundle(loaded);
            } catch (IOException e) {
                throw log.errored(StorageOp.FIND, null,
                    new RuntimeException("GroupedFile: failed to read key=" + key, e));
            } finally {
                lock.readLock().unlock();
            }
        }, StorageExecutors.get());
    }

    @Override
    public CompletableFuture<Void> batchKey(Object key, Consumer<KeyBatch> writes) {
        if (writes == null) return failed(new IllegalArgumentException("writes cannot be null"));
        return CompletableFuture.supplyAsync(() -> {
            // Collected before any lock is taken: the consumer's code may call back into this
            // storage, and running it under the key's write lock would let it deadlock on itself.
            // It also means an exception thrown here happens before anything on disk is touched.
            CollectedBatch batch = new CollectedBatch();
            writes.accept(batch);
            if (batch.operations.isEmpty()) return null;

            EntityDescriptor<?, ?>[] touched = batch.operations.keySet().toArray(new EntityDescriptor<?, ?>[0]);
            List<GroupedFileRepository<?, ?>> repositories = repositoriesOf("batchKey", key, touched);
            KeyFileStore store = sharedStore("batchKey", touched);

            long startMs = System.currentTimeMillis();
            String sanitized = KeyFileStore.sanitize(key);
            ReadWriteLock lock = store.lockFor(sanitized);
            lock.writeLock().lock();
            try {
                Path file = store.keyFile(sanitized);
                ObjectNode root = store.mutableRoot(file);
                if (root == null) root = store.mapper().createObjectNode();

                int index = 0;
                for (Map.Entry<EntityDescriptor<?, ?>, Object> operation : batch.operations.entrySet()) {
                    GroupedFileRepository<?, ?> repository = repositories.get(index++);
                    if (operation.getValue() == REMOVED) {
                        root.remove(repository.collection());
                    } else {
                        root.set(repository.collection(), encodeUnchecked(repository, operation.getValue()));
                    }
                }

                if (root.size() == 0) {
                    // Nothing left for this key - same rule as a delete that empties the file.
                    if (Files.exists(file)) store.delete(file);
                } else {
                    store.writeAtomic(file, root);
                }
                log.savedBatch(collectionNames(repositories), batch.operations.size(),
                               System.currentTimeMillis() - startMs);
                return null;
            } catch (IOException e) {
                throw log.errored(StorageOp.SAVE, null,
                    new RuntimeException("GroupedFile: failed to write key=" + key, e));
            } finally {
                lock.writeLock().unlock();
            }
        }, StorageExecutors.get());
    }

    /**
     * The repositories for {@code descriptors}, in order, after checking that the key is one they
     * can address. The key is untyped on this API - it names a file, and one file serves collections
     * with different key types - so the check has to happen here.
     */
    private List<GroupedFileRepository<?, ?>> repositoriesOf(String what, Object key,
                                                             EntityDescriptor<?, ?>... descriptors) {
        if (key == null) throw new IllegalArgumentException("GroupedFileStorage." + what + ": key cannot be null");
        if (descriptors == null || descriptors.length == 0) {
            throw new IllegalArgumentException("GroupedFileStorage." + what + ": at least one descriptor is required");
        }
        List<GroupedFileRepository<?, ?>> resolved = new ArrayList<>(descriptors.length);
        for (EntityDescriptor<?, ?> descriptor : descriptors) {
            if (!descriptor.keyType().isInstance(key)) {
                throw new IllegalArgumentException(
                    "GroupedFileStorage." + what + ": collection '" + descriptor.collection() + "' is keyed by "
                    + descriptor.keyType().getSimpleName() + ", but the key given is a "
                    + key.getClass().getSimpleName() + " (" + key + ").");
            }
            resolved.add((GroupedFileRepository<?, ?>) repository(descriptor));
        }
        return resolved;
    }

    /**
     * The one store all {@code descriptors} live in.
     *
     * <p>Collections in different key spaces do not share a file, so there is no single read or
     * single write to be had. Accepting them and quietly doing N of each would hide exactly the cost
     * this API exists to remove, so it is refused instead.
     */
    private KeyFileStore sharedStore(String what, EntityDescriptor<?, ?>... descriptors) {
        String keySpace = config.keySpaceOf(descriptors[0].collection());
        for (EntityDescriptor<?, ?> descriptor : descriptors) {
            String other = config.keySpaceOf(descriptor.collection());
            if (!Objects.equals(keySpace, other)) {
                throw new IllegalArgumentException(
                    "GroupedFileStorage." + what + ": collections '" + descriptors[0].collection() + "' ("
                    + describeKeySpace(keySpace) + ") and '" + descriptor.collection() + "' ("
                    + describeKeySpace(other) + ") are stored in different key spaces, so they do not share "
                    + "a file. Group them in one key space, or address them one collection at a time.");
            }
        }
        return storeFor(descriptors[0].collection());
    }

    private static String describeKeySpace(String keySpace) {
        return keySpace == null ? "the base directory" : "key space '" + keySpace + "'";
    }

    private static String collectionNames(List<GroupedFileRepository<?, ?>> repositories) {
        StringBuilder names = new StringBuilder();
        for (GroupedFileRepository<?, ?> repository : repositories) {
            if (names.length() > 0) names.append(',');
            names.append(repository.collection());
        }
        return names.toString();
    }

    @SuppressWarnings("unchecked")
    private static JsonNode encodeUnchecked(GroupedFileRepository<?, ?> repository, Object entity)
            throws IOException {
        return ((GroupedFileRepository<?, Object>) repository).encodeSub(entity);
    }

    private static <T> CompletableFuture<T> failed(RuntimeException e) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(e);
        return future;
    }

    /** Marks a collection as being dropped rather than written, inside a collected batch. */
    private static final Object REMOVED = new Object();

    /** What the caller's {@code Consumer} asked for, in the order it asked. */
    private static final class CollectedBatch implements KeyBatch {

        private final Map<EntityDescriptor<?, ?>, Object> operations = new LinkedHashMap<>();

        @Override
        public <K, V> KeyBatch put(EntityDescriptor<K, V> descriptor, V entity) {
            if (descriptor == null) throw new IllegalArgumentException("descriptor cannot be null");
            if (entity == null)     throw new IllegalArgumentException("entity cannot be null; use remove(...)");
            operations.put(descriptor, entity);
            return this;
        }

        @Override
        public <K, V> KeyBatch remove(EntityDescriptor<K, V> descriptor) {
            if (descriptor == null) throw new IllegalArgumentException("descriptor cannot be null");
            operations.put(descriptor, REMOVED);
            return this;
        }
    }

    /** A bundle over what one read of the key file produced; a {@code null} value means absent. */
    private static final class LoadedKeyBundle implements KeyBundle {

        private final Map<String, Object> byCollection;

        LoadedKeyBundle(Map<String, Object> byCollection) {
            this.byCollection = byCollection;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> Optional<V> get(EntityDescriptor<K, V> descriptor) {
            if (descriptor == null || !byCollection.containsKey(descriptor.collection())) {
                throw new IllegalArgumentException(
                    "KeyBundle: collection '" + (descriptor == null ? null : descriptor.collection())
                    + "' was not part of this read; it holds " + byCollection.keySet() + ".");
            }
            return Optional.ofNullable((V) byCollection.get(descriptor.collection()));
        }

        @Override
        public Set<String> collections() {
            return Collections.unmodifiableSet(byCollection.keySet());
        }

        @Override
        public boolean isEmpty() {
            for (Object value : byCollection.values()) {
                if (value != null) return false;
            }
            return true;
        }
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
