package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.writeback.testdata.GuildBank;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.TradeContract;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** The per-type {@link ConflictHooks} the write-back suites drive the flusher with. */
final class TestHooks {

    private TestHooks() {
    }

    /** The ordinary ADOPT_WINNER wiring: a stored winner replaces the live values wholesale. */
    static final ConflictHooks<UUID, GuildBank> GUILD_BANK_HOOKS = new ConflictHooks<UUID, GuildBank>() {

        @Override
        public UUID storageKey(GuildBank live) {
            return live.getId();
        }

        @Override
        public ReentrantLock lock(GuildBank live) {
            return live.lock();
        }

        @Override
        public void adoptStoredState(GuildBank live, GuildBank stored) {
            PersistedState.copyInto(live, stored);
        }

        @Override
        public void adoptStoredLockVersion(GuildBank live, GuildBank stored) {
            live.setLockVersion(stored.getLockVersion());
        }

        @Override
        public void resetLockForRecreate(GuildBank live) {
            live.setLockVersion(null);
        }
    };

    /**
     * The same type wired to MERGE instead of adopt: {@code adoptStoredState} owns the whole
     * resolution (combine both sides, adopt the winner's lock version, re-mark dirty).
     */
    static final ConflictHooks<UUID, GuildBank> MERGING_GUILD_BANK_HOOKS = new ConflictHooks<UUID, GuildBank>() {

        @Override
        public UUID storageKey(GuildBank live) {
            return live.getId();
        }

        @Override
        public ReentrantLock lock(GuildBank live) {
            return live.lock();
        }

        @Override
        public void adoptStoredState(GuildBank live, GuildBank stored) {
            live.mergeFrom(stored);
        }

        @Override
        public void adoptStoredLockVersion(GuildBank live, GuildBank stored) {
            live.setLockVersion(stored.getLockVersion());
        }

        @Override
        public void resetLockForRecreate(GuildBank live) {
            live.setLockVersion(null);
        }

        @Override
        public boolean mergesOnConflict() {
            return true;
        }
    };

    /** The same protocol over a String-keyed type - the engine must not assume a UUID key. */
    static final ConflictHooks<String, TradeContract> TRADE_CONTRACT_HOOKS = new ConflictHooks<String, TradeContract>() {

        @Override
        public String storageKey(TradeContract live) {
            return live.getId();
        }

        @Override
        public ReentrantLock lock(TradeContract live) {
            return live.lock();
        }

        @Override
        public void adoptStoredState(TradeContract live, TradeContract stored) {
            PersistedState.copyInto(live, stored);
        }

        @Override
        public void adoptStoredLockVersion(TradeContract live, TradeContract stored) {
            live.setLockVersion(stored.getLockVersion());
        }

        @Override
        public void resetLockForRecreate(TradeContract live) {
            live.setLockVersion(null);
        }
    };
}
