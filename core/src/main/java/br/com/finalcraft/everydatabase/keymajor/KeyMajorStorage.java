package br.com.finalcraft.everydatabase.keymajor;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A {@link Storage} that stores every collection of one key together, and can therefore read or
 * write all of them in a single operation.
 *
 * <p>Capability interface, checked with {@code instanceof} like
 * {@link br.com.finalcraft.everydatabase.tx.TransactionalStorage} and
 * {@link br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage}. Only
 * {@code GroupedFileStorage} implements it; every other backend stores collections apart, so the
 * caller falls back to N {@code find}/{@code save} calls:
 *
 * <pre>{@code
 * if (storage instanceof KeyMajorStorage kms) {
 *     KeyBundle bundle = kms.loadKey(uuid, PLAYER_DATA, ECONOMY, HOMES).join();
 *     ...
 * } else {
 *     PlayerData data = storage.repository(PLAYER_DATA).find(uuid).join().orElse(null);
 *     ...
 * }
 * }</pre>
 *
 * <p><b>Atomicity is per key, and only per key.</b> {@link #batchKey} publishes its whole batch with
 * one atomic file move, so a crash never leaves half the collections updated. That is as far as it
 * goes: two keys are two independent writes. This is not a transaction, and a key-major storage does
 * <em>not</em> implement {@code TransactionalStorage} - there is no rollback, no isolation between
 * concurrent batches beyond the per-key lock, and no way to span keys.
 */
public interface KeyMajorStorage extends Storage {

    /**
     * Reads every listed collection of {@code key} in one go.
     *
     * @throws IllegalArgumentException if no descriptor is given, or if the key does not match a
     *                                  descriptor's key type
     */
    CompletableFuture<KeyBundle> loadKey(Object key, EntityDescriptor<?, ?>... descriptors);

    /**
     * Applies every write in {@code writes} to {@code key} as a single atomic publication.
     *
     * <p>{@code writes} runs before any lock is taken and before anything is read, so it may call
     * back into the storage without deadlocking, and an exception it throws leaves the stored key
     * untouched.
     */
    CompletableFuture<Void> batchKey(Object key, Consumer<KeyBatch> writes);
}
