package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Backend-agnostic contract for cross-instance cache synchronization, mirroring
 * {@code AbstractStorageTest}: <b>two</b> storage instances on the <b>same</b> database (a writer and
 * a reader), wired through the single {@link CacheSync#attach(Storage) CacheSync} facade, which picks
 * the backend-native push feed (Mongo/Postgres) or the polling fallback (MySQL/MariaDB/H2/LocalFile)
 * transparently. A write/delete on the writer must invalidate/evict the reader's cache.
 *
 * <p>Every concrete backend extends this and only provides {@link #openWriter()}/{@link #openReader()}
 * (two instances on one shared DB) plus reachability ({@link #assumeAvailable()}); the suite runs on
 * all of them at once. {@code InMemory} is excluded - its data is per-instance, so there is no
 * cross-instance scenario (see {@code CacheSyncTest} for the in-process InMemory + facade routing).
 *
 * <h3>Update vs delete</h3>
 * <b>Delete propagation</b> works on every backend (a push delete event, or the version poll finding
 * the key gone). <b>Update propagation</b> needs an increasing version: push backends carry it on the
 * event, and a versioned descriptor on an <em>enforcing</em> backend (MariaDB) bumps {@code lock_version}
 * on save - but H2 and LocalFile do not enforce versioning, so polling there sees version {@code 0} and
 * catches only deletes. Those backends override {@link #supportsUpdatePropagation()} to {@code false}.
 */
public abstract class AbstractCacheSyncTest {

    /** A storage instance acting as the "writer" (one application instance). */
    protected abstract Storage openWriter();

    /** A <b>second</b> storage instance on the <b>same</b> database (another application instance). */
    protected abstract Storage openReader();

    /** Self-skip hook: a Docker-backed subclass asserts its server is reachable; embedded ones no-op. */
    protected void assumeAvailable() {
    }

    /** Poll cadence for the polling fallback (ignored by push backends). */
    protected Duration pollInterval() {
        return Duration.ofMillis(100);
    }

    /** Whether a remote <em>update</em> (not just a delete) can be observed. False for H2/LocalFile. */
    protected boolean supportsUpdatePropagation() {
        return true;
    }

    protected Storage writerStorage;
    protected Storage readerStorage;
    protected CachingManager<UUID, Quest> writer;
    protected CachingManager<UUID, Quest> reader;

    @BeforeEach
    void setUp() {
        assumeAvailable();
        String collection = "quests_" + UUID.randomUUID().toString().replace("-", "");

        writerStorage = openWriter();
        writerStorage.init().join();
        readerStorage = openReader();
        readerStorage.init().join();

        RefRegistry writerReg = new RefRegistry();
        RefRegistry readerReg = new RefRegistry();
        writer = writerReg.manager(questDescriptor(writerReg, collection), writerStorage, CachePolicy.always());
        reader = readerReg.manager(questDescriptor(readerReg, collection), readerStorage, CachePolicy.always());
    }

    @AfterEach
    void tearDown() {
        if (readerStorage != null) {
            try { readerStorage.close().join(); } catch (Exception ignored) { }
        }
        if (writerStorage != null) {
            try { writerStorage.close().join(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------

    @Test
    void a_remote_update_invalidates_the_local_cache() {
        Assumptions.assumeTrue(supportsUpdatePropagation(),
                "this backend cannot propagate updates (polling without enforced versioning) - delete-only");

        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "v0", 0L)).join();
        reader.resolve(id).join();
        assertTrue(reader.peek(id).isPresent(), "reader cached v0");

        try (CacheSync sync = openSync()) {
            // Re-write a distinct title each round: a real version bump (poll) and a fresh event
            // (push), so once the feed/poll is live the reader observes the change - no fixed sleep.
            AtomicInteger n = new AtomicInteger();
            awaitUntil(() -> {
                Quest current = writer.resolve(id).join().orElseThrow(AssertionError::new);
                current.setTitle("v-" + n.incrementAndGet());
                writer.saveAndCache(current).join();
                sync.pollOnce();                       // drives poll backends; no-op for push
                Quest seen = reader.resolve(id).join().orElse(null);
                return seen != null && seen.getTitle().startsWith("v-");
            }, Duration.ofSeconds(20));

            assertTrue(reader.resolve(id).join().orElseThrow(AssertionError::new).getTitle().startsWith("v-"),
                    "reader reloaded the remote update");
        }
    }

    @Test
    void a_remote_delete_evicts_the_local_cache() {
        UUID id = UUID.randomUUID();
        writer.saveAndCache(new Quest(id, "present", 0L)).join();
        reader.resolve(id).join();
        assertTrue(reader.peek(id).isPresent(), "reader cached the entity");

        try (CacheSync sync = openSync()) {
            establishFeedLive(sync, id);                 // for push backends only (poll has no race)

            writer.repository().delete(id).join();       // delete on the writer's backend

            // peek (cache-only, no reload) can only empty via the sync evicting it - not via a resolve
            // that would find the row gone anyway.
            awaitUntil(() -> {
                sync.pollOnce();                         // drives poll backends; no-op for push
                return !reader.peek(id).isPresent();
            }, Duration.ofSeconds(15));
            assertFalse(reader.peek(id).isPresent(), "reader evicted the deleted entity via the sync");
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    protected CacheSync openSync() {
        return CacheSync.attach(readerStorage).pollEvery(pollInterval()).bind(reader).start();
    }

    /**
     * Push feeds (change streams / LISTEN) only deliver events that occur after the subscriber is
     * live; a one-shot delete can race that startup. So for push backends we first re-write the key
     * until the reader reacts (proving the feed is live), leaving it freshly cached for the delete.
     * Poll backends have no such race - {@link CacheSync#pollOnce()} reads current state - so this is a
     * no-op there.
     */
    private void establishFeedLive(CacheSync sync, UUID id) {
        if (!(readerStorage instanceof ChangeFeedStorage)) {
            return;
        }
        AtomicInteger n = new AtomicInteger();
        awaitUntil(() -> {
            Quest current = writer.resolve(id).join().orElseThrow(AssertionError::new);
            current.setTitle("live-" + n.incrementAndGet());
            writer.saveAndCache(current).join();
            Quest seen = reader.resolve(id).join().orElse(null);
            return seen != null && seen.getTitle().startsWith("live-");
        }, Duration.ofSeconds(20));
    }

    protected static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("condition not met within " + timeout);
    }

    private static EntityDescriptor<UUID, Quest> questDescriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Quest.class)
                .collection(collection)
                .keyExtractor(Quest::getId)
                .codec(registry.codec(Quest.class))
                .build();
    }
}
