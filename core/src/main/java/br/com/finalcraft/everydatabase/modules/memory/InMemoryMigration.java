package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;

/**
 * Convenience base class for in-memory migrations.
 *
 * <p>Subclasses implement {@link #executeOnStorage(InMemoryStorage)} and operate through
 * {@code storage.repository(descriptor)} - the storage itself is the only handle (there is
 * no {@code Connection}/{@code MongoDatabase}-style native client).
 *
 * <p>Because {@link InMemoryStorage} is ephemeral, schema-DDL migrations have nothing to do
 * (entities are JSON blobs in a map, not rows with columns). The meaningful use here is a
 * <em>data</em> migration: seed reference data, or transform existing entities.
 *
 * <pre>{@code
 * public final class V001_SeedDefaults extends InMemoryMigration {
 *
 *     public static final V001_SeedDefaults INSTANCE = new V001_SeedDefaults();
 *     private V001_SeedDefaults() {}
 *
 *     public String version()     { return "001"; }
 *     public String description() { return "Seed default players"; }
 *
 *     protected void executeOnStorage(InMemoryStorage storage) {
 *         storage.repository(PLAYERS).saveAll(DEFAULT_PLAYERS).join();
 *     }
 * }
 * }</pre>
 */
public abstract class InMemoryMigration implements Migration {

    @Override
    public final void execute(MigrationContext context) throws Exception {
        InMemoryStorage storage = context.getNativeClient(InMemoryStorage.class);
        executeOnStorage(storage);
    }

    /**
     * Performs the migration using the given {@link InMemoryStorage}.
     *
     * <p>Use {@code storage.repository(descriptor)} for standard CRUD.
     *
     * @param storage the storage to migrate
     * @throws Exception if the migration fails
     */
    protected abstract void executeOnStorage(InMemoryStorage storage) throws Exception;
}
