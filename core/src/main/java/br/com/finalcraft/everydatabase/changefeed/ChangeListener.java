package br.com.finalcraft.everydatabase.changefeed;

/**
 * Receives {@link ChangeEvent}s pushed by a {@link ChangeFeedStorage}.
 *
 * <p>Implementations should be cheap and non-blocking - a listener may run on the source's
 * delivery thread (the change-stream / NOTIFY listener thread, or the writer's thread for the
 * in-memory backend). A listener that throws never breaks the storage operation that produced the
 * event: {@link ChangeFeedSupport} isolates each callback.
 */
@FunctionalInterface
public interface ChangeListener {

    /** Called once per change. Must not throw across into the source (exceptions are isolated). */
    void onChange(ChangeEvent event);
}
