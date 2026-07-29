package br.com.finalcraft.everydatabase.keymajor;

import br.com.finalcraft.everydatabase.EntityDescriptor;

/**
 * The writes to apply to one key, collected by
 * {@link KeyMajorStorage#batchKey(Object, java.util.function.Consumer)} before anything is touched.
 *
 * <p>Calls are recorded, not executed: the batch is assembled first and applied as one write, so an
 * exception thrown while assembling it leaves the stored key exactly as it was.
 *
 * <p>The last call for a given collection wins - {@code put} then {@code remove} of the same
 * collection removes it, and the reverse writes it.
 */
public interface KeyBatch {

    /** Stores {@code entity} as this key's value in {@code descriptor}'s collection. */
    <K, V> KeyBatch put(EntityDescriptor<K, V> descriptor, V entity);

    /** Drops this key's value in {@code descriptor}'s collection, if it has one. */
    <K, V> KeyBatch remove(EntityDescriptor<K, V> descriptor);
}
