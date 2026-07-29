package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.ScanRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fan-out: a key space large enough that one directory listing hurts spreads its files over
 * sub-directories chosen by a pure function of the key.
 *
 * <p>The property that matters most here is not the spread, it is that the function is
 * <em>permanent</em>: it is what says where a file already is. So these tests pin the mapping with
 * hard-coded expected buckets - a test that would fail if anyone swapped the hash - and pin that
 * opening a key space with a different partitioner fails instead of quietly finding nothing.
 */
@DisplayName("GroupedFile - directory fan-out")
class GroupedFilePartitionerTest {

    @TempDir Path baseDir;

    private static final String PLAYERDATA = "playerdata";

    // ------------------------------------------------------------------
    //  The mapping itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the hash is fixed forever - these buckets may never change")
    void hashFanout_isStable() {
        GroupedFilePartitioner two = GroupedFilePartitioner.hashFanout(2);

        // SHA-1 of the key's UTF-8 bytes, first bytes as hex. Hard-coded on purpose: a file's
        // location is permanent, so changing the function would strand every file already written.
        assertEquals("52/2b", two.directoryFor("alice"));
        assertEquals("10/c5", two.directoryFor("00000000-0000-0000-0000-000000000001"));
        assertEquals("88/97", two.directoryFor("player_42"));

        assertEquals("52", GroupedFilePartitioner.hashFanout(1).directoryFor("alice"));
        assertEquals(2, two.depth());
    }

    @Test
    @DisplayName("flat() is the absence of fan-out")
    void flat_isEmpty() {
        assertEquals("", GroupedFilePartitioner.flat().directoryFor("anything"));
        assertEquals(0,  GroupedFilePartitioner.flat().depth());
    }

    @Test
    @DisplayName("prefix() pads short keys instead of failing on them")
    void prefix_padsShortKeys() {
        GroupedFilePartitioner three = GroupedFilePartitioner.prefix(3);
        assertEquals("abc", three.directoryFor("abcdef"));
        assertEquals("ab_", three.directoryFor("ab"), "a write must never fail because a key was short");
        assertEquals("a__", three.directoryFor("a"));
        assertEquals(1, three.depth());
    }

    @Test
    @DisplayName("prefix() will not name a bucket after a reserved Windows device")
    void prefix_avoidsReservedNames() {
        // A safe key stem is not automatically a safe directory name: "CONfig..." starts with CON,
        // and writing into CON on Windows can silently discard bytes.
        assertNotEquals("CON", GroupedFilePartitioner.prefix(3).directoryFor("CONfig"));
        assertTrue(GroupedFilePartitioner.prefix(3).directoryFor("CONfig").startsWith("CON"));
    }

    @Test
    @DisplayName("out-of-range levels are refused")
    void outOfRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GroupedFilePartitioner.hashFanout(0));
        assertThrows(IllegalArgumentException.class, () -> GroupedFilePartitioner.hashFanout(9));
        assertThrows(IllegalArgumentException.class, () -> GroupedFilePartitioner.prefix(0));
        assertThrows(IllegalArgumentException.class, () -> GroupedFilePartitioner.prefix(9));
    }

    @Test
    @DisplayName("one level spreads a thousand keys without a hot bucket")
    void hashFanout_spreadsEvenly() {
        GroupedFilePartitioner one = GroupedFilePartitioner.hashFanout(1);
        Map<String, Integer> buckets = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            buckets.merge(one.directoryFor(UUID.nameUUIDFromBytes(("k" + i).getBytes()).toString()), 1, Integer::sum);
        }
        int biggest = buckets.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(biggest < 150, "no bucket may hold 15% of the keys, got " + biggest + "/1000");
        assertTrue(buckets.size() > 100, "a thousand keys should reach many buckets, got " + buckets.size());
    }

    // ------------------------------------------------------------------
    //  On disk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a key space with fan-out writes into its bucket, and reads back")
    void fanOut_writesIntoBuckets() {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Repository<UUID, TestPlayer> repo = repo(config(GroupedFilePartitioner.hashFanout(2)));
        repo.save(new TestPlayer(alice, "Alice", 100)).join();

        assertTrue(tree().contains("player/10/c5/" + alice + ".json"), "expected the bucket path, got " + tree());
        assertEquals("Alice", repo.find(alice).join().map(TestPlayer::getName).orElse(null));
    }

    @Test
    @DisplayName("find resolves the path instead of searching for it")
    void find_doesNotSearch() throws Exception {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Repository<UUID, TestPlayer> repo = repo(config(GroupedFilePartitioner.hashFanout(2)));
        repo.save(new TestPlayer(alice, "Alice", 100)).join();

        // Move the file into a bucket the partitioner would never pick for this key. A find that
        // scanned would still turn it up; one that resolves the path cannot see it any more.
        Path from = baseDir.resolve("player").resolve("10").resolve("c5").resolve(alice + ".json");
        Path to   = baseDir.resolve("player").resolve("ff").resolve("ff").resolve(alice + ".json");
        Files.createDirectories(to.getParent());
        Files.move(from, to);

        assertFalse(repo.find(alice).join().isPresent(), "a file outside its bucket is not addressable");
        assertEquals(1L, repo.count().join(), "but a scan still walks every bucket and finds it");
    }

    @Test
    @DisplayName("every scan walks all the buckets")
    void scans_coverEveryBucket() {
        Repository<UUID, TestPlayer> repo = repo(config(GroupedFilePartitioner.hashFanout(2)));
        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID key = UUID.nameUUIDFromBytes(("scan" + i).getBytes());
            keys.add(key);
            repo.save(new TestPlayer(key, "p" + i, i)).join();
        }

        assertEquals(40L, repo.count().join());
        assertEquals(40L, repo.all().join().count());

        List<ScanRow<TestPlayer>> rows = repo.scanAll(Cursor.scan(), 100).join().content();
        assertEquals(40, rows.size());
        assertTrue(rows.stream().noneMatch(ScanRow::isFailed));
        for (UUID key : keys) assertTrue(repo.exists(key).join(), "missing " + key);
    }

    @Test
    @DisplayName("a scan of the base directory still ignores the reserved directory")
    void baseScan_ignoresSchemaDirectory() {
        // The layout file is JSON and lives under _schema/. Walking deeper than one level without
        // excluding it would count it as a key file of a JSON store.
        Repository<UUID, TestPlayer> repo = repo(new GroupedFileConfig(baseDir));
        repo.save(new TestPlayer(UUID.randomUUID(), "Alice", 100)).join();

        assertTrue(Files.exists(baseDir.resolve("_schema").resolve("layout.json")));
        assertEquals(1L, repo.count().join());
    }

    @Test
    @DisplayName("flat() leaves the tree exactly as a key space without fan-out")
    void flat_matchesNoFanOut() {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        repo(config(GroupedFilePartitioner.flat())).save(new TestPlayer(alice, "Alice", 100)).join();

        assertEquals(List.of("_schema/layout.json", "player/" + alice + ".json"), sorted(tree()));
    }

    @Test
    @DisplayName("a key at the length limit still fits under two levels of fan-out")
    void longestKey_stillFits() {
        StringBuilder key = new StringBuilder();
        while (key.length() < StorageKeys.MAX_KEY_LENGTH) key.append('k');

        GroupedFileStorage storage = open(GroupedFileConfig.builder(baseDir)
            .keySpace("player", GroupedFilePartitioner.hashFanout(2), PLAYERDATA)
            .build());
        Repository<String, StringKeyed> repo = storage.repository(
            EntityDescriptor.builder(String.class, StringKeyed.class)
                .collection(PLAYERDATA)
                .keyExtractor(StringKeyed::getId)
                .codec(new JacksonJsonCodec<>(StringKeyed.class))
                .build());

        repo.save(new StringKeyed(key.toString(), "long")).join();
        assertEquals("long", repo.find(key.toString()).join().map(StringKeyed::getLabel).orElse(null));
    }

    // ------------------------------------------------------------------
    //  Changing it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("changing the partitioner without moving the files fails on open")
    void changingPartitioner_failsOnOpen() {
        UUID alice = UUID.randomUUID();
        repo(config(GroupedFilePartitioner.flat())).save(new TestPlayer(alice, "Alice", 100)).join();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> repo(config(GroupedFilePartitioner.hashFanout(2))));

        String message = thrown.getMessage();
        assertTrue(message.contains("flat"),          () -> "names the recorded partitioner: " + message);
        assertTrue(message.contains("hashFanout:2"),  () -> "names the requested one: " + message);
        assertTrue(message.contains("player"),        () -> "names the key space: " + message);
    }

    @Test
    @DisplayName("relayout reorganises a key space into its new fan-out")
    void relayout_reorganisesBuckets() {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        GroupedFileConfig flat = config(GroupedFilePartitioner.flat());
        repo(flat).save(new TestPlayer(alice, "Alice", 100)).join();
        assertTrue(tree().contains("player/" + alice + ".json"));

        GroupedFileConfig fanned = config(GroupedFilePartitioner.hashFanout(2));
        GroupedFileRelayout.RelayoutReport report = GroupedFileRelayout.relayout(fanned);

        assertTrue(report.changed());
        assertEquals(1, report.entriesMoved());
        assertEquals(List.of("_schema/layout.json", "player/10/c5/" + alice + ".json"), sorted(tree()));
        assertEquals("Alice", repo(fanned).find(alice).join().map(TestPlayer::getName).orElse(null));
    }

    @Test
    @DisplayName("relayout back to flat() removes the bucket directories it emptied")
    void relayout_prunesEmptyBuckets() {
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        GroupedFileConfig fanned = config(GroupedFilePartitioner.hashFanout(2));
        repo(fanned).save(new TestPlayer(alice, "Alice", 100)).join();

        GroupedFileConfig flat = config(GroupedFilePartitioner.flat());
        GroupedFileRelayout.RelayoutReport report = GroupedFileRelayout.relayout(flat);

        assertTrue(report.changed());
        assertEquals(2, report.directoriesRemoved(), "both levels of the old bucket are gone");
        assertEquals(List.of("_schema/layout.json", "player/" + alice + ".json"), sorted(tree()));
        assertFalse(Files.exists(baseDir.resolve("player").resolve("10")));
        assertEquals("Alice", repo(flat).find(alice).join().map(TestPlayer::getName).orElse(null));
    }

    @Test
    @DisplayName("relayout of an unchanged fan-out is a no-op")
    void relayout_isIdempotent() {
        GroupedFileConfig fanned = config(GroupedFilePartitioner.hashFanout(2));
        repo(fanned).save(new TestPlayer(UUID.randomUUID(), "Alice", 100)).join();

        List<String> before = sorted(tree());
        assertFalse(GroupedFileRelayout.relayout(fanned).changed());
        assertEquals(before, sorted(tree()));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private GroupedFileConfig config(GroupedFilePartitioner partitioner) {
        return GroupedFileConfig.builder(baseDir)
            .keySpace("player", partitioner, PLAYERDATA)
            .build();
    }

    private GroupedFileStorage open(GroupedFileConfig config) {
        GroupedFileStorage storage = Storages.createGroupedFile(config);
        storage.init().join();
        return storage;
    }

    private Repository<UUID, TestPlayer> repo(GroupedFileConfig config) {
        return open(config).repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(PLAYERDATA)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build());
    }

    private List<String> tree() {
        try (Stream<Path> paths = Files.walk(baseDir)) {
            return paths.filter(Files::isRegularFile)
                .map(p -> baseDir.relativize(p).toString().replace('\\', '/'))
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> sorted(List<String> paths) {
        List<String> copy = new ArrayList<>(paths);
        copy.sort(String::compareTo);
        return copy;
    }

    /** A String-keyed entity, for the key-length limit - {@code TestPlayer} is keyed by UUID. */
    public static final class StringKeyed {

        private String id;
        private String label;

        public StringKeyed() {}

        StringKeyed(String id, String label) {
            this.id    = id;
            this.label = label;
        }

        public String getId()    { return id; }
        public String getLabel() { return label; }
        public void setId(String id)       { this.id = id; }
        public void setLabel(String label) { this.label = label; }
    }
}
