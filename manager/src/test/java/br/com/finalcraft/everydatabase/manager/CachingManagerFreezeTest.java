package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.cache.DirtyAccessor;
import br.com.finalcraft.everydatabase.manager.testdata.Bank;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.manager.testdata.Vault;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write freeze: while a {@link CachingManager.FreezeHandle} is open the manager persists nothing
 * and retains its dirty set, so everything mutated during the window drains once it is released.
 */
class CachingManagerFreezeTest {

    private RefRegistry registry;
    private InMemoryStorage storage;
    private EntityDescriptor<UUID, Bank> bankDescriptor;
    private EntityDescriptor<UUID, Vault> vaultDescriptor;
    private EntityDescriptor<UUID, Player> playerDescriptor;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storage = Storages.createInMemory();
        storage.init().join();
        bankDescriptor = EntityDescriptor.builder(UUID.class, Bank.class)
                .collection("banks")
                .keyExtractor(Bank::getId)
                .codec(registry.codec(Bank.class))
                .build();
        vaultDescriptor = EntityDescriptor.builder(UUID.class, Vault.class)
                .collection("vaults")
                .keyExtractor(Vault::getId)
                .codec(registry.codec(Vault.class))
                .build();
        playerDescriptor = EntityDescriptor.builder(UUID.class, Player.class)
                .collection("players")
                .keyExtractor(Player::getUuid)
                .codec(registry.codec(Player.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    private CachingManager<UUID, Bank> bankManager() {
        return new CachingManager<>(bankDescriptor, storage, CachePolicy.always(), registry);
    }

    /** The cause of a future that failed, unwrapped from the CompletionException join() wraps it in. */
    private static Throwable causeOf(Executable failing) {
        CompletionException wrapper = assertThrows(CompletionException.class, failing);
        return wrapper.getCause();
    }

    // ------------------------------------------------------------------
    //  1-2: a frozen save is deferred, a frozen flush retains everything
    // ------------------------------------------------------------------

    @Test
    void a_frozen_save_is_deferred_as_dirty_and_drains_after_the_release() {
        CachingManager<UUID, Bank> manager = bankManager();
        UUID id = UUID.randomUUID();
        Bank bank = new Bank(id, 100);

        CachingManager.FreezeHandle freeze = manager.freezeWrites();
        manager.saveAndCache(bank).join();   // completes: the write was accepted, just not written yet

        assertTrue(bank.isDirty(), "the deferred write is retained as a dirty flag");
        assertFalse(manager.repository().find(id).join().isPresent(), "nothing reached the backend");
        assertSame(bank, manager.peek(id).get(), "the cache is still updated write-through");

        freeze.close();
        manager.flushDirty().join();

        assertEquals(100, manager.repository().find(id).join().get().getCoins(), "drained on the first flush");
        assertFalse(bank.isDirty());
    }

    @Test
    void a_frozen_flush_is_a_no_op_that_keeps_the_dirty_set() {
        CachingManager<UUID, Bank> manager = bankManager();
        UUID id = UUID.randomUUID();
        Bank bank = manager.seedIfAbsent(id, new Bank(id, 0));
        bank.deposit(70);

        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            BatchSaveReport<UUID> report = manager.flushDirty().join();

            assertTrue(report.isEmpty(), "an empty report: nothing failed, nothing was written");
            assertTrue(bank.isDirty(), "the flag was NOT cleared - the write must survive the window");
            assertFalse(manager.repository().find(id).join().isPresent(), "nothing reached the backend");
        }

        manager.flushDirty().join();
        assertEquals(70, manager.repository().find(id).join().get().getCoins());
    }

    // ------------------------------------------------------------------
    //  3: no drain channel -> refuse rather than report a false durable write
    // ------------------------------------------------------------------

    @Test
    void a_frozen_save_of_a_type_without_dirty_tracking_fails() {
        CachingManager<UUID, Player> manager =
                new CachingManager<>(playerDescriptor, storage, CachePolicy.always(), registry);
        UUID id = UUID.randomUUID();
        Player player = new Player(id, "Steve");

        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            Throwable cause = causeOf(() -> manager.saveAndCache(player).join());

            assertTrue(cause instanceof IllegalStateException, "refused, not silently dropped: " + cause);
            assertTrue(cause.getMessage().contains("not dirty-trackable"), cause.getMessage());
            assertFalse(manager.repository().find(id).join().isPresent());
        }

        manager.saveAndCache(player).join();   // the same save works once released
        assertEquals("Steve", manager.repository().find(id).join().get().getName());
    }

    // ------------------------------------------------------------------
    //  4: a delete cannot be deferred
    // ------------------------------------------------------------------

    @Test
    void a_frozen_delete_fails_and_works_again_after_the_release() {
        CachingManager<UUID, Bank> manager = bankManager();
        UUID id = UUID.randomUUID();
        manager.saveAndCache(new Bank(id, 10)).join();

        CachingManager.FreezeHandle freeze = manager.freezeWrites();
        Throwable cause = causeOf(() -> manager.deleteAndEvict(id).join());

        assertTrue(cause instanceof IllegalStateException, "refused, not deferred: " + cause);
        assertTrue(manager.repository().find(id).join().isPresent(), "the row is untouched");

        freeze.close();
        assertTrue(manager.deleteAndEvict(id).join(), "the delete works once released");
        assertFalse(manager.repository().find(id).join().isPresent());
    }

    // ------------------------------------------------------------------
    //  5: one freeze at a time; close is idempotent
    // ------------------------------------------------------------------

    @Test
    void only_one_freeze_at_a_time_and_close_is_idempotent() {
        CachingManager<UUID, Bank> manager = bankManager();

        CachingManager.FreezeHandle first = manager.freezeWrites();
        assertThrows(IllegalStateException.class, manager::freezeWrites, "a second freeze throws");
        assertFalse(manager.tryFreezeWrites().isPresent(), "...and tryFreezeWrites answers it without throwing");

        first.close();
        CachingManager.FreezeHandle second = manager.freezeWrites();   // the freeze is available again
        assertTrue(manager.isFrozen());

        // A stale handle closing twice must not release the freeze somebody else now holds.
        first.close();
        assertTrue(manager.isFrozen(), "the second freeze survives a re-close of the first handle");

        second.close();
        second.close();   // inert
        assertFalse(manager.isFrozen());
    }

    // ------------------------------------------------------------------
    //  6: the state is observable and try-with-resources releases it
    // ------------------------------------------------------------------

    @Test
    void isFrozen_reflects_the_cycle_and_try_with_resources_releases() {
        CachingManager<UUID, Bank> manager = bankManager();

        assertFalse(manager.isFrozen());
        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            assertTrue(manager.isFrozen(), "frozen for the whole block");
        }
        assertFalse(manager.isFrozen(), "released by try-with-resources");

        // Even a block that blows up releases the freeze - the leak the javadoc warns about is
        // exactly what try-with-resources removes.
        assertThrows(RuntimeException.class, () -> {
            try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
                throw new RuntimeException("transfer exploded");
            }
        });
        assertFalse(manager.isFrozen(), "released even when the block threw");
    }

    // ------------------------------------------------------------------
    //  7: a mutation made DURING the window is never lost
    // ------------------------------------------------------------------

    @Test
    void a_mutation_made_during_the_window_reaches_the_backend_after_the_release() {
        CachingManager<UUID, Bank> manager = bankManager();
        UUID id = UUID.randomUUID();
        Bank bank = new Bank(id, 100);
        manager.saveAndCache(bank).join();
        assertEquals(100, manager.repository().find(id).join().get().getCoins(), "the pre-freeze state is stored");

        CachingManager.FreezeHandle freeze = manager.freezeWrites();
        bank.deposit(50);                     // the application keeps mutating through the window
        manager.flushDirty().join();          // a periodic flush lands mid-window and must not eat it
        assertEquals(100, manager.repository().find(id).join().get().getCoins(), "the backend stays frozen");

        freeze.close();
        manager.flushDirty().join();

        assertEquals(150, manager.repository().find(id).join().get().getCoins(),
                "the window's mutation drains into the backend");
    }

    // ------------------------------------------------------------------
    //  8: the @DirtyFlag form behaves identically (through the DirtyAccessor)
    // ------------------------------------------------------------------

    @Test
    void the_annotation_dirty_flag_form_defers_the_same_way() {
        CachingManager<UUID, Vault> manager =
                new CachingManager<>(vaultDescriptor, storage, CachePolicy.always(), registry);
        DirtyAccessor accessor = DirtyAccessor.forType(Vault.class);
        UUID id = UUID.randomUUID();
        Vault vault = new Vault(id, 100);

        CachingManager.FreezeHandle freeze = manager.freezeWrites();
        manager.saveAndCache(vault).join();

        assertTrue(accessor.isDirty(vault), "the manager set the annotated flag by reflection");
        assertFalse(manager.repository().find(id).join().isPresent(), "nothing reached the backend");

        freeze.close();
        manager.flushDirty().join();

        assertEquals(100, manager.repository().find(id).join().get().getCoins(), "drained on the first flush");
        assertFalse(accessor.isDirty(vault));
    }

    // ------------------------------------------------------------------
    //  The drain channel needs a cache, not just a dirty flag
    // ------------------------------------------------------------------

    /**
     * A dirty flag alone is not a drain channel: {@code flushDirty} scans cached cells, so a manager
     * that caches nothing could never find the value again. Deferring there would report a durable
     * success for a write nothing would ever perform.
     */
    @Test
    void a_frozen_save_on_a_non_caching_manager_fails_even_for_a_dirty_trackable_type() {
        CachingManager<UUID, Bank> manager =
                new CachingManager<>(bankDescriptor, storage, CachePolicy.noCache(), registry);
        UUID id = UUID.randomUUID();

        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            Throwable cause = causeOf(() -> manager.saveAndCache(new Bank(id, 5)).join());

            assertTrue(cause instanceof IllegalStateException, "refused: " + cause);
            assertTrue(cause.getMessage().contains("does not cache"), cause.getMessage());
        }
        assertFalse(manager.repository().find(id).join().isPresent());
    }

    /** A frozen batch is deferred entity by entity and reports no failures. */
    @Test
    void a_frozen_batch_save_defers_every_entity() {
        CachingManager<UUID, Bank> manager = bankManager();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Bank one = new Bank(first, 1);
        Bank two = new Bank(second, 2);

        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            BatchSaveReport<UUID> report = manager.saveAllAndCache(Arrays.asList(one, two)).join();

            assertTrue(report.isEmpty(), "nothing failed - the batch is merely deferred");
            assertTrue(one.isDirty());
            assertTrue(two.isDirty());
            assertFalse(manager.repository().find(first).join().isPresent());
        }

        manager.flushDirty().join();
        assertEquals(1, manager.repository().find(first).join().get().getCoins());
        assertEquals(2, manager.repository().find(second).join().get().getCoins());
    }

    /** An empty batch is satisfiable while frozen: there is nothing to defer and nothing to lose. */
    @Test
    void a_frozen_empty_batch_is_a_no_op() {
        CachingManager<UUID, Bank> manager = bankManager();
        try (CachingManager.FreezeHandle freeze = manager.freezeWrites()) {
            assertTrue(manager.saveAllAndCache(Collections.<Bank>emptyList()).join().isEmpty());
        }
    }
}
