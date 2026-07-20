package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.StorageConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

import java.nio.file.Path;

/**
 * Configuration for the local file-system storage backend.
 *
 * <p>Stores one file per entity, grouped in subdirectories by collection name. Best for development,
 * testing, and single-server deployments with small datasets.
 *
 * <p>Does <em>not</em> support transactions - use {@link SqlConfig} if ACID semantics are required.
 *
 * <pre>{@code
 * Storage storage = Storages.create(new LocalFileConfig(Path.of("data")));
 * }</pre>
 *
 * <p>Formatting is the codec's job: pair the descriptor with {@code JacksonYamlCodec} or
 * {@code JacksonJsonCodec.pretty(Type.class)} for human-readable files. The atomic {@code .tmp} +
 * {@code ATOMIC_MOVE} replace on every write gives crash-atomicity (no torn files); the last write
 * may still be lost on power loss unless the OS has flushed it to disk.
 */
public final class LocalFileConfig implements StorageConfig {

    private final Path baseDirectory;
    private final String sharedIdentity;

    /**
     * @param baseDirectory  root directory where collections are stored
     * @param sharedIdentity explicit identity for the store behind this directory, or {@code null}
     *                       to derive it (see {@link #sharedIdentity()})
     */
    public LocalFileConfig(Path baseDirectory, String sharedIdentity) {
        this.baseDirectory  = baseDirectory;
        this.sharedIdentity = sharedIdentity;
    }

    /**
     * @param baseDirectory root directory where collections are stored
     */
    public LocalFileConfig(Path baseDirectory) {
        this(baseDirectory, null);
    }

    public Path baseDirectory() { return baseDirectory; }

    /**
     * An explicit identity for the physical store, or {@code null} to derive one from the directory
     * path plus a machine discriminator.
     *
     * <p>A directory is machine-local by definition, so the derived identity deliberately differs
     * between machines. Set this when the directory really is shared - a network mount several
     * servers write to - so they invalidate each other's caches again. When present it IS the
     * identity, verbatim. Never put a credential in it: the identity travels on change events and
     * may be logged.
     */
    public String sharedIdentity() { return sharedIdentity; }
}
