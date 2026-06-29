package br.com.finalcraft.everydatabase.manager.observ;

/**
 * An optional observer of {@code CacheSync} lifecycle events - transport connect/disconnect and mode
 * changes. All methods default to no-op, so an implementation overrides only what it cares about
 * (e.g. log "transport down, falling back to polling"). Register it with {@code CacheSync.observe(...)}.
 *
 * <p>Callbacks fire on the connectivity-delivery thread; keep them cheap and non-throwing (a thrown
 * exception is isolated and ignored). Carries no entity content - only operational state.
 */
public interface CacheSyncObserver {

    /** The transport reported it is connected (push is live). */
    default void onTransportConnected() { }

    /** The transport reported it is disconnected (the standby poller, if enabled, takes over). */
    default void onTransportDisconnected() { }

    /** The active mechanism changed (e.g. {@code TRANSPORT_PUSH} -> {@code TRANSPORT_FALLBACK_POLL}). */
    default void onModeChange(CacheSyncMode mode) { }
}
