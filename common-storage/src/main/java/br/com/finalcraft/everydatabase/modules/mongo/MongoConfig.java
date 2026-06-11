package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.StorageConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the MongoDB storage backend.
 *
 * <p>Transactions require a MongoDB replica set (MongoDB 4.0+).
 * On standalone deployments, calling {@code inTransaction} will throw at runtime.
 *
 * <pre>{@code
 * // Minimal
 * Storage storage = Storages.create(
 *     new MongoConfig("mongodb://localhost:27017", "mc"));
 *
 * // With auth and explicit connect timeout
 * Storage storage = Storages.create(new MongoConfig(
 *     "mongodb://user:pass@host:27017",
 *     "mc",
 *     Optional.of(Duration.ofSeconds(10))));
 * }</pre>
 */
public final class MongoConfig implements StorageConfig {

    private final String connectionString;
    private final String database;
    private final Optional<Duration> connectTimeout;

    /**
     * Full constructor.
     *
     * @param connectionString MongoDB connection string (URI format)
     * @param database         database name to use
     * @param connectTimeout   optional socket connect timeout; empty uses the driver default
     */
    public MongoConfig(String connectionString, String database, Optional<Duration> connectTimeout) {
        this.connectionString = connectionString;
        this.database         = database;
        this.connectTimeout   = connectTimeout;
    }

    /**
     * Convenience constructor - uses the driver's default connect timeout.
     */
    public MongoConfig(String connectionString, String database) {
        this(connectionString, database, Optional.empty());
    }

    public String              connectionString() { return connectionString; }
    public String              database()         { return database; }
    public Optional<Duration>  connectTimeout()   { return connectTimeout; }
}
