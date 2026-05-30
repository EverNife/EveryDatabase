package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.tx.TransactionScope;
import br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
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
 * <p>The default upsert dialect is MySQL/MariaDB ({@code ON DUPLICATE KEY UPDATE}).
 * For other dialects, subclass and override {@link #createRepository(EntityDescriptor)} to return
 * a dialect-specific {@link SqlRepository} subclass (e.g. {@code PostgreSqlRepository}).
 */
public class SqlStorage implements Storage, TransactionalStorage {

    private final SqlConfig config;
    private HikariDataSource dataSource;

    /** Routes the active transactional connection to all repositories on this thread. */
    protected final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    /** Cache of initialised repositories (table is guaranteed to exist). */
    private final ConcurrentHashMap<String, SqlRepository<?, ?>> repositories = new ConcurrentHashMap<>();

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
    //  Repository factory
    // ------------------------------------------------------------------

    /**
     * Exposes the active {@link javax.sql.DataSource} to subclasses without leaking the
     * HikariCP-specific type into the public API.
     *
     * <p>Subclasses that override {@link #createRepository} should obtain the DataSource
     * via this method rather than a direct field reference.
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
}
