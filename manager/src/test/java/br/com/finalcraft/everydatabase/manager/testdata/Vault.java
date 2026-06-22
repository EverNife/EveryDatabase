package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.cache.DirtyFlag;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * A write-back test entity using the {@link DirtyFlag @DirtyFlag} annotation form (no
 * {@code IDirtyable} interface): a coin balance mutated in memory sets the annotated flag directly,
 * and the manager reads / clears / re-sets it by reflection. Jackson reads/writes the fields
 * directly, so decoding never trips the dirtying mutator and a freshly loaded instance is clean.
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class Vault {

    private UUID id;
    private long coins;

    @DirtyFlag
    @JsonIgnore
    private transient boolean dirty;

    public Vault() {
    }

    public Vault(UUID id, long coins) {
        this.id = id;
        this.coins = coins;
    }

    public UUID getId() {
        return id;
    }

    public long getCoins() {
        return coins;
    }

    /** A domain mutation sets the annotated dirty flag directly (write-back). */
    public void deposit(long amount) {
        this.coins += amount;
        this.dirty = true;
    }
}
