package br.com.finalcraft.everydatabase;

import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;

import java.util.concurrent.CompletableFuture;

/**
 * Base contract for all storage backends.
 *
 * <p>A {@code Storage} instance manages lifecycle (connection pool, file handles, etc.)
 * and acts as a factory for typed {@link Repository} instances.</p>
 *
 * <p>Optional capabilities are expressed as additional interfaces, not flags:
 * <ul>
 *   <li>{@link TransactionalStorage} - atomic transactions</li>
 *   <li>{@link SchemaAwareStorage} - schema migrations</li>
 * </ul>
 *
 * <p>Backends are obtained via {@link Storages#create(StorageConfig)}.
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
     * <p>Call this only after {@link #init()} and before {@link #close()}. The connection-backed
     * backends (SQL, MongoDB) throw {@link IllegalStateException} when called before init or after
     * close, so the misuse fails fast rather than surfacing a raw {@code NullPointerException} deep
     * in a later operation; the file and in-memory backends need no live connection and may return a
     * repository without an init, but that is not a guarantee to rely on.
     *
     * <p>Repositories are cached per collection name: calling this twice with descriptors that share
     * a collection name returns the <em>first</em> descriptor's repository (the collection name is the
     * identity), so register one descriptor per collection.
     *
     * @throws IllegalStateException if the backend requires a live connection and has not been
     *         initialised (or has been closed)
     * @throws IllegalArgumentException if the descriptor's codec is incompatible with the backend
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
