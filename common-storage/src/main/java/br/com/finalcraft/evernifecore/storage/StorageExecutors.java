package br.com.finalcraft.evernifecore.storage;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor for async storage I/O operations.
 *
 * <p>On Java 21+, uses virtual threads via reflection (zero-overhead concurrency for I/O).
 * On older JVMs, falls back to a daemon thread pool bounded to the number of CPU cores.
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
            // Java < 21 fallback: bounded daemon thread pool
            int cores = Runtime.getRuntime().availableProcessors();
            AtomicInteger counter = new AtomicInteger();
            return new ThreadPoolExecutor(
                1, cores, 3L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("storage-async-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            );
        }
    }

    public static Executor async() {
        return EXECUTOR;
    }
}
