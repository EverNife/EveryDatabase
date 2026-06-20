package br.com.finalcraft.everydatabase.schema;

import br.com.finalcraft.everydatabase.Storage;

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
 * <h3>Supported backends</h3>
 * <ul>
 *   <li>{@code SqlStorage} (MariaDB / MySQL) - tracks applied migrations in a
 *       {@code _schema_migrations} table; migration authors use {@code SqlMigration}.</li>
 *   <li>{@code PostgreSqlStorage} / {@code H2SqlStorage} - inherit from {@code SqlStorage}.</li>
 *   <li>{@code MongoStorage} - tracks in a {@code _schema_migrations} collection;
 *       migration authors use {@code MongoMigration}.</li>
 *   <li>{@code LocalFileStorage} - tracks in a metadata file;
 *       migration authors use {@code LocalFileMigration}.</li>
 *   <li>{@code InMemoryStorage} - tracks in an ephemeral in-memory ledger that dies with
 *       the instance, so a fresh instance re-applies every migration; migration authors use
 *       {@code InMemoryMigration}. There is no schema to alter, so data-only migrations
 *       (seeding / transformation through the repository) are the meaningful use.</li>
 * </ul>
 *
 * <h3>Typical usage (SQL)</h3>
 * <pre>{@code
 * SqlStorage sql = Storages.createSQL(new SqlConfig("jdbc:mariadb://localhost/mc", "root", "pass"));
 * sql.init().join();
 *
 * sql.register(V001_CreateAuditLog.INSTANCE, V002_BackfillScores.INSTANCE)
 *    .migrate()
 *    .join();
 * }</pre>
 *
 * <h3>Typical usage (generic - works for any SchemaAware backend)</h3>
 * <pre>{@code
 * Storage storage = Storages.create(config);
 * storage.init().join();
 *
 * if (storage instanceof SchemaAwareStorage) {
 *     ((SchemaAwareStorage) storage)
 *         .register(V001_CreatePlayers.INSTANCE, V002_AddLastSeen.INSTANCE)
 *         .migrate()
 *         .join();
 * }
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
