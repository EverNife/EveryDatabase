package br.com.finalcraft.everydatabase.modules.groupedfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves the files of a grouped-file directory to where a new configuration says they belong -
 * after declaring a key space for collections that used to live in the base directory, say, or
 * after changing how a key space fans its files out into sub-directories.
 *
 * <p>It is a call the consumer makes, not something an open storage does on its own. Opening a
 * storage whose configuration disagrees with the recorded layout <em>fails</em>, and deliberately:
 * a storage that quietly rearranged the consumer's files on boot would be doing bulk I/O nobody
 * asked for, and a crash halfway through would leave the collection split across two directories
 * with nothing recording that it happened. So the utility runs without a storage - it reads the
 * directory's own layout record, moves what needs moving, and rewrites the record last:
 *
 * <pre>{@code
 * GroupedFileConfig config = GroupedFileConfig.builder(base)
 *     .keySpace("player", "playerdata", "player_stats")
 *     .build();
 *
 * GroupedFileRelayout.relayout(config);          // once, before opening
 * Storage storage = Storages.createGroupedFile(config);
 * }</pre>
 *
 * <p><b>It moves entries, not files.</b> A key file holds every collection sharing its key, so
 * moving one collection out of it means lifting that sub-node into the target's file for the same
 * key (creating or merging it) and dropping it from the source - which is deleted only once nothing
 * is left in it. Collections staying behind never move.
 *
 * <p>Running it twice is harmless: the second run compares the recorded layout with the
 * configuration, finds them equal, and does nothing.
 */
public final class GroupedFileRelayout {

    private GroupedFileRelayout() {}

    /**
     * Relocates whatever the configuration places differently from the directory's recorded layout.
     *
     * @return what was moved; {@link RelayoutReport#changed()} is {@code false} when the directory
     *         already matched the configuration (including a directory that holds nothing yet)
     * @throws IllegalStateException if the directory cannot be read or written
     */
    public static RelayoutReport relayout(GroupedFileConfig config) {
        GroupedFileLayout layout = new GroupedFileLayout(config.baseDirectory());
        GroupedFileLayout.Document doc = layout.readOrNull();
        if (doc == null) return RelayoutReport.nothingToDo();   // nothing was ever stored here

        Map<String, String> moves = plan(doc, config);          // collection -> destination key space
        if (moves.isEmpty()) return RelayoutReport.nothingToDo();

        ContainerFormat format = ContainerFormat.byName(doc.format);
        Stores stores = new Stores(config, doc, format);
        RelayoutReport report = new RelayoutReport(new ArrayList<>(moves.keySet()));

        for (String source : sourcesOf(doc, moves)) {
            KeyFileStore from = stores.source(source);
            try {
                for (Path file : from.keyFiles()) {
                    moveEntriesOut(file, from, source, doc, moves, stores, format, report);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                    "GroupedFileRelayout: failed to list '" + from.directory() + "' while moving "
                    + "collections out of it.", e);
            }
            pruneEmptyDirectories(from.directory(), config.baseDirectory(), report);
        }

        for (Map.Entry<String, String> move : moves.entrySet()) {
            String destination = move.getValue();
            doc.collections.put(move.getKey(), destination);
            if (!GroupedFileLayout.ROOT_KEY_SPACE.equals(destination)) {
                GroupedFileLayout.KeySpace entry =
                    doc.keySpaces.computeIfAbsent(destination, k -> new GroupedFileLayout.KeySpace());
                entry.partitioner = config.partitionerOf(destination).partitionerName();
            }
        }
        // Last, and only once every file is where the record will claim it is: a record written
        // first would describe a move that a crash could still leave half-done.
        layout.overwrite(doc);
        return report;
    }

    // ------------------------------------------------------------------
    //  Planning
    // ------------------------------------------------------------------

    /**
     * The collections the configuration places somewhere other than where they are recorded -
     * either in another key space, or in the same one spread by a different partitioner. Both are
     * the same operation: the file is not where the new configuration would look for it.
     */
    private static Map<String, String> plan(GroupedFileLayout.Document doc, GroupedFileConfig config) {
        Map<String, String> moves = new LinkedHashMap<>();
        for (Map.Entry<String, String> recorded : doc.collections.entrySet()) {
            String collection = recorded.getKey();
            String from       = recorded.getValue();
            String to         = config.keySpaceOf(collection);
            if (to == null) to = GroupedFileLayout.ROOT_KEY_SPACE;

            if (!to.equals(from) || !recordedPartitioner(doc, from).equals(configuredPartitioner(config, to))) {
                moves.put(collection, to);
            }
        }
        return moves;
    }

    private static String recordedPartitioner(GroupedFileLayout.Document doc, String keySpace) {
        if (GroupedFileLayout.ROOT_KEY_SPACE.equals(keySpace)) return GroupedFilePartitioner.FLAT;
        GroupedFileLayout.KeySpace entry = doc.keySpaces.get(keySpace);
        return entry == null || entry.partitioner == null ? GroupedFilePartitioner.FLAT : entry.partitioner;
    }

    private static String configuredPartitioner(GroupedFileConfig config, String keySpace) {
        return GroupedFileLayout.ROOT_KEY_SPACE.equals(keySpace)
            ? GroupedFilePartitioner.FLAT
            : config.partitionerOf(keySpace).partitionerName();
    }

    /** The key spaces something is moving out of, so each source directory is walked once. */
    private static Set<String> sourcesOf(GroupedFileLayout.Document doc, Map<String, String> moves) {
        Set<String> sources = new LinkedHashSet<>();
        for (String collection : moves.keySet()) sources.add(doc.collections.get(collection));
        return sources;
    }

    // ------------------------------------------------------------------
    //  Moving
    // ------------------------------------------------------------------

    private static void moveEntriesOut(Path file, KeyFileStore from, String source,
                                       GroupedFileLayout.Document doc, Map<String, String> moves,
                                       Stores stores, ContainerFormat format, RelayoutReport report) {
        try {
            ObjectNode root = from.mutableRoot(file);
            if (root == null) return;

            String stem = stemOf(file, format);
            boolean touched = false;
            for (Map.Entry<String, String> move : moves.entrySet()) {
                String collection = move.getKey();
                if (!source.equals(doc.collections.get(collection))) continue;   // moving out of elsewhere
                JsonNode entry = root.get(collection);
                if (entry == null) continue;

                KeyFileStore to = stores.target(move.getValue());
                Path targetFile = to.keyFile(stem);
                if (targetFile.equals(file)) continue;   // already exactly where it is going

                ObjectNode target = to.mutableRoot(targetFile);
                if (target == null) target = format.mapper().createObjectNode();
                target.set(collection, entry);
                to.writeAtomic(targetFile, target);

                root.remove(collection);
                report.entriesMoved++;
                report.filesWritten++;
                touched = true;
            }
            if (!touched) return;

            if (root.size() == 0) {
                from.delete(file);
                report.filesRemoved++;
            } else {
                from.writeAtomic(file, root);
                report.filesWritten++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "GroupedFileRelayout: failed to move entries out of '" + file + "'.", e);
        }
    }

    /**
     * Drops the bucket directories the move emptied, deepest first.
     *
     * <p>Only here: a normal delete leaves its bucket in place, because checking emptiness on every
     * delete costs a listing per call to save an inode. A relayout is already walking the whole
     * tree, so cleaning up is free - and leaving the old fan-out's skeleton behind would make the
     * new layout look like it had not happened.
     */
    private static void pruneEmptyDirectories(Path directory, Path baseDirectory, RelayoutReport report) {
        if (!Files.isDirectory(directory)) return;
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(directory)) {
            candidates = new ArrayList<>();
            for (Path path : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                if (!path.equals(directory) && !path.equals(baseDirectory)) candidates.add(path);
            }
        } catch (IOException e) {
            return;   // best effort: an empty directory left behind is untidy, never wrong
        }
        candidates.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path candidate : candidates) {
            if (candidate.getFileName().toString().equals(GroupedFileStorage.SCHEMA_DIR)) continue;
            try {
                Files.delete(candidate);   // fails harmlessly when it is not empty
                report.directoriesRemoved++;
            } catch (IOException ignored) {
                // not empty, or in use - either way there is nothing to clean up here
            }
        }
    }

    private static String stemOf(Path file, ContainerFormat format) {
        String name = file.getFileName().toString();
        return name.endsWith(format.extension())
            ? name.substring(0, name.length() - format.extension().length())
            : name;
    }

    /**
     * The stores on both ends of the move. They are kept apart because a key space can be its own
     * source and target - that is what a partitioner change is - and then the two disagree on where
     * a key's file goes, which is the entire point.
     */
    private static final class Stores {

        private final GroupedFileConfig          config;
        private final GroupedFileLayout.Document doc;
        private final ContainerFormat            format;
        private final Map<String, KeyFileStore>  sources = new LinkedHashMap<>();
        private final Map<String, KeyFileStore>  targets = new LinkedHashMap<>();

        Stores(GroupedFileConfig config, GroupedFileLayout.Document doc, ContainerFormat format) {
            this.config = config;
            this.doc    = doc;
            this.format = format;
        }

        KeyFileStore source(String keySpace) {
            KeyFileStore store = sources.get(keySpace);
            if (store == null) {
                store = build(keySpace, GroupedFilePartitioner.byName(recordedPartitioner(doc, keySpace)));
                sources.put(keySpace, store);
            }
            return store;
        }

        KeyFileStore target(String keySpace) {
            KeyFileStore store = targets.get(keySpace);
            if (store == null) {
                store = build(keySpace, GroupedFileLayout.ROOT_KEY_SPACE.equals(keySpace)
                    ? GroupedFilePartitioner.flat()
                    : config.partitionerOf(keySpace));
                targets.put(keySpace, store);
            }
            return store;
        }

        private KeyFileStore build(String keySpace, GroupedFilePartitioner partitioner) {
            Path directory = GroupedFileLayout.ROOT_KEY_SPACE.equals(keySpace)
                ? config.baseDirectory()
                : config.baseDirectory().resolve(keySpace);
            // No memo: this walks every file once and then throws the stores away.
            return new KeyFileStore(directory, format, 0,
                partitioner == null ? GroupedFilePartitioner.flat() : partitioner);
        }
    }

    // ------------------------------------------------------------------
    //  Report
    // ------------------------------------------------------------------

    /** What one {@link #relayout(GroupedFileConfig)} call did. */
    public static final class RelayoutReport {

        private final List<String> collectionsMoved;
        int entriesMoved;
        int filesWritten;
        int filesRemoved;
        int directoriesRemoved;

        RelayoutReport(List<String> collectionsMoved) {
            this.collectionsMoved = collectionsMoved;
        }

        static RelayoutReport nothingToDo() {
            return new RelayoutReport(Collections.emptyList());
        }

        /** Whether anything at all was relocated. */
        public boolean changed() {
            return entriesMoved > 0;
        }

        /** The collections whose files were relocated, in the order they were planned. */
        public List<String> collectionsMoved() {
            return Collections.unmodifiableList(collectionsMoved);
        }

        /** How many stored entities changed directory (one per key, per moved collection). */
        public int entriesMoved() {
            return entriesMoved;
        }

        public int filesWritten() {
            return filesWritten;
        }

        /** Source files left empty by the move and therefore deleted. */
        public int filesRemoved() {
            return filesRemoved;
        }

        /** Bucket directories the move emptied. */
        public int directoriesRemoved() {
            return directoriesRemoved;
        }

        @Override
        public String toString() {
            return "RelayoutReport{collections=" + collectionsMoved + ", entries=" + entriesMoved
                 + ", written=" + filesWritten + ", removed=" + filesRemoved
                 + ", dirs=" + directoriesRemoved + "}";
        }
    }
}
