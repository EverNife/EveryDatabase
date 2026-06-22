package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * The reference-target entity, and the showcase for reference shapes: a guild that points back at
 * {@link Player}s through several {@link Ref} fields, each declaring a different per-reference
 * policy, plus a list of refs. One entity therefore covers every {@code Ref}/{@code @RefPolicy}
 * case (default, TTL, no-cache, list element types, and the nested-reference chain
 * {@code Player -> Guild -> Player}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Guild {

    private UUID id;                    // key
    private String name;

    /** Default-policy reference. */
    private Ref<UUID, Player> leader;

    /** Per-reference TTL override (baked into the ref at deserialization). */
    @RefPolicy(ttlSeconds = 180)
    private Ref<UUID, Player> founder;

    /** Per-reference no-cache override. */
    @RefPolicy(noCache = true)
    private Ref<UUID, Player> rival;

    /** A list of references - exercises type recovery for container elements. */
    private List<Ref<UUID, Player>> members;

    /** A bare guild (no references). */
    public Guild(UUID id, String name) {
        this(id, name, null, null, null, null);
    }
}
