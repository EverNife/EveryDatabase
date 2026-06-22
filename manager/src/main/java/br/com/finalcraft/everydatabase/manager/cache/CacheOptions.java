package br.com.finalcraft.everydatabase.manager.cache;

import java.util.Objects;

/**
 * Store-level configuration for a {@code CachingManager}: the default {@link CachePolicy}
 * (freshness) plus the cache capacity (max size / LRU eviction).
 *
 * <p>Capacity lives here and not on {@link CachePolicy} on purpose - the policy is
 * per-reference overridable, but the store's size bound is shared by every entry and cannot
 * be set per reference.
 */
public class CacheOptions {

    /** Sentinel for {@link #maxSize()}: no bound - entries are evicted only by invalidation. */
    public static final int UNBOUNDED = 0;

    protected final CachePolicy policy;
    protected final int maxSize;

    private CacheOptions(CachePolicy policy, int maxSize) {
        this.policy  = Objects.requireNonNull(policy, "policy");
        this.maxSize = Math.max(UNBOUNDED, maxSize);
    }

    /** The default freshness policy applied when a {@link Ref} declares no override. */
    public CachePolicy policy() {
        return policy;
    }

    /** Maximum number of cached entries; {@link #UNBOUNDED} ({@code 0}) means no bound. */
    public int maxSize() {
        return maxSize;
    }

    /** Options with the given policy and no size bound. */
    public static CacheOptions of(CachePolicy policy) {
        return new CacheOptions(policy, UNBOUNDED);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "CacheOptions{" + policy + ", maxSize=" + (maxSize == UNBOUNDED ? "unbounded" : maxSize) + "}";
    }

    public static class Builder {
        protected CachePolicy policy = CachePolicy.always();
        protected int maxSize = UNBOUNDED;

        /** Default freshness policy (default {@link CachePolicy#always()}). */
        public Builder policy(CachePolicy policy) {
            this.policy = policy;
            return this;
        }

        /** Bounded LRU: keep at most {@code maxSize} hottest entries; {@code 0} = unbounded. */
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public CacheOptions build() {
            return new CacheOptions(policy, maxSize);
        }
    }
}
