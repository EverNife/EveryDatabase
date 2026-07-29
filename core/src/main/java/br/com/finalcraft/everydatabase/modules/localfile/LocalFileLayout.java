package br.com.finalcraft.everydatabase.modules.localfile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The self-description of a local-file store, persisted as {@code <base>/_schema_layout.json}.
 *
 * <p>Every path this backend resolves - the file it writes, the files it lists, the ones it counts -
 * ends in {@code codec.fileExtension()}. Nothing on disk recorded which extension a collection was
 * actually written with, so opening one with a codec of another format was not an error: the
 * listing matched nothing, the collection read as empty, and the first save wrote a parallel file
 * beside the one still holding the data. Silence is the worst failure mode a persistence library
 * has, so the extension is now written down and every open is checked against it.
 *
 * <pre>{@code
 * {
 *   "collections" : { "playerdata" : "json", "quests" : "yml" }
 * }
 * }</pre>
 *
 * <p>Unlike the grouped-file backend, where one format governs the whole directory because the
 * collections share physical files, here each collection owns a sub-directory of its own and may
 * legitimately differ from its neighbours. So the record is per collection.
 *
 * <p>It is a loose file in the base directory, named like the migration ledger beside it. A
 * collection is a <em>sub-directory</em>, so a file can never be mistaken for one - which is why
 * this does not need a reserved directory that a reserved collection name could one day collide
 * with.
 */
final class LocalFileLayout {

    static final String LAYOUT_FILE = "_schema_layout.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // forward-compatible
        .build();

    private final Path baseDirectory;

    private volatile Document document;

    LocalFileLayout(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    /** The layout as stored. Unknown fields are tolerated so an older build can read a newer file. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class Document {
        /** collection -&gt; the file extension its entities are stored with, without the dot. */
        public Map<String, String> collections = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    //  Reconciliation
    // ------------------------------------------------------------------

    /**
     * Reconciles the extension a codec is about to use for {@code collection} against what the store
     * says that collection is written with, recording it the first time.
     *
     * <p>Four cases, in order:
     * <ol>
     *   <li>nothing recorded, no files - a new collection: write it down;</li>
     *   <li>nothing recorded, files present - a collection from before this file existed: infer the
     *       extension from what is on disk, check the codec against it, then write it down;</li>
     *   <li>recorded and matching - proceed;</li>
     *   <li>recorded and different - fail, naming both sides.</li>
     * </ol>
     *
     * @throws IllegalStateException when the codec and the store disagree, when the collection holds
     *                               files of two extensions, or when the layout cannot be read
     */
    synchronized void reconcile(String collection, String extension) {
        if (document == null) document = readLayout();

        String recorded = document.collections.get(collection);
        if (recorded == null) {
            String onDisk = inferExtension(collection);
            if (onDisk != null && !onDisk.equals(extension)) {
                throw mismatch(collection, onDisk, extension, "inferred from the files already there");
            }
            document.collections.put(collection, extension);
            writeLayout(document);
        } else if (!recorded.equals(extension)) {
            throw mismatch(collection, recorded, extension, "recorded in " + LAYOUT_FILE);
        }
    }

    // ------------------------------------------------------------------
    //  Reading and writing the file
    // ------------------------------------------------------------------

    Path layoutFile() {
        return baseDirectory.resolve(LAYOUT_FILE);
    }

    /**
     * The stored layout, or an empty one when the store does not describe itself yet.
     *
     * <p>An unreadable file is <b>not</b> treated as absent. The migration ledger beside it does
     * exactly that - a failed read there means "nothing applied yet" - but the same leniency here
     * would re-enact the bug this file exists to prevent: falling back to the codec's extension is
     * precisely the guess that hides every file already stored.
     */
    private Document readLayout() {
        Path file = layoutFile();
        if (!Files.isRegularFile(file)) return new Document();
        Document parsed;
        try {
            parsed = MAPPER.readValue(Files.readAllBytes(file), Document.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                "LocalFileStorage: cannot read the layout file '" + file + "' (" + e + "). It records "
                + "which format each collection is stored in; opening without it would mean guessing, "
                + "and a wrong guess hides every file already stored. Repair the file, or delete it to "
                + "have the layout inferred from the files on disk again.", e);
        }
        if (parsed == null) parsed = new Document();
        if (parsed.collections == null) parsed.collections = new LinkedHashMap<>();
        return parsed;
    }

    private void writeLayout(Document doc) {
        Path file = layoutFile();
        try {
            Files.createDirectories(baseDirectory);
            byte[] bytes = MAPPER.writeValueAsBytes(doc);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "LocalFileStorage: failed to write the layout file '" + file + "'. It records the "
                + "format of each collection, and a store that cannot describe itself cannot be "
                + "opened safely by a different codec later.", e);
        }
    }

    // ------------------------------------------------------------------
    //  Inference (collections written before the layout file existed)
    // ------------------------------------------------------------------

    /**
     * The extension the files already in {@code collection}'s directory use, or {@code null} when
     * there are none.
     *
     * @throws IllegalStateException when two extensions are present - the fingerprint of a mismatch
     *                               that already happened, which cannot be resolved by guessing
     */
    private String inferExtension(String collection) {
        Path directory = baseDirectory.resolve(collection);
        if (!Files.isDirectory(directory)) return null;
        Map<String, Integer> byExtension = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : (Iterable<Path>) entries.filter(Files::isRegularFile)::iterator) {
                String name = entry.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if ("tmp".equals(extension)) continue;      // an orphan from an interrupted write
                byExtension.merge(extension, 1, Integer::sum);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "LocalFileStorage: failed to list '" + directory + "' while working out which format "
                + "collection '" + collection + "' is stored in.", e);
        }
        if (byExtension.isEmpty()) return null;
        if (byExtension.size() > 1) {
            throw new IllegalStateException(
                "LocalFileStorage: collection '" + collection + "' holds files of more than one format "
                + "(" + describe(byExtension) + ") and " + LAYOUT_FILE + " does not say which is "
                + "authoritative. That is the fingerprint of a format mismatch that already happened: "
                + "one set was written by a codec that could not see the other. Move or delete the set "
                + "you do not want, then open the collection again.");
        }
        return byExtension.keySet().iterator().next();
    }

    private static String describe(Map<String, Integer> byExtension) {
        StringBuilder counts = new StringBuilder();
        for (Map.Entry<String, Integer> e : byExtension.entrySet()) {
            if (counts.length() > 0) counts.append(", ");
            counts.append(e.getValue()).append(" .").append(e.getKey());
        }
        return counts.toString();
    }

    private IllegalStateException mismatch(String collection, String stored, String want, String source) {
        return new IllegalStateException(
            "LocalFileStorage: collection '" + collection + "' stores its entities as ." + stored
            + " files (" + source + "), but it was opened with a codec that writes ." + want
            + " files. Reading it that way finds nothing, and the first save writes a parallel set of ."
            + want + " files next to the ." + stored + " files that hold the data. Open this collection "
            + "with a ." + stored + " codec, or point the storage at a different directory.");
    }
}
