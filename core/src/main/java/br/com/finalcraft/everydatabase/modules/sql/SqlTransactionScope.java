package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.tx.TransactionScope;

import java.sql.Connection;

/**
 * {@link TransactionScope} bound to a single JDBC {@link Connection} with auto-commit disabled.
 *
 * <p>All repositories obtained from this scope share the same connection via
 * {@link SqlStorage}'s {@link ThreadLocal}, so their operations participate
 * in the same transaction.
 *
 * <p>The scope is valid only for the duration of the {@code inTransaction(...)} lambda. Once the
 * lambda returns, the transaction connection is removed from the ThreadLocal and closed, so retaining
 * the scope (or a repository obtained from it) and using it afterwards would silently run outside any
 * transaction. To make that misuse loud, the scope is marked ended and further use throws.
 */
final class SqlTransactionScope implements TransactionScope {

    private final SqlStorage storage;
    private volatile boolean ended = false;
    private boolean rolledBack = false;

    SqlTransactionScope(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        if (ended) {
            throw new IllegalStateException(
                "TransactionScope used after the inTransaction(...) lambda returned; the transaction "
                + "connection is gone, so this call would silently run outside the transaction. "
                + "Do not retain the scope or its repositories past the lambda.");
        }
        return storage.repository(descriptor);
    }

    @Override
    public void rollback() {
        if (ended) {
            throw new IllegalStateException("rollback() called after the transaction already ended");
        }
        rolledBack = true;
    }

    boolean isRolledBack() { return rolledBack; }

    /** Marks the scope finished so any later use fails fast instead of silently escaping the transaction. */
    void markEnded() { this.ended = true; }
}
