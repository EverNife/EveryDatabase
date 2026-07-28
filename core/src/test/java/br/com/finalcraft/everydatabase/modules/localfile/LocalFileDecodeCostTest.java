package br.com.finalcraft.everydatabase.modules.localfile;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many stored rows each read actually deserialises on the collection-major backend.
 *
 * <p>LocalFile has no index, so a query has to walk every file - but walking them is not the same
 * as decoding them. What a stored file holds is the codec's own output, so the conditions can be
 * tested against the tree parsed from disk and only the matches ever have to become entities. The
 * counting codec is what makes that difference observable: the returned list is identical either
 * way.
 */
@DisplayName("LocalFile - decode cost of reads")
class LocalFileDecodeCostTest {

    private static final int KEYS = 50;

    @TempDir Path baseDir;

    private CountingCodec<TestPlayer>          codec;
    private Repository<UUID, TestPlayer>       repo;
    private List<UUID>                         keys;

    @BeforeEach
    void setUp() {
        codec = new CountingCodec<>(new JacksonJsonCodec<>(TestPlayer.class));
        EntityDescriptor<UUID, TestPlayer> descriptor =
            EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("scanned_players")
                .keyExtractor(TestPlayer::getUuid)
                .codec(codec)
                .index(IndexHint.string("name"))
                .index(IndexHint.integer("score"))
                .build();

        LocalFileStorage storage = new LocalFileStorage(new LocalFileConfig(baseDir));
        storage.init().join();
        repo = storage.repository(descriptor);

        keys = new ArrayList<>(KEYS);
        for (int i = 0; i < KEYS; i++) {
            UUID key = UUID.randomUUID();
            keys.add(key);
            repo.save(new TestPlayer(key, "player_" + i, i)).join();
        }
    }

    @Test
    @DisplayName("query() decodes only the rows that match")
    void query_decodesOnlyMatches() {
        codec.resetCounts();

        List<TestPlayer> found = repo.query(Query.range("score", 0, 1)).join();

        assertEquals(2, found.size(), "scores 0 and 1 match");
        assertEquals(2, codec.decodeCount(),
            "query() must decode only the 2 matches, not all " + KEYS + " stored files");
    }

    @Test
    @DisplayName("query() on an equality condition decodes only the single match")
    void query_equality_decodesOnlyMatch() {
        codec.resetCounts();

        List<TestPlayer> found = repo.query(Query.eq("name", "player_7")).join();

        assertEquals(1, found.size(), "exactly one player carries that name");
        assertEquals(1, codec.decodeCount(), "query() must decode only the match");
    }

    @Test
    @DisplayName("exists() and versions() decode nothing")
    void presenceProbes_decodeNothing() {
        codec.resetCounts();

        assertTrue(repo.exists(keys.get(0)).join(), "key 0 was stored");
        assertEquals(KEYS, repo.versions(keys).join().size(), "every key was stored");

        assertEquals(0, codec.decodeCount(), "a presence probe must never deserialise a row");
    }

    @Test
    @DisplayName("count() decodes nothing")
    void count_decodesNothing() {
        codec.resetCounts();

        assertEquals(KEYS, repo.count().join(), "every stored file is a row");
        assertEquals(0, codec.decodeCount(),
            "the directory already answers how many rows exist - opening them adds nothing");
    }
}
