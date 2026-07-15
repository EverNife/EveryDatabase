package br.com.finalcraft.everydatabase.manager.writeback.testdata;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The workhorse write-back test entity: a guild's shared vault, mutated in memory by whoever is
 * online and flushed later in a batch. It carries everything a conflict resolution needs - a
 * {@code UUID} key, an optimistic-lock counter, dirty tracking through the {@link IDirtyable}
 * interface form, and its own lock guarding the mutable state.
 *
 * <p>The {@code ledger} exists so an adopted winner is visible as more than one number: a whole-row
 * adopt that drops the other instance's entries shows up as a missing line, not just a different
 * total.
 *
 * <p>Jackson binds the fields directly (no accessors), so decoding never trips the dirtying mutator
 * and a freshly loaded instance is clean.
 */
@JsonAutoDetectFieldsOnly
public class GuildBank implements IDirtyable {

    private UUID id;
    private long gold;
    private List<String> ledger = new ArrayList<>();

    @OptimisticLock
    private Long lockVersion;

    /** Guards the mutable state - the very lock a conflict resolution must decide under. */
    @JsonIgnore
    private final transient ReentrantLock lock = new ReentrantLock();

    @JsonIgnore
    private transient boolean dirty;

    public GuildBank() {
    }

    public GuildBank(UUID id, long gold) {
        this.id = id;
        this.gold = gold;
    }

    public UUID getId() {
        return id;
    }

    public long getGold() {
        return gold;
    }

    public List<String> getLedger() {
        return ledger;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public ReentrantLock lock() {
        return lock;
    }

    /** A domain mutation, under the bank's own lock: it changes the state AND marks it dirty. */
    public void deposit(long amount, String reason) {
        lock.lock();
        try {
            this.gold += amount;
            this.ledger.add(reason);
            markDirty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Combines another instance's persisted state into this one, modelling a type that MERGES a
     * stored winner instead of adopting it: deposits are additive, so both sides' entries survive and
     * the winner's lock version is taken on for the next flush to land cleanly.
     */
    public void mergeFrom(GuildBank other) {
        lock.lock();
        try {
            this.gold += other.gold;
            this.ledger.addAll(other.ledger);
            this.lockVersion = other.lockVersion;
            markDirty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markClean() {
        this.dirty = false;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }
}
