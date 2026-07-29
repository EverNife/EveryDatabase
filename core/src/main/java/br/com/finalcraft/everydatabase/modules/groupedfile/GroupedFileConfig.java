package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.StorageConfig;
import br.com.finalcraft.everydatabase.SyncParticipation;
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
 *   _schema/layout.json              (reserved - records the container format of this directory)
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
 * the same physical files); a mismatch fails fast. The format is recorded in {@code _schema/layout.json}
 * on first use, so reopening a directory with a codec of the other format fails instead of quietly
 * reporting an empty collection and writing a parallel set of files.
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

    /**
     * Aggregate documents memoized by default. Counts <em>files</em>, not bytes: the working set
     * this is sized for is "the keys currently being served" (players online, say), not the whole
     * directory. A document stays memoized until evicted, so a large value over large documents is
     * a real memory commitment.
     */
    public static final int DEFAULT_ROOT_CACHE_SIZE = 256;

    private final Path baseDirectory;
    private final String sharedIdentity;
    private final SyncParticipation syncParticipation;
    private final int rootCacheSize;

    /**
     * @param baseDirectory     root directory where the per-key files live
     * @param sharedIdentity    explicit identity for the store behind this directory, or {@code null}
     *                          to derive it (see {@link #sharedIdentity()})
     * @param syncParticipation how this store participates in transport publishing (see
     *                          {@link #syncParticipation()})
     */
    public GroupedFileConfig(Path baseDirectory, String sharedIdentity, SyncParticipation syncParticipation) {
        this(baseDirectory, sharedIdentity, syncParticipation, DEFAULT_ROOT_CACHE_SIZE);
    }

    private GroupedFileConfig(Path baseDirectory, String sharedIdentity,
                              SyncParticipation syncParticipation, int rootCacheSize) {
        this.baseDirectory     = baseDirectory;
        this.sharedIdentity    = sharedIdentity;
        this.syncParticipation = syncParticipation;
        this.rootCacheSize     = rootCacheSize;
    }

    /**
     * A copy of this config memoizing at most {@code size} aggregate documents; {@code 0} disables
     * the memo entirely.
     *
     * <p>Reading one key's collections is the case this pays for: the key file holds all of them, so
     * the first read parses the document and the rest take it from memory. Validity is checked with
     * a file stamp, which costs one extra syscall on a read that misses - so a workload that only
     * ever touches a single collection per key is marginally better off with {@code 0}.
     *
     * <p>Turn it off, too, where another process writes the same directory and the timestamp
     * resolution is too coarse to notice: the stamp is {@code (lastModifiedTime, size)}, so an
     * external rewrite to the same length within the same filesystem tick reads as unchanged.
     * Writes made through this storage refresh the memo directly and are never affected.
     */
    public GroupedFileConfig rootCacheSize(int size) {
        if (size < 0) throw new IllegalArgumentException("rootCacheSize cannot be negative: " + size);
        return new GroupedFileConfig(baseDirectory, sharedIdentity, syncParticipation, size);
    }

    /** How many aggregate documents this storage memoizes; {@code 0} when the memo is disabled. */
    public int rootCacheSize() {
        return rootCacheSize;
    }

    /**
     * @param baseDirectory  root directory where the per-key files live
     * @param sharedIdentity explicit identity for the store behind this directory, or {@code null}
     *                       to derive it (see {@link #sharedIdentity()})
     */
    public GroupedFileConfig(Path baseDirectory, String sharedIdentity) {
        this(baseDirectory, sharedIdentity, SyncParticipation.RECOMMENDED);
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

    /**
     * How this store participates in the publish side of an explicit pub/sub cache-sync transport;
     * never {@code null}. Defaults to {@link SyncParticipation#RECOMMENDED}.
     */
    public SyncParticipation syncParticipation() {
        return syncParticipation;
    }
}
