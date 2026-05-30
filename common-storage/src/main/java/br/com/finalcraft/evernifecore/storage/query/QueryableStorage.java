package br.com.finalcraft.evernifecore.storage.query;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Storage;

/**
 * Optional capability: returns {@link QueryableRepository} instances that support
 * rich filter queries via {@link Spec}.
 *
 * <p>Backends that do not support querying (e.g., basic file storage) simply
 * do not implement this interface.</p>
 */
public interface QueryableStorage extends Storage {

    /**
     * Returns a queryable repository for the described entity.
     *
     * @throws UnsupportedOperationException if the backend cannot create indexes
     *         required by the descriptor
     */
    <K, V> QueryableRepository<K, V> queryRepository(EntityDescriptor<K, V> descriptor);
}
