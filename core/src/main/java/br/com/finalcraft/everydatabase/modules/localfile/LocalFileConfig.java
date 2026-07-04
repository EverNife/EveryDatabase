package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.StorageConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the local file-system storage backend.
 *
 * <p>Stores one JSON file per entity, grouped in subdirectories by collection name.
 * Best for development, testing, and single-server deployments with small datasets.
 *
 * <p>Does <em>not</em> support transactions - use {@link SqlConfig}
 * if ACID semantics are required.
 *
 * <pre>{@code
 * Storage storage = Storages.create(new LocalFileConfig(Path.of("data")));
 * }</pre>
 *
 * <p><b>{@code prettyPrint} and {@code fsyncEvery} are advisory and currently not applied.</b>
 * LocalFile treats each entity's serialized form as opaque bytes, so the <em>codec</em> owns
 * formatting: for human-readable files use {@code JacksonYamlCodec} or
 * {@code JacksonJsonCodec.pretty(Type.class)} on the descriptor rather than {@code prettyPrint}.
 * Durability comes from the atomic {@code .tmp} + {@code ATOMIC_MOVE} replace on every write, not from
 * a periodic fsync loop. The two fields are kept for source and forward compatibility; do not rely on
 * them to change on-disk behavior today.
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
