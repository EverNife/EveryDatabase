package br.com.finalcraft.evernifecore.storage.modules.sql.h2;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlRepository;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlStorage;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * H2-backed {@link br.com.finalcraft.evernifecore.storage.Storage} that works in all three
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
 */
public class H2SqlStorage extends SqlStorage {

    public H2SqlStorage(SqlConfig config) {
        super(config);
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
        return new H2SqlRepository<>(descriptor, getDataSource(), txConnection);
    }

    // ------------------------------------------------------------------
    //  Inner repository - H2-native SQL dialect
    // ------------------------------------------------------------------

    private static final class H2SqlRepository<K, V> extends SqlRepository<K, V> {

        H2SqlRepository(EntityDescriptor<K, V> descriptor,
                        DataSource dataSource,
                        ThreadLocal<Connection> txConnection) {
            super(descriptor, dataSource, txConnection);
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
            // H2 does not support DATETIME; its native type is TIMESTAMP.
            if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP)
                return "TIMESTAMP(3)";
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
