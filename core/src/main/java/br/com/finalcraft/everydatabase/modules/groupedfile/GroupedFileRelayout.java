package br.com.finalcraft.everydatabase.modules.groupedfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves the files of a grouped-file directory to where a new configuration says they belong -
 * after declaring a key space for collections that used to live in the base directory, say.
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
 * moving one collection out of it means lifting that sub-node into the target key space's file for
 * the same key (creating or merging it) and dropping it from the source - which is deleted only
 * once nothing is left in it. Collections staying behind never move.
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
        Map<String, KeyFileStore> stores = new LinkedHashMap<>();
        RelayoutReport report = new RelayoutReport(new ArrayList<>(moves.keySet()));

        for (String source : sourcesOf(doc, moves)) {
            KeyFileStore from = storeOf(stores, config, format, source);
            try {
                for (Path file : from.keyFiles()) {
                    moveEntriesOut(file, from, source, doc, moves, stores, config, format, report);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                    "GroupedFileRelayout: failed to list '" + from.directory() + "' while moving "
                    + "collections out of it.", e);
            }
        }

        for (Map.Entry<String, String> move : moves.entrySet()) {
            doc.collections.put(move.getKey(), move.getValue());
            if (!GroupedFileLayout.ROOT_KEY_SPACE.equals(move.getValue())) {
                doc.keySpaces.computeIfAbsent(move.getValue(), k -> new GroupedFileLayout.KeySpace());
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

    /** The collections the configuration places somewhere other than where they are recorded. */
    private static Map<String, String> plan(GroupedFileLayout.Document doc, GroupedFileConfig config) {
        Map<String, String> moves = new LinkedHashMap<>();
        for (Map.Entry<String, String> recorded : doc.collections.entrySet()) {
            String configured = config.keySpaceOf(recorded.getKey());
            if (configured == null) configured = GroupedFileLayout.ROOT_KEY_SPACE;
            if (!configured.equals(recorded.getValue())) moves.put(recorded.getKey(), configured);
        }
        return moves;
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
                                       Map<String, KeyFileStore> stores, GroupedFileConfig config,
                                       ContainerFormat format, RelayoutReport report) {
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

                KeyFileStore to = storeOf(stores, config, format, move.getValue());
                Path targetFile = to.keyFile(stem);
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

    private static KeyFileStore storeOf(Map<String, KeyFileStore> stores, GroupedFileConfig config,
                                        ContainerFormat format, String keySpace) {
        KeyFileStore store = stores.get(keySpace);
        if (store == null) {
            Path directory = GroupedFileLayout.ROOT_KEY_SPACE.equals(keySpace)
                ? config.baseDirectory()
                : config.baseDirectory().resolve(keySpace);
            // No memo: this walks every file once and then throws the stores away.
            store = new KeyFileStore(directory, format, 0);
            stores.put(keySpace, store);
        }
        return store;
    }

    private static String stemOf(Path file, ContainerFormat format) {
        String name = file.getFileName().toString();
        return name.endsWith(format.extension())
            ? name.substring(0, name.length() - format.extension().length())
            : name;
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

        @Override
        public String toString() {
            return "RelayoutReport{collections=" + collectionsMoved + ", entries=" + entriesMoved
                 + ", written=" + filesWritten + ", removed=" + filesRemoved + "}";
        }
    }
}
