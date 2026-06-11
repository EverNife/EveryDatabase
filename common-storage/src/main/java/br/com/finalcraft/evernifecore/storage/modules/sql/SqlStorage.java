package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.*;
import br.com.finalcraft.evernifecore.storage.log.StorageLog;
import br.com.finalcraft.evernifecore.storage.log.StorageLogConfig;
import br.com.finalcraft.evernifecore.storage.log.StorageLogLevel;
import br.com.finalcraft.evernifecore.storage.log.StorageOp;
import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;
import br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage;
import br.com.finalcraft.evernifecore.storage.schema.SchemaVersion;
import br.com.finalcraft.evernifecore.storage.tx.TransactionScope;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * SQL {@link Storage} backend using JDBC with HikariCP connection pooling.
 *
 * <p>Implements {@link TransactionalStorage}: repositories obtained inside an
 * {@link #inTransaction} lambda share the same JDBC {@link Connection} (tracked via
 * {@link ThreadLocal}) with auto-commit disabled.
 * Commit happens on success; rollback happens on exception or {@link TransactionScope#rollback()}.
 *
 * <p>Implements {@link SchemaAwareStorage}: applied migrations are tracked in the
 * reserved {@value #MIGRATIONS_TABLE} table. Register migrations with
 * {@link #register(List)} before calling {@link #migrate()}.
 *
 * <p>The default SQL dialect is MySQL/MariaDB (backtick quoting, {@code MEDIUMTEXT},
 * {@code ON DUPLICATE KEY UPDATE}).
 * Subclasses override {@link #q(String)} and {@link #createRepository(EntityDescriptor)} to
 * provide dialect-specific behaviour (e.g. {@code PostgreSqlStorage}, {@code H2SqlStorage}).
 *
 * <h3>SchemaAware + auto-create coexistence</h3>
 * Both mechanisms exist and are complementary - they do NOT conflict:
 * <ul>
 *   <li>{@code createTableIfAbsent()} - auto-creates the entity table when
 *       {@link #repository(EntityDescriptor)} is first called. Idempotent, always active.</li>
 *   <li>{@link #migrate()} - applies custom DDL/DML registered via {@link #register(List)}.
 *       Used for backfills, auxiliary tables, views, constraints and anything
 *       {@code createTableIfAbsent} does not cover.</li>
 * </ul>
 *
 * <h3>DDL and auto-commit (MySQL/MariaDB)</h3>
 * DDL statements cause an implicit commit in MySQL/MariaDB, so running a migration
 * and recording it in {@value #MIGRATIONS_TABLE} is NOT atomic. If the process dies
 * between the DDL and the INSERT, the migration re-runs on next startup.
 * <b>Write all migrations idempotent</b> ({@code CREATE TABLE IF NOT EXISTS},
 * {@code CREATE INDEX IF NOT EXISTS}, upsert DML) so re-execution is harmless.
 *
 * <h3>Concurrent startup</h3>
 * {@code PRIMARY KEY (version)} on {@value #MIGRATIONS_TABLE} prevents double-insertion
 * when multiple servers run {@link #migrate()} simultaneously. Idempotent DDL absorbs
 * the race on the actual schema changes.
 */
public class SqlStorage implements Storage, TransactionalStorage, SchemaAwareStorage {

    /** Reserved table used to record applied migration versions. */
    static final String MIGRATIONS_TABLE = "_schema_migrations";

    private final SqlConfig config;
    /** Written by init()/close() on an executor thread, read everywhere - volatile for visibility. */
    private volatile HikariDataSource dataSource;

    /** Routes the active transactional connection to all repositories on this thread. */
    protected final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    /** Cache of initialised repositories (table is guaranteed to exist). */
    private final ConcurrentHashMap<String, SqlRepository<?, ?>> repositories = new ConcurrentHashMap<>();

    /** Registered migrations, sorted by version. Mutated only before migrate() is called. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    // ------------------------------------------------------------------
    //  Logging
    // ------------------------------------------------------------------

    /** Live log config - volatile so that setStorageLogConfig() is picked up by all repos. */
    private volatile StorageLogConfig logConfig;

    /**
     * Dispatcher shared by this storage and all its repositories.
     * Reads logConfig live via the volatile field.
     */
    protected final StorageLog log;

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    public SqlStorage(SqlConfig config) {
        this(config, StorageLogConfig.defaults(), "sql");
    }

    public SqlStorage(SqlConfig config, StorageLogConfig logConfig) {
        this(config, logConfig, "sql");
    }

    /**
     * Base constructor used by subclasses to set the correct backend name.
     *
     * @param backendName short identifier used in log lines: "sql", "postgresql", "h2"
     */
    protected SqlStorage(SqlConfig config, StorageLogConfig logConfig, String backendName) {
        this.config    = config;
        this.logConfig = logConfig;
        this.log       = new StorageLog(backendName, () -> this.logConfig);
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
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(config.jdbcUrl());
            hc.setUsername(config.username());
            hc.setPassword(config.password());
            hc.setMinimumIdle(config.pool().minIdle());
            hc.setMaximumPoolSize(config.pool().maxSize());
            hc.setConnectionTimeout(config.pool().connectTimeout().toMillis());
            hc.setIdleTimeout(config.pool().idleTimeout().toMillis());
            hc.setMaxLifetime(config.pool().maxLifetime().toMillis());
            hc.setPoolName("EverNifeCore-SQL");

            try {
                dataSource = new HikariDataSource(hc);
                try (Connection conn = dataSource.getConnection()) {
                    if (!conn.isValid(5)) {
                        dataSource.close();
                        throw log.errored(StorageOp.INIT, null,
                            new RuntimeException("SQL: initial connection validation failed"));
                    }
                }
            } catch (SQLException e) {
                if (dataSource != null) dataSource.close();
                throw log.errored(StorageOp.INIT, null,
                    new RuntimeException("SQL: failed to obtain initial connection", e));
            }
            log.initialized("pool=" + hc.getPoolName() + " url=" + config.jdbcUrl());
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.supplyAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) dataSource.close();
            log.closed();
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<HealthStatus> health() {
        return CompletableFuture.supplyAsync(() -> {
            if (dataSource == null || dataSource.isClosed())
                return HealthStatus.down("DataSource is closed or not initialized");
            long start = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection()) {
                boolean valid = conn.isValid(5);
                long ping = System.currentTimeMillis() - start;
                if (valid) {
                    log.emit(StorageOp.HEALTH, StorageLogLevel.DEBUG,
                        b -> b.durationMs(ping).detail("connected=true"));
                    return HealthStatus.ok(ping);
                } else {
                    log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                        b -> b.detail("Connection.isValid() returned false"));
                    return HealthStatus.down("Connection.isValid() returned false");
                }
            } catch (SQLException e) {
                log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                    b -> b.detail("Connection error: " + e.getMessage()).error(e));
                return HealthStatus.down("Connection error: " + e.getMessage());
            }
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  Dialect quoting
    // ------------------------------------------------------------------

    /**
     * Wraps an SQL identifier in the dialect's quoting character.
     *
     * <p>Default: backtick for MySQL/MariaDB (e.g. {@code `version`}).
     * Subclasses ({@code PostgreSqlStorage}, {@code H2SqlStorage}) override this
     * to return the dialect-appropriate quoting (double-quote for ANSI SQL).
     *
     * <p>This method is used by the SchemaAware migration infrastructure in this class.
     * {@link SqlRepository} has its own parallel {@code q()} for entity table DDL/DML.
     */
    protected String q(String identifier) {
        return "`" + identifier + "`";
    }

    // ------------------------------------------------------------------
    //  Repository factory
    // ------------------------------------------------------------------

    /**
     * Exposes the active {@link javax.sql.DataSource} to subclasses without leaking the
     * HikariCP-specific type into the public API.
     */
    protected javax.sql.DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Exposes the shared {@link StorageLog} dispatcher to subclasses so they can pass it to
     * dialect-specific repositories in {@link #createRepository(EntityDescriptor)}.
     */
    protected StorageLog storageLog() {
        return log;
    }

    /**
     * Factory method for creating a dialect-specific {@link SqlRepository}.
     *
     * <p>Override this in a subclass (e.g. {@code PostgreSqlStorage}) to return
     * a repository that uses the correct SQL dialect.
     */
    protected <K, V> SqlRepository<K, V> createRepository(EntityDescriptor<K, V> descriptor) {
        return new SqlRepository<>(descriptor, dataSource, txConnection, log);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        if (!descriptor.codec().isJsonCodec()) {
            throw new IllegalArgumentException(
                "SqlStorage requires a JSON codec (e.g. JacksonJsonCodec), but descriptor '"
                + descriptor.collection() + "' uses '" + descriptor.codec().contentType() + "'. "
                + "YAML and other non-JSON codecs are only supported by LocalFileStorage.");
        }
        return (Repository<K, V>) repositories.computeIfAbsent(
            descriptor.collection(),
            k -> {
                SqlRepository<K, V> repo = createRepository(descriptor);
                try (Connection conn = dataSource.getConnection()) {
                    repo.createTableIfAbsent(conn);
                } catch (SQLException e) {
                    throw log.errored(StorageOp.TABLE_CREATE, descriptor.collection(),
                        new RuntimeException(
                            "SQL: failed to create table for collection '"
                            + descriptor.collection() + "'", e));
                }
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
            Connection conn;
            try {
                conn = dataSource.getConnection();
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                throw log.errored(StorageOp.TX_BEGIN, null,
                    new RuntimeException("SQL: failed to open transaction connection", e));
            }

            txConnection.set(conn);
            SqlTransactionScope scope = new SqlTransactionScope(this, conn);
            long startMs = System.currentTimeMillis();
            log.txBegin(null);

            try {
                R result = work.apply(scope).join();

                if (scope.isRolledBack()) {
                    conn.rollback();
                    log.txRollback(null, System.currentTimeMillis() - startMs, null);
                } else {
                    conn.commit();
                    log.txCommit(null, System.currentTimeMillis() - startMs);
                }

                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                    log.txRollback(null, System.currentTimeMillis() - startMs, e);
                } catch (SQLException ignored) {}
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("SQL transaction failed", e);
            } finally {
                txConnection.remove();
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  SchemaAwareStorage
    // ------------------------------------------------------------------

    @Override
    public SchemaAwareStorage register(List<Migration> migrations) {
        registeredMigrations.addAll(migrations);
        registeredMigrations.sort(Comparator.comparing(Migration::version));
        return this;
    }

    @Override
    public CompletableFuture<SchemaVersion> currentVersion() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                ensureMigrationsTable(conn);
                String sql = "SELECT " + q("version") + ", " + q("applied_at")
                           + " FROM " + q(MIGRATIONS_TABLE)
                           + " ORDER BY " + q("version") + " DESC LIMIT 1";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (!rs.next()) return SchemaVersion.none();
                    return new SchemaVersion(rs.getString(1), rs.getLong(2));
                }
            } catch (SQLException e) {
                throw log.errored(StorageOp.MIGRATION_PENDING, null,
                    new RuntimeException("SQL: currentVersion() failed", e));
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<List<Migration>> pending() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                ensureMigrationsTable(conn);
                Set<String> applied = readAppliedVersions(conn);
                List<Migration> result = new ArrayList<>();
                for (Migration m : registeredMigrations) {
                    if (!applied.contains(m.version())) result.add(m);
                }
                return result;
            } catch (SQLException e) {
                throw log.errored(StorageOp.MIGRATION_PENDING, null,
                    new RuntimeException("SQL: pending() failed", e));
            }
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> migrate() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                ensureMigrationsTable(conn);
                Set<String> applied = readAppliedVersions(conn);
                MigrationContext ctx = new SqlMigrationContext(conn);

                // Count pending for the pending log
                int pendingCount = 0;
                for (Migration m : registeredMigrations) {
                    if (!applied.contains(m.version())) pendingCount++;
                }
                log.migrationPending(pendingCount);

                int appliedCount = 0;
                int skippedCount = 0;
                String lastVersion = null;

                for (Migration m : registeredMigrations) {
                    if (applied.contains(m.version())) {
                        log.migrationSkipped(m.version());
                        skippedCount++;
                        continue;
                    }

                    long startMs = System.currentTimeMillis();
                    try {
                        m.execute(ctx);
                    } catch (Exception e) {
                        throw log.errored(StorageOp.MIGRATION_APPLY, null,
                            new RuntimeException(
                                "SQL migration " + m.version()
                                + " [" + m.description() + "] failed", e));
                    }

                    recordApplied(conn, m);
                    long elapsed = System.currentTimeMillis() - startMs;
                    log.migrationApplied(m.version(), m.description(), elapsed);
                    appliedCount++;
                    lastVersion = m.version();
                }

                String target = lastVersion != null ? lastVersion
                    : (registeredMigrations.isEmpty() ? "none"
                       : registeredMigrations.get(registeredMigrations.size() - 1).version());
                log.migrationComplete(appliedCount, skippedCount, target);

                return null;
            } catch (SQLException e) {
                throw log.errored(StorageOp.MIGRATION_COMPLETE, null,
                    new RuntimeException("SQL: migrate() failed", e));
            }
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  SchemaAware private helpers
    // ------------------------------------------------------------------

    /**
     * Creates the migrations tracking table if it does not exist yet.
     * Called lazily from every SchemaAware method - idempotent.
     */
    private void ensureMigrationsTable(Connection conn) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + q(MIGRATIONS_TABLE) + " ("
                   + q("version")     + " VARCHAR(255) NOT NULL, "
                   + q("description") + " VARCHAR(255), "
                   + q("applied_at")  + " BIGINT NOT NULL, "
                   + "PRIMARY KEY (" + q("version") + "))";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** Reads all applied version strings from the migrations tracking table. */
    private Set<String> readAppliedVersions(Connection conn) throws SQLException {
        Set<String> applied = new HashSet<>();
        String sql = "SELECT " + q("version") + " FROM " + q(MIGRATIONS_TABLE);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) applied.add(rs.getString(1));
        }
        return applied;
    }

    /** Inserts a record into the migrations tracking table for the given migration. */
    private void recordApplied(Connection conn, Migration m) throws SQLException {
        String sql = "INSERT INTO " + q(MIGRATIONS_TABLE) + " ("
                   + q("version") + ", " + q("description") + ", " + q("applied_at")
                   + ") VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, m.version());
            ps.setString(2, m.description());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Private: MigrationContext implementation for SQL
    // ------------------------------------------------------------------

    /**
     * Passes the dedicated migration {@link Connection} to {@link Migration#execute(MigrationContext)}.
     *
     * <p>All migrations in a single {@link #migrate()} call share the same connection so they
     * see a consistent view of the schema. This is a dedicated connection from the pool,
     * separate from any {@link TransactionalStorage#inTransaction} scope.
     */
    private static final class SqlMigrationContext implements MigrationContext {

        private final Connection conn;

        SqlMigrationContext(Connection conn) {
            this.conn = conn;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeClient(Class<T> type) {
            if (type.isInstance(conn)) return (T) conn;
            throw new IllegalArgumentException(
                "SqlStorage migration context does not provide: " + type.getName()
                + " (available: " + Connection.class.getName() + ")");
        }
    }
}
