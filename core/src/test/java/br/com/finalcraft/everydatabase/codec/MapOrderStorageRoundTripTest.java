package br.com.finalcraft.everydatabase.codec;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The codec-level guarantee that a {@code Map} keeps its insertion order is only worth
 * something if it survives a real backend: the entity is encoded on {@code save} and
 * decoded again on {@code find}, so this pins the whole path rather than the mapper alone.
 *
 * <p>Runs on the two backends that need no external server ({@code LocalFile} writes real
 * files, {@code InMemory} keeps encoded payloads in a map), so it never self-skips.
 */
@DisplayName("Map insertion order survives a save/find round trip on a real backend")
class MapOrderStorageRoundTripTest {

    /** Ordered text where the key sequence is the data - the case sorting destroys. */
    public static class Sign {
        public UUID id;
        public Map<String, String> lines = new LinkedHashMap<>();

        public UUID getId() { return id; }
    }

    private static EntityDescriptor<UUID, Sign> descriptor() {
        return EntityDescriptor.builder(UUID.class, Sign.class)
            .collection("signs")
            .keyExtractor(Sign::getId)
            .codec(new JacksonJsonCodec<>(Sign.class))
            .build();
    }

    /** Keys "1".."12" in numeric order: alphabetical sorting would reshuffle them to 1, 10, 11, 12, 2, ... */
    private static Sign sequencedSign() {
        Sign sign = new Sign();
        sign.id = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        for (int i = 1; i <= 12; i++) {
            sign.lines.put(String.valueOf(i), "line " + i);
        }
        return sign;
    }

    private static void assertOrderSurvives(Storage storage) {
        try {
            storage.init().join();
            Repository<UUID, Sign> repo = storage.repository(descriptor());

            Sign written = sequencedSign();
            List<String> expected = new ArrayList<>(written.lines.keySet());
            repo.save(written).join();

            Sign read = repo.find(written.id).join().orElseThrow();

            assertEquals(expected, new ArrayList<>(read.lines.keySet()),
                "the stored key sequence must come back exactly as it went in");
            assertEquals("line 11", read.lines.get("11"));
        } finally {
            storage.close().join();
        }
    }

    @Test
    @DisplayName("LocalFile: the sequence written to disk reads back unchanged")
    void localFile_preservesMapOrder(@TempDir Path dir) {
        assertOrderSurvives(Storages.createLocalFile(new LocalFileConfig(dir)));
    }

    @Test
    @DisplayName("InMemory: the sequence survives encode/decode through the repository")
    void inMemory_preservesMapOrder() {
        assertOrderSurvives(Storages.createInMemory());
    }
}
