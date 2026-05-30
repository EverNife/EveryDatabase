package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.StorageConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the local file-system storage backend.
 *
 * <p>Stores one JSON file per entity, grouped in subdirectories by collection name.
 * Best for development, testing, and single-server deployments with small datasets.
 *
 * <p>Does <em>not</em> support transactions - use {@link br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig}
 * if ACID semantics are required.
 *
 * <pre>{@code
 * // Simple - pretty-print enabled, no periodic fsync
 * Storage storage = Storages.create(new LocalFileConfig(Path.of("data")));
 *
 * // Full control
 * Storage storage = Storages.create(
 *     new LocalFileConfig(Path.of("data"), false, Optional.of(Duration.ofSeconds(30))));
 * }</pre>
 */
public final class LocalFileConfig implements StorageConfig {

    private final Path baseDirectory;
    private final boolean prettyPrint;
    private final Optional<Duration> fsyncEvery;

    /**
     * Full constructor.
     *
     * @param baseDirectory root directory where collections are stored
     * @param prettyPrint   whether JSON files should be human-readable (true) or compact (false)
     * @param fsyncEvery    optional interval for periodic fsync; empty disables periodic fsync
     */
    public LocalFileConfig(Path baseDirectory, boolean prettyPrint, Optional<Duration> fsyncEvery) {
        this.baseDirectory = baseDirectory;
        this.prettyPrint   = prettyPrint;
        this.fsyncEvery    = fsyncEvery;
    }

    /**
     * Convenience constructor - pretty-print enabled, no periodic fsync.
     */
    public LocalFileConfig(Path baseDirectory) {
        this(baseDirectory, true, Optional.empty());
    }

    public Path              baseDirectory() { return baseDirectory; }
    public boolean           prettyPrint()   { return prettyPrint; }
    public Optional<Duration> fsyncEvery()   { return fsyncEvery; }
}
