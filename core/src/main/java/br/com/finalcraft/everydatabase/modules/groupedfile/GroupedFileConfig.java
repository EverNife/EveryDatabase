package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.StorageConfig;
import br.com.finalcraft.everydatabase.SyncParticipation;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.codec.JacksonYamlCodec;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 *   player/&lt;key&gt;.yml                 (a declared key space - see {@link #builder(Path)})
 * </pre>
 *
 * <p><b>Key spaces.</b> One base directory often ends up holding collections keyed by unrelated
 * things - player UUIDs, account UUIDs, free-form cooldown ids. They share the directory but never
 * share a meaningful key, so every scan pays for files that can never hold the collection it is
 * looking for, and an accidental key collision makes two unrelated collections write into the same
 * file, behind the same lock. {@link #builder(Path)} splits them: each key space gets its own
 * sub-directory, its own listing and its own locks. Declaring none keeps today's flat layout.
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

    /** Key-space names are directory names on Windows and Linux alike, so they stay conservative. */
    private static final Pattern KEY_SPACE_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final Path baseDirectory;
    private final String sharedIdentity;
    private final SyncParticipation syncParticipation;
    private final int rootCacheSize;
    /** collection -> key space; a collection absent from this map lives directly in the base. */
    private final Map<String, String> collectionKeySpaces;
    /** key space -> how its files fan out into sub-directories. */
    private final Map<String, GroupedFilePartitioner> keySpacePartitioners;

    /**
     * @param baseDirectory     root directory where the per-key files live
     * @param sharedIdentity    explicit identity for the store behind this directory, or {@code null}
     *                          to derive it (see {@link #sharedIdentity()})
     * @param syncParticipation how this store participates in transport publishing (see
     *                          {@link #syncParticipation()})
     */
    public GroupedFileConfig(Path baseDirectory, String sharedIdentity, SyncParticipation syncParticipation) {
        this(baseDirectory, sharedIdentity, syncParticipation, DEFAULT_ROOT_CACHE_SIZE,
             Collections.emptyMap(), Collections.emptyMap());
    }

    private GroupedFileConfig(Path baseDirectory, String sharedIdentity,
                              SyncParticipation syncParticipation, int rootCacheSize,
                              Map<String, String> collectionKeySpaces,
                              Map<String, GroupedFilePartitioner> keySpacePartitioners) {
        this.baseDirectory        = baseDirectory;
        this.sharedIdentity       = sharedIdentity;
        this.syncParticipation    = syncParticipation;
        this.rootCacheSize        = rootCacheSize;
        this.collectionKeySpaces  = collectionKeySpaces;
        this.keySpacePartitioners = keySpacePartitioners;
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
        return new GroupedFileConfig(baseDirectory, sharedIdentity, syncParticipation, size,
                                     collectionKeySpaces, keySpacePartitioners);
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

    // ------------------------------------------------------------------
    //  Key spaces
    // ------------------------------------------------------------------

    /**
     * The key space a collection's files live in, or {@code null} when they live directly under the
     * base directory (the layout of a config built without key spaces).
     */
    public String keySpaceOf(String collection) {
        return collectionKeySpaces.get(collection);
    }

    /** collection -&gt; key space, for every collection that declared one; never {@code null}. */
    public Map<String, String> collectionKeySpaces() {
        return collectionKeySpaces;
    }

    /** The declared key-space names, in declaration order. */
    public Set<String> keySpaces() {
        return new LinkedHashSet<>(collectionKeySpaces.values());
    }

    /**
     * How a key space spreads its files into sub-directories; {@link GroupedFilePartitioner#flat()}
     * for the base directory and for any key space that declared none.
     */
    public GroupedFilePartitioner partitionerOf(String keySpace) {
        GroupedFilePartitioner declared = keySpacePartitioners.get(keySpace);
        return declared != null ? declared : GroupedFilePartitioner.flat();
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    /** A config built collection by collection - the only way to declare key spaces. */
    public static Builder builder(Path baseDirectory) {
        return new Builder(baseDirectory);
    }

    /**
     * Builds a config, optionally splitting the base directory into key spaces.
     *
     * <p>Collections are declared <em>grouped by key space</em> rather than one at a time, because
     * the group is the point: co-location is what a key space means, and listing the members
     * together makes a typo look wrong instead of silently splitting one entity's file in two.
     */
    public static final class Builder {

        private final Path baseDirectory;
        private String sharedIdentity;
        private SyncParticipation syncParticipation = SyncParticipation.RECOMMENDED;
        private int rootCacheSize = DEFAULT_ROOT_CACHE_SIZE;
        private final Map<String, String> collectionKeySpaces = new LinkedHashMap<>();
        private final Map<String, GroupedFilePartitioner> keySpacePartitioners = new LinkedHashMap<>();

        private Builder(Path baseDirectory) {
            if (baseDirectory == null) throw new IllegalArgumentException("baseDirectory cannot be null");
            this.baseDirectory = baseDirectory;
        }

        /** See {@link GroupedFileConfig#sharedIdentity()}. */
        public Builder sharedIdentity(String sharedIdentity) {
            this.sharedIdentity = sharedIdentity;
            return this;
        }

        /** See {@link GroupedFileConfig#syncParticipation()}. */
        public Builder syncParticipation(SyncParticipation syncParticipation) {
            if (syncParticipation == null) throw new IllegalArgumentException("syncParticipation cannot be null");
            this.syncParticipation = syncParticipation;
            return this;
        }

        /** See {@link GroupedFileConfig#rootCacheSize(int)}. */
        public Builder rootCacheSize(int size) {
            if (size < 0) throw new IllegalArgumentException("rootCacheSize cannot be negative: " + size);
            this.rootCacheSize = size;
            return this;
        }

        /**
         * Puts {@code collections} in their own sub-directory {@code <base>/<name>/}, isolating them
         * from every other key space: their files are listed, locked and scanned apart.
         *
         * <pre>{@code
         * GroupedFileConfig.builder(base)
         *     .keySpace("player",  "playerdata", "player_stats")
         *     .keySpace("account", "accounts")
         *     .build();
         * }</pre>
         *
         * <p>Collections not named here keep living directly under the base directory, so adding a
         * key space to one group leaves the rest of the tree exactly as it was.
         *
         * @throws IllegalArgumentException if the name is not a safe directory name, is the reserved
         *                                  {@code _schema}, is declared twice, or if a collection is
         *                                  claimed by two key spaces
         */
        public Builder keySpace(String name, String... collections) {
            return keySpace(name, GroupedFilePartitioner.flat(), collections);
        }

        /**
         * The same, with the files of that key space spread over sub-directories by
         * {@code partitioner} - for a key space large enough that a single directory listing hurts.
         *
         * <pre>{@code
         * GroupedFileConfig.builder(base)
         *     .keySpace("player", GroupedFilePartitioner.hashFanout(2), "playerdata")
         *     .build();                              // -> base/player/3f/9c/<uuid>.yml
         * }</pre>
         *
         * <p>The choice is permanent for the files already written under it: it is what says where
         * they are. Changing it is a relayout, not a config edit, and opening a key space with a
         * partitioner other than the recorded one fails rather than finding nothing.
         */
        public Builder keySpace(String name, GroupedFilePartitioner partitioner, String... collections) {
            if (partitioner == null) throw new IllegalArgumentException("partitioner cannot be null");
            if (GroupedFileStorage.SCHEMA_DIR.equals(name)) {
                throw new IllegalArgumentException(
                    "GroupedFileConfig: '" + GroupedFileStorage.SCHEMA_DIR + "' is reserved for the "
                    + "storage's own bookkeeping (the layout and the migration ledger) and cannot be a "
                    + "key space.");
            }
            if (name == null || !KEY_SPACE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "GroupedFileConfig: key space name must match " + KEY_SPACE_NAME.pattern()
                    + " (it becomes a directory name); got: " + name);
            }
            if (collectionKeySpaces.containsValue(name)) {
                throw new IllegalArgumentException(
                    "GroupedFileConfig: key space '" + name + "' is declared twice. Declare all of its "
                    + "collections in one call - the group is what makes co-location visible.");
            }
            if (collections == null || collections.length == 0) {
                throw new IllegalArgumentException(
                    "GroupedFileConfig: key space '" + name + "' declares no collections. An empty key "
                    + "space would be an unused directory.");
            }
            for (String collection : collections) {
                String previous = collectionKeySpaces.get(collection);
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "GroupedFileConfig: collection '" + collection + "' is claimed by two key spaces, "
                        + "'" + previous + "' and '" + name + "'. A collection lives in exactly one.");
                }
                collectionKeySpaces.put(collection, name);
            }
            keySpacePartitioners.put(name, partitioner);
            return this;
        }

        public GroupedFileConfig build() {
            return new GroupedFileConfig(baseDirectory, sharedIdentity, syncParticipation, rootCacheSize,
                Collections.unmodifiableMap(new LinkedHashMap<>(collectionKeySpaces)),
                Collections.unmodifiableMap(new LinkedHashMap<>(keySpacePartitioners)));
        }
    }
}
