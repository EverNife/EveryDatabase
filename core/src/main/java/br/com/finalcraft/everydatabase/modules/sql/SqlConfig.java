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
    private final String sharedIdentity;

    /**
     * Full constructor, with an explicit backend identity.
     *
     * @param jdbcUrl        full JDBC connection URL
     * @param username       database username
     * @param password       database password
     * @param pool           HikariCP pool tuning parameters
     * @param sharedIdentity explicit identity for the store this URL points at, or {@code null} to
     *                       derive it from the URL (see {@link #sharedIdentity()})
     */
    public SqlConfig(String jdbcUrl, String username, String password, PoolTuning pool, String sharedIdentity) {
        this.jdbcUrl        = jdbcUrl;
        this.username       = username;
        this.password       = password;
        this.pool           = pool;
        this.sharedIdentity = sharedIdentity;
    }

    /**
     * Full constructor.
     *
     * @param jdbcUrl  full JDBC connection URL
     * @param username database username
     * @param password database password
     * @param pool     HikariCP pool tuning parameters
     */
    public SqlConfig(String jdbcUrl, String username, String password, PoolTuning pool) {
        this(jdbcUrl, username, password, pool, null);
    }

    /**
     * Convenience constructor - uses {@link PoolTuning#defaults()}.
     */
    public SqlConfig(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, PoolTuning.defaults());
    }

    /**
     * An explicit identity for the physical store, or {@code null} to derive one from the URL.
     *
     * <p>Set it when the derived identity would be wrong: several servers reaching one shared
     * database through coordinates that differ textually (a host name on one, its IP on another, a
     * tunnel on a third) derive different identities and would stop invalidating each other's
     * caches. Give all of them the same string and they name one store again.
     *
     * <p>When present it IS the identity, verbatim - no type prefix, no machine discriminator - so
     * two different backend kinds can deliberately be told they share a store. Never put a
     * credential in it: the identity travels on change events and may be logged.
     */
    public String     sharedIdentity() { return sharedIdentity; }

    public String     jdbcUrl()  { return jdbcUrl; }
    public String     username() { return username; }
    public String     password() { return password; }
    public PoolTuning pool()     { return pool; }
}
