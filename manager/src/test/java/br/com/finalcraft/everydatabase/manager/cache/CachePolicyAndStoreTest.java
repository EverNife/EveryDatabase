package br.com.finalcraft.everydatabase.manager.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Freshness policies and the LRU store - the freshness/capacity split in action. */
class CachePolicyAndStoreTest {

    // ---- CachePolicy (freshness) -------------------------------------------------

    @Test
    void always_is_fresh_until_marked_stale() {
        CacheEntry<String> entry = new CacheEntry<>("v");
        CachePolicy policy = CachePolicy.always();
        assertTrue(policy.isFresh(entry));
        entry.markStale();
        assertFalse(policy.isFresh(entry));
    }

    @Test
    void ttl_expires_with_age() {
        CachePolicy policy = CachePolicy.ttl(Duration.ofSeconds(30));
        assertTrue(policy.isFresh(new CacheEntry<>("v", Instant.now())));
        assertFalse(policy.isFresh(new CacheEntry<>("v", Instant.now().minusSeconds(60))));
    }

    @Test
    void ttl_respects_manual_invalidation_even_when_young() {
        CacheEntry<String> entry = new CacheEntry<>("v", Instant.now());
        entry.markStale();
        assertFalse(CachePolicy.ttl(Duration.ofSeconds(30)).isFresh(entry));
    }

    @Test
    void noCache_is_never_fresh() {
        assertFalse(CachePolicy.noCache().isFresh(new CacheEntry<>("v")));
    }

    @Test
    void fromAdminConfig_builds_the_expected_policies() {
        assertTrue(CachePolicy.fromAdminConfig("ALWAYS", null).isFresh(new CacheEntry<>("v")));
        assertFalse(CachePolicy.fromAdminConfig("NOCACHE", null).isFresh(new CacheEntry<>("v")));
        CachePolicy ttl = CachePolicy.fromAdminConfig("TTL", 5);
        assertTrue(ttl.isFresh(new CacheEntry<>("v", Instant.now())));
        assertFalse(ttl.isFresh(new CacheEntry<>("v", Instant.now().minusSeconds(10))));
    }

    // ---- LruCacheStore (capacity) ------------------------------------------------

    @Test
    void lru_evicts_the_eldest_past_the_bound() {
        LruCacheStore<String, String> store = new LruCacheStore<>(2);
        store.put("a", new CacheEntry<>("a"));
        store.put("b", new CacheEntry<>("b"));
        store.put("c", new CacheEntry<>("c")); // overflows -> evicts "a"

        assertEquals(2, store.size());
        assertNull(store.get("a"));
        assertNotNull(store.get("b"));
        assertNotNull(store.get("c"));
    }

    @Test
    void lru_is_access_ordered() {
        LruCacheStore<String, String> store = new LruCacheStore<>(2);
        store.put("a", new CacheEntry<>("a"));
        store.put("b", new CacheEntry<>("b"));
        store.get("a");                          // "a" is now most-recently-used
        store.put("c", new CacheEntry<>("c"));   // evicts the LRU, which is "b"

        assertNotNull(store.get("a"));
        assertNull(store.get("b"));
        assertNotNull(store.get("c"));
    }

    @Test
    void unbounded_store_keeps_everything() {
        LruCacheStore<Integer, Integer> store = new LruCacheStore<>(0);
        for (int i = 0; i < 1000; i++) {
            store.put(i, new CacheEntry<>(i));
        }
        assertEquals(1000, store.size());
    }

    // ---- atomic compound operations (identity-map stability) ---------------------

    @Test
    void installIfAbsent_keeps_the_first_instance() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        CacheEntry<String> first = new CacheEntry<>("first");
        CacheEntry<String> second = new CacheEntry<>("second");

        assertSame(first, store.installIfAbsent("k", first));
        assertSame(first, store.installIfAbsent("k", second)); // existing wins
        assertSame(first, store.get("k"));
    }

    @Test
    void markStale_marks_the_current_entry() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        CacheEntry<String> entry = new CacheEntry<>("v");
        store.put("k", entry);

        store.markStale("k");

        assertTrue(entry.isStale());
    }

    @Test
    void publish_is_guarded_by_a_monotonic_stamp() {
        CacheEntry<String> cell = new CacheEntry<>("v1");        // stamp 0
        assertTrue(cell.publish("v2", 2L));                      // newer stamp applies
        assertEquals("v2", cell.getValue());

        assertFalse(cell.publish("v1", 1L));                     // older stamp is rejected...
        assertEquals("v2", cell.getValue(), "an older reload must not regress a newer write");

        assertTrue(cell.publish("v3", 2L));                      // ...equal stamp still applies (>=)
        assertEquals("v3", cell.getValue());
    }

    // ---- tombstone hardening (delete vs a concurrent in-flight reload) -----------

    @Test
    void installColdMiss_keeps_the_first_live_instance() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        CacheEntry<String> first = store.installColdMiss("k", "first", 1L);
        CacheEntry<String> second = store.installColdMiss("k", "second", 2L);
        assertSame(first, second, "concurrent cold misses converge on the first instance");
        assertEquals("first", second.getValue());
    }

    @Test
    void installColdMiss_respects_a_newer_tombstone() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        store.tombstone("k", 10L);                                       // deleted at stamp 10
        CacheEntry<String> cell = store.installColdMiss("k", "v", 5L);   // reload issued earlier (5 < 10)
        assertTrue(cell.isDeleted(), "an older reload must not resurrect a newer delete");
    }

    @Test
    void installColdMiss_resurrects_an_older_tombstone() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        store.tombstone("k", 5L);
        CacheEntry<String> cell = store.installColdMiss("k", "v", 10L);  // reload issued later (10 > 5)
        assertFalse(cell.isDeleted());
        assertEquals("v", cell.getValue());
    }

    @Test
    void tombstone_is_guarded_by_stamp() {
        LruCacheStore<String, String> store = new LruCacheStore<>(0);
        CacheEntry<String> cell = new CacheEntry<>("v");
        store.put("k", cell);
        cell.publish("v2", 5L);                  // a write at stamp 5

        store.tombstone("k", 3L);                // older delete -> rejected
        assertFalse(cell.isDeleted(), "an older delete must not tombstone a newer write");

        store.tombstone("k", 6L);                // newer delete -> applies
        assertTrue(cell.isDeleted());
    }
}
