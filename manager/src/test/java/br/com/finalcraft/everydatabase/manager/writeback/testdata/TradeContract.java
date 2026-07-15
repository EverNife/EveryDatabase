package br.com.finalcraft.everydatabase.manager.writeback.testdata;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A second write-back test entity whose key is a {@code String} ({@code "seller>buyer#serial"}), not
 * a {@code UUID}. It exists to hold the flusher to its generic key parameter: the engine was
 * extracted from a code base where every key happened to be a player's {@code UUID}, so a suite that
 * only ever used {@code UUID} keys could not tell a real {@code <K>} from a leftover assumption.
 *
 * <p>Same shape as {@link GuildBank} otherwise: dirty tracking, an optimistic-lock counter, and its
 * own lock guarding the mutable state.
 */
@JsonAutoDetectFieldsOnly
public class TradeContract implements IDirtyable {

    private String id;
    private String item;
    private long price;

    @OptimisticLock
    private Long lockVersion;

    /** Guards the mutable state - the very lock a conflict resolution must decide under. */
    @JsonIgnore
    private final transient ReentrantLock lock = new ReentrantLock();

    @JsonIgnore
    private transient boolean dirty;

    public TradeContract() {
    }

    public TradeContract(String id, String item, long price) {
        this.id = id;
        this.item = item;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public long getPrice() {
        return price;
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

    /** A domain mutation, under the contract's own lock: it changes the state AND marks it dirty. */
    public void reprice(long newPrice) {
        lock.lock();
        try {
            this.price = newPrice;
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
