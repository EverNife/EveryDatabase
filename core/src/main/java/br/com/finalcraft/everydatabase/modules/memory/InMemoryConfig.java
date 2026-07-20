package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.StorageConfig;

/**
 * Configuration for the in-memory storage backend.
 *
 * <p>No parameters needed - data exists only while the JVM is running.
 * Ideal for unit tests and CI pipelines where no external service is available.
 *
 * <pre>{@code
 * Storage storage = Storages.create(new InMemoryConfig());
 * }</pre>
 */
public final class InMemoryConfig implements StorageConfig {

    private final String sharedIdentity;

    public InMemoryConfig() {
        this(null);
    }

    /**
     * @param sharedIdentity explicit identity for this store, or {@code null} for the per-instance
     *                       default (see {@link #sharedIdentity()})
     */
    public InMemoryConfig(String sharedIdentity) {
        this.sharedIdentity = sharedIdentity;
    }

    /**
     * An explicit identity for this store, or {@code null} for a per-instance one.
     *
     * <p>An in-memory store is never shared with another process, so the default identity is unique
     * per instance and no cross-process signal ever matches it. Setting this is a test/fixture
     * affordance: it lets two in-memory instances stand in for one physical store.
     */
    public String sharedIdentity() {
        return sharedIdentity;
    }
}
