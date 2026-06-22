package br.com.finalcraft.everydatabase.changefeed;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code changefeed} capability on the reference backend: {@link InMemoryStorage} emits
 * {@link ChangeEvent}s on local writes, and {@link ChangeFeedSupport} fans them out, isolating a
 * throwing listener.
 */
class ChangeFeedTest {

    private InMemoryStorage storage;
    private Repository<UUID, TestPlayer> repo;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        storage.init().join();
        EntityDescriptor<UUID, TestPlayer> descriptor = EntityDescriptor.builder(UUID.class, TestPlayer.class)
                .collection("players")
                .keyExtractor(TestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(TestPlayer.class))
                .build();
        repo = storage.repository(descriptor);
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    @Test
    void save_emits_a_save_event_with_collection_key_and_origin() {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        storage.subscribe(events::add);

        UUID id = UUID.randomUUID();
        repo.save(new TestPlayer(id, "alice", 10)).join();

        assertEquals(1, events.size());
        ChangeEvent e = events.get(0);
        assertEquals(ChangeOp.SAVE, e.op());
        assertEquals("players", e.collection());
        assertEquals(id.toString(), e.key());
        assertEquals(storage.originId(), e.originId());
    }

    @Test
    void delete_emits_a_delete_event_only_when_it_existed() {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        storage.subscribe(events::add);

        UUID id = UUID.randomUUID();
        repo.delete(id).join();                 // nothing there -> no event
        assertTrue(events.isEmpty());

        repo.save(new TestPlayer(id, "bob", 5)).join();   // SAVE
        repo.delete(id).join();                            // DELETE
        assertEquals(2, events.size());
        assertEquals(ChangeOp.SAVE, events.get(0).op());
        assertEquals(ChangeOp.DELETE, events.get(1).op());
    }

    @Test
    void save_all_emits_one_event_per_entity() {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        storage.subscribe(events::add);

        repo.saveAll(Arrays.asList(
                new TestPlayer(UUID.randomUUID(), "a", 1),
                new TestPlayer(UUID.randomUUID(), "b", 2),
                new TestPlayer(UUID.randomUUID(), "c", 3))).join();

        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> e.op() == ChangeOp.SAVE));
    }

    @Test
    void closing_a_subscription_stops_delivery() {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        ChangeSubscription sub = storage.subscribe(events::add);
        assertTrue(sub.isActive());

        sub.close();
        assertFalse(sub.isActive());

        repo.save(new TestPlayer(UUID.randomUUID(), "ghost", 0)).join();
        assertTrue(events.isEmpty(), "no delivery after close");
    }

    @Test
    void a_throwing_listener_never_breaks_the_write_and_others_still_receive() {
        List<ChangeEvent> good = new CopyOnWriteArrayList<>();
        storage.subscribe(e -> { throw new RuntimeException("boom"); });
        storage.subscribe(good::add);

        assertDoesNotThrow(() -> repo.save(new TestPlayer(UUID.randomUUID(), "ok", 1)).join());
        assertEquals(1, good.size(), "the well-behaved listener still received the event");
    }

    @Test
    void support_reports_active_subscription_count() {
        ChangeFeedSupport support = new ChangeFeedSupport();
        assertEquals(0, support.activeCount());
        ChangeSubscription s1 = support.subscribe(e -> {});
        ChangeSubscription s2 = support.subscribe(e -> {});
        assertEquals(2, support.activeCount());
        s1.close();
        assertEquals(1, support.activeCount());
        support.closeAll();
        assertEquals(0, support.activeCount());
    }
}
