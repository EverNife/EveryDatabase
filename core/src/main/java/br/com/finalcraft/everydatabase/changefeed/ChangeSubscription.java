package br.com.finalcraft.everydatabase.changefeed;

/**
 * A handle to an active {@link ChangeListener} registration. Close it to stop delivery.
 *
 * <p>{@link #close()} is idempotent. Closing the originating {@link ChangeFeedStorage} (via
 * {@code Storage.close()}) closes all of its subscriptions.
 */
public interface ChangeSubscription extends AutoCloseable {

    /** Stops delivery to the associated listener. Idempotent. */
    @Override
    void close();

    /** Whether this subscription is still delivering events. */
    boolean isActive();
}
