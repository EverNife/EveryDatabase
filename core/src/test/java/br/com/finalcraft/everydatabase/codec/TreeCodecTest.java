package br.com.finalcraft.everydatabase.codec;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.Storages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tree fast path: backends that hold entities as Jackson trees must reach the codec through
 * {@link TreeCodec} rather than serialising a tree just to have it parsed straight back.
 *
 * <p>Proving that is awkward with a normal codec, because both paths return the same entity. So the
 * codec used here <em>throws</em> from {@link Codec#encode} and {@link Codec#decode}: any operation
 * that completes is an operation that never touched the byte form. The mirror test does the
 * opposite - a codec with no tree form at all must still work everywhere.
 */
@DisplayName("TreeCodec - the tree fast path")
class TreeCodecTest {

    @TempDir Path baseDir;

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB   = UUID.randomUUID();

    // ------------------------------------------------------------------
    //  Capability and round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the bundled Jackson codecs expose a tree form")
    void jacksonCodecs_areTreeCodecs() {
        assertInstanceOf(TreeCodec.class, new JacksonJsonCodec<>(TestPlayer.class));
        assertInstanceOf(TreeCodec.class, new JacksonYamlCodec<>(TestPlayer.class));
        assertInstanceOf(TreeCodec.class, JacksonJsonCodec.pretty(TestPlayer.class));
    }

    @Test
    @DisplayName("the tree form round-trips an entity, in JSON and in YAML")
    void treeForm_roundTrips() {
        TestPlayer alice = new TestPlayer(ALICE, "Alice", 100, "world_nether", true, 1234L);

        JacksonJsonCodec<TestPlayer> json = new JacksonJsonCodec<>(TestPlayer.class);
        assertEquals(alice, json.decodeTree(json.encodeTree(alice)));

        JacksonYamlCodec<TestPlayer> yaml = new JacksonYamlCodec<>(TestPlayer.class);
        assertEquals(alice, yaml.decodeTree(yaml.encodeTree(alice)));
    }

    @Test
    @DisplayName("the tree form and the byte form are interchangeable")
    void treeForm_agreesWithByteForm() throws Exception {
        TestPlayer alice = new TestPlayer(ALICE, "Alice", 100, "world_nether", true, 1234L);
        JacksonJsonCodec<TestPlayer> codec = new JacksonJsonCodec<>(TestPlayer.class);

        // Written as a tree, read as bytes - and the reverse. The contract is that either form can
        // be handed to the other, which is what lets a backend switch paths without a migration.
        assertEquals(alice, codec.decode(codec.objectMapper().writeValueAsBytes(codec.encodeTree(alice))),
            "a tree the codec produced must parse back through the byte form");
        assertEquals(alice, codec.decodeTree(codec.objectMapper().readTree(codec.encode(alice))),
            "bytes the codec produced must parse back through the tree form");
    }

    @Test
    @DisplayName("encodeTree hands out a fresh tree every call")
    void encodeTree_returnsFreshTree() {
        TestPlayer alice = new TestPlayer(ALICE, "Alice", 100);
        JacksonJsonCodec<TestPlayer> codec = new JacksonJsonCodec<>(TestPlayer.class);

        // Backends embed the result into documents they then mutate; a shared tree would leak.
        assertNotSame(codec.encodeTree(alice), codec.encodeTree(alice));
    }

    // ------------------------------------------------------------------
    //  The fast path is actually taken
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the key-major backend never falls back to the byte form")
    void groupedFile_usesTreeFormOnly() {
        Repository<UUID, TestPlayer> repo = repositoryOn(
            Storages.createGroupedFile(new GroupedFileConfig(baseDir)), new ExplodingBytesCodec());

        repo.save(new TestPlayer(ALICE, "Alice", 100)).join();
        repo.save(new TestPlayer(BOB,   "Bob",    50)).join();

        assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null));
        assertEquals(2L, repo.count().join());
        assertEquals(2L, repo.all().join().count());

        List<TestPlayer> matched = repo.query(Query.eq("name", "Bob")).join();
        assertEquals(1, matched.size(), "the query still filters on the tree read from disk");

        List<ScanRow<TestPlayer>> scanned = repo.scanAll(Cursor.scan(), 10).join().content();
        assertTrue(scanned.stream().noneMatch(ScanRow::isFailed), "no row may fail to decode");
        assertEquals(2, scanned.size());

        assertTrue(repo.delete(ALICE).join());
        assertEquals(1L, repo.count().join());
    }

    @Test
    @DisplayName("the in-memory backend never falls back to the byte form")
    void inMemory_usesTreeFormOnly() {
        Repository<UUID, TestPlayer> repo = repositoryOn(new InMemoryStorage(), new ExplodingBytesCodec());

        repo.save(new TestPlayer(ALICE, "Alice", 100)).join();

        // The in-memory backend copies through the codec on every read and write to isolate the
        // caller's instance from the stored one - that copy is the round-trip being avoided here.
        Optional<TestPlayer> found = repo.find(ALICE).join();
        assertEquals("Alice", found.map(TestPlayer::getName).orElse(null));
        assertNotSame(found.orElseThrow(AssertionError::new), repo.find(ALICE).join().orElseThrow(AssertionError::new),
            "each read must still hand back its own copy");
        assertEquals(1, repo.query(Query.eq("name", "Alice")).join().size());
    }

    // ------------------------------------------------------------------
    //  A codec with no tree form still works
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a codec that only speaks bytes keeps working on every tree-holding backend")
    void byteOnlyCodec_stillWorks() {
        for (Storage storage : List.<Storage>of(
                Storages.createGroupedFile(new GroupedFileConfig(baseDir.resolve("bytes-only"))),
                new InMemoryStorage())) {

            Repository<UUID, TestPlayer> repo = repositoryOn(storage, new ByteOnlyCodec());
            repo.save(new TestPlayer(ALICE, "Alice", 100)).join();
            repo.save(new TestPlayer(BOB,   "Bob",    50)).join();

            String where = storage.getClass().getSimpleName();
            assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null), where);
            assertEquals(2L, repo.count().join(), where);
            assertEquals(List.of("Bob"), repo.query(Query.eq("name", "Bob")).join()
                .stream().map(TestPlayer::getName).collect(Collectors.toList()), where);
        }
    }

    @Test
    @DisplayName("a document written before the tree path existed still reads back")
    void storedFormat_isUnchanged() throws Exception {
        // Byte-for-byte what the byte-only path produced: the codec's own JSON, embedded verbatim as
        // a sub-node of the aggregate document. Nothing about the tree path may require a migration.
        Path keyFile = baseDir.resolve(ALICE + ".json");
        Files.write(keyFile, ("{\n  \"tree_players\" : {\n"
            + "    \"uuid\" : \"" + ALICE + "\",\n"
            + "    \"name\" : \"Alice\",\n"
            + "    \"score\" : 100,\n"
            + "    \"world\" : \"world\",\n"
            + "    \"active\" : true,\n"
            + "    \"createdAt\" : 0\n"
            + "  }\n}").getBytes(StandardCharsets.UTF_8));

        Repository<UUID, TestPlayer> repo = repositoryOn(
            Storages.createGroupedFile(new GroupedFileConfig(baseDir)), new JacksonJsonCodec<>(TestPlayer.class));

        assertEquals("Alice", repo.find(ALICE).join().map(TestPlayer::getName).orElse(null),
            "a file written by the byte path must read back through the tree path");
        assertEquals(1L, repo.count().join());

        // And writing over it keeps producing something the byte path could read.
        repo.save(new TestPlayer(ALICE, "Alice2", 7)).join();
        String rewritten = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"tree_players\""), "the collection is still the top-level field");
        assertEquals("Alice2", new JacksonJsonCodec<>(TestPlayer.class)
            .decodeTree(new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewritten).get("tree_players"))
            .getName());
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Repository<UUID, TestPlayer> repositoryOn(Storage storage, Codec<TestPlayer> codec) {
        storage.init().join();
        return storage.repository(EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("tree_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(codec)
            .index(IndexHint.string("name"))
            .build());
    }

    /** Jackson-backed, but the byte form is a trap: reaching it fails the test that triggered it. */
    private static final class ExplodingBytesCodec implements Codec<TestPlayer>, ObjectMapperAware, TreeCodec<TestPlayer> {

        private final JacksonJsonCodec<TestPlayer> delegate = new JacksonJsonCodec<>(TestPlayer.class);

        @Override public byte[]     encode(TestPlayer v)  { throw new AssertionError("the byte form was used to write"); }
        @Override public TestPlayer decode(byte[] data)   { throw new AssertionError("the byte form was used to read"); }
        @Override public JsonNode   encodeTree(TestPlayer v) { return delegate.encodeTree(v); }
        @Override public TestPlayer decodeTree(JsonNode n)   { return delegate.decodeTree(n); }
        @Override public String     contentType()   { return delegate.contentType(); }
        @Override public ObjectMapper objectMapper() { return delegate.objectMapper(); }
    }

    /** The mirror: no tree form and no exposed mapper, so every backend must fall back to bytes. */
    private static final class ByteOnlyCodec implements Codec<TestPlayer> {

        private final JacksonJsonCodec<TestPlayer> delegate = new JacksonJsonCodec<>(TestPlayer.class);

        @Override public byte[]     encode(TestPlayer v) { return delegate.encode(v); }
        @Override public TestPlayer decode(byte[] data)  { return delegate.decode(data); }
        @Override public String     contentType()        { return delegate.contentType(); }
    }
}
