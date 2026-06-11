package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.log.StorageLogConfig;

import java.util.concurrent.CompletableFuture;

/**
 * Base contract for all storage backends.
 *
 * <p>A {@code Storage} instance manages lifecycle (connection pool, file handles, etc.)
 * and acts as a factory for typed {@link Repository} instances.</p>
 *
 * <p>Optional capabilities are expressed as additional interfaces, not flags:
 * <ul>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.tx.TransactionalStorage} - atomic transactions</li>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.schema.SchemaAwareStorage} - schema migrations</li>
 * </ul>
 *
 * <p>Backends are obtained via {@link Storages#create(br.com.finalcraft.evernifecore.storage.StorageConfig)}.
 */
public interface Storage {

    /**
     * Initializes pool/connection. Idempotent.
     */
    CompletableFuture<Void> init();

    /**
     * Closes pool/connection. Idempotent.
     */
    CompletableFuture<Void> close();

    /**
     * Fast healthcheck: connected? ping?
     */
    CompletableFuture<HealthStatus> health();

    /**
     * Returns a typed repository for the entity described by the given descriptor.
     *
     * @throws UnsupportedOperationException if the backend cannot model this entity
     */
    <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor);

    /**
     * Returns the <b>live, mutable</b> {@link StorageLogConfig} for this storage.
     *
     * <p>The returned object is shared with all repositories belonging to this storage.
     * Editing it takes effect immediately for all repositories without any restart or
     * re-injection.
     *
     * <p>Example - enable write logging at runtime via a command:
     * <pre>{@code
     * storage.getStorageLogConfig()
     *        .level(StorageLogTopic.WRITE, StorageLogLevel.DEBUG)
     *        .includeKeys(true);
     * }</pre>
     */
    StorageLogConfig getStorageLogConfig();

    /**
     * Replaces the entire {@link StorageLogConfig} with a new instance.
     *
     * <p>The new config is picked up immediately by all repositories (the dispatcher
     * re-reads it on every emit call). The previous config object is discarded.
     *
     * <p>For runtime tweaks prefer {@link #getStorageLogConfig()} and editing in-place;
     * use this method only when a clean slate is needed.
     *
     * @return {@code this} for chaining
     */
    Storage setStorageLogConfig(StorageLogConfig config);
}
