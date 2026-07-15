package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.ScriptedRepository;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.GuildBank;
import br.com.finalcraft.everydatabase.manager.log.ManagerLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.bankManagerOver;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.collected;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.scriptLostRace;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.storageReturning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flusher's health counters - the numbers a caller polls to put "the storage is unhappy" on a
 * status readout, without any log parsing. Their point is the aggregate: one periodic tick reports a
 * single line no matter how many keys misbehaved.
 */
class WriteBackCountersTest {

    private RefRegistry registry;
    private WriteBackFlusher flusher;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        flusher = new WriteBackFlusher(ManagerLog.SILENT);
    }

    @Test
    void a_fresh_flusher_starts_at_zero() {
        assertEquals(0, flusher.conflictsAdoptedCount());
        assertEquals(0, flusher.drainWriteFailureCount());
        assertEquals(0L, flusher.lastWriteFailureAt(), "zero means no write has ever failed");
    }

    @Test
    void conflictsAdoptedCount_counts_every_conflict_and_only_ever_grows() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GuildBank one = manager.seedIfAbsent(first, new GuildBank(first, 10));
        GuildBank two = manager.seedIfAbsent(second, new GuildBank(second, 20));
        scriptLostRace(repo, first, new GuildBank(first, 500));
        scriptLostRace(repo, second, new GuildBank(second, 600));

        flusher.persistBatch(manager, collected(one, two), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        assertEquals(2, flusher.conflictsAdoptedCount(), "one per conflicted key");

        flusher.persistBatch(manager, collected(one), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        assertEquals(3, flusher.conflictsAdoptedCount(),
                "the readout is monotonic - there is no drain, unlike the failure count");
    }

    @Test
    void conflictsAdoptedCount_counts_a_conflict_whose_winner_was_never_adopted() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 10));
        scriptLostRace(repo, key, new GuildBank(key, 500));
        repo.failFind(key, () -> new RuntimeException("backend unavailable"));

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        // The counter is raised as the resolution is entered, not when a winner is taken on: it
        // answers "how often did instances race here", which is the health question, and every branch
        // of the resolution is a race that happened.
        assertEquals(1, flusher.conflictsAdoptedCount());
    }

    @Test
    void drainWriteFailureCount_reports_the_total_then_resets() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GuildBank one = manager.seedIfAbsent(first, new GuildBank(first, 10));
        GuildBank two = manager.seedIfAbsent(second, new GuildBank(second, 20));
        repo.failSave(first, () -> new RuntimeException("disk full"));
        repo.failSave(second, () -> new RuntimeException("disk full"));

        flusher.persistBatch(manager, collected(one, two), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        assertEquals(2, flusher.drainWriteFailureCount(), "one per failed key");
        assertEquals(0, flusher.drainWriteFailureCount(),
                "draining resets it, so the next tick counts only its own failures");
    }

    @Test
    void lastWriteFailureAt_stamps_the_failure_and_is_not_cleared_by_a_later_clean_flush() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank bank = manager.seedIfAbsent(key, new GuildBank(key, 10));
        long before = System.currentTimeMillis();
        repo.failSave(key, () -> new RuntimeException("disk full"));

        flusher.persistBatch(manager, collected(bank), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        long stamped = flusher.lastWriteFailureAt();
        assertTrue(stamped >= before, "the failure is stamped with the wall clock");
        assertTrue(stamped <= System.currentTimeMillis());

        UUID healthy = UUID.randomUUID();
        GuildBank fine = manager.seedIfAbsent(healthy, new GuildBank(healthy, 30));
        flusher.persistBatch(manager, collected(fine), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        // "when did storage last misbehave" must survive the recovery, or a readout polling after a
        // single good flush would claim nothing ever went wrong.
        assertEquals(stamped, flusher.lastWriteFailureAt());
    }
}
