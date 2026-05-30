package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.StorageConfig;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration for the SQL (JDBC + HikariCP) storage backend.
 *
 * <p>Pass a full JDBC URL - the driver and dialect are inferred from the URL prefix:
 * <ul>
 *   <li>{@code jdbc:mariadb://host/db} - MariaDB / MySQL</li>
 *   <li>{@code jdbc:postgresql://host/db} - PostgreSQL</li>
 *   <li>{@code jdbc:sqlite:data.db} - SQLite (override {@code buildUpsertSql} in {@link SqlRepository})</li>
 *   <li>{@code jdbc:h2:mem:test} - H2 in-memory (for integration tests)</li>
 * </ul>
 *
 * <pre>{@code
 * // Minimal
 * Storage storage = Storages.create(
 *     new SqlConfig("jdbc:mariadb://localhost/mc", "root", "pass"));
 *
 * // Full control
 * Storage storage = Storages.create(new SqlConfig(
 *     "jdbc:mariadb://localhost/mc",
 *     "root", "pass",
 *     PoolTuning.defaults(),
 *     Optional.of(Path.of("migrations"))));
 * }</pre>
 */
public final class SqlConfig implements StorageConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final PoolTuning pool;
    private final Optional<Path> migrationsDir;

    /**
     * Full constructor.
     *
     * @param jdbcUrl       full JDBC connection URL
     * @param username      database username
     * @param password      database password
     * @param pool          HikariCP pool tuning parameters
     * @param migrationsDir optional path to a directory containing {@link br.com.finalcraft.evernifecore.storage.schema.Migration} scripts
     */
    public SqlConfig(String jdbcUrl, String username, String password,
                     PoolTuning pool, Optional<Path> migrationsDir) {
        this.jdbcUrl       = jdbcUrl;
        this.username      = username;
        this.password      = password;
        this.pool          = pool;
        this.migrationsDir = migrationsDir;
    }

    /**
     * Convenience constructor - uses {@link PoolTuning#defaults()} and no migrations directory.
     */
    public SqlConfig(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, PoolTuning.defaults(), Optional.empty());
    }

    public String            jdbcUrl()       { return jdbcUrl; }
    public String            username()      { return username; }
    public String            password()      { return password; }
    public PoolTuning        pool()          { return pool; }
    public Optional<Path>    migrationsDir() { return migrationsDir; }
}
