package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.StorageConfig;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

import java.nio.file.Path;

/**
 * Configuration for the grouped (key-major) file-system storage backend.
 *
 * <p>Where {@link LocalFileConfig} stores one file per entity grouped in per-collection
 * sub-directories ({@code <base>/<collection>/<key>.json}), this backend inverts the layout: one file
 * per <em>key</em> directly under the base directory, holding every collection that shares that key:
 *
 * <pre>
 * &lt;baseDirectory&gt;/
 *   _schema/migrations.json          (reserved - never collides with a key file)
 *   &lt;key&gt;.yml                        (one file per key; e.g. one file per player UUID)
 * </pre>
 *
 * <p>Each key file is a single structured document:
 * <pre>{@code
 * PlayerData:        # collection 1
 *   username: "EverNife"
 *   ...
 * AuthMe:            # collection 2
 *   ...
 * }</pre>
 *
 * <p>Best for "everything about one entity-root in one file" workloads - e.g. a player whose data is
 * spread across many logical collections, loaded on join and persisted on quit as a single read/write.
 * Collections only co-locate when they share the same key space (same {@code key.toString()}).
 *
 * <p>Does <em>not</em> support transactions - use {@link SqlConfig} if ACID semantics are required.
 *
 * <p><b>Format follows the codec.</b> There is no format option: the container format (JSON or YAML)
 * is taken from the {@code Codec} on the {@link br.com.finalcraft.everydatabase.EntityDescriptor} -
 * a {@link JacksonJsonCodec} yields {@code .json} files, a {@link JacksonYamlCodec} yields readable
 * {@code .yml} files. All collections sharing this base directory must agree on one format (they share
 * the same physical files); a mismatch fails fast.
 *
 * <pre>{@code
 * // YAML, human-readable: just use a YAML codec on the descriptor
 * EntityDescriptor<UUID, Player> d = EntityDescriptor.builder(UUID.class, Player.class)
 *     .collection("PlayerData").keyExtractor(Player::getUuid)
 *     .codec(new JacksonYamlCodec<>(Player.class))
 *     .build();
 * Storage storage = Storages.createGroupedFile(new GroupedFileConfig(Path.of("playerdata")));
 * }</pre>
 */
public final class GroupedFileConfig implements StorageConfig {

    private final Path baseDirectory;
    private final String sharedIdentity;

    /**
     * @param baseDirectory  root directory where the per-key files live
     * @param sharedIdentity explicit identity for the store behind this directory, or {@code null}
     *                       to derive it (see {@link #sharedIdentity()})
     */
    public GroupedFileConfig(Path baseDirectory, String sharedIdentity) {
        this.baseDirectory  = baseDirectory;
        this.sharedIdentity = sharedIdentity;
    }

    /**
     * @param baseDirectory root directory where the per-key files live
     */
    public GroupedFileConfig(Path baseDirectory) {
        this(baseDirectory, null);
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

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
    public String sharedIdentity() {
        return sharedIdentity;
    }
}
