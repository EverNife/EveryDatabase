package br.com.finalcraft.evernifecore.storage.schema;

import br.com.finalcraft.evernifecore.storage.Storage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Optional capability: schema migration support.
 *
 * <p>Any backend that tracks applied migrations and can apply pending ones should
 * implement this interface. The migration logic is backend-specific - each backend
 * keeps its own record of applied versions (e.g. a {@code _schema_migrations} table
 * in SQL, a {@code _schema_migrations} collection in Mongo).
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * Storage storage = Storages.create(config);
 *
 * if (storage instanceof SchemaAwareStorage) {
 *     ((SchemaAwareStorage) storage)
 *         .register(V001_CreatePlayers.INSTANCE, V002_AddLastSeen.INSTANCE)
 *         .migrate()
 *         .join();
 * }
 *
 * storage.init().join();
 * }</pre>
 */
public interface SchemaAwareStorage extends Storage {

    /**
     * Registers a list of migrations to be managed by this storage.
     *
     * <p>Must be called <em>before</em> {@link #migrate()}.
     * Migrations are sorted by {@link Migration#version()} automatically.
     * Calling {@code register()} multiple times accumulates migrations.
     *
     * @param migrations the migrations to register
     * @return {@code this} for fluent chaining
     */
    SchemaAwareStorage register(List<Migration> migrations);

    /**
     * Varargs shorthand for {@link #register(List)}.
     *
     * <pre>{@code
     * storage.register(V001.INSTANCE, V002.INSTANCE).migrate().join();
     * }</pre>
     */
    default SchemaAwareStorage register(Migration... migrations) {
        return register(Arrays.asList(migrations));
    }

    /**
     * Returns the version of the latest applied migration,
     * or {@link SchemaVersion#none()} if no migrations have been applied.
     */
    CompletableFuture<SchemaVersion> currentVersion();

    /**
     * Returns the registered migrations that have not yet been applied, in version order.
     * An empty list means the schema is up to date.
     */
    CompletableFuture<List<Migration>> pending();

    /**
     * Applies all pending migrations in version order.
     *
     * <p>Already-applied migrations are skipped (idempotent).
     * If any migration fails, the sequence is aborted and the returned future
     * completes exceptionally - migrations after the failing one are NOT applied.
     */
    CompletableFuture<Void> migrate();
}
