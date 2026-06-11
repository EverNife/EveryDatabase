package br.com.finalcraft.everydatabase.tx;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;

/**
 * A live transaction context passed to the work lambda in
 * {@link TransactionalStorage#inTransaction(java.util.function.Function)}.
 *
 * <p>All repositories obtained from this scope participate in the same transaction.
 * Once the work lambda returns, the caller should not retain references to the scope.</p>
 */
public interface TransactionScope {

    /**
     * Returns a repository that operates within the current transaction.
     */
    <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor);

    /**
     * Marks the current transaction for rollback.
     * The work future should still complete normally after calling this;
     * the backend will abort the transaction instead of committing it.
     */
    void rollback();
}
