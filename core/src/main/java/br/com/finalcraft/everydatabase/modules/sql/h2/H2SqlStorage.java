package br.com.finalcraft.everydatabase.modules.sql.h2;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlRepository;
import br.com.finalcraft.everydatabase.modules.sql.SqlStorage;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.util.BackendIdentities;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

/**
 * H2-backed {@link Storage} that works in all three
 * H2 deployment modes depending on the JDBC URL supplied via {@link SqlConfig}:
 *
 * <ul>
 *   <li><b>In-memory</b> - {@code jdbc:h2:mem:mydb;DATABASE_TO_UPPER=FALSE}</li>
 *   <li><b>Embedded file</b> - {@code jdbc:h2:file:./data/storage}</li>
 *   <li><b>Server / TCP</b> - {@code jdbc:h2:tcp://localhost:9092/./data/storage}</li>
 * </ul>
 *
 * <p>SQL dialect features:
 * <ul>
 *   <li>ANSI double-quote identifier quoting ({@code "column"}).</li>
 *   <li>{@code TEXT} column type for the data column.</li>
 *   <li>{@code MERGE INTO ... KEY (...) VALUES (?)} for upsert.</li>
 * </ul>
 *
 * <p><b>Optimistic locking is NOT enforced on H2, even in TCP/server mode.</b> H2 opts out
 * of the extra SELECT+conditional-UPDATE, so a versioned descriptor silently degrades to a
 * plain last-writer-wins upsert - concurrent writers over a shared TCP/server database will
 * not see {@code OptimisticLockException}. Use MySQL/MariaDB, PostgreSQL or MongoDB when
 * concurrent writers must be guarded by {@code lock_version}.
 */
public class H2SqlStorage extends SqlStorage {

    public H2SqlStorage(SqlConfig config) {
        this(config, StorageLogConfig.defaults());
    }

    /**
     * Identity of an in-memory database, or {@code null} when this instance is file/TCP backed and
     * the URL derivation applies. An in-memory database lives inside one JVM: two storages on the
     * same {@code mem:} URL really do share it, while no other process can ever reach it - so the
     * identity is the URL scoped to this JVM, never a coordinate another process could match.
     */
    private final String inMemoryIdentity;

    public H2SqlStorage(SqlConfig config, StorageLogConfig logConfig) {
        super(config, logConfig, "h2");
        String url = config.jdbcUrl() == null ? "" : config.jdbcUrl().toLowerCase(Locale.ROOT);
        this.inMemoryIdentity = config.sharedIdentity() == null && url.startsWith("jdbc:h2:mem:")
                ? "h2-mem:" + BackendIdentities.jvmId() + ":" + url
                : null;
    }

    /**
     * H2 opts out of the version check, so it reports {@code false} where the SQL base reports
     * {@code true}: a versioned descriptor degrades to a plain last-writer-wins upsert here, even
     * in TCP/server mode with several writers.
     */
    @Override
    public boolean enforcesOptimisticLock() {
        return false;
    }

    /**
     * A {@code mem:} database is identified per JVM (see {@link #inMemoryIdentity}); a file or TCP
     * one falls back to the URL derivation every SQL dialect shares.
     */
    @Override
    public String backendIdentity() {
        return inMemoryIdentity != null ? inMemoryIdentity : super.backendIdentity();
    }

    /**
     * An in-memory database lives inside this JVM and no other process can reach it, so it is
     * machine-local; a file or TCP database falls back to the URL classification the SQL base does.
     * {@link #inMemoryIdentity} is already {@code null} when a shared identity is set, so that case
     * correctly defers to {@code super}.
     */
    @Override
    public boolean isMachineLocalIdentity() {
        return inMemoryIdentity != null || super.isMachineLocalIdentity();
    }

    /**
     * H2 uses ANSI double-quote for identifier quoting (same as PostgreSQL).
     * Overrides the base class backtick default so the {@code _schema_migrations}
     * table and its columns are quoted correctly.
     */
    @Override
    protected String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    protected <K, V> SqlRepository<K, V> createRepository(EntityDescriptor<K, V> descriptor) {
        return new H2SqlRepository<>(descriptor, getDataSource(), txConnection, storageLog());
    }

    // ------------------------------------------------------------------
    //  Inner repository - H2-native SQL dialect
    // ------------------------------------------------------------------

    private static final class H2SqlRepository<K, V> extends SqlRepository<K, V> {

        H2SqlRepository(EntityDescriptor<K, V> descriptor,
                        DataSource dataSource,
                        ThreadLocal<Connection> txConnection,
                        StorageLog log) {
            super(descriptor, dataSource, txConnection, log);
        }

        @Override
        protected String q(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        protected boolean supportsVersioning() {
            return false;
        }

        /** H2 indexes {@code TEXT} columns without any prefix length. */
        @Override
        protected String indexLengthFor(IndexHint hint) {
            return "";
        }

        /** H2 drops indexes by name without a table qualifier. */
        @Override
        protected void dropIndex(Connection conn, String indexName) throws java.sql.SQLException {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("DROP INDEX IF EXISTS " + q(indexName));
            }
        }

        @Override
        protected String dataColumnType() {
            return "TEXT";
        }

        @Override
        protected String sqlTypeFor(IndexHint hint) {
            // H2 1.4 maps TEXT to CLOB and cannot index BLOB/CLOB columns, so string
            // index columns must be VARCHAR (indexable at any length on both 1.x and 2.x).
            // The storage_data column stays TEXT - it is never indexed.
            if (hint.fieldType() == IndexHint.FieldType.STRING)
                return "VARCHAR";
            // H2 does not support DATETIME; its native type is TIMESTAMP.
            if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP)
                return "TIMESTAMP(3)";
            // Deliberately VARCHAR and not H2's native UUID: that type sorts by the two longs
            // signed, which is a different order from the canonical string every other backend
            // stores and compares.
            if (hint.fieldType() == IndexHint.FieldType.UUID)
                return "VARCHAR(36)";
            return super.sqlTypeFor(hint);
        }

        @Override
        protected String buildUpsertSql() {
            List<String> cols = allColumnsForWrite();
            StringBuilder sb = new StringBuilder("MERGE INTO ").append(q(tableName())).append(" (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(q(cols.get(i)));
            }
            sb.append(") KEY (").append(q(COL_KEY)).append(") VALUES (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('?');
            }
            sb.append(')');
            return sb.toString();
        }
    }
}
