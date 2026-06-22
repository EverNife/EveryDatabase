package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * The workhorse test entity: a server player. Rich enough to exercise most of the manager on its
 * own - a nested {@link Inventory} value object (deep codec round-trip), scalar fields
 * ({@code level}, {@code coins}) for write-through/index-style assertions, and a {@link Ref} to a
 * {@link Guild} for cross-manager reference resolution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Player {

    private UUID uuid;            // key
    private String name;
    private int level;
    private long coins;
    private Inventory inventory;
    private Ref<UUID, Guild> guild;

    /** A bare player (no inventory, no guild). */
    public Player(UUID uuid, String name) {
        this(uuid, name, 0, 0L, null, null);
    }

    /** A player at a given level - used where the level is asserted. */
    public Player(UUID uuid, String name, int level) {
        this(uuid, name, level, 0L, null, null);
    }

    /** A player that references a guild (for reference-resolution tests). */
    public Player(UUID uuid, Ref<UUID, Guild> guild) {
        this(uuid, null, 0, 0L, null, guild);
    }
}
