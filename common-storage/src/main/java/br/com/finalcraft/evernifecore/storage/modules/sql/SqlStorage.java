package br.com.finalcraft.evernifecore.storage.modules.sql;

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
    private HikariDataSource dataSource;

    /** Routes the active transactional connection to all repositories on this thread. */
    protected final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    /** Cache of initialised repositories (table is guaranteed to exist). */
    private final ConcurrentHashMap<String, SqlRepository<?, ?>> repositories = new ConcurrentHashMap<>();

    /** Registered migrations, sorted by version. Mutated only before migrate() is called. */
    private final List<Migration> registeredMigrations = new ArrayList<>();

    public SqlStorage(SqlConfig config) {
        this.config = config;
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
            hc.setMaxLifetime(config.pool().idleTimeout().toMillis() * 3L);
            hc.setPoolName("EverNifeCore-SQL");

            dataSource = new HikariDataSource(hc);

            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(5)) throw new RuntimeException("SQL: initial connection validation failed");
            } catch (SQLException e) {
                dataSource.close();
                throw new RuntimeException("SQL: failed to obtain initial connection", e);
            }
            return null;
        }, StorageExecutors.async());
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.supplyAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) dataSource.close();
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
                return valid ? HealthStatus.ok(ping) : HealthStatus.down("Connection.isValid() returned false");
            } catch (SQLException e) {
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
     * Factory method for creating a dialect-specific {@link SqlRepository}.
     *
     * <p>Override this in a subclass (e.g. {@code PostgreSqlStorage}) to return
     * a repository that uses the correct SQL dialect.
     */
    protected <K, V> SqlRepository<K, V> createRepository(EntityDescriptor<K, V> descriptor) {
        return new SqlRepository<>(descriptor, dataSource, txConnection);
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
                    throw new RuntimeException(
                        "SQL: failed to create table for collection '"
                        + descriptor.collection() + "'", e);
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
                throw new RuntimeException("SQL: failed to open transaction connection", e);
            }

            txConnection.set(conn);
            SqlTransactionScope scope = new SqlTransactionScope(this, conn);

            try {
                R result = work.apply(scope).join();

                if (scope.isRolledBack()) conn.rollback();
                else                      conn.commit();

                return result;
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
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
                throw new RuntimeException("SQL: currentVersion() failed", e);
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
                throw new RuntimeException("SQL: pending() failed", e);
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

                for (Migration m : registeredMigrations) {
                    if (applied.contains(m.version())) continue;

                    try {
                        m.execute(ctx);
                    } catch (Exception e) {
                        throw new RuntimeException(
                            "SQL migration " + m.version()
                            + " [" + m.description() + "] failed", e);
                    }

                    recordApplied(conn, m);
                }
                return null;
            } catch (SQLException e) {
                throw new RuntimeException("SQL: migrate() failed", e);
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
