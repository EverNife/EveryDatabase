package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.util.FileKeyNames;

import br.com.finalcraft.everydatabase.codec.Codec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared, storage-wide coordinator for the per-key aggregate files of a {@link GroupedFileStorage}.
 *
 * <p>This is the structural difference from LocalFile: there, locks and file resolution live inside
 * each repository (one repository per collection, each its own directory). Here several collections
 * share the <em>same</em> physical file (the one named after the key), so the per-key lock and the
 * file-level read/write primitives must live <b>above</b> the repositories - one instance per base
 * directory, shared by every {@link GroupedFileRepository}. Without that, two repositories writing the
 * same key for different collections would read-modify-write the same file concurrently and lose
 * updates.
 *
 * <p><b>Container format follows the codec.</b> The aggregate document is a Jackson tree, so it must be
 * a format Jackson round-trips as a tree - JSON or YAML (a {@link YAMLMapper} is an {@link ObjectMapper},
 * sharing the same {@code jackson-databind} tree model). The format is resolved <em>lazily</em> from the
 * first descriptor's {@code Codec} ({@link #resolveFormat(Codec)}) and is locked for the storage's
 * lifetime: every collection in one base directory writes the same files, so they must agree on one
 * format. A mismatched codec fails fast.
 *
 * <p>The atomic file primitive (write to a sibling {@code .tmp}, then {@link StandardCopyOption#ATOMIC_MOVE})
 * is the same crash-safety mechanism LocalFile uses; here it publishes the whole multi-collection
 * document at once. Callers must hold the appropriate {@link #lockFor(String)} lock around the
 * read-modify-write sequence - the atomic move guarantees no truncated file, not a consistent merge.
 */
final class KeyFileStore {

    private final Path baseDirectory;

    /** Per-key locks, keyed by sanitised key. Global across all repositories of the owning storage. */
    private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    // Format state - resolved once from the first codec, then immutable.
    private volatile ObjectMapper mapper;     // matches the resolved format (JSON or YAML)
    private volatile String       extension;  // ".json" or ".yml"
    private volatile Boolean      yaml;        // null until resolved

    KeyFileStore(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    Path baseDirectory() {
        return baseDirectory;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Resolves and locks the container format from a descriptor's codec, the first time a repository is
     * created. JSON ({@link Codec#isJsonCodec()}) and YAML (content-type) are supported; any other codec
     * (opaque/binary) cannot be embedded into a structured aggregate document and is rejected. Every
     * collection of one storage shares the files, so all codecs must resolve to the same format.
     *
     * @throws IllegalArgumentException if the codec is neither JSON nor YAML
     * @throws IllegalStateException    if a later codec resolves to a different format than the first
     */
    synchronized void resolveFormat(Codec<?> codec) {
        boolean wantYaml = isYaml(codec);
        if (yaml == null) {
            yaml      = wantYaml;
            extension = wantYaml ? ".yml" : ".json";
            mapper    = wantYaml ? newYamlMapper() : newJsonMapper();
        } else if (yaml != wantYaml) {
            throw new IllegalStateException(
                "GroupedFileStorage: all collections in one base directory must share a container format, "
                + "but got both " + (yaml ? "YAML" : "JSON") + " and " + (wantYaml ? "YAML" : "JSON")
                + " codecs. Use a single format (all JSON or all YAML) per base directory.");
        }
    }

    private static boolean isYaml(Codec<?> codec) {
        if (codec.isJsonCodec()) return false;
        String ct = codec.contentType().toLowerCase();
        if (ct.contains("yaml") || ct.contains("yml")) return true;
        throw new IllegalArgumentException(
            "GroupedFileStorage requires a JSON or YAML codec (the aggregate file is a structured "
            + "document); got contentType=" + codec.contentType());
    }

    private static ObjectMapper newJsonMapper() {
        // Local files are meant to be human-inspectable - keep the aggregate document indented.
        return JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    private static ObjectMapper newYamlMapper() {
        // Drop the leading "---" document-start marker so files read like a plain config.
        return YAMLMapper.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build();
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
        return baseDirectory.resolve(sanitizedKey + extension);
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
     * Lists the regular key files directly under the base directory (depth 1), filtered by the resolved
     * format's extension. The reserved {@code _schema/} directory is naturally excluded (it is a
     * directory, not a regular file), and so are sibling {@code .tmp} files.
     */
    List<Path> keyFiles() throws IOException {
        if (!Files.isDirectory(baseDirectory)) return Collections.emptyList();
        String ext = extension;
        try (Stream<Path> entries = Files.list(baseDirectory)) {
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
        byte[] bytes = readIfPresent(file);
        if (bytes == null) return false;
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
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
        byte[] bytes = readIfPresent(file);
        if (bytes == null) return null;
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            return seekField(parser, collection) ? parser.readValueAsTree() : null;
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

    void delete(Path target) throws IOException {
        Files.delete(target);
    }
}
