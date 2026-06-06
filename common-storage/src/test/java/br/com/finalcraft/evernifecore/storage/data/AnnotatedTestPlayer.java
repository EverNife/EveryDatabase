package br.com.finalcraft.evernifecore.storage.data;

import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Indexed;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Test entity used by {@code IndexedAnnotationTest} to verify {@link Indexed} auto-detection,
 * including nested dot-path indexing and un-indexed list fields.
 *
 * <p>Three nested classes live inside this entity:
 * <ul>
 *   <li>{@link Rank}     - indexed via {@code rank.title} (dot-path, type override)</li>
 *   <li>{@link Location} - indexed via {@code location.world} (dot-path, type override)</li>
 *   <li>{@link Badge}    - stored as a {@code List} with no index (tests serialization only)</li>
 * </ul>
 *
 * <p>Separate from {@link TestPlayer} (which declares its {@link IndexHint}s manually):
 * this entity exists only to exercise annotation-driven index discovery.
 */
@Data
@NoArgsConstructor
public class AnnotatedTestPlayer {

    private UUID uuid;

    @Indexed
    private String name;

    @Indexed
    private int score;

    /**
     * Indexed via the nested {@code rank.title} path.
     * {@code type = String.class} is required because the field's Java type ({@link Rank})
     * is not a primitive or String - the scanner cannot infer the index type otherwise.
     */
    @Indexed(path = "rank.title", type = String.class)
    private Rank rank;

    /**
     * Indexed via the nested {@code location.world} path.
     * Same reasoning as {@link #rank}: explicit {@code type} required.
     */
    @Indexed(path = "location.world", type = String.class)
    private Location location;

    /**
     * A list of {@link Badge} objects. Lists cannot be meaningfully indexed in the
     * storage layer; this field exists purely to verify that list-typed fields are
     * serialised and deserialised correctly alongside indexed fields.
     */
    private List<Badge> badges;

    public AnnotatedTestPlayer(UUID uuid, String name, int score,
                               Rank rank, Location location, List<Badge> badges) {
        this.uuid     = uuid;
        this.name     = name;
        this.score    = score;
        this.rank     = rank;
        this.location = location;
        this.badges   = badges;
    }

    // ------------------------------------------------------------------
    //  Nested classes
    // ------------------------------------------------------------------

    /** Guild/player rank - contributes the {@code rank.title} index. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rank {
        private String title;
        private int    level;
    }

    /** World position - contributes the {@code location.world} index. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private String world;
        private double x;
        private double y;
        private double z;
    }

    /**
     * An achievement badge. Stored as a list; no indexes declared or expected.
     * Used to verify that list serialisation works alongside indexed fields.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Badge {
        private String name;
        private long   earnedAt;
    }
}
