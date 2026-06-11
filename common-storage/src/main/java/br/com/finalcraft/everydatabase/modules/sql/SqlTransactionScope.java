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
 */
final class SqlTransactionScope implements TransactionScope {

    private final SqlStorage storage;
    @SuppressWarnings("unused")
    private final Connection conn;   // kept for reference; actual routing via ThreadLocal in SqlStorage
    private boolean rolledBack = false;

    SqlTransactionScope(SqlStorage storage, Connection conn) {
        this.storage = storage;
        this.conn    = conn;
    }

    @Override
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        return storage.repository(descriptor);
    }

    @Override
    public void rollback() {
        rolledBack = true;
    }

    boolean isRolledBack() { return rolledBack; }
}
