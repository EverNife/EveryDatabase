package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.StorageConfig;

/**
 * Configuration for the SQL (JDBC + HikariCP) storage backend.
 *
 * <p>Pass a full JDBC URL; the JDBC driver is resolved from the URL prefix:
 * <ul>
 *   <li>{@code jdbc:mariadb://host/db} - MariaDB / MySQL</li>
 *   <li>{@code jdbc:postgresql://host/db} - PostgreSQL</li>
 *   <li>{@code jdbc:h2:mem:test} - H2 in-memory (for integration tests)</li>
 * </ul>
 *
 * <p>The <b>SQL dialect is not inferred from the URL</b> - it is decided by the storage
 * subclass. {@code Storages.create(SqlConfig)} always builds the MySQL/MariaDB dialect;
 * call {@code Storages.createPostgreSQL} / {@code Storages.createH2} to select PostgreSQL or H2.
 *
 * <pre>{@code
 * // Minimal - MySQL/MariaDB dialect
 * SqlStorage storage = Storages.createSQL(
 *     new SqlConfig("jdbc:mariadb://localhost/mc", "root", "pass"));
 *
 * // Full control
 * SqlStorage storage = Storages.createSQL(new SqlConfig(
 *     "jdbc:mariadb://localhost/mc",
 *     "root", "pass",
 *     PoolTuning.defaults()));
 * }</pre>
 */
public final class SqlConfig implements StorageConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final PoolTuning pool;

    /**
     * Full constructor.
     *
     * @param jdbcUrl  full JDBC connection URL
     * @param username database username
     * @param password database password
     * @param pool     HikariCP pool tuning parameters
     */
    public SqlConfig(String jdbcUrl, String username, String password, PoolTuning pool) {
        this.jdbcUrl  = jdbcUrl;
        this.username = username;
        this.password = password;
        this.pool     = pool;
    }

    /**
     * Convenience constructor - uses {@link PoolTuning#defaults()}.
     */
    public SqlConfig(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, PoolTuning.defaults());
    }

    public String     jdbcUrl()  { return jdbcUrl; }
    public String     username() { return username; }
    public String     password() { return password; }
    public PoolTuning pool()     { return pool; }
}
