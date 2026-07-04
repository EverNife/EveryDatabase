package br.com.finalcraft.everydatabase.modules.sql.postgresql;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.modules.sql.SqlRepository;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /** Origin id of the owning storage, stamped on NOTIFY payloads; {@code null} disables emitting. */
    private final String originId;

    public PostgreSqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                                ThreadLocal<Connection> txConnection, StorageLog log) {
        this(descriptor, dataSource, txConnection, log, null);
    }

    public PostgreSqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                                ThreadLocal<Connection> txConnection, StorageLog log, String originId) {
        super(descriptor, dataSource, txConnection, log);
        this.originId = originId;
    }

    // ------------------------------------------------------------------
    //  Change feed: emit a NOTIFY after each successful write
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> save(V entity) {
        return super.save(entity).thenApply(done -> {
            notifyChange(ChangeOp.SAVE, descriptor.keyExtractor().apply(entity), versionOf(entity));
            return done;
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        return super.saveAll(entities).thenApply(done -> {
            for (V entity : entities) {
                notifyChange(ChangeOp.SAVE, descriptor.keyExtractor().apply(entity), versionOf(entity));
            }
            return done;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        return super.delete(key).thenApply(existed -> {
            if (existed) {
                notifyChange(ChangeOp.DELETE, key, ChangeEvent.UNKNOWN_VERSION);
            }
            return existed;
        });
    }

    /**
     * Publishes a change on the {@code NOTIFY} channel. Skipped when {@code originId} is null
     * (the storage's change feed is not in use). A failure here never breaks the write it
     * follows; cache freshness self-heals.
     *
     * <p>Two emission paths, both piggybacking on PostgreSQL's native NOTIFY visibility:
     * <ul>
     *   <li><b>Inside a transaction</b> (the write ran on the {@code txConnection} and this
     *       method runs inline on the same thread): the NOTIFY is issued on that same
     *       connection, so PostgreSQL queues it and only delivers on COMMIT - and silently
     *       discards it on ROLLBACK. No phantom events for uncommitted or rolled-back writes.</li>
     *   <li><b>Autocommit</b> (no transaction in progress): a fresh pooled connection emits
     *       immediately, after the already-committed write.</li>
     * </ul>
     */
    private void notifyChange(ChangeOp op, K key, long version) {
        if (originId == null) {
            return;
        }
        String payload = PgChangePayload.encode(
            PAYLOAD_MAPPER, descriptor.collection(), key.toString(), op, version, originId);
        Connection tx = txConnection.get();
        if (tx != null) {
            // Do NOT close the transaction's connection here - only the statement.
            try (PreparedStatement ps = tx.prepareStatement("SELECT pg_notify(?, ?)")) {
                ps.setString(1, PgChangePayload.CHANNEL);
                ps.setString(2, payload);
                ps.execute();
            } catch (SQLException e) {
                log.emit(StorageOp.SAVE, StorageLogLevel.WARN,
                    b -> b.detail("pg_notify failed for '" + descriptor.collection() + "'").error(e));
            }
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, PgChangePayload.CHANNEL);
            ps.setString(2, payload);
            ps.execute();
        } catch (SQLException e) {
            log.emit(StorageOp.SAVE, StorageLogLevel.WARN,
                b -> b.detail("pg_notify failed for '" + descriptor.collection() + "'").error(e));
        }
    }

    /**
     * The entity's optimistic-lock version after the write, or {@code -1} when not versioned. Shared
     * with the other backends via {@link ChangeEvent#versionFor} (a never-persisted, still-{@code null}
     * version reads as {@code 0}).
     */
    private long versionOf(V entity) {
        return ChangeEvent.versionFor(descriptor.versionGetter(), entity);
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

    /**
     * PostgreSQL uses {@code TIMESTAMPTZ} (an absolute instant), so bind a {@link java.sql.Timestamp}
     * - the driver stores the instant tz-safely and there is no wall-clock ambiguity. (The MySQL/H2
     * base binds a UTC {@code LocalDateTime} because their columns carry no timezone.)
     */
    @Override
    protected Object toJdbcValue(Object value, IndexHint hint) {
        if (value == null) return null;
        if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP) {
            Long epoch = IndexValueExtractor.toEpochMilli(value);
            return epoch != null ? new java.sql.Timestamp(epoch) : null;
        }
        return value;
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
