package br.com.finalcraft.everydatabase.schema;

/**
 * Contextual handle passed to {@link Migration#execute(MigrationContext)}.
 *
 * <p>Each backend wraps its native client in a private implementation of this interface
 * and passes it to migrations at execution time.
 *
 * <p>Callers use {@link #getNativeClient(Class)} to retrieve the typed handle:
 *
 * <pre>{@code
 * // inside a Migration.execute() implementation:
 * Connection  conn = context.getNativeClient(Connection.class);   // SQL
 * MongoDatabase db = context.getNativeClient(MongoDatabase.class); // Mongo
 * Path        dir  = context.getNativeClient(Path.class);          // LocalFile
 * }</pre>
 *
 * <p>Requesting a type that the backend does not provide throws
 * {@link IllegalArgumentException}, which surfaces as a migration failure.
 */
public interface MigrationContext {

    /**
     * Returns the backend-native resource cast to {@code type}.
     *
     * @param <T>  the expected resource type
     * @param type the class to cast to
     * @return the backend resource
     * @throws IllegalArgumentException if this backend does not provide the requested type
     */
    <T> T getNativeClient(Class<T> type);
}
