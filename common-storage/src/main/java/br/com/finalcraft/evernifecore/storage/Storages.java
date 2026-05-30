package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileConfig;
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileStorage;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryConfig;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryStorage;
import br.com.finalcraft.evernifecore.storage.modules.mongo.MongoConfig;
import br.com.finalcraft.evernifecore.storage.modules.mongo.MongoStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.postgresql.PostgreSqlStorage;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Factory for creating {@link Storage} instances.
 *
 * <p>Two ways to use this class:
 * <ul>
 *   <li><b>Specific builders</b> ({@link #createSQL}, {@link #createPostgreSQL},
 *       {@link #createH2}, {@link #createMongo}, {@link #createLocalFile},
 *       {@link #createInMemory}) - return the concrete storage type, so backend-specific
 *       APIs (e.g. {@code TransactionalStorage}) are accessible without a cast.</li>
 *   <li><b>Generic builder</b> ({@link #create(StorageConfig)}) - returns the
 *       {@link Storage} interface; useful when the backend is selected at runtime
 *       from configuration.</li>
 * </ul>
 *
 * <pre>{@code
 * // Specific (preferred when the backend is known at compile time)
 * SqlStorage         sql  = Storages.createSQL(new SqlConfig("jdbc:mariadb://localhost/mc", "root", "pass"));
 * PostgreSqlStorage  pg   = Storages.createPostgreSQL(new SqlConfig("jdbc:postgresql://localhost/mc", "u", "p"));
 * H2SqlStorage       h2   = Storages.createH2(new SqlConfig("jdbc:h2:file:./data/storage", "", ""));
 * MongoStorage       mg   = Storages.createMongo(new MongoConfig("mongodb://localhost:27017", "mc"));
 * LocalFileStorage   file = Storages.createLocalFile(new LocalFileConfig(Path.of("data")));
 * InMemoryStorage    mem  = Storages.createInMemory();
 *
 * // Generic (when the config type is decided at runtime)
 * Storage storage = Storages.create(loadConfigFromYaml());
 * storage.init().join();
 * }</pre>
 *
 * <p><b>Note on SQL dialects:</b> {@link SqlConfig} is shared by MySQL/MariaDB, PostgreSQL,
 * and H2 - the dialect comes from the {@link Storage} subclass, not from the config.
 * The generic {@link #create} cannot disambiguate, so it falls back to the MySQL/MariaDB
 * dialect for any {@link SqlConfig}. Use a specific builder when you need PostgreSQL or H2.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Storages {

    /**
     * Creates a {@link SqlStorage} using the default MySQL/MariaDB dialect
     * (backtick identifiers, {@code MEDIUMTEXT}, {@code ON DUPLICATE KEY UPDATE}).
     */
    public static SqlStorage createSQL(SqlConfig config) {
        return new SqlStorage(config);
    }

    /**
     * Creates a {@link PostgreSqlStorage} (double-quote identifiers, {@code TEXT},
     * {@code INSERT ... ON CONFLICT DO UPDATE}).
     */
    public static PostgreSqlStorage createPostgreSQL(SqlConfig config) {
        return new PostgreSqlStorage(config);
    }

    /**
     * Creates an {@link H2SqlStorage}. The JDBC URL decides the H2 mode:
     * <ul>
     *   <li>{@code jdbc:h2:mem:name} - in-memory (ephemeral)</li>
     *   <li>{@code jdbc:h2:file:./path} - embedded file (persists on disk)</li>
     *   <li>{@code jdbc:h2:tcp://host/path} - H2 server (multi-JVM)</li>
     * </ul>
     */
    public static H2SqlStorage createH2(SqlConfig config) {
        return new H2SqlStorage(config);
    }

    /**
     * Creates a {@link MongoStorage}.
     */
    public static MongoStorage createMongo(MongoConfig config) {
        return new MongoStorage(config);
    }

    /**
     * Creates a {@link LocalFileStorage} (one file per entity on the local filesystem).
     */
    public static LocalFileStorage createLocalFile(LocalFileConfig config) {
        return new LocalFileStorage(config);
    }

    /**
     * Creates an {@link InMemoryStorage}. Data is lost when the JVM exits.
     * The {@link InMemoryConfig} parameter is unused but kept for API symmetry;
     * see {@link #createInMemory()} for a no-arg overload.
     */
    public static InMemoryStorage createInMemory(InMemoryConfig config) {
        return new InMemoryStorage();
    }

    /**
     * Creates an {@link InMemoryStorage} with no configuration.
     */
    public static InMemoryStorage createInMemory() {
        return new InMemoryStorage();
    }

    // ------------------------------------------------------------------
    //  Generic builder - delegates to a specific builder by config type
    // ------------------------------------------------------------------

    /**
     * Creates the {@link Storage} matching the given config type by delegating to the
     * corresponding specific builder.
     *
     * <p><b>SQL dialect note:</b> a {@link SqlConfig} always produces a {@link SqlStorage}
     * (MySQL/MariaDB dialect) here. To get PostgreSQL or H2, call
     * {@link #createPostgreSQL} or {@link #createH2} directly.
     *
     * @throws IllegalArgumentException if the config type is not recognised
     */
    public static Storage create(StorageConfig config) {
        if (config instanceof SqlConfig){
            return createSQL((SqlConfig) config);
        }

        if (config instanceof MongoConfig){
            return createMongo((MongoConfig) config);
        }

        if (config instanceof LocalFileConfig){
            return createLocalFile((LocalFileConfig) config);
        }

        if (config instanceof InMemoryConfig){
            return createInMemory((InMemoryConfig) config);
        }

        throw new IllegalArgumentException("Unknown StorageConfig type: " + config.getClass().getName());
    }
}
