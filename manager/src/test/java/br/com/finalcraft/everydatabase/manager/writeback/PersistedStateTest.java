package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.writeback.testdata.GuildBank;
import br.com.finalcraft.everydatabase.manager.writeback.testdata.TradeContract;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a stored winner carries into a live instance - and what it must never touch. The split is the
 * whole point of adopting a winner in place: the persisted values come over so the instance matches
 * the backend, while the runtime wiring (locks, dirty flags, attached references) stays put so every
 * reference held to it keeps working.
 */
class PersistedStateTest {

    /** A base class, so the copy is forced to walk past the concrete type it was handed. */
    static class Stash {

        /** A constant in the hierarchy: copying it would throw, so the walk must skip statics. */
        static final int MAX_SLOTS = 27;

        String owner;

        transient int openCount;

        @JsonIgnore
        Object attachment;
    }

    static class GuildStash extends Stash {
        long balance;
    }

    @Test
    void copyInto_carries_every_persisted_field_of_the_whole_hierarchy() {
        GuildStash live = new GuildStash();
        live.owner = "local";
        live.balance = 10;
        GuildStash stored = new GuildStash();
        stored.owner = "winner";
        stored.balance = 999;

        PersistedState.copyInto(live, stored);

        assertEquals(999, live.balance, "a field declared on the concrete type is copied");
        assertEquals("winner", live.owner, "a field inherited from a superclass is copied too");
    }

    @Test
    void copyInto_skips_static_and_runtime_only_fields() {
        GuildStash live = new GuildStash();
        live.openCount = 7;
        live.attachment = "held by the live instance";
        GuildStash stored = new GuildStash();
        stored.openCount = 99;
        stored.attachment = "decoded alongside the winner";

        PersistedState.copyInto(live, stored);   // MAX_SLOTS is static final: copying it would throw

        assertEquals(7, live.openCount, "a transient field is runtime-only and stays with the live instance");
        assertEquals("held by the live instance", live.attachment, "so does a @JsonIgnore field");
        assertEquals(27, GuildStash.MAX_SLOTS);
    }

    @Test
    void copyInto_keeps_the_live_instances_identity_lock_and_dirty_flag() {
        UUID id = UUID.randomUUID();
        GuildBank live = new GuildBank(id, 50);
        live.deposit(25, "local deposit");   // unsaved change -> dirty, and the lock is the live one
        GuildBank stored = new GuildBank(id, 400);
        stored.getLedger().add("winner deposit");
        stored.setLockVersion(9L);

        ReentrantLock lockBefore = live.lock();
        PersistedState.copyInto(live, stored);

        assertEquals(400, live.getGold(), "the winner's persisted values land on the live instance");
        assertEquals(Arrays.asList("winner deposit"), live.getLedger());
        assertEquals(Long.valueOf(9L), live.getLockVersion(), "the winner's lock version comes over, so the next write lands");
        assertSame(lockBefore, live.lock(), "the live lock is untouched - callers may be blocked on it");
        assertTrue(live.isDirty(), "copyInto does not touch the dirty flag; the caller owns that decision");
    }

    @Test
    void copyInto_does_not_mutate_the_stored_instance() {
        UUID id = UUID.randomUUID();
        GuildBank live = new GuildBank(id, 50);
        GuildBank stored = new GuildBank(id, 400);
        stored.setLockVersion(3L);

        PersistedState.copyInto(live, stored);

        assertEquals(400, stored.getGold(), "the copy reads the winner, never writes to it");
        assertEquals(Long.valueOf(3L), stored.getLockVersion());
        assertFalse(stored.isDirty());
    }

    @Test
    void copyInto_refuses_two_different_concrete_types() {
        GuildBank live = new GuildBank(UUID.randomUUID(), 1);
        TradeContract stored = new TradeContract("seller>buyer#1", "sword", 10);

        // Both instances must be the same concrete type; a mismatch fails loudly rather than
        // half-copying whichever fields happen to line up.
        assertThrows(IllegalArgumentException.class, () -> PersistedState.copyInto(live, stored));
    }
}
