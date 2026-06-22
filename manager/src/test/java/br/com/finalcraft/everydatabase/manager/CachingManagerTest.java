package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.manager.testdata.Inventory;
import br.com.finalcraft.everydatabase.manager.testdata.Item;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The cache-backed manager: resolve/peek, write-through, batching, invalidation, per-ref policy. */
class CachingManagerTest {

    private RefRegistry registry;
    private InMemoryStorage storage;
    private EntityDescriptor<UUID, Guild> guildDescriptor;

    @BeforeEach
    void setUp() {
        registry = new RefRegistry();
        storage = Storages.createInMemory();
        storage.init().join();
        guildDescriptor = EntityDescriptor.builder(UUID.class, Guild.class)
                .collection("guilds")
                .keyExtractor(g -> g.getId())
                .codec(registry.codec(Guild.class))   // Guild carries Ref fields (leader/founder/...)
                .build();
    }

    private EntityDescriptor<UUID, Player> playerDescriptor() {
        return EntityDescriptor.builder(UUID.class, Player.class)
                .collection("players")
                .keyExtractor(Player::getUuid)
                .codec(registry.codec(Player.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    private CachingManager<UUID, Guild> manager(CacheOptions options) {
        return new CachingManager<>(guildDescriptor, storage, options, registry);
    }

    private CachingManager<UUID, Guild> manager(CachePolicy policy) {
        return new CachingManager<>(guildDescriptor, storage, policy, registry);
    }

    /** Seeds the backend directly (bypassing the manager's cache). */
    private UUID seedGuild(CachingManager<UUID, Guild> mgr, String name) {
        UUID id = UUID.randomUUID();
        mgr.repository().save(new Guild(id, name)).join();
        return id;
    }

    @Test
    void a_player_with_a_nested_inventory_round_trips_through_the_cache() {
        CachingManager<UUID, Player> players =
                new CachingManager<>(playerDescriptor(), storage, CachePolicy.always(), registry);
        UUID id = UUID.randomUUID();
        Item sword = new Item("DIAMOND_SWORD", 1,
                Arrays.asList("Sharp", "Legendary"), Map.of("sharpness", 5, "unbreaking", 3));
        Player player = new Player(id, "Steve", 42, 1000L,
                new Inventory("Hotbar", 9, Arrays.asList(sword, new Item("APPLE", 16))), null);

        players.saveAndCache(player).join();
        players.evict(id);   // drop the in-memory instance so the next read decodes from the backend

        Player back = players.resolve(id).join().orElseThrow(AssertionError::new);
        assertEquals(42, back.getLevel());
        assertEquals(1000L, back.getCoins());
        assertEquals("Hotbar", back.getInventory().getTitle());
        assertEquals(9, back.getInventory().getMaxSize());
        assertEquals(2, back.getInventory().getItems().size());
        Item firstItem = back.getInventory().getItems().get(0);
        assertEquals("DIAMOND_SWORD", firstItem.getMaterial());
        assertEquals(Arrays.asList("Sharp", "Legendary"), firstItem.getLore());
        assertEquals(5, firstItem.getEnchants().get("sharpness"));
    }

    @Test
    void resolve_loads_from_backend_then_serves_from_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Knights");

        assertFalse(mgr.peek(id).isPresent(), "not cached before first resolve");

        Optional<Guild> resolved = mgr.resolve(id).join();
        assertTrue(resolved.isPresent());
        assertEquals("Knights", resolved.get().getName());
        assertEquals(1, mgr.cachedSize());

        // The identity map: peek returns the same cached instance the resolve produced.
        assertSame(resolved.get(), mgr.peek(id).get());
    }

    @Test
    void save_is_write_through() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        Guild guild = new Guild(UUID.randomUUID(), "Wolves");

        mgr.saveAndCache(guild).join();

        // Cached without any read, and the cached instance is exactly what we saved.
        assertSame(guild, mgr.peek(guild.getId()).get());
        // ...and it is actually in the backend.
        assertTrue(mgr.repository().find(guild.getId()).join().isPresent());
    }

    @Test
    void getAll_serves_hits_from_cache_and_batches_the_misses() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");
        UUID c = seedGuild(mgr, "C");
        mgr.resolve(a).join(); // pre-cache one

        List<Guild> all = mgr.getAll(Arrays.asList(a, b, c)).join();

        Set<UUID> ids = all.stream().map(g -> g.getId()).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(a, b, c)), ids);
        assertEquals(3, mgr.cachedSize());
    }

    @Test
    void preloadAll_mirrors_the_whole_collection() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");

        mgr.preloadAll().join();

        assertEquals(2, mgr.cachedSize());
        assertTrue(mgr.peek(a).isPresent());
        assertTrue(mgr.peek(b).isPresent());
    }

    @Test
    void invalidate_forces_a_reload_on_next_read() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Old");
        mgr.resolve(id).join();

        mgr.invalidate(id);
        assertFalse(mgr.peek(id).isPresent(), "stale entry is not served");

        // A fresh resolve reloads from the backend.
        assertTrue(mgr.resolve(id).join().isPresent());
        assertTrue(mgr.peek(id).isPresent());
    }

    @Test
    void evict_removes_the_entry() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Gone");
        mgr.resolve(id).join();
        assertEquals(1, mgr.cachedSize());

        mgr.evict(id);
        assertEquals(0, mgr.cachedSize());
        assertFalse(mgr.peek(id).isPresent());
    }

    @Test
    void deleteAndEvict_removes_from_backend_and_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Doomed");
        mgr.resolve(id).join();                        // now cached
        assertEquals(1, mgr.cachedSize());

        boolean existed = mgr.deleteAndEvict(id).join();

        assertTrue(existed);
        assertEquals(0, mgr.cachedSize(), "evicted from cache");
        assertFalse(mgr.peek(id).isPresent());
        assertFalse(mgr.repository().find(id).join().isPresent(), "deleted from the backend");
    }

    @Test
    void deleteAndEvict_is_safe_when_the_key_is_not_cached() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Doomed");            // in the backend, never cached
        assertEquals(0, mgr.cachedSize());

        boolean existed = mgr.deleteAndEvict(id).join();

        assertTrue(existed);
        assertFalse(mgr.repository().find(id).join().isPresent());
        assertEquals(0, mgr.cachedSize());
    }

    @Test
    void deleteAndEvict_makes_a_memoized_ref_resolve_empty() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Doomed");
        Ref<UUID, Guild> ref = registry.ref(id, Guild.class);
        assertEquals("Doomed", ref.resolve().join().get().getName());   // memoizes the cell

        mgr.deleteAndEvict(id).join();

        assertFalse(ref.resolve().join().isPresent(), "the handle re-resolves and finds it gone");
    }

    @Test
    void saveAndCache_after_deleteAndEvict_recreates_the_entity() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Old");
        mgr.resolve(id).join();

        mgr.deleteAndEvict(id).join();
        assertFalse(mgr.peek(id).isPresent());
        assertFalse(mgr.resolve(id).join().isPresent(), "gone from the backend");

        // A later save resurrects the tombstone (it carries a newer stamp than the delete).
        mgr.saveAndCache(new Guild(id, "New")).join();
        assertEquals("New", mgr.resolve(id).join().get().getName());
        assertTrue(mgr.peek(id).isPresent());
        assertEquals(1, mgr.cachedSize(), "the resurrected entity counts as live again");
    }

    @Test
    void purgeExpired_drops_entries_the_policy_no_longer_considers_fresh() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Stale");
        mgr.resolve(id).join();
        assertEquals(1, mgr.cachedSize());

        mgr.invalidate(id);                 // marks stale -> no longer fresh
        int purged = mgr.purgeExpired();

        assertEquals(1, purged);
        assertEquals(0, mgr.cachedSize(), "stale entries are released so the GC can reclaim them");
    }

    @Test
    void a_memoized_ref_sees_writes_via_in_place_cell_update() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = UUID.randomUUID();
        mgr.saveAndCache(new Guild(id, "Before")).join();

        Ref<UUID, Guild> ref = registry.ref(id, Guild.class);
        assertEquals("Before", ref.resolve().join().get().getName());   // memoizes the live cell

        // A later write updates the SAME cell in place...
        mgr.saveAndCache(new Guild(id, "After")).join();

        // ...so the memoized handle observes it without re-resolving.
        assertEquals("After", ref.peek().get().getName());
    }

    @Test
    void a_memoized_ref_re_resolves_after_its_cell_is_evicted() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "X");
        Ref<UUID, Guild> ref = registry.ref(id, Guild.class);
        assertEquals("X", ref.resolve().join().get().getName());        // memoizes the live cell

        mgr.evict(id);                                             // marks the cell evicted

        // The fast path sees the evicted cell and falls back: peek (cache-only) misses...
        assertFalse(ref.peek().isPresent());
        // ...and resolve reloads from the backend.
        assertEquals("X", ref.resolve().join().get().getName());
    }

    @Test
    void per_reference_policy_override_can_bypass_the_shared_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Hot");
        mgr.resolve(id).join(); // now cached

        // Default policy serves it...
        assertTrue(mgr.peek(id).isPresent());
        // ...but a no-cache override never serves from cache (forces a fresh load).
        assertFalse(mgr.peek(id, CachePolicy.noCache()).isPresent());
    }

    @Test
    void maxSize_bounds_the_cache() {
        CachingManager<UUID, Guild> mgr = manager(
                CacheOptions.builder().policy(CachePolicy.always()).maxSize(2).build());
        UUID a = seedGuild(mgr, "A");
        UUID b = seedGuild(mgr, "B");
        UUID c = seedGuild(mgr, "C");

        mgr.resolve(a).join();
        mgr.resolve(b).join();
        mgr.resolve(c).join(); // overflows the bound of 2

        assertEquals(2, mgr.cachedSize());
    }

    @Test
    void isOptimisticLock_unwraps_nested_causes() {
        OptimisticLockException ole = new OptimisticLockException(Guild.class, UUID.randomUUID(), 1L, 2L);
        assertTrue(CachingManager.isOptimisticLock(ole));
        assertTrue(CachingManager.isOptimisticLock(new CompletionException(ole)));
        assertFalse(CachingManager.isOptimisticLock(new RuntimeException("unrelated")));
    }

    @Test
    void noCache_override_does_not_populate_the_shared_cache() {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Hot");

        Optional<Guild> resolved = mgr.resolve(id, CachePolicy.noCache()).join();

        assertTrue(resolved.isPresent());
        assertEquals(0, mgr.cachedSize(), "a noCache resolve is a true bypass - it must not populate the cache");
        assertFalse(mgr.peek(id).isPresent());
    }

    @Test
    void concurrent_cold_misses_converge_on_one_instance() throws Exception {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "One");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Guild>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mgr.resolve(id).join().get();
                }));
            }
            start.countDown();

            Set<Guild> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Guild> f : futures) {
                distinct.add(f.get());
            }
            assertEquals(1, distinct.size(), "all concurrent resolves must converge on one instance");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ref_resolve_without_a_registered_manager_returns_a_failed_future() {
        // The ref is bound to this registry, but no manager is registered for String in it.
        Ref<UUID, String> ref = registry.ref(UUID.randomUUID(), String.class);

        CompletableFuture<Optional<String>> future = ref.resolve();

        assertTrue(future.isCompletedExceptionally(), "wiring errors must surface via the future, not a sync throw");
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void ref_resolve_when_unbound_returns_a_failed_future() {
        // An unbound ref (explicit null registry) has nothing to resolve against - it must fail fast, not guess.
        Ref<UUID, String> ref = Ref.of(UUID.randomUUID(), String.class, null);

        CompletableFuture<Optional<String>> future = ref.resolve();

        assertTrue(future.isCompletedExceptionally(), "an unbound ref cannot resolve");
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void concurrent_resolve_save_invalidate_stay_consistent() throws Exception {
        CachingManager<UUID, Guild> mgr = manager(CachePolicy.always());
        UUID id = seedGuild(mgr, "Init");
        int threads = 12;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    for (int r = 0; r < rounds; r++) {
                        switch (r % 3) {
                            case 0:
                                mgr.resolve(id).join();
                                break;
                            case 1:
                                mgr.saveAndCache(new Guild(id, "v" + tid + "_" + r)).join();
                                break;
                            default:
                                mgr.invalidate(id);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();   // surfaces any race / crash from a worker
            }
            // No deadlock; the key still resolves, and the cache holds at most this one key.
            assertTrue(mgr.resolve(id).join().isPresent());
            assertTrue(mgr.cachedSize() <= 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
