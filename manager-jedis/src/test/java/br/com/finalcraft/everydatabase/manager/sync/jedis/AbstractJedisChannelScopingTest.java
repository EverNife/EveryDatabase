package br.com.finalcraft.everydatabase.manager.sync.jedis;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.changefeed.ChangePayload;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.sync.CacheSync;
import br.com.finalcraft.everydatabase.manager.testdata.Quest;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the per-store channel scoping <b>at the level of the server</b>, not just in memory: which
 * channel a signal is published on, and which channels an instance actually holds, read back from the
 * Jedis server itself via {@code PUBSUB CHANNELS}/{@code PUBSUB NUMSUB}.
 *
 * <p>This is the counterpart of {@link AbstractJedisCacheSyncTest}, which proves that two instances of
 * the <b>same</b> store still invalidate each other. Here the point is the opposite: two instances of
 * <b>different</b> stores never even receive each other's traffic, because the server routes it apart.
 *
 * <p>The data backend is in-memory with an explicit store identity, so a channel name is short and
 * predictable; the only external dependency is the Jedis server. Concrete subclasses point at a
 * specific server (Valkey / Redis) via {@link #port()}/{@link #serverName()} and self-skip when it is
 * unreachable.
 */
public abstract class AbstractJedisChannelScopingTest {

    /** The Jedis server port this subclass connects to. */
    protected abstract int port();

    /** Human name of the server (for skip messages), e.g. "Valkey"/"Redis". */
    protected abstract String serverName();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String channelBase;
    private String otherBase;      // a second application's prefix; deliberately not under channelBase
    private String collection;
    private final List<AutoCloseable> openResources = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(AbstractJedisCacheSyncTest.reachable(port()),
                serverName() + " not reachable on localhost:" + port() + " - run 'docker compose up -d "
                        + serverName().toLowerCase() + "'. Skipping the channel-scoping contract.");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        channelBase = "everydatabase:scoping:" + suffix;         // unique per test: no cross-test bleed
        otherBase = "everydatabase:otherapp:" + suffix;          // no prefix relation to channelBase
        collection = "quests_" + suffix;
    }

    @AfterEach
    void tearDown() {
        for (int i = openResources.size() - 1; i >= 0; i--) {
            try {
                openResources.get(i).close();
            } catch (Exception ignored) {
                // teardown is best-effort
            }
        }
        openResources.clear();
    }

    // ------------------------------------------------------------------

    @Test
    void a_signal_is_published_on_the_channel_of_the_store_it_came_from() {
        String storeA = "store-a";
        String storeB = "store-b";
        RawSubscriber watcher = watch(channelBase, channelBase + ":" + storeA, channelBase + ":" + storeB);

        JedisCacheSyncTransport transport = transport(channelBase);
        transport.publish(new ChangeEvent(collection, "k1", ChangeOp.SAVE, 1L, "origin", storeA));

        awaitUntil(() -> !watcher.channelsSeen().isEmpty(), Duration.ofSeconds(10));
        assertEquals(Collections.singletonList(channelBase + ":" + storeA), watcher.channelsSeen(),
                "the signal reached the channel of its own store, and no other");
    }

    @Test
    void a_signal_naming_no_store_is_published_on_the_bare_prefix() {
        RawSubscriber watcher = watch(channelBase, channelBase + ":store-a");

        JedisCacheSyncTransport transport = transport(channelBase);
        transport.publish(ChangeEvent.save(collection, "k1"));   // the form a pre-identity producer emits

        awaitUntil(() -> !watcher.channelsSeen().isEmpty(), Duration.ofSeconds(10));
        assertEquals(Collections.singletonList(channelBase), watcher.channelsSeen(),
                "a signal that names no store falls back to the bare prefix");
    }

    @Test
    void an_instance_holds_the_channels_of_its_own_stores_and_no_others() {
        World a = world("store-a");

        awaitUntil(() -> subscribedChannels().size() >= 2, Duration.ofSeconds(10));
        assertEquals(new HashSet<>(Arrays.asList(channelBase, channelBase + ":" + a.identity)),
                subscribedChannels(),
                "exactly the bare prefix plus one channel per store this instance reads");
        assertEquals(0L, subscriberCount(channelBase + ":store-b"),
                "a store nobody reads has no subscriber - that is the traffic that stops being delivered");
    }

    @Test
    void a_write_in_another_store_never_reaches_this_instance_over_the_wire() {
        World reader = world("store-a");
        awaitUntil(() -> subscribedChannels().size() >= 2, Duration.ofSeconds(10));

        // A writer on a different store, same collection name, same channel prefix.
        JedisCacheSyncTransport writerTransport = transport(channelBase);
        UUID id = UUID.randomUUID();
        reader.manager.saveAndCache(new Quest(id, "v0", 0L)).join();
        assertTrue(reader.manager.peek(id).isPresent(), "the reader cached the entity");

        assertEquals(0L, subscriberCount(channelBase + ":store-b"),
                "nobody subscribes the other store's channel, so its signals are dropped by the server");
        writerTransport.publish(new ChangeEvent(collection, id.toString(), ChangeOp.DELETE, 1L, "foreign", "store-b"));

        // Give a wrongly-routed signal every chance to arrive before declaring it absent.
        sleep(Duration.ofSeconds(2));
        assertTrue(reader.manager.peek(id).isPresent(), "a foreign store's delete never touched this cache");
        // The reader's own writes echo back on its own channel and are skipped by origin, so only a
        // signal that genuinely crossed from the other store could ever be applied.
        assertEquals(0L, reader.sync.stats().signalsApplied(), "no foreign signal was applied");
    }

    @Test
    void two_applications_with_different_prefixes_never_cross_invalidate() {
        World app = world("store-a");                                  // on channelBase
        World otherApp = world(otherBase, "store-a", collection);      // deliberately the same store and collection

        awaitUntil(() -> subscribedChannels().size() >= 2, Duration.ofSeconds(10));

        UUID id = UUID.randomUUID();
        app.manager.saveAndCache(new Quest(id, "v0", 0L)).join();
        assertTrue(app.manager.peek(id).isPresent(), "the first application cached the entity");

        otherApp.manager.saveAndCache(new Quest(id, "v1", 0L)).join();   // publishes on otherBase:store-a

        sleep(Duration.ofSeconds(2));
        assertTrue(app.manager.peek(id).isPresent(),
                "the prefix isolates the applications even when store and collection coincide");
        // A leaked signal from the other application carries a foreign origin, so it would have been
        // applied rather than skipped - the counter staying at zero is what rules that out.
        assertEquals(0L, app.sync.stats().signalsApplied(), "no signal crossed the prefix boundary");
    }

    @Test
    void a_signal_naming_no_store_still_reaches_a_scoped_instance() {
        World reader = world("store-a");
        awaitUntil(() -> subscribedChannels().contains(channelBase), Duration.ofSeconds(10));

        UUID id = UUID.randomUUID();
        reader.manager.saveAndCache(new Quest(id, "present", 0L)).join();
        assertTrue(reader.manager.peek(id).isPresent(), "the reader cached the entity");

        // What a producer built before store identities existed puts on the wire.
        publishRaw(channelBase, ChangePayload.encode(MAPPER,
                new ChangeEvent(collection, id.toString(), ChangeOp.DELETE, 1L, "foreign")));

        awaitUntil(() -> !reader.manager.peek(id).isPresent(), Duration.ofSeconds(15));
        assertFalse(reader.manager.peek(id).isPresent(),
                "an unattributed signal still arrives, because every instance also holds the bare prefix");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** A storage + manager + transport + sync quartet, torn down together. */
    private final class World {
        private final String identity;
        private final CachingManager<UUID, Quest> manager;
        private final CacheSync sync;

        World(String base, String identity, String collection) {
            this.identity = identity;
            InMemoryStorage storage = Storages.createInMemory(new InMemoryConfig(identity));
            storage.init().join();
            openResources.add(() -> storage.close().join());
            RefRegistry registry = new RefRegistry();
            this.manager = registry.manager(descriptor(registry, collection), storage, CachePolicy.always());
            JedisCacheSyncTransport transport = transport(base);
            this.sync = CacheSync.attach(storage).via(transport).bind(manager).start();
            openResources.add(sync);
        }
    }

    private World world(String identity) {
        return new World(channelBase, identity, collection);
    }

    private World world(String base, String identity, String collection) {
        return new World(base, identity, collection);
    }

    private JedisCacheSyncTransport transport(String base) {
        JedisCacheSyncTransport transport =
                JedisCacheSyncTransport.connect(new JedisCacheSyncConfig("localhost", port()).withChannel(base));
        openResources.add(transport);
        return transport;
    }

    private static EntityDescriptor<UUID, Quest> descriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Quest.class)
                .collection(collection)
                .keyExtractor(Quest::getId)
                .codec(registry.codec(Quest.class))
                .build();
    }

    /** The channels under this test's prefix that currently have at least one subscriber. */
    private Set<String> subscribedChannels() {
        try (Jedis jedis = new Jedis("localhost", port())) {
            return new HashSet<>(jedis.pubsubChannels(channelBase + "*"));
        }
    }

    /** How many subscribers the server sees on {@code channel}. */
    private long subscriberCount(String channel) {
        try (Jedis jedis = new Jedis("localhost", port())) {
            Map<String, Long> counts = jedis.pubsubNumSub(channel);
            Long count = counts.get(channel);
            return count == null ? 0L : count;
        }
    }

    private void publishRaw(String channel, String payload) {
        try (Jedis jedis = new Jedis("localhost", port())) {
            jedis.publish(channel, payload);
        }
    }

    /** A bare Jedis subscriber recording which channel each message arrived on. */
    private RawSubscriber watch(String... channels) {
        RawSubscriber subscriber = new RawSubscriber(channels);
        openResources.add(subscriber);
        return subscriber;
    }

    private final class RawSubscriber implements AutoCloseable {
        private final Jedis jedis = new Jedis("localhost", port());
        private final List<String> channelsSeen = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch ready;
        private final JedisPubSub pubSub;

        RawSubscriber(String... channels) {
            this.ready = new CountDownLatch(channels.length);
            this.pubSub = new JedisPubSub() {
                @Override public void onSubscribe(String channel, int subscribedChannels) { ready.countDown(); }
                @Override public void onMessage(String channel, String message) { channelsSeen.add(channel); }
            };
            Thread thread = new Thread(() -> {
                try {
                    jedis.subscribe(pubSub, channels);
                } catch (Exception ignored) {
                    // the connection is torn down by close()
                }
            }, "jedis-scoping-watcher");
            thread.setDaemon(true);
            thread.start();
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "the watcher never subscribed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        List<String> channelsSeen() {
            return new ArrayList<>(channelsSeen);
        }

        @Override
        public void close() {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                jedis.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(150));
        }
        fail("condition not met within " + timeout);
    }
}
