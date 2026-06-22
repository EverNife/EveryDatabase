package br.com.finalcraft.everydatabase.manager.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Freshness strategy for a cached {@link CacheEntry}. <b>Freshness only</b> - capacity
 * (max size / eviction) is a property of the cache store, configured via
 * {@link CacheOptions}, not here. Keeping the two separate is deliberate: a policy is
 * <em>per-read overridable</em> (the manager's default vs a per-reference override), while
 * capacity is shared by the whole store and cannot be overridden per reference.
 *
 * <p>Built via the static factories and evaluated by a {@code CachingManager} (or a single
 * {@link Ref} via {@code @RefPolicy}) on each {@code peek}/{@code resolve}.
 */
public interface CachePolicy {

    /** Returns {@code true} when {@code entry} may be served without reloading from the backend. */
    boolean isFresh(CacheEntry<?> entry);

    /**
     * Whether reads under this policy participate in caching at all. When {@code false}, a
     * {@code resolve} loads from the backend and returns the value <em>without</em> populating or
     * touching the shared cache entry - a true bypass. Default {@code true}.
     */
    default boolean cacheable() {
        return true;
    }

    /** Keeps it cached until explicitly invalidated/evicted. The default for hot, bounded sets. */
    static CachePolicy always() {
        return AlwaysPolicy.INSTANCE;
    }

    /** Refetches when the entry is older than {@code duration}. */
    static CachePolicy ttl(Duration duration) {
        return new TtlPolicy(duration);
    }

    /** Never serves from cache - every read goes to the backend (debug / always-fresh). */
    static CachePolicy noCache() {
        return NoCachePolicy.INSTANCE;
    }

    /** Builds a policy from raw config values (admin override). */
    static CachePolicy fromAdminConfig(String policyName, Integer ttlSeconds) {
        if (policyName == null) {
            return always();
        }
        switch (policyName.toUpperCase(Locale.ROOT)) {
            case "ALWAYS":
                return always();
            case "TTL":
                return ttl(Duration.ofSeconds(ttlSeconds != null ? ttlSeconds : 30));
            case "NOCACHE":
                return noCache();
            default:
                throw new IllegalArgumentException("Unknown cache policy '" + policyName
                        + "' (expected ALWAYS | TTL | NOCACHE)");
        }
    }

    // ---------------------------------------------------------------------

    class AlwaysPolicy implements CachePolicy {
        protected static final AlwaysPolicy INSTANCE = new AlwaysPolicy();

        @Override
        public boolean isFresh(CacheEntry<?> entry) {
            return !entry.isStale();
        }

        @Override
        public String toString() {
            return "CachePolicy{ALWAYS}";
        }
    }

    class TtlPolicy implements CachePolicy {
        protected final Duration ttl;

        TtlPolicy(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getTtl() {
            return ttl;
        }

        @Override
        public boolean isFresh(CacheEntry<?> entry) {
            return !entry.isStale()
                    && Instant.now().isBefore(entry.getLoadedAt().plus(ttl));
        }

        @Override
        public String toString() {
            return "CachePolicy{TTL=" + ttl.getSeconds() + "s}";
        }
    }

    class NoCachePolicy implements CachePolicy {
        protected static final NoCachePolicy INSTANCE = new NoCachePolicy();

        @Override
        public boolean isFresh(CacheEntry<?> entry) {
            return false;
        }

        /** A true bypass: never serve from cache, and never populate it on load. */
        @Override
        public boolean cacheable() {
            return false;
        }

        @Override
        public String toString() {
            return "CachePolicy{NOCACHE}";
        }
    }
}
