package br.com.finalcraft.everydatabase.log;

/**
 * Consumer of structured {@link StorageLogEvent}s emitted by a storage backend.
 *
 * <p>This is a functional interface; common implementations are available via
 * {@link StorageLogSinks} (no-op, stdout, SLF4J bridge, host-installed default).
 *
 * <p>Implementations must be thread-safe - the same sink instance may receive events
 * from multiple threads concurrently (storage backends use a shared async executor).
 *
 * <p>Sink failures are silently swallowed by the dispatcher ({@link StorageLog}) to ensure
 * that a broken logging destination never interrupts a storage operation.
 */
@FunctionalInterface
public interface StorageLogSink {

    /**
     * Consumes the given event.
     *
     * <p>Implementations should not throw: any exception escaping this method will be
     * caught and discarded by the dispatcher.
     */
    void accept(StorageLogEvent event);
}
