package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.ScriptedRepository;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchemaMigrations;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Talisman;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.GuildBank;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.TradeContract;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.bankDescriptor;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.bankManagerOver;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.collected;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.scriptLostRace;
import static br.com.finalcraft.everydatabase.manager.writeback.WriteBackFixture.storageReturning;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write-back engine's contract, branch by branch: what a batch does when it lands, when the write
 * fails, and when another instance got there first.
 *
 * <p>The conflict paths need a backend that enforces optimistic locking, which none of the embedded
 * ones do, so they run over a {@link ScriptedRepository} whose per-key failures are dictated by the
 * test; the paths that only need a real round-trip run over an InMemory storage.
 */
class WriteBackFlusherTest {

    private RefRegistry registry;
    private InMemoryStorage inMemory;
    private CapturingManagerLog log;
    private WriteBackFlusher flusher;

    @BeforeEach
    void setUp() {
        EntitySchemaMigrations.clear();   // the ahead-write guard reads the process-wide chain registry
        registry = new RefRegistry();
        inMemory = Storages.createInMemory();
        inMemory.init().join();
        log = new CapturingManagerLog();
        flusher = new WriteBackFlusher(log);
    }

    @AfterEach
    void tearDown() {
        inMemory.close().join();
        EntitySchemaMigrations.clear();
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    private EntityDescriptor<String, TradeContract> contractDescriptor() {
        return EntityDescriptor.builder(String.class, TradeContract.class)
                .collection("trade_contracts")
                .keyExtractor(TradeContract::getId)
                .codec(registry.codec(TradeContract.class))
                .build();
    }

    private CachingManager<String, TradeContract> contractManagerOver(Storage storage) {
        return new CachingManager<>(contractDescriptor(), storage, CacheOptions.of(CachePolicy.always()), registry);
    }

    // ------------------------------------------------------------------
    //  1 - the batch lands
    // ------------------------------------------------------------------

    @Test
    void a_clean_batch_persists_every_entity_and_leaves_the_counters_at_zero() {
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, inMemory);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GuildBank one = manager.seedIfAbsent(first, new GuildBank(first, 100));
        GuildBank two = manager.seedIfAbsent(second, new GuildBank(second, 200));
        one.deposit(10, "tax");
        two.deposit(20, "raid loot");
        List<GuildBank> persisted = new ArrayList<>();

        flusher.persistBatch(manager, collected(one, two), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, persisted::add).join();

        assertEquals(2, persisted.size(), "onPersisted runs for every entity of a clean batch");
        assertEquals(110, manager.repository().find(first).join().get().getGold());
        assertEquals(220, manager.repository().find(second).join().get().getGold());
        assertFalse(one.isDirty(), "nothing re-marks a landed entity");
        assertEquals(0, flusher.conflictsAdoptedCount());
        assertEquals(0, flusher.drainWriteFailureCount());
        assertEquals(0L, flusher.lastWriteFailureAt());
        assertTrue(log.lines().isEmpty(), "a flush that went fine says nothing at all");
    }

    // ------------------------------------------------------------------
    //  2 - the write fails
    // ------------------------------------------------------------------

    @Test
    void a_write_error_re_marks_the_entity_dirty_and_never_fails_a_background_flush() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank bank = manager.seedIfAbsent(key, new GuildBank(key, 100));
        bank.deposit(10, "tax");
        repo.failSave(key, () -> new RuntimeException("disk full"));

        assertDoesNotThrow(() -> {
            flusher.persistBatch(manager, collected(bank), FlushMode.BACKGROUND, "guild bank",
                    TestHooks.GUILD_BANK_HOOKS, null).join();
        });

        assertTrue(bank.isDirty(), "a transient error re-marks the entity, so the next flush retries it");
        assertTrue(flusher.lastWriteFailureAt() > 0L);
        assertTrue(log.has(Level.FINE, "Failed to save guild bank"));
    }

    @Test
    void a_forced_flush_surfaces_a_write_error_as_a_storage_write_exception() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank bank = manager.seedIfAbsent(key, new GuildBank(key, 100));
        bank.deposit(10, "tax");
        repo.failSave(key, () -> new RuntimeException("disk full"));

        CompletableFuture<Void> flush = flusher.persistBatch(manager, collected(bank), FlushMode.FORCED,
                "guild bank", TestHooks.GUILD_BANK_HOOKS, null);

        CompletionException boom = assertThrows(CompletionException.class, flush::join);
        StorageWriteException failure = assertInstanceOf(StorageWriteException.class, boom.getCause(),
                "an explicit durability request must learn that its write did not land");
        assertEquals("guild bank", failure.getWhat());
        assertEquals(Collections.singletonList(key), failure.getFailedKeys());
        assertTrue(bank.isDirty(), "the entity is still retried in the background regardless");
    }

    @Test
    void a_repository_that_throws_instead_of_failing_its_future_still_re_marks_the_whole_batch() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GuildBank one = manager.seedIfAbsent(first, new GuildBank(first, 10));
        GuildBank two = manager.seedIfAbsent(second, new GuildBank(second, 20));
        repo.throwOnSave(first, () -> new IllegalStateException("connection pool exploded"));

        CompletableFuture<Void> flush = flusher.persistBatch(manager, collected(one, two), FlushMode.FORCED,
                "guild bank", TestHooks.GUILD_BANK_HOOKS, null);

        assertThrows(CompletionException.class, flush::join);
        // The batch was mark-cleaned before the call, so a manager breaking its report-never-throw
        // contract would otherwise drop every unsaved change on the floor, silently.
        assertTrue(one.isDirty(), "the entity whose write blew up is re-marked...");
        assertTrue(two.isDirty(), "...and so is the one that was never even attempted");
        assertEquals(2, flusher.drainWriteFailureCount());
    }

    // ------------------------------------------------------------------
    //  3 to 7 - another instance got there first
    // ------------------------------------------------------------------

    @Test
    void a_conflict_adopts_the_stored_winner_into_a_clean_live_instance() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        live.deposit(10, "local tax");
        GuildBank winner = new GuildBank(key, 500);
        winner.getLedger().add("remote raid loot");
        winner.setLockVersion(7L);
        scriptLostRace(repo, key, winner);
        RecordingConflictHooks<UUID, GuildBank> hooks = new RecordingConflictHooks<>(TestHooks.GUILD_BANK_HOOKS);

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank", hooks, null).join();

        assertEquals(Arrays.asList("adoptStoredState", "afterAdopt"), hooks.calls(),
                "a clean live instance takes the winner on, then gets the post-adopt callback");
        assertEquals(500, live.getGold(), "the live instance now agrees with the backend");
        assertEquals(Arrays.asList("remote raid loot"), live.getLedger());
        assertEquals(Long.valueOf(7L), live.getLockVersion());
        assertSame(live, manager.peek(key).get(),
                "the SAME instance is re-installed as the canonical cell - held references stay flushable");
        assertEquals(1, flusher.conflictsAdoptedCount());
        assertTrue(log.has(Level.WARNING, "ADOPT_WINNER"));
    }

    @Test
    void a_forced_flush_surfaces_a_lost_race_as_an_optimistic_conflict() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        live.deposit(10, "local tax");
        scriptLostRace(repo, key, new GuildBank(key, 500));

        CompletableFuture<Void> flush = flusher.persistBatch(manager, collected(live), FlushMode.FORCED,
                "guild bank", TestHooks.GUILD_BANK_HOOKS, null);

        CompletionException boom = assertThrows(CompletionException.class, flush::join);
        OptimisticConflictException conflict = assertInstanceOf(OptimisticConflictException.class, boom.getCause());
        assertEquals(Collections.singletonList(key), conflict.getConflictedKeys());
        assertEquals(500, live.getGold(), "the winner was adopted before the caller was told it lost");
        assertTrue(log.has(Level.WARNING, "[forced] "), "the caller-initiated path tags its conflict log");
    }

    @Test
    void a_live_instance_re_dirtied_during_the_resolution_keeps_its_local_values() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        GuildBank winner = new GuildBank(key, 500);
        winner.setLockVersion(7L);
        scriptLostRace(repo, key, winner);
        repo.beforeFind(key, () -> live.deposit(42, "late deposit"));   // a change lands while we re-read
        RecordingConflictHooks<UUID, GuildBank> hooks = new RecordingConflictHooks<>(TestHooks.GUILD_BANK_HOOKS);

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank", hooks, null).join();

        assertEquals(Collections.singletonList("adoptStoredLockVersion"), hooks.calls(),
                "only the winner's lock version is taken; adopting its values would eat the late deposit");
        assertEquals(142, live.getGold(), "the local state survives...");
        assertEquals(Long.valueOf(7L), live.getLockVersion(), "...and now knows the version it must beat");
        assertTrue(live.isDirty(), "so the next flush overwrites the remote values cleanly");
        assertSame(live, manager.peek(key).get());
    }

    @Test
    void a_winner_that_vanished_mid_resolution_resets_the_lock_so_the_row_is_re_created() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        live.setLockVersion(3L);   // it was loaded from a row that has since been deleted
        repo.failSave(key, () -> new OptimisticLockException(GuildBank.class, key, 3L, 7L));
        RecordingConflictHooks<UUID, GuildBank> hooks = new RecordingConflictHooks<>(TestHooks.GUILD_BANK_HOOKS);

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank", hooks, null).join();

        assertEquals(Collections.singletonList("resetLockForRecreate"), hooks.calls());
        assertNull(live.getLockVersion(), "with the lock reset the next flush inserts instead of updating");
        assertTrue(live.isDirty());
        assertTrue(log.has(Level.WARNING, "vanished"));
        assertSame(live, manager.peek(key).get());
    }

    @Test
    void a_failed_re_read_leaves_the_conflict_to_the_next_flush() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        live.deposit(10, "local tax");
        scriptLostRace(repo, key, new GuildBank(key, 500));
        repo.failFind(key, () -> new RuntimeException("backend unavailable"));
        RecordingConflictHooks<UUID, GuildBank> hooks = new RecordingConflictHooks<>(TestHooks.GUILD_BANK_HOOKS);

        assertDoesNotThrow(() -> {
            flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank", hooks, null).join();
        });

        assertTrue(hooks.calls().isEmpty(), "nothing is adopted - the winner could not even be read");
        assertEquals(110, live.getGold(), "the local state is untouched");
        assertTrue(live.isDirty(), "and dirty, so the whole race is replayed on the next flush");
        assertTrue(log.has(Level.WARNING, "re-reading the winner FAILED"));
        assertSame(live, manager.peek(key).get());
    }

    @Test
    void a_merging_type_combines_both_sides_and_skips_the_post_adopt_callback() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        live.deposit(10, "local tax");
        GuildBank winner = new GuildBank(key, 500);
        winner.getLedger().add("remote raid loot");
        winner.setLockVersion(7L);
        scriptLostRace(repo, key, winner);
        repo.beforeFind(key, () -> live.deposit(5, "late tip"));   // even re-dirtied, a merge still runs
        RecordingConflictHooks<UUID, GuildBank> hooks = new RecordingConflictHooks<>(TestHooks.MERGING_GUILD_BANK_HOOKS);

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank", hooks, null).join();

        assertEquals(Collections.singletonList("adoptStoredState"), hooks.calls(),
                "the merge owns the whole resolution: it beats the dirty check and gets no afterAdopt");
        assertEquals(615, live.getGold(), "both sides' deposits count");
        assertEquals(Arrays.asList("local tax", "late tip", "remote raid loot"), live.getLedger());
        assertEquals(Long.valueOf(7L), live.getLockVersion());
        assertTrue(live.isDirty(), "the merged state still has to persist");
        assertTrue(log.has(Level.WARNING, "MERGED"));
    }

    // ------------------------------------------------------------------
    //  8 - the canonical cell
    // ------------------------------------------------------------------

    @Test
    void re_installing_the_canonical_cell_evicts_a_duplicate_loaded_in_the_window() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID key = UUID.randomUUID();
        GuildBank live = manager.seedIfAbsent(key, new GuildBank(key, 100));
        scriptLostRace(repo, key, new GuildBank(key, 500));
        GuildBank duplicate = new GuildBank(key, 500);
        duplicate.deposit(1, "racer deposit");
        // The conflict evicted the cell; a racer cold-loads its own copy into that window.
        repo.beforeFind(key, () -> manager.seedIfAbsent(key, duplicate));

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, null).join();

        assertSame(live, manager.peek(key).get(), "the held instance wins the cell back");
        assertTrue(log.has(Level.WARNING, "discarding a concurrently loaded duplicate"),
                "and the duplicate's unsaved change is dropped loudly, never in silence");
    }

    @Test
    void a_canonical_re_install_that_never_wins_gives_up_loudly_without_failing_the_flush() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        UUID key = UUID.randomUUID();
        scriptLostRace(repo, key, new GuildBank(key, 500));
        GuildBank duplicate = new GuildBank(key, 500);
        // A racer that re-seeds its own copy faster than the re-install can evict it, every time.
        CachingManager<UUID, GuildBank> manager = new CachingManager<UUID, GuildBank>(bankDescriptor(registry),
                storageReturning(repo), CacheOptions.of(CachePolicy.always()), registry) {
            @Override
            public GuildBank seedIfAbsent(UUID seedKey, GuildBank value) {
                return duplicate;
            }
        };
        GuildBank live = new GuildBank(key, 100);

        assertDoesNotThrow(() -> {
            flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "guild bank",
                    TestHooks.GUILD_BANK_HOOKS, null).join();
        });

        // Losing the canonical cell orphans every held reference, so it is the loudest thing the
        // flusher says - but there is nothing a background pass could do about it, so it keeps going.
        assertTrue(log.has(Level.SEVERE, "may no longer be flushed"));
    }

    // ------------------------------------------------------------------
    //  9 and 11 - what a mixed report does
    // ------------------------------------------------------------------

    @Test
    void a_forced_flush_reports_the_write_failure_first_and_attaches_the_lost_race() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID brokenKey = UUID.randomUUID();
        UUID racedKey = UUID.randomUUID();
        GuildBank broken = manager.seedIfAbsent(brokenKey, new GuildBank(brokenKey, 10));
        GuildBank raced = manager.seedIfAbsent(racedKey, new GuildBank(racedKey, 20));
        repo.failSave(brokenKey, () -> new RuntimeException("disk full"));
        scriptLostRace(repo, racedKey, new GuildBank(racedKey, 999));

        CompletableFuture<Void> flush = flusher.persistBatch(manager, collected(broken, raced),
                FlushMode.FORCED, "guild bank", TestHooks.GUILD_BANK_HOOKS, null);

        CompletionException boom = assertThrows(CompletionException.class, flush::join);
        StorageWriteException failure = assertInstanceOf(StorageWriteException.class, boom.getCause(),
                "a write that did not land is the primary failure - a lost race at least stored something");
        assertEquals(Collections.singletonList(brokenKey), failure.getFailedKeys());
        Throwable[] suppressed = failure.getSuppressed();
        assertEquals(1, suppressed.length);
        OptimisticConflictException conflict = assertInstanceOf(OptimisticConflictException.class, suppressed[0],
                "and the race rides along, so the caller can see both");
        assertEquals(Collections.singletonList(racedKey), conflict.getConflictedKeys());
    }

    @Test
    void onPersisted_runs_only_for_the_entities_that_actually_landed() {
        ScriptedRepository<UUID, GuildBank> repo = new ScriptedRepository<>(GuildBank::getId);
        CachingManager<UUID, GuildBank> manager = bankManagerOver(registry, storageReturning(repo));
        UUID savedKey = UUID.randomUUID();
        UUID brokenKey = UUID.randomUUID();
        UUID racedKey = UUID.randomUUID();
        GuildBank saved = manager.seedIfAbsent(savedKey, new GuildBank(savedKey, 10));
        GuildBank broken = manager.seedIfAbsent(brokenKey, new GuildBank(brokenKey, 20));
        GuildBank raced = manager.seedIfAbsent(racedKey, new GuildBank(racedKey, 30));
        repo.failSave(brokenKey, () -> new RuntimeException("disk full"));
        scriptLostRace(repo, racedKey, new GuildBank(racedKey, 999));
        List<UUID> persisted = new ArrayList<>();

        flusher.persistBatch(manager, collected(saved, broken, raced), FlushMode.BACKGROUND, "guild bank",
                TestHooks.GUILD_BANK_HOOKS, bank -> persisted.add(bank.getId())).join();

        // onPersisted is where a consumer flags "this row now exists in the backend"; setting it for a
        // row that never landed would turn the next save into an update against nothing.
        assertEquals(Collections.singletonList(savedKey), persisted);
    }

    // ------------------------------------------------------------------
    //  10 - the ahead-schema guard
    // ------------------------------------------------------------------

    @Test
    void refuseAheadWrite_blocks_a_payload_from_a_newer_build_and_warns_once_per_type() {
        Talisman ahead = new Talisman(UUID.randomUUID(), 5, "fire", 4);        // stamped v4...
        Talisman alsoAhead = new Talisman(UUID.randomUUID(), 6, "ice", 7);     // ...but this build knows v1

        assertTrue(flusher.refuseAheadWrite(ahead, "talisman", UUID.randomUUID()),
                "flushing it would strip the fields this build cannot even see");
        assertTrue(flusher.refuseAheadWrite(alsoAhead, "talisman", UUID.randomUUID()), "every row of the type");
        assertEquals(1, log.count(Level.WARNING, "written by a NEWER schema version"),
                "but a whole stale server would drown in the warning, so it is once per type");
    }

    @Test
    void refuseAheadWrite_never_blocks_an_entity_that_does_not_version_its_payload() {
        GuildBank bank = new GuildBank(UUID.randomUUID(), 10);

        assertFalse(flusher.refuseAheadWrite(bank, "guild bank", bank.getId()),
                "a consumer that does not use payload versioning is untouched by the guard");
        assertTrue(log.lines().isEmpty());
    }

    // ------------------------------------------------------------------
    //  The key type is a parameter, not a UUID
    // ------------------------------------------------------------------

    @Test
    void a_string_keyed_type_is_persisted_the_same_way() {
        CachingManager<String, TradeContract> manager = contractManagerOver(inMemory);
        String key = "alice>bob#1";
        TradeContract contract = manager.seedIfAbsent(key, new TradeContract(key, "sword", 100));
        contract.reprice(250);
        List<TradeContract> persisted = new ArrayList<>();

        flusher.persistBatch(manager, collected(contract), FlushMode.BACKGROUND, "trade contract",
                TestHooks.TRADE_CONTRACT_HOOKS, persisted::add).join();

        assertEquals(1, persisted.size());
        assertEquals(250, manager.repository().find(key).join().get().getPrice());
    }

    @Test
    void a_string_keyed_type_resolves_a_conflict_the_same_way() {
        ScriptedRepository<String, TradeContract> repo = new ScriptedRepository<>(TradeContract::getId);
        CachingManager<String, TradeContract> manager = contractManagerOver(storageReturning(repo));
        String key = "alice>bob#1";
        TradeContract live = manager.seedIfAbsent(key, new TradeContract(key, "sword", 100));
        TradeContract winner = new TradeContract(key, "sword", 900);
        winner.setLockVersion(2L);
        repo.put(winner);
        repo.failSave(key, () -> new OptimisticLockException(TradeContract.class, key, 0L, 2L));

        flusher.persistBatch(manager, collected(live), FlushMode.BACKGROUND, "trade contract",
                TestHooks.TRADE_CONTRACT_HOOKS, null).join();

        assertEquals(900, live.getPrice(), "a winner is adopted through a String key just as through a UUID");
        assertEquals(Long.valueOf(2L), live.getLockVersion());
        assertSame(live, manager.peek(key).get());
        assertTrue(log.has(Level.WARNING, "of [alice>bob#1]"), "and the key is reported as itself");
    }
}
