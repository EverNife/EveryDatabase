package br.com.finalcraft.evernifecore.storage;

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
 *   <li>{@link br.com.finalcraft.evernifecore.storage.query.QueryableStorage} - rich filter queries</li>
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
}
