package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;
import com.mongodb.client.MongoDatabase;

/**
 * Convenience base class for MongoDB migrations.
 *
 * <p>Exposes the standard document field names used by {@link MongoRepository}
 * so migration subclasses can reference them without accessing the package-private class:
 * <ul>
 *   <li>{@link #FIELD_KEY}  - {@code "_sk"}: the serialised entity key</li>
 *   <li>{@link #FIELD_DATA} - {@code "_data"}: the JSON-encoded entity blob</li>
 * </ul>
 *
 *
 * <p>Subclasses implement {@link #executeOnDatabase(MongoDatabase)} to perform
 * any imperative operations - creating indexes, renaming fields inside JSON blobs,
 * back-filling data, dropping collections, etc.
 *
 * <p>Unlike SQL migrations, Mongo migrations are always code-based (no "script" concept):
 * you write plain Java that manipulates {@link MongoDatabase} directly.
 *
 * <pre>{@code
 * public final class V002_AddNameIndex extends MongoMigration {
 *
 *     public static final V002_AddNameIndex INSTANCE = new V002_AddNameIndex();
 *     private V002_AddNameIndex() {}
 *
 *     public String version()     { return "002"; }
 *     public String description() { return "Add ascending index on name in player_data"; }
 *
 *     protected void executeOnDatabase(MongoDatabase db) {
 *         db.getCollection("player_data")
 *           .createIndex(Indexes.ascending(MongoRepository.FIELD_DATA + ".name"));
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Transactions:</strong> {@link #executeOnDatabase(MongoDatabase)} runs outside
 * a client session. MongoDB multi-document transactions require a replica set; the
 * migration runner does not wrap migrations in a transaction automatically. If you need
 * transactional safety, implement {@link #execute(MigrationContext)} directly and start
 * your own session.
 */
public abstract class MongoMigration implements Migration {

    /** MongoDB field that stores the serialised entity key ({@code "_sk"}). */
    public static final String FIELD_KEY  = MongoRepository.FIELD_KEY;

    /** MongoDB field that stores the JSON-encoded entity blob ({@code "_data"}). */
    public static final String FIELD_DATA = MongoRepository.FIELD_DATA;

    /**
     * Unwraps the {@link MongoDatabase} from the context and delegates to
     * {@link #executeOnDatabase(MongoDatabase)}.
     */
    @Override
    public final void execute(MigrationContext context) throws Exception {
        MongoDatabase db = context.getNativeClient(MongoDatabase.class);
        executeOnDatabase(db);
    }

    /**
     * Performs the migration against the given {@link MongoDatabase}.
     *
     * <p>Called by the migration runner when this migration is pending.
     * Throw any exception to signal failure and abort the migration sequence.
     *
     * @param db the database to migrate
     * @throws Exception if the migration fails
     */
    protected abstract void executeOnDatabase(MongoDatabase db) throws Exception;
}
