package br.com.finalcraft.everydatabase.standalone;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage;
import br.com.finalcraft.everydatabase.modules.sql.PoolTuning;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the {@code everydatabase-standalone} fat jar.
 *
 * <p><b>This suite runs against the shadow jar only</b> (see the classpath wiring in
 * {@code standalone/build.gradle}): neither {@code :core}'s classes nor the unshaded
 * libraries are on the classpath, so a broken relocation rule or a missing bundled
 * dependency fails the build here.</p>
 */
@DisplayName("everydatabase-standalone fat jar - smoke")
class StandaloneSmokeTest {

    /**
     * Entity annotated with the REAL (unrelocated) Jackson annotation. The standalone jar
     * keeps {@code com.fasterxml.jackson.annotation} at its original coordinates exactly so
     * this works against the relocated databind.
     */
    public static class Player {
        private UUID uuid;
        @JsonProperty("display_name")
        private String name;
        private int score;

        public Player() {}

        public Player(UUID uuid, String name, int score) {
            this.uuid = uuid;
            this.name = name;
            this.score = score;
        }

        public UUID getUuid() { return uuid; }
        public void setUuid(UUID uuid) { this.uuid = uuid; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Player)) return false;
            Player p = (Player) o;
            return score == p.score && Objects.equals(uuid, p.uuid) && Objects.equals(name, p.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, name, score);
        }
    }

    private static EntityDescriptor<UUID, Player> descriptor() {
        return EntityDescriptor.builder(UUID.class, Player.class)
            .collection("smoke_players")
            .keyExtractor(Player::getUuid)
            .codec(new JacksonJsonCodec<>(Player.class))
            .build();
    }

    @Test
    @DisplayName("relocation sanity: shaded names exist, original names do not, slf4j and jackson-annotations untouched")
    void relocationSanity() {
        // Relocated classes must be present...
        assertDoesNotThrow(() -> Class.forName("br.com.finalcraft.everydatabase.libs.hikari.HikariConfig"));
        assertDoesNotThrow(() -> Class.forName("br.com.finalcraft.everydatabase.libs.jackson.databind.ObjectMapper"));
        assertDoesNotThrow(() -> Class.forName("br.com.finalcraft.everydatabase.libs.h2.Driver"));
        assertDoesNotThrow(() -> Class.forName("br.com.finalcraft.everydatabase.libs.mysql.cj.jdbc.Driver"));
        assertDoesNotThrow(() -> Class.forName("br.com.finalcraft.everydatabase.libs.postgresql.Driver"));

        // ...the original heavy-dep classes must NOT be reachable...
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.zaxxer.hikari.HikariConfig"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.h2.Driver"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.mysql.cj.jdbc.Driver"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.postgresql.Driver"));

        // ...and the two deliberate exceptions stay at their original coordinates:
        assertDoesNotThrow(() -> Class.forName("org.slf4j.LoggerFactory"));
        assertDoesNotThrow(() -> Class.forName("com.fasterxml.jackson.annotation.JsonProperty"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("br.com.finalcraft.everydatabase.libs.jackson.annotation.JsonProperty"));
    }

    @Test
    @DisplayName("H2 backend works end-to-end from the fat jar (relocated Hikari + H2 + Jackson)")
    void h2RoundTrip() {
        String url = "jdbc:h2:mem:standalone_smoke;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        Storage storage = new H2SqlStorage(new SqlConfig(url, "sa", "",
            new PoolTuning(1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30))));
        storage.init().join();
        try {
            Repository<UUID, Player> repo = storage.repository(descriptor());
            Player alice = new Player(UUID.randomUUID(), "Alice", 42);

            repo.save(alice).join();
            assertEquals(alice, repo.find(alice.getUuid()).join().orElseThrow(AssertionError::new));
            assertEquals(1L, repo.count().join());
            assertTrue(repo.delete(alice.getUuid()).join());
        } finally {
            storage.close().join();
        }
    }

    @Test
    @DisplayName("@JsonProperty (real, unrelocated) is honored by the relocated databind")
    void jacksonAnnotationHonored(@TempDir Path dir) throws Exception {
        Storage storage = new LocalFileStorage(new LocalFileConfig(dir));
        storage.init().join();
        try {
            Repository<UUID, Player> repo = storage.repository(descriptor());
            Player alice = new Player(UUID.randomUUID(), "Alice", 42);
            repo.save(alice).join();

            // Round-trip still works...
            assertEquals(alice, repo.find(alice.getUuid()).join().orElseThrow(AssertionError::new));

            // ...and the bytes on disk prove the annotation was applied by the shaded mapper.
            String stored = readAllFiles(dir);
            assertTrue(stored.contains("display_name"),
                "serialized form must use the @JsonProperty name, got: " + stored);
            assertFalse(stored.contains("\"name\""),
                "the raw field name must have been replaced by @JsonProperty, got: " + stored);
        } finally {
            storage.close().join();
        }
    }

    private static String readAllFiles(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                sb.append(new String(Files.readAllBytes(p))).append('\n');
            }
        }
        return sb.toString();
    }
}
