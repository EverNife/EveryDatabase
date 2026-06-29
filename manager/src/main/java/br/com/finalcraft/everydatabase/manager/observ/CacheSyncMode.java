package br.com.finalcraft.everydatabase.manager.observ;

/**
 * The mechanism a {@code CacheSync} is currently using to keep caches fresh - the single most useful
 * operational signal (more than hit-rate): it tells you whether you are on the fast push path or the
 * slower polling fallback.
 */
public enum CacheSyncMode {

    /** A backend-native change feed (Mongo change streams / Postgres LISTEN-NOTIFY). */
    FEED,

    /** Version polling (backends with no native feed). */
    POLL,

    /** An explicit pub/sub transport, connected and delivering push signals. */
    TRANSPORT_PUSH,

    /** An explicit pub/sub transport that is currently disconnected; the standby poller has taken over. */
    TRANSPORT_FALLBACK_POLL,

    /** Not started, or nothing bound. */
    IDLE
}
