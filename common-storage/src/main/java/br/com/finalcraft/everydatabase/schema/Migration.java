package br.com.finalcraft.everydatabase.schema;

/**
 * A single, forward-only schema migration.
 *
 * <p>Migrations are ordered by their {@link #version()} string (natural string order)
 * and applied exactly once. There is intentionally no {@code downScript()} -
 * rollback-by-rollback-migration is an anti-pattern in production; write a
 * compensating forward migration instead.
 *
 * <p>Each backend provides a convenience base class that wraps its native client:
 * <ul>
 *   <li>SQL   - extend {@code SqlMigration} and implement {@link #upScript()}</li>
 *   <li>Mongo - extend {@code MongoMigration} and implement {@code executeOnDatabase(MongoDatabase)}</li>
 * </ul>
 *
 * <p>For full control (e.g. multi-statement SQL, mixed operations), implement
 * {@link #execute(MigrationContext)} directly and call {@link MigrationContext#getNativeClient}.
 *
 * <p>Example - SQL, using the helper base:
 * <pre>{@code
 * public final class V001_CreatePlayers extends SqlMigration {
 *     public String version()     { return "001"; }
 *     public String description() { return "Create players table"; }
 *     public String upScript()    {
 *         return "CREATE TABLE players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64))";
 *     }
 * }
 * }</pre>
 *
 * <p>Example - Mongo, using the helper base:
 * <pre>{@code
 * public final class V001_AddNameIndex extends MongoMigration {
 *     public String version()     { return "001"; }
 *     public String description() { return "Add index on name"; }
 *     protected void executeOnDatabase(MongoDatabase db) {
 *         db.getCollection("players").createIndex(Indexes.ascending("name"));
 *     }
 * }
 * }</pre>
 */
public interface Migration {

    /**
     * A lexicographically sortable version identifier, e.g. {@code "001"}, {@code "2024-01-15"}.
     * Must be unique across all migrations for a given storage.
     */
    String version();

    /** Human-readable description of what this migration does. */
    String description();

    /**
     * Executes this migration against the backend.
     *
     * <p>Use {@link MigrationContext#getNativeClient(Class)} to obtain the backend-specific
     * resource (e.g. {@code Connection} for SQL, {@code MongoDatabase} for Mongo).
     *
     * <p>If this method throws, the migration runner will wrap the exception and
     * abort the migration sequence - subsequent pending migrations will NOT be applied.
     *
     * @param context provides access to the backend-native client
     * @throws Exception if the migration fails
     */
    void execute(MigrationContext context) throws Exception;
}
