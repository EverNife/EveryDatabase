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
}
