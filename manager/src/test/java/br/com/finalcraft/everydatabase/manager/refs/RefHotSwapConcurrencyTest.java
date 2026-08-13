package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Guild;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zero-window guarantee of {@link RefRegistry#replace}, as a mechanism test (not a count):
 * readers hammer {@code ref.join()} and {@code registry.resolver(type)} in a tight loop while the
 * main thread hot-swaps the manager generation over and over. A resolve concurrent with a swap may
 * legitimately see the old generation or the new one - what it may <b>never</b> see is the gap the
 * unregister-then-register dance has: a {@code null} resolver, a {@code null} value for a key
 * present in every generation, or an exception. The run ends by swap count, never by clock.
 */
class RefHotSwapConcurrencyTest {

    private static final int READER_THREADS = 4;
    private static final int SWAPS = 200;

    @Test
    void concurrentResolvesDuringRepeatedSwaps_neverObserveAGap() throws Exception {
        RefRegistry registry = new RefRegistry();
        InMemoryStorage storage = Storages.createInMemory();
        storage.init().join();
        try {
            // two generations' worth of truth, one collection each; the value names its generation
            EntityDescriptor<UUID, Guild> genA = descriptor(registry, "guilds_gen_a");
            EntityDescriptor<UUID, Guild> genB = descriptor(registry, "guilds_gen_b");
            UUID id = UUID.randomUUID();
            storage.repository(genA).save(new Guild(id, "A")).join();
            storage.repository(genB).save(new Guild(id, "B")).join();

            registry.manager(genA, storage, CachePolicy.always());
            Ref<UUID, Guild> ref = registry.ref(id, Guild.class);

            ConcurrentLinkedQueue<String> gaps = new ConcurrentLinkedQueue<>();
            CountDownLatch started = new CountDownLatch(READER_THREADS);
            CompletableFuture<Void> done = new CompletableFuture<>();

            ExecutorService readers = Executors.newFixedThreadPool(READER_THREADS);
            List<Future<Long>> iterations = new ArrayList<>();
            for (int t = 0; t < READER_THREADS; t++) {
                iterations.add(readers.submit(() -> {
                    started.countDown();
                    long loops = 0;
                    while (!done.isDone()) {
                        loops++;
                        try {
                            Guild guild = ref.join();
                            if (guild == null) {
                                gaps.add("ref.join() returned null");
                            } else if (!"A".equals(guild.getName()) && !"B".equals(guild.getName())) {
                                gaps.add("ref.join() saw a value of no generation: " + guild.getName());
                            }
                            if (registry.resolver(Guild.class) == null) {
                                gaps.add("registry.resolver(type) returned null");
                            }
                        } catch (Throwable observed) {
                            gaps.add("reader threw: " + observed);
                        }
                    }
                    return loops;
                }));
            }

            started.await();
            for (int swap = 1; swap <= SWAPS; swap++) {
                EntityDescriptor<UUID, Guild> next = (swap % 2 == 0) ? genA : genB;
                registry.managerReplacing(next, storage, CachePolicy.always(),
                        retired -> ((CachingManager<?, ?>) retired).clearCache());
            }
            done.complete(null);

            readers.shutdown();
            assertTrue(readers.awaitTermination(30, TimeUnit.SECONDS), "readers did not finish");
            for (Future<Long> reader : iterations) {
                assertTrue(reader.get() > 0, "a reader never got to iterate");
            }
            assertTrue(gaps.isEmpty(), () -> "observed " + gaps.size() + " gap(s), first: " + gaps.peek());
        } finally {
            storage.close().join();
        }
    }

    private static EntityDescriptor<UUID, Guild> descriptor(RefRegistry registry, String collection) {
        return EntityDescriptor.builder(UUID.class, Guild.class)
                .collection(collection)
                .keyExtractor(Guild::getId)
                .codec(registry.codec(Guild.class))
                .build();
    }
}
