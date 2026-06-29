package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
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
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        InMemoryStorage storage = Storages.createInMemory();
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
    //  Test doubles
    // ------------------------------------------------------------------

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
