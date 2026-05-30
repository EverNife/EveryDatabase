package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.StorageExecutors;
import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.codec.CodecException;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.IndexValueExtractor;
import br.com.finalcraft.evernifecore.storage.query.Query;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * SQL-backed {@link Repository} that stores entity data as a JSON blob,
 * with optional sibling columns ({@code _idx_<field>}) for declared
 * {@link IndexHint}s.
 *
 * <p>Table structure per collection:
 * <pre>
 * CREATE TABLE `{collection}` (
 *   `storage_key`  VARCHAR(255) NOT NULL,
 *   `storage_data` MEDIUMTEXT   NOT NULL,
 *   `_idx_type`    VARCHAR(255),         -- one per IndexHint
 *   ...
 *   PRIMARY KEY (`storage_key`),
 *   INDEX        (`_idx_type`)            -- one per IndexHint
 * );
 * </pre>
 *
 * <p>The key is {@code K.toString()}. The data column holds the codec output as UTF-8.
 * Each {@code _idx_*} column is populated in Java at {@code save()} time by extracting
 * the field from the entity's Jackson tree representation - no DB-side JSON functions
 * are required, so the same code path works on every SQL dialect.
 *
 * <p>Upsert uses MySQL/MariaDB syntax by default ({@code INSERT ... ON DUPLICATE KEY UPDATE}).
 * Override {@link #buildUpsertSql()} in a subclass for other dialects.
 *
 * <p>Identifier quoting uses backtick by default (MySQL/MariaDB). Override {@link #q(String)}
 * to return the dialect's quoting style (e.g. double-quote for PostgreSQL).
 *
 * <h3>Transaction semantics</h3>
 * When called from within an {@link SqlStorage#inTransaction} scope the
 * {@link #txConnection} ThreadLocal is set on the calling thread. In that case every
 * repository operation executes <em>synchronously on the calling thread</em> using the shared
 * transaction connection, so the ThreadLocal is visible throughout the entire work chain
 * and proper COMMIT / ROLLBACK is guaranteed. Outside a transaction, operations are
 * dispatched asynchronously to {@link FCScheduler}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class SqlRepository<K, V> implements Repository<K, V> {

    protected static final String COL_KEY  = "storage_key";
    protected static final String COL_DATA = "storage_data";

    protected final EntityDescriptor<K, V> descriptor;
    protected final DataSource dataSource;
    /** Non-null on the transaction thread when inside an {@link SqlStorage#inTransaction} scope. */
    protected final ThreadLocal<Connection> txConnection;

    /** Declared index hints in iteration order (preserves descriptor declaration order). */
    protected final List<IndexHint> indexes;
    /** {@code fieldPath} → declared {@link IndexHint}, for query dispatch. */
    protected final Map<String, IndexHint> hintsByPath;

    protected SqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                             ThreadLocal<Connection> txConnection) {
        this.descriptor   = descriptor;
        this.dataSource   = dataSource;
        this.txConnection = txConnection;
        this.indexes      = new ArrayList<>(descriptor.indexes());
        this.hintsByPath  = new HashMap<>();
        for (IndexHint hint : this.indexes) this.hintsByPath.put(hint.fieldPath(), hint);
    }

    // ------------------------------------------------------------------
    //  Dialect helpers
    // ------------------------------------------------------------------

    /**
     * Wraps an SQL identifier in the dialect's quoting character.
     * Default: MySQL/MariaDB backtick. Override for PostgreSQL/H2.
     */
    protected String q(String identifier) {
        return "`" + identifier + "`";
    }

    /** SQL column type for the storage_data column. Default: {@code MEDIUMTEXT} (MySQL). */
    protected String dataColumnType() {
        return "MEDIUMTEXT";
    }

    /** Maps an {@link IndexHint.FieldType} to a SQL column type. Default: portable choices. */
    protected String sqlTypeFor(IndexHint.FieldType type) {
        switch (type) {
            case STRING:    return "VARCHAR(255)";
            case INT:       return "INT";
            case LONG:      return "BIGINT";
            case DOUBLE:    return "DOUBLE";
            case BOOLEAN:   return "BOOLEAN";
            case TIMESTAMP: return "DATETIME(3)";   // MySQL/MariaDB native; override in dialects
            default: throw new IllegalArgumentException("Unknown FieldType: " + type);
        }
    }

    /**
     * Converts a value to the correct JDBC type for a given {@link IndexHint}.
     * For {@link IndexHint.FieldType#TIMESTAMP}: epoch-millis ({@code Long}) or
     * {@code Instant}/{@code LocalDateTime} → {@link java.sql.Timestamp}.
     * For all other types the value is returned as-is.
     */
    protected Object toJdbcValue(Object value, IndexHint hint) {
        if (value == null) return null;
        if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP) {
            Long epoch = IndexValueExtractor.toEpochMilli(value);
            return epoch != null ? new java.sql.Timestamp(epoch) : null;
        }
        return value;
    }

    // ------------------------------------------------------------------
    //  Table management
    // ------------------------------------------------------------------

    /**
     * Creates the table (idempotent) and ensures every declared {@code _idx_*} column and
     * its B-tree index exist.
     *
     * <p><b>Schema-drift handling:</b> {@code CREATE TABLE IF NOT EXISTS} is a no-op when the
     * table already exists. {@link #ensureIndexColumn} then checks each hint column via JDBC
     * metadata and issues {@code ALTER TABLE ADD COLUMN} for any column that is missing.
     * This covers the common case of adding a new {@link IndexHint} to an existing deployment
     * without a manual migration.
     *
     * <p><b>Backfill note:</b> newly added columns are initially {@code NULL} for pre-existing
     * rows. Queries on those rows will not match until the entities are re-saved (which populates
     * the column). A full backfill can be done via a {@code Migration} that calls
     * {@code repo.saveAll(repo.all().join().collect(...))} after this init step.
     */
    protected void createTableIfAbsent(Connection conn) throws SQLException {
        // --- Step 1: create base table (no-op if already exists) ---
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(q(tableName())).append(" (");
        sql.append(q(COL_KEY)).append(" VARCHAR(255) NOT NULL, ");
        sql.append(q(COL_DATA)).append(' ').append(dataColumnType()).append(" NOT NULL, ");
        for (IndexHint hint : indexes) {
            sql.append(q(hint.indexColumnName())).append(' ').append(sqlTypeFor(hint.fieldType())).append(", ");
        }
        sql.append("PRIMARY KEY (").append(q(COL_KEY)).append("))");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }

        // --- Step 2: add any missing _idx_* columns (schema-drift after new IndexHint added) ---
        for (IndexHint hint : indexes) {
            ensureIndexColumn(conn, hint);
        }

        // --- Step 3: create B-tree indexes (separate DDL - inline INDEX is MySQL-specific) ---
        for (IndexHint hint : indexes) {
            createIndexIfAbsent(conn, hint);
        }
    }

    /**
     * Adds the {@code _idx_*} column for {@code hint} if it does not yet exist in the table.
     *
     * <p>Uses {@link DatabaseMetaData#getColumns} for a portable existence check that works
     * on all supported dialects (MariaDB, PostgreSQL, H2) regardless of identifier-case rules.
     */
    protected void ensureIndexColumn(Connection conn, IndexHint hint) throws SQLException {
        if (indexColumnExists(conn, hint)) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName())
                + " ADD COLUMN " + q(hint.indexColumnName())
                + " " + sqlTypeFor(hint.fieldType()));
        }
    }

    /**
     * Returns {@code true} when the table already has the column that backs {@code hint}.
     *
     * <p>Queries {@link DatabaseMetaData#getColumns} with both the original table name and its
     * upper-case form so the check works on H2 (which may fold identifiers to upper-case in its
     * internal catalog) as well as on case-preserving databases like PostgreSQL and MariaDB.
     */
    private boolean indexColumnExists(Connection conn, IndexHint hint) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String colName = hint.indexColumnName();
        // Try original name first, then upper-case (H2 default-mode stores identifiers in UPPER).
        for (String tbl : new String[]{tableName(), tableName().toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getColumns(null, null, tbl, null)) {
                while (rs.next()) {
                    if (rs.getString("COLUMN_NAME").equalsIgnoreCase(colName)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Issues {@code CREATE [UNIQUE] INDEX IF NOT EXISTS} on the sibling column.
     * Works on PostgreSQL 9.5+, MySQL 8.0.29+, MariaDB 10.0+, H2.
     */
    protected void createIndexIfAbsent(Connection conn, IndexHint hint) throws SQLException {
        String name = "idx_" + tableName() + "_" + hint.fieldPath().replace('.', '_');
        String sql = "CREATE " + (hint.unique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS "
            + q(name) + " ON " + q(tableName())
            + " (" + q(hint.indexColumnName())
            + (hint.order() == IndexHint.Order.DESCENDING ? " DESC" : "") + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    protected String tableName() {
        return descriptor.collection();
    }

    // ------------------------------------------------------------------
    //  Upsert dialect - override for non-MySQL databases
    // ------------------------------------------------------------------

    /**
     * Builds the upsert SQL for this dialect.
     * Default: MySQL/MariaDB {@code ON DUPLICATE KEY UPDATE}.
     * The column list includes all declared {@code _idx_*} sibling columns.
     */
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
        sb.append(") ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String c : cols) {
            if (c.equals(COL_KEY)) continue; // never update the primary key
            if (!first) sb.append(", ");
            sb.append(q(c)).append(" = VALUES(").append(q(c)).append(")");
            first = false;
        }
        return sb.toString();
    }

    /** Ordered column list used in {@code INSERT (col1, col2, ...)} and {@code setX(i, ...)}. */
    protected List<String> allColumnsForWrite() {
        List<String> cols = new ArrayList<>(2 + indexes.size());
        cols.add(COL_KEY);
        cols.add(COL_DATA);
        for (IndexHint hint : indexes) cols.add(hint.indexColumnName());
        return cols;
    }

    // ------------------------------------------------------------------
    //  Connection dispatch helper
    // ------------------------------------------------------------------

    @FunctionalInterface
    interface SqlWork<T> {
        T execute(Connection conn) throws SQLException, CodecException;
    }

    <T> CompletableFuture<T> withConnection(SqlWork<T> work) {
        Connection tx = txConnection.get();
        if (tx != null) {
            try {
                return CompletableFuture.completedFuture(work.execute(tx));
            } catch (Exception e) {
                CompletableFuture<T> f = new CompletableFuture<>();
                f.completeExceptionally(e instanceof RuntimeException ? e
                    : new RuntimeException("SQL operation failed (tx)", e));
                return f;
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return work.execute(conn);
            } catch (SQLException | CodecException e) {
                throw new RuntimeException("SQL operation failed", e);
            }
        }, StorageExecutors.async());
    }

    // ------------------------------------------------------------------
    //  CRUD
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        String sql = "SELECT " + q(COL_DATA) + " FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " = ?";
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    byte[] data = rs.getString(1).getBytes(StandardCharsets.UTF_8);
                    return Optional.of(descriptor.codec().decode(data));
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<V>> findMany(Collection<K> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());
        List<K> keyList = new ArrayList<>(keys);
        String placeholders = repeat("?", keyList.size(), ",");
        String sql = "SELECT " + q(COL_DATA) + " FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " IN (" + placeholders + ")";
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < keyList.size(); i++) ps.setString(i + 1, keyList.get(i).toString());
                return readEntities(ps);
            }
        });
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        return withConnection(conn -> {
            byte[] data = descriptor.codec().encode(entity);
            try (PreparedStatement ps = conn.prepareStatement(buildUpsertSql())) {
                bindUpsertParameters(ps, key, entity, data);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(buildUpsertSql())) {
                for (V entity : entities) {
                    K key = descriptor.keyExtractor().apply(entity);
                    byte[] data = descriptor.codec().encode(entity);
                    bindUpsertParameters(ps, key, entity, data);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Binds {@code (storage_key, storage_data, _idx_a, _idx_b, ...)} parameters. */
    private void bindUpsertParameters(PreparedStatement ps, K key, V entity, byte[] data) throws SQLException {
        ps.setString(1, key.toString());
        ps.setString(2, new String(data, StandardCharsets.UTF_8));
        if (!indexes.isEmpty()) {
            JsonNode tree = IndexValueExtractor.toTree(entity);
            int slot = 3;
            for (IndexHint hint : indexes) {
                Object value = IndexValueExtractor.extract(tree, hint);
                ps.setObject(slot++, toJdbcValue(value, hint));
            }
        }
    }

    @Override
    public CompletableFuture<Boolean> delete(K key) {
        String sql = "DELETE FROM " + q(tableName()) + " WHERE " + q(COL_KEY) + " = ?";
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        String sql = "SELECT 1 FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " = ? LIMIT 1";
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }

    @Override
    public CompletableFuture<Long> count() {
        String sql = "SELECT COUNT(*) FROM " + q(tableName());
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        String sql = "SELECT " + q(COL_DATA) + " FROM " + q(tableName());
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                return readEntities(ps).stream();
            }
        });
    }

    // ------------------------------------------------------------------
    //  Index queries
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        return query(Query.eq(fieldPath, value));
    }

    @Override
    public CompletableFuture<List<V>> query(Query query) {
        // Build WHERE clause and parameter list.
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (int i = 0; i < query.conditions().size(); i++) {
            Query.Condition c = query.conditions().get(i);
            IndexHint hint = hintsByPath.get(c.fieldPath());
            if (hint == null) {
                throw new IllegalArgumentException(
                    "SQL: field '" + c.fieldPath() + "' is not indexed. "
                    + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
            }
            if (i > 0) where.append(" AND ");
            appendCondition(where, params, c, hint);
        }

        String sql = "SELECT " + q(COL_DATA) + " FROM " + q(tableName()) + " WHERE " + where;

        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                return readEntities(ps);
            }
        });
    }

    private void appendCondition(StringBuilder where, List<Object> params, Query.Condition c, IndexHint hint) {
        String column = q(hint.indexColumnName());
        switch (c.op()) {
            case EQ:
                where.append(column).append(" = ?");
                params.add(toJdbcValue(c.value(), hint));
                break;
            case IN:
                where.append(column).append(" IN (")
                    .append(repeat("?", c.inValues().size(), ","))
                    .append(')');
                for (Object v : c.inValues()) params.add(toJdbcValue(v, hint));
                break;
            case RANGE:
                Object from = toJdbcValue(c.rangeFrom(), hint);
                Object to   = toJdbcValue(c.rangeTo(),   hint);
                if (from != null && to != null) {
                    where.append(column).append(" BETWEEN ? AND ?");
                    params.add(from);
                    params.add(to);
                } else if (from != null) {
                    where.append(column).append(" >= ?");
                    params.add(from);
                } else if (to != null) {
                    where.append(column).append(" <= ?");
                    params.add(to);
                } else {
                    where.append(column).append(" IS NOT NULL");
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    //  Utility
    // ------------------------------------------------------------------

    private List<V> readEntities(PreparedStatement ps) throws SQLException {
        List<V> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] data = rs.getString(1).getBytes(StandardCharsets.UTF_8);
                try { result.add(descriptor.codec().decode(data)); }
                catch (CodecException ignored) {}
            }
        }
        return result;
    }

    private static String repeat(String token, int count, String separator) {
        StringBuilder sb = new StringBuilder(count * (token.length() + separator.length()));
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(separator);
            sb.append(token);
        }
        return sb.toString();
    }
}
