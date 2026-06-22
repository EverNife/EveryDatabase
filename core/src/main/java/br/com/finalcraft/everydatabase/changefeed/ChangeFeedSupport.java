package br.com.finalcraft.everydatabase.changefeed;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reusable in-process fan-out a {@link ChangeFeedStorage} composes to manage its listeners. It is
 * <b>only the local dispatcher</b>; the <em>source</em> of events differs per backend (a Mongo
 * change-stream thread, a Postgres NOTIFY thread, or the writer's own thread for in-memory) and
 * calls {@link #emit(ChangeEvent)} to publish.
 *
 * <p>Thread-safe via a {@link CopyOnWriteArrayList}. A listener that throws never breaks the source:
 * each callback is isolated, and the failure is handed to the optional error sink (so a storage can
 * route it to its log) rather than propagating.
 */
public final class ChangeFeedSupport {

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Consumer<Throwable> errorSink;

    /** Creates a dispatcher that silently isolates listener failures. */
    public ChangeFeedSupport() {
        this(null);
    }

    /**
     * Creates a dispatcher that hands listener failures to {@code errorSink} (e.g. a storage log)
     * instead of swallowing them silently. The source operation still never fails.
     */
    public ChangeFeedSupport(Consumer<Throwable> errorSink) {
        this.errorSink = errorSink;
    }

    /** Registers a listener and returns its subscription handle. */
    public ChangeSubscription subscribe(ChangeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        Subscription sub = new Subscription(listener);
        subscriptions.add(sub);
        return sub;
    }

    /** Fans {@code event} out to every active listener, isolating any that throws. */
    public void emit(ChangeEvent event) {
        for (Subscription sub : subscriptions) {
            if (!sub.active.get()) {
                continue;
            }
            try {
                sub.listener.onChange(event);
            } catch (Throwable t) {
                if (errorSink != null) {
                    try {
                        errorSink.accept(t);
                    } catch (Throwable ignored) {
                        // an error sink must never break delivery either
                    }
                }
            }
        }
    }

    /** Closes every subscription - called when the owning storage closes. */
    public void closeAll() {
        for (Subscription sub : subscriptions) {
            sub.close();
        }
        subscriptions.clear();
    }

    /** Number of currently active subscriptions (for tests/diagnostics). */
    public int activeCount() {
        int n = 0;
        for (Subscription sub : subscriptions) {
            if (sub.active.get()) {
                n++;
            }
        }
        return n;
    }

    private final class Subscription implements ChangeSubscription {
        private final ChangeListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        Subscription(ChangeListener listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                subscriptions.remove(this);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
