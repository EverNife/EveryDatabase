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
 * {@code JacksonJsonCodec.pretty(Type.class)} for human-readable files. Durability comes from the
 * atomic {@code .tmp} + {@code ATOMIC_MOVE} replace on every write.
 */
public final class LocalFileConfig implements StorageConfig {

    private final Path baseDirectory;

    /**
     * @param baseDirectory root directory where collections are stored
     */
    public LocalFileConfig(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public Path baseDirectory() { return baseDirectory; }
}
