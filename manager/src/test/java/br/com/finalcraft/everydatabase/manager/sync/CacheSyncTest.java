package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.SyncParticipation;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeListener;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.changefeed.ChangeSubscription;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncMode;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncObserver;
import br.com.finalcraft.everydatabase.manager.observ.CacheSyncStats;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code CacheSync} routing: a backend change event invalidates the matching {@link CachingManager}
 * cache so the next read reloads. End-to-end over the in-memory change feed, plus precise routing
 * tests over a fake {@link ChangeFeedStorage} that lets us push synthetic events with any origin.
 */
class CacheSyncTest {

    private EntityDescriptor<UUID, Guild> guildDescriptor(RefRegistry registry) {
        return guildDescriptor(registry, "guilds");
    }

    private EntityDescriptor<UUID, Guild> guildDescriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection(collection)
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }

    // ------------------------------------------------------------------
    //  End-to-end over the in-memory feed: shows the staleness bug, then the fix
    // ------------------------------------------------------------------

    @Test
    void a_write_through_one_cache_invalidates_another_over_the_same_feed() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();

        RefRegistry registryA = new RefRegistry();
        RefRegistry registryB = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = guildDescriptor(registryA);

        // Two independent caches sharing one storage: A is the "writer", B the "reader" instance.
        CachingManager<UUID, Guild> cacheA = new CachingManager<>(descriptor, storage, CachePolicy.always(), registryA);
        CachingManager<UUID, Guild> cacheB = new CachingManager<>(descriptor, storage, CachePolicy.always(), registryB);

        UUID id = UUID.randomUUID();
        cacheA.saveAndCache(new Guild(id, "v1")).join();
        cacheB.resolve(id).join();
        assertEquals("v1", cacheB.peek(id).get().getName());

        // The bug, with no sync wired: a write A makes leaves B serving the stale copy.
        cacheA.saveAndCache(new Guild(id, "v1b")).join();
        assertEquals("v1", cacheB.peek(id).get().getName(), "B is stale without CacheSync");

        // The fix: wire CacheSync. includeOwnOrigin() because A and B share one storage instance
        // in-process (same originId), so the writer's own event must still fan out to B.
        try (CacheSync sync = CacheSync.attach(storage).includeOwnOrigin().bind(cacheB).start()) {
            assertTrue(sync.isRunning());

            cacheA.saveAndCache(new Guild(id, "v2")).join();   // emits a SAVE -> invalidates B

            // B no longer serves from cache (marked stale); the next resolve reloads v2.
            assertFalse(cacheB.peek(id).isPresent(), "B was invalidated");
            assertEquals("v2", cacheB.resolve(id).join().get().getName(), "B reloaded after sync");
        }

        storage.close().join();
    }

    @Test
    void delete_event_evicts_from_the_synced_cache() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = guildDescriptor(registry);
        CachingManager<UUID, Guild> cache = new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);

        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "doomed")).join();
        assertEquals(1, cache.cachedSize());

        try (CacheSync sync = CacheSync.attach(storage).includeOwnOrigin().bind(cache).start()) {
            // Delete straight on the backend (another instance would do this); the feed evicts our cell.
            cache.repository().delete(id).join();
            assertFalse(cache.peek(id).isPresent(), "deleted entity evicted from cache");
        }

        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Routing over a fake feed: precise control of origin / collection / op / key
    // ------------------------------------------------------------------

    @Test
    void foreign_origin_save_invalidates_but_own_origin_is_skipped() {
        FakeFeedStorage storage = new FakeFeedStorage();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = guildDescriptor(registry);
        CachingManager<UUID, Guild> cache = new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);

        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "cached")).join();
        assertTrue(cache.peek(id).isPresent());

        try (CacheSync sync = CacheSync.attach(storage).bind(cache).start()) {
            // Our own origin: skipped (default), cache untouched.
            storage.push(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, storage.originId()));
            assertTrue(cache.peek(id).isPresent(), "own-origin event is skipped");

            // A foreign instance's write: invalidates.
            storage.push(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 2, "other-instance"));
            assertFalse(cache.peek(id).isPresent(), "foreign-origin event invalidates");
        }

        storage.close().join();
    }

    @Test
    void events_for_unmapped_collections_are_ignored() {
        FakeFeedStorage storage = new FakeFeedStorage();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = guildDescriptor(registry);
        CachingManager<UUID, Guild> cache = new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);

        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "cached")).join();

        try (CacheSync sync = CacheSync.attach(storage).bind(cache).start()) {
            storage.push(new ChangeEvent("some_other_collection", id.toString(), ChangeOp.SAVE, 1, "other"));
            assertTrue(cache.peek(id).isPresent(), "unmapped collection does not touch this cache");
        }

        storage.close().join();
    }

    @Test
    void an_unparseable_key_is_reported_and_skipped_not_thrown() {
        FakeFeedStorage storage = new FakeFeedStorage();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Guild> descriptor = guildDescriptor(registry);
        CachingManager<UUID, Guild> cache = new CachingManager<>(descriptor, storage, CachePolicy.always(), registry);

        AtomicReference<Throwable> reported = new AtomicReference<>();
        try (CacheSync sync = CacheSync.attach(storage).onError(reported::set).bind(cache).start()) {
            // "not-a-uuid" cannot be parsed to a UUID key: handled, not propagated into the feed thread.
            assertDoesNotThrow(() ->
                    storage.push(new ChangeEvent("guilds", "not-a-uuid", ChangeOp.SAVE, 1, "other")));
            assertNotNull(reported.get(), "the parse failure was reported to onError");
        }

        storage.close().join();
    }

    @Test
    void start_throws_when_the_backend_cannot_push_and_no_poll_interval_is_set() {
        NoFeedStorage storage = new NoFeedStorage();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(
                guildDescriptor(registry), storage, CachePolicy.always(), registry);

        CacheSync sync = CacheSync.attach(storage).bind(cache);
        IllegalStateException ex = assertThrows(IllegalStateException.class, sync::start);
        assertTrue(ex.getMessage().contains("pollEvery") || ex.getMessage().contains("ChangeFeedStorage"));

        storage.close().join();
    }

    @Test
    void attach_falls_back_to_polling_when_the_backend_cannot_push() {
        NoFeedStorage storage = new NoFeedStorage();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(
                guildDescriptor(registry), storage, CachePolicy.always(), registry);

        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "present")).join();
        cache.resolve(id).join();
        assertEquals(1, cache.cachedSize());

        // No change feed -> the facade routes to polling. Drive it deterministically via pollOnce().
        try (CacheSync sync = CacheSync.attach(storage).pollEvery(Duration.ofHours(1)).bind(cache).start()) {
            assertTrue(sync.isRunning());
            sync.pollOnce();                              // first poll records the version
            cache.repository().delete(id).join();        // another instance deletes it
            sync.pollOnce();                              // poll sees it gone -> evict
            assertFalse(cache.peek(id).isPresent(), "polling fallback evicted the deleted key");
        }

        storage.close().join();
    }

    @Test
    void auto_routes_each_manager_by_its_own_storage() {
        InMemoryStorage pushStore = Storages.createInMemory();   // a ChangeFeedStorage -> push
        NoFeedStorage   pollStore = new NoFeedStorage();          // no feed -> poll
        pushStore.init().join();
        pollStore.init().join();

        RefRegistry pushReg = new RefRegistry();
        RefRegistry pollReg = new RefRegistry();
        CachingManager<UUID, Guild> pushMgr = new CachingManager<>(
                guildDescriptor(pushReg, "guilds_push"), pushStore, CachePolicy.always(), pushReg);
        CachingManager<UUID, Guild> pollMgr = new CachingManager<>(
                guildDescriptor(pollReg, "guilds_poll"), pollStore, CachePolicy.always(), pollReg);

        UUID pushId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        pushMgr.saveAndCache(new Guild(pushId, "p")).join();
        pollMgr.saveAndCache(new Guild(pollId, "q")).join();
        pollMgr.resolve(pollId).join();
        assertTrue(pushMgr.peek(pushId).isPresent());
        assertTrue(pollMgr.peek(pollId).isPresent());

        try (CacheSync sync = CacheSync.auto()
                .includeOwnOrigin()                       // single-process: let the writer's own write fan out
                .pollEvery(Duration.ofHours(1))           // fallback for the poll-only manager
                .bind(pushMgr)
                .bind(pollMgr)
                .start()) {

            // Push manager: a write straight to the backend (as another instance would) fans out
            // through the InMemory feed synchronously. Going via the repository (not saveAndCache)
            // means no write-through re-freshens the cell, so the feed's invalidation is observable.
            pushMgr.repository().save(new Guild(pushId, "p2")).join();
            assertFalse(pushMgr.peek(pushId).isPresent(), "push-backed manager invalidated via its feed");

            // Poll manager: untouched by the feed; a poll cycle picks up the backend delete.
            sync.pollOnce();
            pollMgr.repository().delete(pollId).join();
            sync.pollOnce();
            assertFalse(pollMgr.peek(pollId).isPresent(), "poll-backed manager evicted via polling");
        }

        pushStore.close().join();
        pollStore.close().join();
    }

    // ------------------------------------------------------------------
    //  Transport (.via): publish hook + routing over a fake transport
    // ------------------------------------------------------------------

    @Test
    void via_transport_publishes_a_signal_on_each_local_write() {
        // A shareable store: a machine-local one under the RECOMMENDED default would not publish
        // (proven separately below), which is not what this test is about - it pins the publish plumbing.
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("store-pub"));
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        try (CacheSync sync = CacheSync.attach(storage).via(transport).bind(cache).start()) {
            cache.saveAndCache(new Guild(id, "v1")).join();
            cache.deleteAndEvict(id).join();
        }

        assertEquals(2, transport.published.size(), "one signal per local write");
        ChangeEvent saved = transport.published.get(0);
        assertEquals(ChangeOp.SAVE, saved.op());
        assertEquals("guilds", saved.collection());
        assertEquals(id.toString(), saved.key());
        assertEquals(transport.originId(), saved.originId(), "stamped with the transport's origin");
        assertEquals(ChangeOp.DELETE, transport.published.get(1).op());
    }

    @Test
    void via_transport_foreign_origin_invalidates_but_own_origin_is_skipped() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        try (CacheSync sync = CacheSync.attach(storage).via(transport).bind(cache).start()) {
            cache.saveAndCache(new Guild(id, "cached")).join();
            assertTrue(cache.peek(id).isPresent());

            // Echo of our own write (same transport origin): skipped, cache untouched.
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, transport.originId()));
            assertTrue(cache.peek(id).isPresent(), "own-origin signal is skipped");

            // A foreign instance's write: invalidates.
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 2, "other-instance"));
            assertFalse(cache.peek(id).isPresent(), "foreign-origin signal invalidates");

            // A foreign instance's delete: evicts.
            cache.saveAndCache(new Guild(id, "again")).join();
            assertTrue(cache.peek(id).isPresent());
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.DELETE, 3, "other-instance"));
            assertFalse(cache.peek(id).isPresent(), "foreign-origin delete evicts");
        }
        storage.close().join();
    }

    @Test
    void via_in_auto_mode_routes_a_shared_transport_across_storages() {
        InMemoryStorage storeA = Storages.createInMemory();
        InMemoryStorage storeB = Storages.createInMemory();
        storeA.init().join();
        storeB.init().join();
        RefRegistry regA = new RefRegistry();
        RefRegistry regB = new RefRegistry();
        CachingManager<UUID, Guild> a = new CachingManager<>(guildDescriptor(regA, "guilds_a"), storeA, CachePolicy.always(), regA);
        CachingManager<UUID, Guild> b = new CachingManager<>(guildDescriptor(regB, "guilds_b"), storeB, CachePolicy.always(), regB);

        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        a.saveAndCache(new Guild(idA, "a")).join();
        b.saveAndCache(new Guild(idB, "b")).join();

        FakeTransport transport = new FakeTransport();
        // One shared transport in auto() mode: managers live on different storages, route by collection.
        try (CacheSync sync = CacheSync.auto().via(transport).bind(a).bind(b).start()) {
            assertTrue(a.peek(idA).isPresent());
            assertTrue(b.peek(idB).isPresent());

            transport.deliver(new ChangeEvent("guilds_a", idA.toString(), ChangeOp.SAVE, 1, "other"));
            transport.deliver(new ChangeEvent("guilds_b", idB.toString(), ChangeOp.DELETE, 1, "other"));

            assertFalse(a.peek(idA).isPresent(), "manager A invalidated via the shared transport");
            assertFalse(b.peek(idB).isPresent(), "manager B evicted via the shared transport");
        }

        storeA.close().join();
        storeB.close().join();
    }

    @Test
    void start_rejects_two_managers_sharing_a_collection_name() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry reg1 = new RefRegistry();
        RefRegistry reg2 = new RefRegistry();
        // Two managers under the SAME collection: a transport routes purely by collection, so this is
        // ambiguous and must be rejected at start() rather than silently dropping one.
        CachingManager<UUID, Guild> a = new CachingManager<>(guildDescriptor(reg1, "guilds"), storage, CachePolicy.always(), reg1);
        CachingManager<UUID, Guild> b = new CachingManager<>(guildDescriptor(reg2, "guilds"), storage, CachePolicy.always(), reg2);

        FakeTransport transport = new FakeTransport();
        CacheSync sync = CacheSync.attach(storage).via(transport).bind(a).bind(b);
        IllegalStateException ex = assertThrows(IllegalStateException.class, sync::start);
        assertTrue(ex.getMessage().contains("collection"), "error names the colliding collection");

        storage.close().join();
    }

    @Test
    void closing_the_sync_stops_publishing() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);
        FakeTransport transport = new FakeTransport();

        CacheSync sync = CacheSync.attach(storage).via(transport).bind(cache).start();
        cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();
        int afterFirstWrite = transport.published.size();
        sync.close();

        cache.saveAndCache(new Guild(UUID.randomUUID(), "v2")).join();
        assertEquals(afterFirstWrite, transport.published.size(), "no publish after close cleared the hook");

        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Transport fallback (V2-1): standby polling while the transport is down
    // ------------------------------------------------------------------

    @Test
    void transport_fallback_polls_for_a_remote_change_when_disconnected() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "v1")).join();
        cache.resolve(id).join();
        assertEquals(1, cache.cachedSize());

        // Default: fallback ON. The transport reports down -> the standby poller takes over.
        try (CacheSync sync = CacheSync.attach(storage).via(transport).pollEvery(Duration.ofHours(1)).bind(cache).start()) {
            transport.fireConnectivity(false);
            cache.repository().delete(id).join();   // another instance deletes straight on the backend
            sync.pollOnce();                          // the active fallback poller catches it
            assertFalse(cache.peek(id).isPresent(), "fallback polling evicted the deleted key while disconnected");
            transport.fireConnectivity(true);         // push restored: the poller steps aside (smoke)
        }

        storage.close().join();
    }

    @Test
    void transport_fallback_can_be_disabled() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "v1")).join();
        cache.resolve(id).join();

        // Fallback OFF: no standby poller is created, so pollOnce() has nothing to drive.
        try (CacheSync sync = CacheSync.attach(storage).via(transport).transportFallback(false)
                .pollEvery(Duration.ofHours(1)).bind(cache).start()) {
            transport.fireConnectivity(false);
            cache.repository().delete(id).join();
            sync.pollOnce();
            assertTrue(cache.peek(id).isPresent(), "with fallback disabled, no polling runs");
        }

        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Observability: mode / transport connectivity / routing counters
    // ------------------------------------------------------------------

    @Test
    void mode_and_observer_track_transport_connectivity() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        List<CacheSyncMode> modeChanges = new ArrayList<>();
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();
        CacheSyncObserver obs = new CacheSyncObserver() {
            @Override public void onTransportConnected() { connected.incrementAndGet(); }
            @Override public void onTransportDisconnected() { disconnected.incrementAndGet(); }
            @Override public void onModeChange(CacheSyncMode mode) { modeChanges.add(mode); }
        };

        try (CacheSync sync = CacheSync.attach(storage).via(transport).observe(obs).bind(cache).start()) {
            transport.fireConnectivity(true);
            assertEquals(CacheSyncMode.TRANSPORT_PUSH, sync.mode());
            assertTrue(sync.transportConnected());
            assertEquals(1, connected.get());

            transport.fireConnectivity(false);
            assertEquals(CacheSyncMode.TRANSPORT_FALLBACK_POLL, sync.mode());
            assertFalse(sync.transportConnected());
            assertEquals(1, disconnected.get());
            assertTrue(sync.timeInFallbackMillis() >= 0);

            transport.fireConnectivity(true);
            assertEquals(CacheSyncMode.TRANSPORT_PUSH, sync.mode());
            assertEquals(2, connected.get());
            assertTrue(modeChanges.size() >= 3, "a mode change fired per connectivity transition");
        }

        storage.close().join();
    }

    @Test
    void routing_counters_are_recorded_in_stats() {
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);

        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        cache.saveAndCache(new Guild(id, "v")).join();

        try (CacheSync sync = CacheSync.attach(storage).via(transport).bind(cache).start()) {
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, "other"));        // applied
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, transport.originId())); // own -> skipped
            transport.deliver(new ChangeEvent("other_coll", id.toString(), ChangeOp.SAVE, 1, "other"));    // unmapped
            transport.deliver(new ChangeEvent("guilds", "not-a-uuid", ChangeOp.SAVE, 1, "other"));         // parse fail

            CacheSyncStats s = sync.stats();
            assertEquals(4, s.signalsReceived());
            assertEquals(1, s.signalsApplied());
            assertEquals(1, s.signalsSkippedOwnOrigin());
            assertEquals(1, s.signalsUnmapped());
            assertEquals(1, s.parseFailures());
        }

        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Routing by physical store: a shared channel is not a shared database
    // ------------------------------------------------------------------

    @Test
    void two_instances_of_the_same_store_still_invalidate_each_other() {
        TransportBus bus = new TransportBus();
        Instance writer = new Instance("shared-store", "guilds", bus);
        Instance reader = new Instance("shared-store", "guilds", bus);

        UUID id = UUID.randomUUID();
        writer.cache.saveAndCache(new Guild(id, "v1")).join();
        reader.cache.saveAndCache(new Guild(id, "v1")).join();
        assertTrue(reader.cache.peek(id).isPresent());

        try (CacheSync w = writer.start(); CacheSync r = reader.start()) {
            writer.cache.saveAndCache(new Guild(id, "v2")).join();

            assertFalse(reader.cache.peek(id).isPresent(),
                "both managers read the same physical store, so the write must invalidate the other cache");
        }
        writer.close();
        reader.close();
    }

    @Test
    void a_write_in_another_store_does_not_invalidate_this_one() {
        TransportBus bus = new TransportBus();
        Instance writer = new Instance("store-a", "guilds", bus);
        Instance reader = new Instance("store-b", "guilds", bus);

        UUID id = UUID.randomUUID();
        reader.cache.saveAndCache(new Guild(id, "mine")).join();
        assertTrue(reader.cache.peek(id).isPresent());

        try (CacheSync w = writer.start(); CacheSync r = reader.start()) {
            writer.cache.saveAndCache(new Guild(id, "theirs")).join();

            assertTrue(reader.cache.peek(id).isPresent(),
                "the two managers share a channel and a collection name, but not a database");
        }
        writer.close();
        reader.close();
    }

    @Test
    void a_write_in_a_different_kind_of_backend_does_not_invalidate_this_one() {
        TransportBus bus = new TransportBus();
        Instance sql = new Instance("sql:jdbc:mariadb://db.example.com:3306/mc", "guilds", bus);
        Instance mongo = new Instance("mongo:mongodb://mongo.example.com:27017/mc", "guilds", bus);

        UUID id = UUID.randomUUID();
        mongo.cache.saveAndCache(new Guild(id, "mongo")).join();

        try (CacheSync a = sql.start(); CacheSync b = mongo.start()) {
            sql.cache.saveAndCache(new Guild(id, "sql")).join();

            assertTrue(mongo.cache.peek(id).isPresent(),
                "the same collection name in two kinds of backend is two different collections");
        }
        sql.close();
        mongo.close();
    }

    @Test
    void a_delete_in_another_store_does_not_evict_this_cache() {
        TransportBus bus = new TransportBus();
        Instance writer = new Instance("store-a", "guilds", bus);
        Instance reader = new Instance("store-b", "guilds", bus);

        UUID id = UUID.randomUUID();
        reader.cache.saveAndCache(new Guild(id, "mine")).join();

        try (CacheSync w = writer.start(); CacheSync r = reader.start()) {
            writer.cache.saveAndCache(new Guild(id, "theirs")).join();
            writer.cache.deleteAndEvict(id).join();

            assertTrue(reader.cache.peek(id).isPresent(),
                "an eviction driven by another database would destroy a live cell for good");
        }
        writer.close();
        reader.close();
    }

    @Test
    void a_delete_in_the_same_store_still_evicts() {
        TransportBus bus = new TransportBus();
        Instance writer = new Instance("shared-store", "guilds", bus);
        Instance reader = new Instance("shared-store", "guilds", bus);

        UUID id = UUID.randomUUID();
        reader.cache.saveAndCache(new Guild(id, "v1")).join();

        try (CacheSync w = writer.start(); CacheSync r = reader.start()) {
            writer.cache.saveAndCache(new Guild(id, "v1")).join();
            writer.cache.deleteAndEvict(id).join();

            assertFalse(reader.cache.peek(id).isPresent(), "the row really is gone from the shared store");
        }
        writer.close();
        reader.close();
    }

    @Test
    void start_accepts_the_same_collection_name_on_different_backends() {
        InMemoryStorage storeA = Storages.createInMemory(new InMemoryConfig("store-a"));
        InMemoryStorage storeB = Storages.createInMemory(new InMemoryConfig("store-b"));
        storeA.init().join();
        storeB.init().join();
        RefRegistry regA = new RefRegistry();
        RefRegistry regB = new RefRegistry();
        CachingManager<UUID, Guild> a = new CachingManager<>(guildDescriptor(regA, "guilds"), storeA, CachePolicy.always(), regA);
        CachingManager<UUID, Guild> b = new CachingManager<>(guildDescriptor(regB, "guilds"), storeB, CachePolicy.always(), regB);

        FakeTransport transport = new FakeTransport();
        // Same collection name, two different databases: an event names the store it came from, so
        // neither manager can be routed the other's signal - there is nothing ambiguous to reject.
        try (CacheSync sync = CacheSync.auto().via(transport).bind(a).bind(b).start()) {
            UUID id = UUID.randomUUID();
            a.saveAndCache(new Guild(id, "a")).join();
            b.saveAndCache(new Guild(id, "b")).join();

            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, "other", "store-a"));

            assertFalse(a.peek(id).isPresent(), "the signal names store-a, so manager A is invalidated");
            assertTrue(b.peek(id).isPresent(), "manager B reads another database and is untouched");
        }

        storeA.close().join();
        storeB.close().join();
    }

    @Test
    void an_event_naming_no_store_reaches_every_manager_of_that_collection() {
        InMemoryStorage storeA = Storages.createInMemory(new InMemoryConfig("store-a"));
        InMemoryStorage storeB = Storages.createInMemory(new InMemoryConfig("store-b"));
        storeA.init().join();
        storeB.init().join();
        RefRegistry regA = new RefRegistry();
        RefRegistry regB = new RefRegistry();
        CachingManager<UUID, Guild> a = new CachingManager<>(guildDescriptor(regA, "guilds"), storeA, CachePolicy.always(), regA);
        CachingManager<UUID, Guild> b = new CachingManager<>(guildDescriptor(regB, "guilds"), storeB, CachePolicy.always(), regB);

        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = CacheSync.auto().via(transport).bind(a).bind(b).start()) {
            UUID id = UUID.randomUUID();
            a.saveAndCache(new Guild(id, "a")).join();
            b.saveAndCache(new Guild(id, "b")).join();

            // The five-argument form is what every producer written before backend identities emits.
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 1, "other"));

            assertFalse(a.peek(id).isPresent(), "an unattributed event must not be dropped");
            assertFalse(b.peek(id).isPresent(), "an unattributed event applies to every manager of the collection");
        }

        storeA.close().join();
        storeB.close().join();
    }

    @Test
    void managers_sharing_one_store_scope_the_transport_to_that_single_store() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("store-a"));
        storage.init().join();
        RefRegistry guildRegistry = new RefRegistry();
        RefRegistry clanRegistry = new RefRegistry();
        CachingManager<UUID, Guild> guilds = new CachingManager<>(guildDescriptor(guildRegistry, "guilds"), storage, CachePolicy.always(), guildRegistry);
        CachingManager<UUID, Guild> clans = new CachingManager<>(guildDescriptor(clanRegistry, "clans"), storage, CachePolicy.always(), clanRegistry);

        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = CacheSync.auto().via(transport).bind(guilds).bind(clans).start()) {
            // Two collections, one database: the transport only ever needs signals from that one store.
            assertEquals(Collections.singleton("store-a"), transport.scopedBackendIds);
        }
        storage.close().join();
    }

    @Test
    void managers_on_different_stores_scope_the_transport_to_both_of_them() {
        InMemoryStorage storeA = Storages.createInMemory(new InMemoryConfig("store-a"));
        InMemoryStorage storeB = Storages.createInMemory(new InMemoryConfig("store-b"));
        storeA.init().join();
        storeB.init().join();
        RefRegistry regA = new RefRegistry();
        RefRegistry regB = new RefRegistry();
        CachingManager<UUID, Guild> a = new CachingManager<>(guildDescriptor(regA, "guilds"), storeA, CachePolicy.always(), regA);
        CachingManager<UUID, Guild> b = new CachingManager<>(guildDescriptor(regB, "clans"), storeB, CachePolicy.always(), regB);

        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = CacheSync.auto().via(transport).bind(a).bind(b).start()) {
            assertEquals(new HashSet<>(Arrays.asList("store-a", "store-b")), transport.scopedBackendIds);
        }
        storeA.close().join();
        storeB.close().join();
    }

    @Test
    void a_published_signal_carries_the_store_it_happened_in() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("store-a"));
        storage.init().join();
        RefRegistry registry = new RefRegistry();
        CachingManager<UUID, Guild> cache = new CachingManager<>(guildDescriptor(registry), storage, CachePolicy.always(), registry);
        FakeTransport transport = new FakeTransport();

        try (CacheSync sync = CacheSync.attach(storage).via(transport).bind(cache).start()) {
            cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();

            assertEquals("store-a", transport.published.get(0).backendId());
        }
        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Participation tri-state: which stores publish, and that none of it touches receiving
    // ------------------------------------------------------------------

    /** Builds a manager over the given in-memory config, wires it through a fake transport. */
    private CacheSync bindOver(InMemoryStorage storage, FakeTransport transport,
                               CachingManager<UUID, Guild> cache) {
        return CacheSync.attach(storage).via(transport).bind(cache).start();
    }

    private CachingManager<UUID, Guild> guildManager(InMemoryStorage storage, String collection) {
        RefRegistry registry = new RefRegistry();
        return new CachingManager<>(guildDescriptor(registry, collection), storage, CachePolicy.always(), registry);
    }

    @Test
    void recommended_default_does_not_publish_on_a_machine_local_backend() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig());   // machine-local, RECOMMENDED
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = bindOver(storage, transport, cache)) {
            cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();
            assertTrue(transport.published.isEmpty(),
                    "a machine-local store under RECOMMENDED must not publish - no peer could match it");
        }
        storage.close().join();
    }

    @Test
    void recommended_default_publishes_on_a_shareable_backend() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("shared-x"));   // shareable
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = bindOver(storage, transport, cache)) {
            cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();
            assertEquals(1, transport.published.size(),
                    "a shareable store keeps publishing under the default - today's behaviour, preserved");
        }
        storage.close().join();
    }

    @Test
    void always_with_a_shared_identity_publishes_and_does_not_throw() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("shared-x", SyncParticipation.ALWAYS));
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();
        assertDoesNotThrow(() -> {
            try (CacheSync sync = bindOver(storage, transport, cache)) {
                cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();
                assertEquals(1, transport.published.size(), "ALWAYS on a shareable store publishes");
            }
        });
        storage.close().join();
    }

    @Test
    void always_on_a_machine_local_backend_without_shared_identity_fails_fast_at_bind() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig(null, SyncParticipation.ALWAYS));
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();

        IllegalStateException fatal = assertThrows(IllegalStateException.class, () ->
                CacheSync.attach(storage).via(transport).bind(cache).start());
        assertTrue(fatal.getMessage().contains("guilds"), "the message names the collection");
        assertTrue(fatal.getMessage().contains("sharedIdentity"), "the message points at the fix");
        assertTrue(transport.published.isEmpty(), "nothing was published before the failure");
        storage.close().join();
    }

    @Test
    void never_does_not_publish_even_on_a_shareable_backend() {
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("shared-x", SyncParticipation.NEVER));
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();
        try (CacheSync sync = bindOver(storage, transport, cache)) {
            cache.saveAndCache(new Guild(UUID.randomUUID(), "v1")).join();
            assertTrue(transport.published.isEmpty(), "NEVER must not publish, shareable or not");
        }
        storage.close().join();
    }

    @Test
    void a_non_publishing_manager_still_receives_and_invalidates() {
        // NEVER on a shareable store: it must not publish, but it MUST still receive - suppressing
        // publish is not suppressing subscribe.
        InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig("shared-x", SyncParticipation.NEVER));
        storage.init().join();
        CachingManager<UUID, Guild> cache = guildManager(storage, "guilds");
        FakeTransport transport = new FakeTransport();
        UUID id = UUID.randomUUID();
        try (CacheSync sync = bindOver(storage, transport, cache)) {
            cache.saveAndCache(new Guild(id, "cached")).join();
            assertTrue(cache.peek(id).isPresent());
            assertTrue(transport.published.isEmpty(), "NEVER does not publish its own write");

            // A foreign instance publishes for the same store+collection: this manager invalidates.
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.SAVE, 2, "other-instance", "shared-x"));
            assertFalse(cache.peek(id).isPresent(), "a non-publishing manager still receives and invalidates");

            // And a foreign delete evicts.
            cache.saveAndCache(new Guild(id, "again")).join();
            assertTrue(cache.peek(id).isPresent());
            transport.deliver(new ChangeEvent("guilds", id.toString(), ChangeOp.DELETE, 3, "other-instance", "shared-x"));
            assertFalse(cache.peek(id).isPresent(), "a non-publishing manager still receives and evicts");
        }
        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Test doubles
    // ------------------------------------------------------------------

    /**
     * One application instance: its own store, its own cache and its own transport endpoint on a
     * shared bus - the shape of the problem, where several servers share a pub/sub channel without
     * necessarily sharing a database.
     */
    private final class Instance {
        private final InMemoryStorage storage;
        private final CachingManager<UUID, Guild> cache;
        private final CacheSyncTransport endpoint;

        Instance(String backendIdentity, String collection, TransportBus bus) {
            this.storage = Storages.createInMemory(new InMemoryConfig(backendIdentity));
            this.storage.init().join();
            RefRegistry registry = new RefRegistry();
            this.cache = new CachingManager<>(guildDescriptor(registry, collection), storage,
                    CachePolicy.always(), registry);
            this.endpoint = bus.endpoint();
        }

        CacheSync start() {
            return CacheSync.attach(storage).via(endpoint).bind(cache).start();
        }

        void close() {
            storage.close().join();
        }
    }

    /** A pub/sub channel shared by several transport endpoints: what one publishes, all receive. */
    private static final class TransportBus {
        private final List<ChangeFeedSupport> endpoints = new ArrayList<>();
        private final AtomicInteger nextOrigin = new AtomicInteger();

        CacheSyncTransport endpoint() {
            ChangeFeedSupport feed = new ChangeFeedSupport();
            endpoints.add(feed);
            String originId = "bus-endpoint-" + nextOrigin.incrementAndGet();
            return new CacheSyncTransport() {
                @Override public String originId() { return originId; }
                @Override public void publish(ChangeEvent event) {
                    for (ChangeFeedSupport other : endpoints) {
                        other.emit(event);
                    }
                }
                @Override public ChangeSubscription subscribe(ChangeListener listener) { return feed.subscribe(listener); }
                @Override public void onConnectionStateChanged(Consumer<Boolean> listener) { }
                @Override public void close() { feed.closeAll(); }
            };
        }
    }

    /**
     * A {@link ChangeFeedStorage} that delegates real storage to an inner {@link InMemoryStorage}
     * but lets a test {@link #push(ChangeEvent) push} arbitrary events with any origin/collection.
     */
    private static final class FakeFeedStorage implements ChangeFeedStorage {
        private final InMemoryStorage inner = Storages.createInMemory();
        private final ChangeFeedSupport feed = new ChangeFeedSupport();

        void push(ChangeEvent event) { feed.emit(event); }

        @Override public String originId() { return "fake-origin"; }
        @Override public ChangeSubscription subscribe(ChangeListener listener) { return feed.subscribe(listener); }

        @Override public CompletableFuture<Void> init() { return inner.init(); }
        @Override public CompletableFuture<Void> close() { feed.closeAll(); return inner.close(); }
        @Override public CompletableFuture<HealthStatus> health() { return inner.health(); }
        @Override public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> d) { return inner.repository(d); }
        @Override public StorageLogConfig getStorageLogConfig() { return inner.getStorageLogConfig(); }
        @Override public Storage setStorageLogConfig(StorageLogConfig config) { inner.setStorageLogConfig(config); return this; }
    }

    /**
     * A {@link CacheSyncTransport} that records published signals and lets a test {@link #deliver}
     * synthetic events with any origin/collection - the transport analogue of {@code FakeFeedStorage}.
     */
    private static final class FakeTransport implements CacheSyncTransport {
        private final List<ChangeEvent> published = new ArrayList<>();
        private final ChangeFeedSupport feed = new ChangeFeedSupport();
        private volatile Consumer<Boolean> connectionListener;
        private volatile Set<String> scopedBackendIds;   // what the caller asked to be scoped to

        void deliver(ChangeEvent event) { feed.emit(event); }
        void fireConnectivity(boolean connected) {
            Consumer<Boolean> l = connectionListener;
            if (l != null) {
                l.accept(connected);
            }
        }

        @Override public String originId() { return "fake-transport"; }
        @Override public void publish(ChangeEvent event) { published.add(event); }
        @Override public ChangeSubscription subscribe(ChangeListener listener) { return feed.subscribe(listener); }
        @Override public ChangeSubscription subscribe(Set<String> backendIds, ChangeListener listener) {
            this.scopedBackendIds = backendIds;
            return subscribe(listener);
        }
        @Override public void onConnectionStateChanged(Consumer<Boolean> listener) { this.connectionListener = listener; }
        @Override public void close() { feed.closeAll(); }
    }

    /** A storage that does not implement {@link ChangeFeedStorage}. */
    private static final class NoFeedStorage implements Storage {
        private final InMemoryStorage inner = Storages.createInMemory();
        @Override public CompletableFuture<Void> init() { return inner.init(); }
        @Override public CompletableFuture<Void> close() { return inner.close(); }
        @Override public CompletableFuture<HealthStatus> health() { return inner.health(); }
        @Override public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> d) { return inner.repository(d); }
        @Override public StorageLogConfig getStorageLogConfig() { return inner.getStorageLogConfig(); }
        @Override public Storage setStorageLogConfig(StorageLogConfig config) { return this; }
    }
}
