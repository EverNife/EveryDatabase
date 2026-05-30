package br.com.finalcraft.evernifecore.storage.modules.sql.postgresql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlRepository;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlStorage;

/**
 * PostgreSQL {@link br.com.finalcraft.evernifecore.storage.Storage} backend.
 *
 * <p>Inherits all lifecycle management and transaction handling from {@link SqlStorage};
 * the only difference is that {@link #createRepository} returns a {@link PostgreSqlRepository}
 * that uses PostgreSQL-compatible SQL dialect (double-quote identifiers, {@code TEXT} column
 * type, {@code INSERT ... ON CONFLICT DO UPDATE} upsert).
 *
 * <p>For integration tests without a real PostgreSQL server, point this at an H2 in-memory
 * database configured with {@code MODE=PostgreSQL} and use {@code H2SqlStorage} instead:
 * <pre>{@code
 * // production - real PostgreSQL
 * Storage storage = new PostgreSqlStorage(
 *     new SqlConfig("jdbc:postgresql://host/db", "user", "pass"));
 *
 * // tests - H2 in PostgreSQL mode
 * Storage storage = new H2SqlStorage(
 *     new SqlConfig("jdbc:h2:mem:testdb;MODE=PostgreSQL", "", ""));
 * }</pre>
 */
public class PostgreSqlStorage extends SqlStorage {

    public PostgreSqlStorage(SqlConfig config) {
        super(config);
    }

    @Override
    protected <K, V> SqlRepository<K, V> createRepository(EntityDescriptor<K, V> descriptor) {
        return new PostgreSqlRepository<>(descriptor, getDataSource(), txConnection);
    }
}
