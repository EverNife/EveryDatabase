package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.util.AtomicFileWrite;
import br.com.finalcraft.everydatabase.util.DirectoryListing;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The self-description of a grouped-file directory, persisted as {@code <base>/_schema/layout.json}.
 *
 * <p>It exists because the format these files are written in used to be knowable only from the
 * {@code Codec} that happened to open the directory first. Nothing on disk said what was actually
 * there, so opening a YAML directory with a JSON codec was not an error: it simply found no files
 * with the extension it was looking for, reported an empty collection, and wrote a parallel set of
 * {@code .json} files next to the {@code .yml} ones that already held the data. Silence is the worst
 * failure mode a persistence library has, so the format is now written down and every open is
 * checked against it.
 *
 * <pre>{@code
 * {
 *   "format" : "yaml"
 * }
 * }</pre>
 *
 * <p>The file lives under the reserved {@code _schema/} sub-directory, alongside the migration
 * ledger, so it can never be mistaken for a key file. It is written with the same temp-file +
 * atomic-move sequence, for the same reason: a half-written layout would be indistinguishable from
 * a corrupt one.
 */
final class GroupedFileLayout {

    static final String LAYOUT_FILE = "layout.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // forward-compatible: newer keys are ignored
        .build();

    private final Path baseDirectory;

    private volatile boolean reconciled;

    GroupedFileLayout(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    // ------------------------------------------------------------------
    //  The persisted document
    // ------------------------------------------------------------------

    /** The layout as stored. Unknown fields are tolerated so an older build can read a newer file. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class Document {
        public String format;
    }

    // ------------------------------------------------------------------
    //  Reconciliation
    // ------------------------------------------------------------------

    /**
     * Reconciles {@code format} - just resolved from a codec - against what the directory says about
     * itself, writing the layout when the directory does not describe itself yet.
     *
     * <p>Four cases, in order:
     * <ol>
     *   <li>no layout, no key files - the directory is new: write the layout from the codec;</li>
     *   <li>no layout, key files present - a directory from before this file existed: infer the
     *       format from the extensions on disk, check the codec against it, then write it down;</li>
     *   <li>layout present and agreeing with the codec - proceed;</li>
     *   <li>layout present and disagreeing - fail, naming both sides.</li>
     * </ol>
     *
     * <p>Runs once per storage: the collections that follow are checked against the first one by
     * {@link ContainerFormat#resolve}, which catches the same disagreement without touching disk.
     *
     * @param collection the collection being opened - named in the error, since it is the one whose
     *                   codec disagrees with the directory
     * @throws IllegalStateException when the codec and the directory disagree, when the directory
     *                               holds both formats, when key files sit below the base directory,
     *                               or when the layout file cannot be read
     */
    synchronized void reconcile(ContainerFormat format, String collection) {
        if (reconciled) return;

        // Before anything is read or written: a key file below the base is unreachable, and every
        // answer that follows - the inferred format, an empty collection, the first save - would be
        // computed as if it were not there.
        rejectKeyFilesBelowBase();

        String want   = format.name();
        Document read = readLayout();

        if (read == null) {
            String onDisk = inferFormat();
            if (onDisk != null && !onDisk.equals(want)) {
                throw formatMismatch(onDisk, want, collection, "inferred from the key files already there");
            }
            read = new Document();
            read.format = want;
            writeLayout(read);
        } else if (!want.equals(read.format)) {
            throw formatMismatch(read.format, want, collection, "recorded in " + relativeLayoutPath());
        }

        this.reconciled = true;
    }

    /**
     * Refuses a directory holding key files anywhere below itself.
     *
     * <p>Only the base directory is read, so those files are invisible: the format would be inferred
     * as if the directory were empty, the collection would report nothing, and the first save would
     * write a second copy beside data nobody can see - the exact failure this file exists to prevent.
     *
     * @throws IllegalStateException naming the first such file found
     */
    private void rejectKeyFilesBelowBase() {
        Path stray = firstKeyFileBelowBase();
        if (stray == null) return;
        throw new IllegalStateException(
            "GroupedFileStorage: the directory '" + baseDirectory + "' holds key files in a "
            + "sub-directory ('" + baseDirectory.relativize(stray) + "'), which is not read: every key "
            + "file lives directly under the base. Opening it this way would report those collections "
            + "empty and start writing a second copy in the base directory. Point the storage at that "
            + "sub-directory, or move the files up into the base directory first.");
    }

    /**
     * The first key file below {@code baseDirectory}, or {@code null} when there is none. Any
     * container extension counts, not just the one being opened: a stray directory of the other
     * format is just as unreadable. The reserved {@code _schema/} is the storage's own bookkeeping.
     */
    private Path firstKeyFileBelowBase() {
        try {
            for (Path child : DirectoryListing.subdirectories(baseDirectory)) {
                if (GroupedFileStorage.SCHEMA_DIR.equals(String.valueOf(child.getFileName()))) continue;
                Path found = DirectoryListing.firstRegularFileBelow(child, GroupedFileLayout::hasContainerExtension);
                if (found != null) return found;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "GroupedFileStorage: failed to list '" + baseDirectory + "' while checking that none "
                + "of its key files are hidden in a sub-directory.", e);
        }
        return null;
    }

    private static boolean hasContainerExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    // ------------------------------------------------------------------
    //  Reading and writing the file
    // ------------------------------------------------------------------

    Path layoutFile() {
        return baseDirectory.resolve(GroupedFileStorage.SCHEMA_DIR).resolve(LAYOUT_FILE);
    }

    private String relativeLayoutPath() {
        return GroupedFileStorage.SCHEMA_DIR + "/" + LAYOUT_FILE;
    }

    /**
     * The stored layout, or {@code null} when the directory does not describe itself yet.
     *
     * <p>An unreadable file is <b>not</b> treated as absent. The migration ledger does exactly that -
     * a failed read there means "nothing applied yet" - but the same leniency here would re-enact the
     * bug this file exists to prevent: falling back to the codec's format is precisely the guess that
     * hides every file already stored.
     */
    private Document readLayout() {
        Path file = layoutFile();
        if (!Files.isRegularFile(file)) return null;
        Document parsed;
        try {
            parsed = MAPPER.readValue(Files.readAllBytes(file), Document.class);
        } catch (Exception e) {
            throw unreadableLayout(file, e.toString(), e);
        }
        if (parsed == null || ContainerFormat.extensionOf(parsed.format) == null) {
            throw unreadableLayout(file, "it declares no usable container format (found: " + (parsed == null ? null : parsed.format) + ")", null);
        }
        return parsed;
    }

    private void writeLayout(Document doc) {
        Path file = layoutFile();
        try {
            AtomicFileWrite.write(file, MAPPER.writeValueAsBytes(doc));
        } catch (IOException e) {
            throw new IllegalStateException(
                "GroupedFileStorage: failed to write the layout file '" + file + "'. It records the "
                + "container format of this directory, and a directory that cannot describe itself "
                + "cannot be opened safely by a different codec later.", e);
        }
    }

    // ------------------------------------------------------------------
    //  Inference (directories written before the layout file existed)
    // ------------------------------------------------------------------

    /**
     * The format the key files already in the directory are written in, or {@code null} when there
     * are none. Both {@code .yml} and {@code .yaml} count as YAML; the reserved {@code _schema/}
     * directory and orphan {@code .tmp} files are not regular key files and never match.
     *
     * @throws IllegalStateException when both formats are present - the fingerprint of a mismatch
     *                               that already happened, which cannot be resolved by guessing
     */
    private String inferFormat() {
        if (!Files.isDirectory(baseDirectory)) return null;
        int json = 0;
        int yaml = 0;
        try {
            for (Path entry : DirectoryListing.regularFiles(baseDirectory)) {
                String name = entry.getFileName().toString().toLowerCase();
                if      (name.endsWith(".json"))                          json++;
                else if (name.endsWith(".yml") || name.endsWith(".yaml")) yaml++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "GroupedFileStorage: failed to list '" + baseDirectory + "' while working out which "
                + "container format its key files use.", e);
        }
        if (json > 0 && yaml > 0) {
            throw new IllegalStateException(
                "GroupedFileStorage: the directory '" + baseDirectory + "' holds both JSON and YAML key "
                + "files (" + json + " .json, " + yaml + " .yml) and no " + relativeLayoutPath() + " to say "
                + "which one is authoritative. That is the fingerprint of a format mismatch that already "
                + "happened: one set was written by a codec that could not see the other. Move or delete "
                + "the set you do not want, then open the directory again.");
        }
        if (json > 0) return ContainerFormat.JSON;
        if (yaml > 0) return ContainerFormat.YAML;
        return null;
    }

    // ------------------------------------------------------------------
    //  Errors
    // ------------------------------------------------------------------

    private IllegalStateException formatMismatch(String stored, String want, String collection, String source) {
        return new IllegalStateException(
            "GroupedFileStorage: the directory '" + baseDirectory + "' stores its key files as "
            + stored.toUpperCase() + " (" + source + "), but collection '" + collection + "' was opened "
            + "with a " + want.toUpperCase() + " codec. Reading it that way finds nothing, and the first "
            + "save writes a parallel set of " + ContainerFormat.extensionOf(want) + " files next to the "
            + ContainerFormat.extensionOf(stored) + " files that hold the data. Open this directory with a "
            + stored.toUpperCase() + " codec, or point the storage at a different directory.");
    }

    private IllegalStateException unreadableLayout(Path file, String reason, Throwable cause) {
        return new IllegalStateException(
            "GroupedFileStorage: cannot read the layout file '" + file + "' (" + reason + "). It records "
            + "the container format of this directory; opening without it would mean guessing, and a wrong "
            + "guess hides every file already stored. Repair the file, or delete it to have the layout "
            + "inferred from the key files again.", cause);
    }
}
