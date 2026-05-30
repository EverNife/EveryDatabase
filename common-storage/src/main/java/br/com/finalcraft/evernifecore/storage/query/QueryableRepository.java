package br.com.finalcraft.evernifecore.storage.query;

import br.com.finalcraft.evernifecore.storage.Repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extended {@link Repository} that supports rich filter queries via {@link Spec}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public interface QueryableRepository<K, V> extends Repository<K, V> {

    /**
     * Returns all entities that match the given filter specification.
     *
     * @param spec the filter; must not be {@code null}
     * @return a future completing with the list of matching entities
     */
    CompletableFuture<List<V>> findWhere(Spec<V> spec);
}
