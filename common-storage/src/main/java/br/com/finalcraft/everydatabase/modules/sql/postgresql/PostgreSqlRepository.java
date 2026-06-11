package br.com.finalcraft.everydatabase.modules.sql.postgresql;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.modules.sql.SqlRepository;
import br.com.finalcraft.everydatabase.query.IndexHint;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * PostgreSQL dialect of {@link SqlRepository}.
 *
 * <p>Differences from the MySQL default:
 * <ul>
 *   <li>Identifier quoting uses double-quote ({@code "name"}) instead of backtick.</li>
 *   <li>Data column uses {@code JSON} (plain text JSON, not JSONB).</li>
 *   <li>{@link #setDataParam} uses {@code setObject(..., Types.OTHER)} because PostgreSQL
 *       rejects binding a JSON column with {@code setString}.</li>
 *   <li>Upsert uses {@code INSERT ... ON CONFLICT (...) DO UPDATE SET} instead of
 *       {@code ON DUPLICATE KEY UPDATE}.</li>
 *   <li>{@code DOUBLE PRECISION} for double columns (PostgreSQL rejects {@code DOUBLE} alone).</li>
 *   <li>{@code TIMESTAMPTZ} for timestamp columns.</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class PostgreSqlRepository<K, V> extends SqlRepository<K, V> {

    public PostgreSqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                                ThreadLocal<Connection> txConnection, StorageLog log) {
        super(descriptor, dataSource, txConnection, log);
    }

    @Override
    protected String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    protected String dataColumnType() {
        return "JSON";
    }

    /**
     * PostgreSQL's type system rejects binding a {@code JSON} column via {@code setString}.
     * Using {@code setObject(slot, json, Types.OTHER)} lets the driver pass the value through
     * as-is, and PostgreSQL casts it to JSON on the server side.
     */
    @Override
    protected void setDataParam(PreparedStatement ps, int slot, String json) throws SQLException {
        ps.setObject(slot, json, Types.OTHER);
    }

    /** PostgreSQL indexes {@code TEXT} columns without any prefix length. */
    @Override
    protected String indexLengthFor(IndexHint hint) {
        return "";
    }

    /** PostgreSQL drops indexes by name without a table qualifier. */
    @Override
    protected void dropIndex(Connection conn, String indexName) throws SQLException {
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS " + q(indexName));
        }
    }

    @Override
    protected String sqlTypeFor(IndexHint hint) {
        // PostgreSQL rejects bare DOUBLE; use the SQL-standard keyword.
        if (hint.fieldType() == IndexHint.FieldType.DOUBLE)    return "DOUBLE PRECISION";
        // PostgreSQL native timestamp with timezone (8 bytes, UTC-normalised).
        if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP) return "TIMESTAMPTZ";
        return super.sqlTypeFor(hint);
    }

    @Override
    protected String buildUpsertSql() {
        List<String> cols = allColumnsForWrite();
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(q(tableName())).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(q(cols.get(i)));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        sb.append(") ON CONFLICT (").append(q(COL_KEY)).append(") DO UPDATE SET ");
        boolean first = true;
        for (String c : cols) {
            if (c.equals(COL_KEY)) continue;
            if (!first) sb.append(", ");
            sb.append(q(c)).append(" = EXCLUDED.").append(q(c));
            first = false;
        }
        return sb.toString();
    }
}
