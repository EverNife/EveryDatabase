package br.com.finalcraft.evernifecore.storage.modules.sql.postgresql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlRepository;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * PostgreSQL dialect of {@link SqlRepository}.
 *
 * <p>Differences from the MySQL default:
 * <ul>
 *   <li>Identifier quoting uses double-quote ({@code "name"}) instead of backtick.</li>
 *   <li>Data column uses {@code TEXT} instead of {@code MEDIUMTEXT}.</li>
 *   <li>Upsert uses {@code INSERT ... ON CONFLICT (...) DO UPDATE SET} instead of
 *       {@code ON DUPLICATE KEY UPDATE}.</li>
 *   <li>{@code DOUBLE PRECISION} for double columns (PostgreSQL rejects {@code DOUBLE} alone).</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class PostgreSqlRepository<K, V> extends SqlRepository<K, V> {

    public PostgreSqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                                ThreadLocal<Connection> txConnection) {
        super(descriptor, dataSource, txConnection);
    }

    @Override
    protected String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    protected String dataColumnType() {
        return "TEXT";
    }

    @Override
    protected String sqlTypeFor(IndexHint.FieldType type) {
        // PostgreSQL rejects bare DOUBLE; use the SQL-standard keyword.
        if (type == IndexHint.FieldType.DOUBLE)    return "DOUBLE PRECISION";
        // PostgreSQL native timestamp with timezone (8 bytes, UTC-normalised).
        if (type == IndexHint.FieldType.TIMESTAMP) return "TIMESTAMPTZ";
        return super.sqlTypeFor(type);
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
