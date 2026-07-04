package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.tx.TransactionScope;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;

/**
 * {@link TransactionScope} bound to a MongoDB {@link ClientSession} with an active transaction.
 *
 * <p>All repositories obtained from this scope share the same session, so their
 * operations participate in the same multi-document transaction.
 * Requires a MongoDB replica set (MongoDB 4.0+).
 *
 * <p>The scope is valid only for the duration of the {@code inTransaction(...)} lambda: once it
 * returns, the session is closed. Retaining the scope and using it afterwards would touch a closed
 * session, so the scope is marked ended and further use throws fast.
 */
final class MongoTransactionScope implements TransactionScope {

    private final MongoDatabase database;
    private final ClientSession session;
    private final StorageLog    log;
    private volatile boolean ended = false;
    private boolean rolledBack = false;

    MongoTransactionScope(MongoDatabase database, ClientSession session, StorageLog log) {
        this.database = database;
        this.session  = session;
        this.log      = log;
    }

    @Override
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        if (ended) {
            throw new IllegalStateException(
                "TransactionScope used after the inTransaction(...) lambda returned; the session is "
                + "closed. Do not retain the scope or its repositories past the lambda.");
        }
        return new MongoRepository<>(
            descriptor,
            database.getCollection(descriptor.collection()),
            session,
            log
        );
    }

    @Override
    public void rollback() {
        if (ended) {
            throw new IllegalStateException("rollback() called after the transaction already ended");
        }
        rolledBack = true;
    }

    boolean isRolledBack()  { return rolledBack; }
    ClientSession session() { return session; }

    /** Marks the scope finished so any later use fails fast instead of touching a closed session. */
    void markEnded() { this.ended = true; }
}
