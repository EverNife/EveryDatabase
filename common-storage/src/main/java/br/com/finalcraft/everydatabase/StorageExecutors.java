package br.com.finalcraft.everydatabase;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor for async storage I/O operations.
 *
 * <p>On Java 21+, uses virtual threads via reflection (zero-overhead concurrency for I/O).
 * On older JVMs (8-20), falls back to a daemon thread pool with {@code max(2, cores)} core
 * threads that time out after 3 seconds of idleness, so an idle application holds no threads.
 *
 * <p><b>Why core threads instead of max threads:</b> a {@link ThreadPoolExecutor} with an
 * unbounded queue never grows past its core size - excess tasks wait in the queue instead of
 * spawning new threads. The fallback pool therefore declares all of its threads as core
 * threads (created on demand) and enables {@link ThreadPoolExecutor#allowCoreThreadTimeOut}.
 *
 * <p><b>Nested blocking caution (fallback pool only):</b> blocking on a storage future from
 * <em>inside</em> a task already running on this executor (e.g. {@code repo.find(...).join()}
 * within {@code inTransaction} work that itself dispatched here) holds a pool thread while it
 * waits. If every pool thread blocks this way, the futures they wait for can never run - a
 * classic pool-starvation deadlock. On Java 21+ virtual threads make this a non-issue.
 */
public final class StorageExecutors {

    private StorageExecutors() {}

    private static final Executor EXECUTOR = createExecutor();

    private static Executor createExecutor() {
        try {
            // Java 21+: newVirtualThreadPerTaskExecutor via reflection so the bytecode stays Java 8 compatible
            return (Executor) Executors.class
                .getMethod("newVirtualThreadPerTaskExecutor")
                .invoke(null);
        } catch (Exception ignored) {
            return createFallbackExecutor();
        }
    }

    /**
     * Pre-Java-21 fallback: daemon pool sized {@code max(2, cores)} with idle core-thread
     * timeout. Package-private so tests can exercise this code path on modern JVMs too.
     */
    static ThreadPoolExecutor createFallbackExecutor() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads, threads, 3L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setName("storage-async-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static Executor async() {
        return EXECUTOR;
    }
}
