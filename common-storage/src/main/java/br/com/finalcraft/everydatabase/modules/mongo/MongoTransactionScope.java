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
 */
final class MongoTransactionScope implements TransactionScope {

    private final MongoDatabase database;
    private final ClientSession session;
    private final StorageLog    log;
    private boolean rolledBack = false;

    MongoTransactionScope(MongoDatabase database, ClientSession session, StorageLog log) {
        this.database = database;
        this.session  = session;
        this.log      = log;
    }

    @Override
    public <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor) {
        return new MongoRepository<>(
            descriptor,
            database.getCollection(descriptor.collection()),
            session,
            log
        );
    }

    @Override
    public void rollback() {
        rolledBack = true;
    }

    boolean isRolledBack()  { return rolledBack; }
    ClientSession session() { return session; }
}
