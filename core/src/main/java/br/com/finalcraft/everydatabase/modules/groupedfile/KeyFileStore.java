package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.util.FileKeyNames;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Coordinator for the per-key aggregate files of one directory of a {@link GroupedFileStorage}.
 *
 * <p>This is the structural difference from LocalFile: there, locks and file resolution live inside
 * each repository (one repository per collection, each its own directory). Here several collections
 * share the <em>same</em> physical file (the one named after the key), so the per-key lock and the
 * file-level read/write primitives must live <b>above</b> the repositories, shared by every
 * {@link GroupedFileRepository} that can reach the same file. Without that, two repositories writing
 * the same key for different collections would read-modify-write the same file concurrently and lose
 * updates.
 *
 * <p>"The same file" is exactly one directory's worth: a storage holds one store per key space (plus
 * one for the base directory itself), and a key space's files are unreachable from any other. That
 * makes <em>same file if and only if same lock</em> true by construction rather than by convention -
 * two stores cannot collide, because neither can name the other's paths.
 *
 * <p><b>Container format follows the codec.</b> The aggregate document is a Jackson tree, so it must be
 * a format Jackson round-trips as a tree - JSON or YAML. That decision is not this class's to make: it
 * belongs to the whole base directory, so it lives in the shared {@link ContainerFormat} handed in at
 * construction, already reconciled against what the directory says about itself.
 *
 * <p>The atomic file primitive (write to a sibling {@code .tmp}, then {@link StandardCopyOption#ATOMIC_MOVE})
 * is the same crash-safety mechanism LocalFile uses; here it publishes the whole multi-collection
 * document at once. Callers must hold the appropriate {@link #lockFor(String)} lock around the
 * read-modify-write sequence - the atomic move guarantees no truncated file, not a consistent merge.
 */
final class KeyFileStore {

    private final Path            directory;
    private final ContainerFormat format;

    /** Per-key locks, keyed by sanitised key. Global across all repositories of the owning storage. */
    private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    /** Memoized aggregate documents, LRU-bounded. Empty and unused when the cache is disabled. */
    private final Map<Path, CachedRoot> roots;
    private final int rootCacheSize;

    /** Full parses that actually happened - the observable the cache exists to reduce. */
    private final AtomicLong rootParses = new AtomicLong();

    KeyFileStore(Path directory, ContainerFormat format) {
        this(directory, format, GroupedFileConfig.DEFAULT_ROOT_CACHE_SIZE);
    }

    KeyFileStore(Path directory, ContainerFormat format, int rootCacheSize) {
        this.directory     = directory;
        this.format        = format;
        this.rootCacheSize = Math.max(0, rootCacheSize);
        this.roots = this.rootCacheSize == 0
            ? null
            : Collections.synchronizedMap(new LinkedHashMap<Path, CachedRoot>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, CachedRoot> eldest) {
                    return size() > KeyFileStore.this.rootCacheSize;
                }
            });
    }

    /** Number of aggregate documents currently memoized; {@code 0} when the cache is disabled. */
    int cachedRootCount() {
        return roots == null ? 0 : roots.size();
    }

    /** How many times a whole aggregate document had to be parsed since this store was created. */
    long rootParseCount() {
        return rootParses.get();
    }

    /** The directory this store owns: the base directory, or one key space's sub-directory of it. */
    Path directory() {
        return directory;
    }

    ObjectMapper mapper() {
        return format.mapper();
    }

    /**
     * Sanitises a key into a safe file-name stem via {@link FileKeyNames} (the same rules
     * LocalFile uses): path separators, case-differing names on case-insensitive file systems
     * and reserved Windows device names all get a stable hash suffix - so the file AND its
     * per-key lock always collide-or-not together.
     *
     * <p>Must be a pure function of the key so that every repository sharing this store resolves the
     * <em>same</em> file and the <em>same</em> lock for a given key.
     */
    static String sanitize(Object key) {
        return FileKeyNames.safeStem(key.toString());
    }

    Path keyFile(String sanitizedKey) {
        return directory.resolve(sanitizedKey + format.extension());
    }

    /**
     * Returns the lock guarding the file for {@code sanitizedKey}. The lock stays in the map after a
     * delete on purpose: removing it would let another thread mint a fresh lock for the same key while
     * a holder still owns the old one, breaking mutual exclusion. The map is bounded by live keys.
     */
    ReadWriteLock lockFor(String sanitizedKey) {
        return locks.computeIfAbsent(sanitizedKey, k -> new ReentrantReadWriteLock());
    }

    /**
     * Lists the regular key files directly under this store's directory (depth 1), filtered by the
     * resolved format's extension. Reserved sub-directories (such as {@code _schema/} under the base)
     * are naturally excluded - they are directories, not regular files - and so are sibling
     * {@code .tmp} files.
     *
     * <p>A store that owns a key space lists <em>only</em> that key space: the files of the other
     * ones are not just filtered out, they are never looked at. That is where the scan cost goes.
     */
    List<Path> keyFiles() throws IOException {
        if (!Files.isDirectory(directory)) return Collections.emptyList();
        String ext = format.extension();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(ext))
                .collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------
    //  Partial reads
    //
    //  An aggregate file holds every collection that shares its key, but a repository owns exactly
    //  one of them. Materializing the whole document to ask for one sub-node makes every scan pay for
    //  the collections it does not read - the sparser the collection, the worse. These two primitives
    //  walk the depth-1 field names with a streaming parser instead, skipping over the values that do
    //  not match and materializing at most the one that does.
    // ------------------------------------------------------------------

    /**
     * Whether the key file declares {@code collection} at the top level, without building any node.
     * {@code false} when the file is absent or its root is not an object.
     *
     * @throws IOException if the file cannot be read or is not well-formed up to the answer
     */
    boolean hasSubNode(Path file, String collection) throws IOException {
        ObjectNode memoized = memoized(file);
        if (memoized != null) return memoized.has(collection);
        byte[] bytes = readIfPresent(file);
        if (bytes == null) return false;
        try (JsonParser parser = format.mapper().getFactory().createParser(bytes)) {
            return seekField(parser, collection);
        }
    }

    /**
     * The sub-node stored under {@code collection}, or {@code null} when the file is absent, its root
     * is not an object, or it holds no such field - the same "absent" contract as reading the whole
     * root and asking for the field.
     *
     * <p>A malformed document repeating the field resolves to the <b>first</b> occurrence, since the
     * scan stops there; a full tree read would instead keep the last.
     *
     * @throws IOException if the file cannot be read or is not well-formed up to the sub-node
     */
    JsonNode readSubNode(Path file, String collection) throws IOException {
        ObjectNode memoized = memoized(file);
        if (memoized != null) return memoized.get(collection);
        byte[] bytes = readIfPresent(file);
        if (bytes == null) return null;
        try (JsonParser parser = format.mapper().getFactory().createParser(bytes)) {
            return seekField(parser, collection) ? parser.readValueAsTree() : null;
        }
    }

    // ------------------------------------------------------------------
    //  Whole-document reads
    //
    //  A key file aggregates every collection sharing its key, so anything addressing one KEY - a
    //  point read, or the read half of a read-modify-write - is about to be asked for the same file
    //  again by the next collection. Those go through the memo. Directory scans do not: they touch
    //  each file once, and parsing whole documents to fill a cache nobody will hit would undo the
    //  streaming reads above.
    // ------------------------------------------------------------------

    /**
     * The memoized aggregate document for {@code file}, or {@code null} when the cache is off, holds
     * nothing for it, or holds a stamp the file no longer matches.
     *
     * <p>Validity is {@code (lastModifiedTime, size)}. That is a coarse stamp: a file rewritten
     * within the filesystem's timestamp resolution, to exactly the same length, reads as unchanged.
     * Writes made through this store refresh the entry directly, so the gap only covers edits by
     * another process - set {@code rootCacheSize(0)} where that matters.
     */
    private ObjectNode memoized(Path file) {
        if (roots == null) return null;
        CachedRoot entry = roots.get(file);
        if (entry == null) return null;
        BasicFileAttributes attrs = statOrNull(file);
        if (attrs == null || !entry.matches(attrs)) {
            roots.remove(file);
            return null;
        }
        return entry.root;
    }

    /**
     * The whole aggregate document for {@code file}, memoized for the collections that follow, or
     * {@code null} when the file is absent or its root is not an object.
     *
     * <p><b>Must not be mutated</b> - it is shared with every other reader of the same file. Callers
     * about to change the document use {@link #mutableRoot(Path)}.
     *
     * <p>The stamp is taken <em>before</em> the content on purpose. A file changing between the two
     * then leaves the entry carrying a stamp older than what it holds, so the next check sees a
     * mismatch and re-reads. Stamping afterwards would do the opposite - pair old content with a new
     * stamp, and confirm it as fresh forever.
     *
     * @throws IOException if the file cannot be read or parsed
     */
    ObjectNode cachedRoot(Path file) throws IOException {
        ObjectNode memoized = memoized(file);
        if (memoized != null) return memoized;

        BasicFileAttributes attrs = statOrNull(file);
        if (attrs == null) return null;
        byte[] bytes = readIfPresent(file);
        if (bytes == null) return null;

        rootParses.incrementAndGet();
        JsonNode node = format.mapper().readTree(bytes);
        if (node == null || !node.isObject()) return null;
        ObjectNode root = (ObjectNode) node;
        if (roots != null) roots.put(file, new CachedRoot(attrs, root));
        return root;
    }

    /**
     * A private copy of {@code file}'s aggregate document, safe to modify, or {@code null} when the
     * file is absent. Copying is what lets the scans read a memoized document without holding the
     * key's lock: a writer never mutates the tree they are walking, it publishes a new one.
     *
     * @throws IOException if the file cannot be read or parsed
     */
    ObjectNode mutableRoot(Path file) throws IOException {
        ObjectNode root = cachedRoot(file);
        return root == null ? null : root.deepCopy();
    }

    /** Publishes {@code root} as the memoized document for {@code file}, after the file was written. */
    private void memoize(Path file, ObjectNode root) {
        if (roots == null) return;
        BasicFileAttributes attrs = statOrNull(file);
        if (attrs != null) roots.put(file, new CachedRoot(attrs, root));
    }

    private static BasicFileAttributes statOrNull(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** An aggregate document plus the file stamp it was read at. */
    private static final class CachedRoot {
        final long       mtimeMillis;
        final long       size;
        final ObjectNode root;

        CachedRoot(BasicFileAttributes attrs, ObjectNode root) {
            this.mtimeMillis = attrs.lastModifiedTime().toMillis();
            this.size        = attrs.size();
            this.root        = root;
        }

        boolean matches(BasicFileAttributes attrs) {
            return attrs.lastModifiedTime().toMillis() == mtimeMillis && attrs.size() == size;
        }
    }

    /**
     * The file's bytes, or {@code null} when it does not exist. Letting the read itself report the
     * absence keeps a scan to one syscall per file: an existence check before it would be both a
     * second syscall and a lie, since the file can vanish in between either way.
     */
    private static byte[] readIfPresent(Path file) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Advances {@code parser} to the value of the depth-1 field {@code name}, skipping over the values
     * of the fields before it. Returns {@code false} (parser exhausted) when the root is not an object
     * or the field is not there.
     */
    private static boolean seekField(JsonParser parser, String name) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) return false;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            boolean match = name.equals(parser.currentName());
            parser.nextToken();          // position on the value
            if (match) return true;
            parser.skipChildren();       // no-op on a scalar, jumps the whole subtree otherwise
        }
        return false;
    }

    /**
     * Crash-safe write: data goes to a sibling {@code .tmp} file first, then is moved over the target
     * with {@link StandardCopyOption#ATOMIC_MOVE} (plain replace on exotic file systems without atomic
     * rename). A crash mid-write never leaves a truncated key file - at worst an orphan {@code .tmp},
     * which {@link #keyFiles()} ignores because it filters by extension.
     */
    void writeAtomic(Path target, byte[] data) throws IOException {
        // The key space's directory is created on first write rather than up front, so declaring a
        // key space nobody writes to never leaves an empty directory behind.
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Publishes {@code root} as {@code target}'s new content and memoizes it, so the collections
     * written or read next do not re-parse what this call already holds in tree form.
     *
     * <p>The caller gives up {@code root} here: it becomes the shared memoized document and must not
     * be modified afterwards. Every write path obtains it from {@link #mutableRoot(Path)}, which
     * already hands out a private copy.
     */
    void writeAtomic(Path target, ObjectNode root) throws IOException {
        writeAtomic(target, format.mapper().writeValueAsBytes(root));
        memoize(target, root);
    }

    void delete(Path target) throws IOException {
        if (roots != null) roots.remove(target);
        Files.delete(target);
    }
}
