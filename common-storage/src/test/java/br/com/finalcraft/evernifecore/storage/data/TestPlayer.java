package br.com.finalcraft.evernifecore.storage.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Simple test entity used across all storage tests.
 *
 * <p>Must have a no-arg constructor and getters/setters so Jackson
 * can serialise and deserialise it without annotations.
 *
 * <p>{@code randomUuid} is intentionally excluded from {@code equals}/{@code hashCode}
 * (via {@link EqualsAndHashCode.Exclude}) because it is never persisted ({@link JsonIgnore}).
 * Every call to {@code alice()}/{@code bob()}/{@code carol()} produces a fresh UUID, so
 * including it in equality would make every deserialized entity unequal to the original.
 */
@Data
@NoArgsConstructor
public class TestPlayer {

    private UUID    uuid;
    private String  name;
    private int     score;
    private String  world;      // additional indexable field (e.g. "world", "world_nether")
    private boolean active;     // additional indexable field (BOOLEAN typed)
    private long    createdAt;  // epoch millis - used by TIMESTAMP index tests

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    private UUID    randomUuid = UUID.randomUUID();

    public TestPlayer(UUID uuid, String name, int score) {
        this(uuid, name, score, "world", true);
    }

    /** Full constructor used by index tests. */
    public TestPlayer(UUID uuid, String name, int score, String world, boolean active) {
        this(uuid, name, score, world, active, 0L);
    }

    /** Full constructor including timestamp - used by timestamp index tests. */
    public TestPlayer(UUID uuid, String name, int score, String world, boolean active, long createdAt) {
        this.uuid      = uuid;
        this.name      = name;
        this.score     = score;
        this.world     = world;
        this.active    = active;
        this.createdAt = createdAt;
    }
}
