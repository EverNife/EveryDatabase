package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.testutil.CountingCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many stored rows each read actually deserialises on the key-major backend.
 *
 * <p>A key file aggregates every collection sharing its key, so a repository that reads its own
 * collection must not pay for the rest: a scan may only decode the keys that hold the collection,
 * a query only the rows that match it, and a presence probe none at all. The counting codec makes
 * that cost observable - without it these reads all return the right answer either way, and the
 * work they do to get there is invisible.
 */
@DisplayName("GroupedFile - decode cost of reads")
class GroupedFileDecodeCostTest {

    private static final int KEYS = 50;

    @TempDir Path baseDir;

    private CountingCodec<TestPlayer>          codec;
    private EntityDescriptor<UUID, TestPlayer> sparse;
    private GroupedFileStorage                 storage;
    private List<UUID>                         keys;

    /**
     * Fills the directory with {@value #KEYS} key files that all hold a {@code companions} row, so
     * every file is a real aggregate document, and lets the caller decide which of them also hold
     * the collection under test.
     */
    @BeforeEach
    void setUp() {
        codec  = new CountingCodec<>(new JacksonJsonCodec<>(TestPlayer.class));
        sparse = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("sparse_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(codec)
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .build();
        storage = new GroupedFileStorage(new GroupedFileConfig(baseDir));
        storage.init().join();

        EntityDescriptor<UUID, TestPlayer> companions =
            EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("companions")
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build();
        Repository<UUID, TestPlayer> other = storage.repository(companions);

        keys = new ArrayList<>(KEYS);
        for (int i = 0; i < KEYS; i++) {
            UUID key = UUID.randomUUID();
            keys.add(key);
            other.save(new TestPlayer(key, "companion_" + i, i)).join();
        }
    }

    private Repository<UUID, TestPlayer> sparseRepo() {
        return storage.repository(sparse);
    }

    /** Puts the collection under test on the first {@code n} keys. */
    private void populateSparse(int n) {
        Repository<UUID, TestPlayer> repo = sparseRepo();
        for (int i = 0; i < n; i++) {
            repo.save(new TestPlayer(keys.get(i), "player_" + i, i)).join();
        }
    }

    @Test
    @DisplayName("all() decodes only the keys that hold the collection")
    void all_decodesOnlyPresentRows() {
        populateSparse(3);
        codec.resetCounts();

        List<TestPlayer> found = sparseRepo().all().join().collect(Collectors.toList());

        assertEquals(3, found.size(), "only the 3 populated keys hold this collection");
        assertEquals(3, codec.decodeCount(),
            "all() must decode the 3 present rows and none of the other " + (KEYS - 3) + " key files");
    }

    @Test
    @DisplayName("query() decodes only the rows that match")
    void query_decodesOnlyMatches() {
        populateSparse(KEYS);          // every key holds the collection: the filter is what must narrow it
        codec.resetCounts();

        List<TestPlayer> found = sparseRepo()
            .query(Query.range("score", 0, 1))
            .join();

        assertEquals(2, found.size(), "scores 0 and 1 match");
        assertEquals(2, codec.decodeCount(),
            "query() must decode only the 2 matches, not all " + KEYS + " stored rows");
    }

    @Test
    @DisplayName("exists() and versions() decode nothing")
    void presenceProbes_decodeNothing() {
        populateSparse(3);
        codec.resetCounts();

        assertTrue(sparseRepo().exists(keys.get(0)).join(), "key 0 holds the collection");
        assertFalse(sparseRepo().exists(keys.get(10)).join(), "key 10 holds only the companion collection");
        assertEquals(3, sparseRepo().versions(keys).join().size(), "3 keys hold the collection");

        assertEquals(0, codec.decodeCount(), "a presence probe must never deserialise a row");
    }

    @Test
    @DisplayName("count() still decodes, so an undecodable row is not counted")
    void count_unchangedThisPhase() {
        populateSparse(3);
        codec.resetCounts();

        assertEquals(3L, sparseRepo().count().join(), "only the 3 populated keys hold this collection");
        assertEquals(3, codec.decodeCount(),
            "count() decodes what it counts, so it stays consistent with all()");
    }
}
