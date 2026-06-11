package br.com.finalcraft.everydatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pre-Java-21 fallback pool directly (the test JVM is 21+, so
 * {@link StorageExecutors#async()} itself returns a virtual-thread executor).
 *
 * <p>Guards against the historical bug where the fallback was created as
 * {@code ThreadPoolExecutor(1, cores, ..., unbounded queue)}: with an unbounded queue the
 * pool never grows past corePoolSize, so the whole library serialized on a single thread.
 */
class StorageExecutorsTest {

    @Test
    @DisplayName("fallback pool runs tasks in parallel (not serialized on one thread)")
    void fallbackExecutor_runsTasksInParallel() throws Exception {
        ThreadPoolExecutor pool = StorageExecutors.createFallbackExecutor();
        try {
            int tasks = pool.getCorePoolSize();
            assertTrue(tasks >= 2, "fallback pool must have at least 2 threads, got " + tasks);

            CountDownLatch allStarted = new CountDownLatch(tasks);
            CountDownLatch release = new CountDownLatch(1);

            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> {
                    allStarted.countDown();
                    try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                });
            }

            // With the old 1-core/unbounded-queue pool only ONE task would ever start: the
            // rest would sit in the queue while the single thread blocks on `release`.
            boolean started = allStarted.await(10, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(started, "all " + tasks + " tasks should start concurrently - pool is serializing");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("fallback pool threads are daemons named storage-async-*")
    void fallbackExecutor_usesNamedDaemonThreads() throws Exception {
        ThreadPoolExecutor pool = StorageExecutors.createFallbackExecutor();
        try {
            Thread worker = CompletableFuture.supplyAsync(Thread::currentThread, pool)
                .get(10, TimeUnit.SECONDS);
            assertTrue(worker.isDaemon(), "pool threads must be daemons");
            assertTrue(worker.getName().startsWith("storage-async-"),
                "unexpected thread name: " + worker.getName());
        } finally {
            pool.shutdownNow();
        }
    }
}
