package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.StorageExecutors;
import br.com.finalcraft.everydatabase.StorageKeys;
import br.com.finalcraft.everydatabase.WriteMode;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.query.Cursor;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.IndexValueExtractor;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.query.QueryResultOrdering;
import br.com.finalcraft.everydatabase.query.ScanRow;
import br.com.finalcraft.everydatabase.query.Slice;
import br.com.finalcraft.everydatabase.query.Slices;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * SQL-backed {@link Repository} that stores entity data as a JSON blob,
 * with optional sibling columns ({@code _idx_<field>}) for declared
 * {@link IndexHint}s.
 *
 * <p>Table structure per collection (MySQL/MariaDB default dialect):
 * <pre>
 * CREATE TABLE `{collection}` (
 *   `storage_key`  VARCHAR(255) NOT NULL,
 *   `storage_data` JSON         NOT NULL,
 *   `_idx_type`    TEXT,                       -- one per IndexHint (column type per FieldType)
 *   ...
 *   PRIMARY KEY (`storage_key`)
 * );
 * CREATE INDEX ... ON `{collection}` (`_idx_type`(191));  -- one per IndexHint
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
 * dispatched asynchronously via {@link StorageExecutors}.
 *
 * @param <K> the key type
 * @param <V> the entity type
 */
public class SqlRepository<K, V> implements Repository<K, V> {

    protected static final String COL_KEY     = "storage_key";
    protected static final String COL_DATA    = "storage_data";
    protected static final String COL_VERSION = "lock_version";

    /**
     * Maximum number of bind parameters per {@code IN (...)} statement. {@code findMany}/{@code versions}
     * split larger key sets into chunks so a big cache-invalidation batch never trips PostgreSQL's
     * 65535-parameter cap or MySQL's {@code max_allowed_packet}. The common case (fewer keys) stays a
     * single statement.
     */
    private static final int MAX_IN_PARAMS = 1000;

    protected final EntityDescriptor<K, V> descriptor;
    protected final DataSource dataSource;
    /** Non-null on the transaction thread when inside an {@link SqlStorage#inTransaction} scope. */
    protected final ThreadLocal<Connection> txConnection;
    /** Shared log dispatcher - reads StorageLogConfig live from the parent Storage. */
    protected final StorageLog log;

    /** Declared index hints in iteration order (preserves descriptor declaration order). */
    protected final List<IndexHint> indexes;
    /** {@code fieldPath} → declared {@link IndexHint}, for query dispatch. */
    protected final Map<String, IndexHint> hintsByPath;

    protected SqlRepository(EntityDescriptor<K, V> descriptor, DataSource dataSource,
                             ThreadLocal<Connection> txConnection, StorageLog log) {
        this.descriptor   = descriptor;
        this.dataSource   = dataSource;
        this.txConnection = txConnection;
        this.log          = log;
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

    /** SQL column type for the storage_data column. Default: {@code JSON} (MySQL/MariaDB). */
    protected String dataColumnType() {
        return "JSON";
    }

    /**
     * Maps an {@link IndexHint} to a SQL column type for the backing index column.
     * Default: portable choices for MySQL/MariaDB.
     */
    protected String sqlTypeFor(IndexHint hint) {
        switch (hint.fieldType()) {
            case STRING:    return "TEXT";
            case INT:       return "INT";
            case LONG:      return "BIGINT";
            case DOUBLE:    return "DOUBLE";
            case BOOLEAN:   return "BOOLEAN";
            case TIMESTAMP: return "DATETIME(3)";   // MySQL/MariaDB native; override in dialects
            case UUID:      return "CHAR(36)";      // the canonical form, fixed width so it indexes whole
            default: throw new IllegalArgumentException("Unknown FieldType: " + hint.fieldType());
        }
    }

    /**
     * Returns the index key length suffix (e.g. {@code "(191)"}) to append to the column name
     * inside a {@code CREATE INDEX} statement for the given hint.
     *
     * <p>MySQL/MariaDB cannot index {@code TEXT} columns without declaring an explicit prefix
     * length. The default prefix for STRING hints is {@code 191} characters, derived from the
     * InnoDB legacy index key size limit:
     * <ul>
     *   <li>InnoDB legacy limit (Compact/Redundant row format): <b>767 bytes</b></li>
     *   <li>utf8mb4 encoding: up to <b>4 bytes per character</b></li>
     *   <li>191 × 4 = 764 bytes — the largest multiple of 4 that fits under 767</li>
     * </ul>
     * This value is conservative and works on all MySQL/MariaDB versions. Servers using
     * {@code ROW_FORMAT=DYNAMIC} (the default since MySQL 5.7.7 / MariaDB 10.2) have a
     * 3072-byte limit, which would allow up to 768 chars — but 191 is always safe.
     *
     * <p>All other field types (INT, BIGINT, DOUBLE, BOOLEAN, DATETIME) use fixed-size
     * column types and need no prefix.
     *
     * <p>Override and return {@code ""} for dialects that support indexing {@code TEXT} directly
     * without a prefix (PostgreSQL, H2).
     */
    protected String indexLengthFor(IndexHint hint) {
        return hint.fieldType() == IndexHint.FieldType.STRING ? "(191)" : "";
    }

    /**
     * Binds the JSON string for the {@link #COL_DATA} column to the prepared statement.
     *
     * <p>Default: {@code setString} (works for MySQL/MariaDB {@code JSON} columns).
     * Override for PostgreSQL, which requires {@code setObject(slot, json, Types.OTHER)}
     * to satisfy its type system.
     */
    protected void setDataParam(PreparedStatement ps, int slot, String json) throws SQLException {
        ps.setString(slot, json);
    }

    /**
     * Converts a value to the correct JDBC bind type for a given {@link IndexHint}.
     *
     * <p>For {@link IndexHint.FieldType#TIMESTAMP} (MySQL/MariaDB {@code DATETIME(3)}, H2
     * {@code TIMESTAMP(3)} - neither carries a timezone) the epoch-millis are bound as a <b>UTC</b>
     * {@link LocalDateTime}. A {@code LocalDateTime} is written as a literal wall-clock with no
     * driver timezone conversion, so the stored value is always the UTC wall-clock regardless of the
     * JVM's default timezone - two processes in different zones then agree on range comparisons and
     * the column reads as human-readable UTC. (PostgreSQL uses {@code TIMESTAMPTZ} and overrides this
     * to bind an absolute {@link java.sql.Timestamp}.) All other types are returned as-is.
     */
    protected Object toJdbcValue(Object value, IndexHint hint) {
        if (value == null) return null;
        if (hint.fieldType() == IndexHint.FieldType.TIMESTAMP) {
            Long epoch = IndexValueExtractor.toEpochMilli(value);
            return epoch != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneOffset.UTC)
                : null;
        }
        return value;
    }

    /**
     * Binds a <b>query parameter</b>: coerces it to the hint's Java type first, then to the
     * dialect's JDBC type. The extra step is what a caller-supplied value needs and a stored one
     * does not - a call site may hold a {@code Long} for an INT index or a {@link java.util.UUID}
     * for a UUID one, while the write path's values come straight out of
     * {@link IndexValueExtractor#extract} already typed.
     */
    private Object toJdbcParam(Object value, IndexHint hint) {
        return toJdbcValue(IndexValueExtractor.normalizeQueryValue(value, hint), hint);
    }

    // ------------------------------------------------------------------
    //  Table management
    // ------------------------------------------------------------------

    /**
     * Creates the table (idempotent) and reconciles its {@code _idx_*} columns and B-tree
     * indexes with the descriptor's declared {@link IndexHint}s on every call.
     *
     * <ol>
     *   <li>{@code CREATE TABLE IF NOT EXISTS} - no-op when the table already exists.</li>
     *   <li>Add any declared {@code _idx_*} column that is missing ({@link #ensureIndexColumn}) -
     *       covers adding a new {@link IndexHint} to an existing deployment.</li>
     *   <li>Create the B-tree index for every declared hint ({@link #createIndexIfAbsent}).</li>
     *   <li><b>Enforcement:</b> drop every {@code _idx_*} index and column that is no longer
     *       declared ({@link #dropUndeclaredIndexes}).</li>
     *   <li><b>Auto-populate:</b> backfill the columns added in step 2 from the existing rows'
     *       stored JSON ({@link #backfillIndexColumns}), so queries on a freshly added index
     *       return correct results without waiting for each entity to be re-saved.</li>
     * </ol>
     */
    protected void createTableIfAbsent(Connection conn) throws SQLException {
        long reconcileStart = System.currentTimeMillis();

        // --- Step 1: create base table (no-op if already exists) ---
        boolean logReconcile = log.isEnabled(StorageOp.INDEX_RECONCILE, StorageLogLevel.INFO)
            || log.isEnabled(StorageOp.INDEX_RECONCILE, StorageLogLevel.DEBUG);

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(q(tableName())).append(" (");
        sql.append(q(COL_KEY)).append(" VARCHAR(255) NOT NULL, ");
        sql.append(q(COL_DATA)).append(' ').append(dataColumnType()).append(" NOT NULL, ");

        if (versioningActive()) {
            // Extra column only when this dialect actually enforces optimistic locking
            sql.append(q(COL_VERSION)).append(" BIGINT NOT NULL DEFAULT 0, ");
        }

        for (IndexHint hint : indexes) {
            sql.append(q(hint.indexColumnName())).append(' ').append(sqlTypeFor(hint)).append(", ");
        }

        sql.append("PRIMARY KEY (").append(q(COL_KEY)).append("))");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }

        // --- Step 2: add any missing _idx_* columns, remembering which were actually added ---
        List<IndexHint> addedHints = new ArrayList<>();
        for (IndexHint hint : indexes) {
            if (ensureIndexColumn(conn, hint)) {
                addedHints.add(hint);
                log.columnAdded(tableName(), hint);
            }
        }

        // --- Step 3: create B-tree indexes (separate DDL - inline INDEX is MySQL-specific) ---
        for (IndexHint hint : indexes) {
            // createIndexIfAbsent uses IF NOT EXISTS - we infer "new" from addedHints
            createIndexIfAbsent(conn, hint);
        }

        // Log newly created indexes (those whose column was just added)
        for (IndexHint hint : addedHints) {
            log.indexCreated(tableName(), hint);
        }

        // --- Step 4: enforcement - drop _idx_* indexes/columns no longer declared ---
        List<String> droppedColumns = dropUndeclaredIndexes(conn);
        for (String col : droppedColumns) {
            log.indexDropped(tableName(), col);
        }

        // --- Step 5: auto-populate freshly added columns from the existing rows ---
        long backfilledRows = 0L;
        if (!addedHints.isEmpty()) {
            backfilledRows = backfillIndexColumns(conn, addedHints);
        }

        // --- Reconcile summary ---
        if (logReconcile) {
            List<String> createdFields = new ArrayList<>(addedHints.size());
            for (IndexHint h : addedHints) createdFields.add(h.fieldPath());
            long elapsed = System.currentTimeMillis() - reconcileStart;
            log.reconcileSummary(tableName(), createdFields, droppedColumns, backfilledRows, elapsed);
        }
    }

    /**
     * Adds the {@code _idx_*} column for {@code hint} if it does not yet exist in the table.
     *
     * <p>Uses {@link DatabaseMetaData#getColumns} for a portable existence check that works
     * on all supported dialects (MariaDB, PostgreSQL, H2) regardless of identifier-case rules.
     *
     * @return {@code true} when the column was actually added (so the caller can backfill it),
     *         {@code false} when it already existed.
     */
    protected boolean ensureIndexColumn(Connection conn, IndexHint hint) throws SQLException {
        if (indexColumnExists(conn, hint)) return false;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName())
                + " ADD COLUMN " + q(hint.indexColumnName())
                + " " + sqlTypeFor(hint));
            return true;
        } catch (SQLException e) {
            // Two instances starting up together both see the column absent and both ALTER (there is
            // no portable ADD COLUMN IF NOT EXISTS on MySQL). If it is now present, the other instance
            // won the race - absorb it and let that instance own the backfill; otherwise it is a real error.
            if (columnExists(conn, hint.indexColumnName())) return false;
            throw e;
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
        return columnExists(conn, hint.indexColumnName());
    }

    /**
     * Portable existence check for a column by name, tolerant of identifier-case rules (tries the
     * original and the upper-case table name, and compares column names case-insensitively). Used both
     * before an {@code ADD COLUMN} and to absorb a concurrent-startup ALTER race.
     */
    private boolean columnExists(Connection conn, String colName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
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
     * Issues {@code CREATE INDEX IF NOT EXISTS} on the sibling column.
     * Works on PostgreSQL 9.5+, MySQL 8.0.29+, MariaDB 10.0+, H2.
     */
    protected void createIndexIfAbsent(Connection conn, IndexHint hint) throws SQLException {
        String name = "idx_" + tableName() + "_" + hint.fieldPath().replace('.', '_');
        String sql = "CREATE INDEX IF NOT EXISTS "
            + q(name) + " ON " + q(tableName())
            + " (" + q(hint.indexColumnName()) + indexLengthFor(hint)
            + (hint.order() == IndexHint.Order.DESCENDING ? " DESC" : "") + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    //  Index enforcement (drop) + auto-populate (backfill)
    // ------------------------------------------------------------------

    /**
     * Drops every {@code _idx_*} index and its backing column that is no longer declared on the
     * descriptor. The complement of {@link #createIndexIfAbsent}/{@link #ensureIndexColumn}:
     * together they keep the persisted index set exactly matching the declared {@link IndexHint}s.
     *
     * <p>Indexes are dropped first (via {@link #dropIndex}) so the subsequent {@code DROP COLUMN}
     * cannot fail on databases that refuse to drop a still-indexed column.
     *
     * @return list of column names that were dropped (for logging)
     */
    protected List<String> dropUndeclaredIndexes(Connection conn) throws SQLException {
        Set<String> declared = new HashSet<>();
        for (IndexHint hint : indexes) declared.add(hint.indexColumnName().toLowerCase(Locale.ROOT));

        List<String> droppedColumns = new ArrayList<>();

        // Drop indexes backed by an _idx_ column that is no longer declared.
        for (Map.Entry<String, String> entry : existingIndexColumns(conn).entrySet()) {
            if (!declared.contains(entry.getValue().toLowerCase(Locale.ROOT))) {
                dropIndex(conn, entry.getKey());
            }
        }
        // Drop the now-orphaned _idx_ columns themselves.
        for (String column : existingIndexColumnNames(conn)) {
            if (!declared.contains(column.toLowerCase(Locale.ROOT))) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE " + q(tableName()) + " DROP COLUMN " + q(column));
                    droppedColumns.add(column);
                } catch (SQLException e) {
                    // A concurrent instance may have dropped the same orphan column first; if it is
                    // already gone, absorb the race - otherwise the failure is real.
                    if (columnExists(conn, column)) throw e;
                }
            }
        }
        return droppedColumns;
    }

    /**
     * Drops an index by its exact (metadata-reported) name. Default dialect (MySQL/MariaDB)
     * requires the {@code ON <table>} qualifier; PostgreSQL and H2 override to omit it.
     */
    protected void dropIndex(Connection conn, String indexName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX " + q(indexName) + " ON " + q(tableName()));
        }
    }

    /**
     * Returns {@code indexName -> backing column} for every index whose column is an
     * {@code _idx_*} sibling column. The primary-key index (on {@link #COL_KEY}) is skipped.
     */
    protected Map<String, String> existingIndexColumns(Connection conn) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        DatabaseMetaData meta = conn.getMetaData();
        for (String tbl : new String[]{tableName(), tableName().toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getIndexInfo(null, null, tbl, false, false)) {
                while (rs.next()) {
                    String idxName = rs.getString("INDEX_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    if (idxName == null || colName == null) continue;
                    if (colName.toLowerCase(Locale.ROOT).startsWith("_idx_")) {
                        result.put(idxName, colName);
                    }
                }
            }
            if (!result.isEmpty()) break;
        }
        return result;
    }

    /** Returns the names of all {@code _idx_*} columns currently present on the table. */
    protected List<String> existingIndexColumnNames(Connection conn) throws SQLException {
        List<String> result = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        for (String tbl : new String[]{tableName(), tableName().toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getColumns(null, null, tbl, null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col == null || !col.toLowerCase(Locale.ROOT).startsWith("_idx_")) continue;
                    boolean dup = false;
                    for (String seen : result) if (seen.equalsIgnoreCase(col)) { dup = true; break; }
                    if (!dup) result.add(col);
                }
            }
            if (!result.isEmpty()) break;
        }
        return result;
    }

    /**
     * Auto-populates freshly added {@code _idx_*} columns for all pre-existing rows.
     *
     * <p>Reads each row's stored JSON, extracts the index value via the same
     * {@link IndexValueExtractor} used at {@code save()} time, and writes it into the new
     * column(s). Only the columns in {@code newHints} are touched; existing index columns are
     * left as-is. Rows whose JSON cannot be decoded are skipped with a WARN log entry
     * (consistent with {@link #readEntities}).
     *
     * @return number of rows actually updated (for progress / reconcile summary)
     */
    protected long backfillIndexColumns(Connection conn, List<IndexHint> newHints) throws SQLException {
        // Count total rows for progress tracking (only when progress logging is useful)
        long totalRows = 0L;
        StorageLog.ProgressTracker tracker = null;
        if (log.isEnabled(StorageOp.INDEX_BACKFILL, StorageLogLevel.DEBUG)) {
            String countSql = "SELECT COUNT(*) FROM " + q(tableName());
            try (Statement cs = conn.createStatement(); ResultSet cr = cs.executeQuery(countSql)) {
                if (cr.next()) totalRows = cr.getLong(1);
            }
            tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, tableName());
        }

        StringBuilder update = new StringBuilder("UPDATE ").append(q(tableName())).append(" SET ");
        for (int i = 0; i < newHints.size(); i++) {
            if (i > 0) update.append(", ");
            update.append(q(newHints.get(i).indexColumnName())).append(" = ?");
        }
        update.append(" WHERE ").append(q(COL_KEY)).append(" = ?");

        String select = "SELECT " + q(COL_KEY) + ", " + q(COL_DATA) + " FROM " + q(tableName());

        long batched = 0L;
        try (Statement selectStmt = conn.createStatement();
             ResultSet rs = selectStmt.executeQuery(select);
             PreparedStatement ps = conn.prepareStatement(update.toString())) {
            while (rs.next()) {
                String key  = rs.getString(1);
                String json = rs.getString(2);
                V entity;
                try {
                    entity = descriptor.codec().decode(json.getBytes(StandardCharsets.UTF_8));
                } catch (CodecException e) {
                    log.skippedCorruptedRow(tableName(), key, e);
                    continue; // skip undecodable rows
                }
                JsonNode tree = IndexValueExtractor.toTree(entity, descriptor.codec());
                int slot = 1;
                for (IndexHint hint : newHints) {
                    ps.setObject(slot++, toJdbcValue(IndexValueExtractor.extract(tree, hint), hint));
                }
                ps.setString(slot, key);
                ps.addBatch();
                batched++;

                if (tracker != null) tracker.tick(batched, totalRows);
            }
            if (batched > 0) ps.executeBatch();
        }

        if (tracker != null) tracker.finish(batched);
        return batched;
    }

    protected String tableName() {
        return descriptor.collection();
    }

    // ------------------------------------------------------------------
    //  Upsert dialect - override for non-MySQL databases
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} when this SQL dialect enforces optimistic locking.
     * Override and return {@code false} for a dialect that opts out of the extra
     * SELECT+conditional UPDATE entirely (e.g. H2) - regardless of deployment mode.
     * When {@code false}, save() falls through to the plain upsert path regardless of
     * whether the descriptor declares versioning.
     */
    protected boolean supportsVersioning() {
        return true;
    }

    /**
     * Single gate for "optimistic locking is in effect": the descriptor opted in AND this
     * dialect enforces it. Everything versioning touches (the {@code lock_version} table
     * column, the save/saveAll dispatch) must use this same gate - that alignment is what
     * lets a versioned descriptor on a non-versioning dialect (H2) degrade cleanly to
     * plain upsert instead of producing a column/parameter mismatch.
     */
    protected final boolean versioningActive() {
        return descriptor.isVersioned() && supportsVersioning();
    }

    /** Cached result of {@link #buildUpsertSql()} - the column list is fixed after construction. */
    private volatile String upsertSqlCache;

    /** Returns the (lazily cached) upsert SQL. */
    private String upsertSql() {
        String sql = upsertSqlCache;
        if (sql == null) upsertSqlCache = sql = buildUpsertSql();
        return sql;
    }

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

    /**
     * Ordered column list used in {@code INSERT (col1, col2, ...)} and {@code setX(i, ...)}.
     * Must stay aligned with {@link #bindUpsertParameters}: key, data, then index columns.
     * {@code lock_version} is deliberately absent - the plain upsert never runs when
     * {@link #versioningActive()} (the versioned path has its own INSERT/UPDATE SQL), and
     * on non-versioning dialects the column does not exist.
     */
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

    /**
     * Runs {@code work} on a connection - the shared transaction connection when inside an
     * {@link SqlStorage#inTransaction} scope (synchronously), otherwise a pooled connection on the
     * async executor. A failure is wrapped and raised on the ERROR log floor for {@code op} via
     * {@link #reportSqlFailure}, so a CRUD failure is always visible even when its READ/WRITE topic
     * is muted - matching the other backends. An {@link OptimisticLockException} is expected control
     * flow (already WARN-logged at the conflict site) and is passed through without an ERROR event.
     */
    <T> CompletableFuture<T> withConnection(StorageOp op, SqlWork<T> work) {
        Connection tx = txConnection.get();
        if (tx != null) {
            try {
                return CompletableFuture.completedFuture(work.execute(tx));
            } catch (Exception e) {
                CompletableFuture<T> f = new CompletableFuture<>();
                f.completeExceptionally(reportSqlFailure(op, e, " (tx)"));
                return f;
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return work.execute(conn);
            } catch (Exception e) {
                throw reportSqlFailure(op, e, "");
            }
        }, StorageExecutors.get());
    }

    /**
     * Wraps a SQL failure into a {@link RuntimeException} (preserving one that is already unchecked)
     * and raises the ERROR-floor event for {@code op}. An {@link OptimisticLockException} anywhere in
     * the cause chain is returned untouched - it is expected control flow and is not an ERROR.
     */
    private RuntimeException reportSqlFailure(StorageOp op, Exception e, String context) {
        RuntimeException wrapped = e instanceof RuntimeException
            ? (RuntimeException) e
            : new RuntimeException("SQL operation failed" + context, e);
        if (isOptimisticLock(wrapped)) {
            return wrapped;
        }
        return log.errored(op, tableName(), wrapped);
    }

    private static boolean isOptimisticLock(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  CRUD
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> find(K key) {
        String sql = "SELECT " + q(COL_DATA) + " FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " = ?";
        return withConnection(StorageOp.FIND, conn -> {
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
        return withConnection(StorageOp.FIND_MANY, conn -> {
            List<V> result = new ArrayList<>(keyList.size());
            for (int start = 0; start < keyList.size(); start += MAX_IN_PARAMS) {
                List<K> chunk = keyList.subList(start, Math.min(start + MAX_IN_PARAMS, keyList.size()));
                String sql = "SELECT " + q(COL_KEY) + ", " + q(COL_DATA) + " FROM " + q(tableName())
                    + " WHERE " + q(COL_KEY) + " IN (" + repeat("?", chunk.size(), ",") + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i).toString());
                    result.addAll(readEntities(ps));
                }
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Map<K, Long>> versions(Collection<K> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyMap());
        List<K> keyList = new ArrayList<>(keys);
        // H2 (and non-versioned descriptors) have no lock_version column - SELECT a literal 0 so the
        // result still reports existence (deletes detectable) even though updates are not.
        String versionExpr = versioningActive() ? q(COL_VERSION) : "0";
        return withConnection(StorageOp.FIND_MANY, conn -> {
            Map<String, K> byString = new HashMap<>();
            for (K k : keyList) byString.put(k.toString(), k);
            Map<K, Long> result = new HashMap<>();
            for (int start = 0; start < keyList.size(); start += MAX_IN_PARAMS) {
                List<K> chunk = keyList.subList(start, Math.min(start + MAX_IN_PARAMS, keyList.size()));
                String sql = "SELECT " + q(COL_KEY) + ", " + versionExpr + " FROM " + q(tableName())
                    + " WHERE " + q(COL_KEY) + " IN (" + repeat("?", chunk.size(), ",") + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            K key = byString.get(rs.getString(1));
                            if (key != null) result.put(key, rs.getLong(2));
                        }
                    }
                }
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Void> save(V entity) {
        K key;
        try {
            key = descriptor.keyExtractor().apply(entity);
        } catch (RuntimeException e) {
            return StorageKeys.failedFuture(e);
        }
        CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, tableName());
        if (reject != null) return reject;
        if (versioningActive()) {
            return saveVersioned(entity);
        }
        return withConnection(StorageOp.SAVE, conn -> {
            byte[] data = descriptor.codec().encode(entity);
            try (PreparedStatement ps = conn.prepareStatement(upsertSql())) {
                bindUpsertParameters(ps, key, entity, data);
                ps.executeUpdate();
            }
            log.saved(tableName(), key, entity);
            return null;
        });
    }

    /**
     * Versioned save: SELECT current lock_version -> INSERT v=0 or UPDATE WHERE lock_version=expected.
     * All steps run inside a single transaction so the check-then-act is atomic.
     *
     * <p>IMPORTANT: the version is applied to the entity (via the setter) BEFORE encoding, so that
     * the JSON blob stored in {@code storage_data} always reflects the correct version. On read-back,
     * {@code find()} decodes the blob which already carries the right version - no extra column read
     * is needed.
     */
    private CompletableFuture<Void> saveVersioned(V entity) {
        K key = descriptor.keyExtractor().apply(entity);
        Long boxedVersion = descriptor.versionGetter().apply(entity);
        long incomingVersion = boxedVersion != null ? boxedVersion : 0L;
        return withConnection(StorageOp.SAVE, conn -> {
            boolean autoCommit = conn.getAutoCommit();
            if (autoCommit) conn.setAutoCommit(false);
            try {
                // 1. Read current version (if any) for this key.
                Long dbVersion = selectVersion(conn, key);

                if (dbVersion == null) {
                    // Row is absent: INSERT with lock_version = 0.
                    // Set the version on the entity first so the JSON blob is correct.
                    descriptor.versionSetter().accept(entity, 0L);
                    byte[] data = descriptor.codec().encode(entity);
                    String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    insertVersioned(conn, key, dataStr, entity, 0L);
                } else if (dbVersion == incomingVersion) {
                    // Versions agree: apply the new version to the entity first, then encode.
                    long newVersion = incomingVersion + 1;
                    descriptor.versionSetter().accept(entity, newVersion);
                    byte[] data = descriptor.codec().encode(entity);
                    String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    // Attempt conditional UPDATE (expects old version in WHERE clause).
                    int rows = updateVersioned(conn, key, dataStr, entity, incomingVersion);
                    if (rows == 0) {
                        // Another writer updated between our SELECT and UPDATE; undo setter.
                        descriptor.versionSetter().accept(entity, incomingVersion);
                        if (autoCommit) conn.rollback();
                        // Report the version the racing writer actually left, not our pre-race
                        // expectation (which equalled incomingVersion). After the rollback, a fresh
                        // SELECT on the autocommit path reads the committed value; -1 means the row was
                        // deleted in the race. Inside an outer transaction we can only report the
                        // pre-race value (the tx snapshot may not see the other writer's commit).
                        long actualVersion = dbVersion;
                        if (autoCommit) {
                            Long current = selectVersion(conn, key);
                            actualVersion = current != null ? current : -1L;
                        }
                        log.optimisticLockConflict(tableName(), key, incomingVersion, actualVersion);
                        throw new OptimisticLockException(
                            descriptor.type(), key, incomingVersion, actualVersion);
                    }
                } else {
                    // In-memory version differs from DB version before we even try to write.
                    if (autoCommit) conn.rollback();
                    log.optimisticLockConflict(tableName(), key, incomingVersion, dbVersion);
                    throw new OptimisticLockException(
                        descriptor.type(), key, incomingVersion, dbVersion);
                }

                if (autoCommit) conn.commit();
                log.saved(tableName(), key, entity);
                return null;
            } catch (OptimisticLockException ole) {
                if (autoCommit) { try { conn.rollback(); } catch (SQLException ignored) {} }
                throw ole;
            } catch (Exception e) {
                if (autoCommit) { try { conn.rollback(); } catch (SQLException ignored) {} }
                throw e;
            } finally {
                if (autoCommit) conn.setAutoCommit(true);
            }
        });
    }

    /** Reads the {@code lock_version} for {@code key}, or {@code null} if the row is absent. */
    private Long selectVersion(Connection conn, K key) throws SQLException {
        String sql = "SELECT " + q(COL_VERSION) + " FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getLong(1);
            }
        }
    }

    /** Inserts a new versioned row with the given lock_version. */
    private void insertVersioned(Connection conn, K key, String dataStr,
                                 V entity, long lockVersion) throws SQLException {
        // Columns: storage_key, storage_data, lock_version, _idx_* ...
        int colCount = 3 + indexes.size();
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(q(tableName())).append(" (");
        sb.append(q(COL_KEY)).append(", ").append(q(COL_DATA)).append(", ").append(q(COL_VERSION));
        for (IndexHint hint : indexes) sb.append(", ").append(q(hint.indexColumnName()));
        sb.append(") VALUES (");
        for (int i = 0; i < colCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        sb.append(')');

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int slot = 1;
            ps.setString(slot++, key.toString());
            setDataParam(ps, slot++, dataStr);
            ps.setLong(slot++, lockVersion);
            if (!indexes.isEmpty()) {
                JsonNode tree = IndexValueExtractor.toTree(entity, descriptor.codec());
                for (IndexHint hint : indexes) {
                    Object value = IndexValueExtractor.extract(tree, hint);
                    ps.setObject(slot++, toJdbcValue(value, hint));
                }
            }
            ps.executeUpdate();
        }
    }

    /**
     * Issues {@code UPDATE ... SET storage_data=?, lock_version=lock_version+1
     * WHERE storage_key=? AND lock_version=?} and returns affected row count.
     */
    private int updateVersioned(Connection conn, K key, String dataStr,
                                V entity, long expectedVersion) throws SQLException {
        StringBuilder sb = new StringBuilder("UPDATE ").append(q(tableName())).append(" SET ");
        sb.append(q(COL_DATA)).append(" = ?, ");
        sb.append(q(COL_VERSION)).append(" = ").append(q(COL_VERSION)).append(" + 1");

        // Update _idx_* columns too.
        if (!indexes.isEmpty()) {
            for (IndexHint hint : indexes) {
                sb.append(", ").append(q(hint.indexColumnName())).append(" = ?");
            }
        }
        sb.append(" WHERE ").append(q(COL_KEY)).append(" = ? AND ")
          .append(q(COL_VERSION)).append(" = ?");

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int slot = 1;
            setDataParam(ps, slot++, dataStr);
            if (!indexes.isEmpty()) {
                JsonNode tree = IndexValueExtractor.toTree(entity, descriptor.codec());
                for (IndexHint hint : indexes) {
                    Object value = IndexValueExtractor.extract(tree, hint);
                    ps.setObject(slot++, toJdbcValue(value, hint));
                }
            }
            ps.setString(slot++, key.toString());
            ps.setLong(slot, expectedVersion);
            return ps.executeUpdate();
        }
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities) {
        if (entities.isEmpty()) return CompletableFuture.completedFuture(null);

        for (V entity : entities) {
            K key;
            try {
                key = descriptor.keyExtractor().apply(entity);
            } catch (RuntimeException e) {
                return StorageKeys.failedFuture(e);
            }
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, tableName());
            if (reject != null) return reject;
        }

        if (versioningActive()) {
            // For versioned descriptors: loop save() per entity within a single connection
            // to ensure each entity's optimistic lock check is atomic.
            return withConnection(StorageOp.SAVE_BATCH, conn -> {
                boolean autoCommit = conn.getAutoCommit();
                if (autoCommit) conn.setAutoCommit(false);
                try {
                    for (V entity : entities) {
                        saveVersionedOnConn(conn, entity);
                    }
                    if (autoCommit) conn.commit();
                    return null;
                } catch (Exception e) {
                    if (autoCommit) { try { conn.rollback(); } catch (SQLException ignored) {} }
                    throw e;
                } finally {
                    if (autoCommit) conn.setAutoCommit(true);
                }
            });
        }

        long startMs = System.currentTimeMillis();
        long count = entities.size();
        return withConnection(StorageOp.SAVE_BATCH, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(upsertSql())) {
                for (V entity : entities) {
                    K key = descriptor.keyExtractor().apply(entity);
                    byte[] data = descriptor.codec().encode(entity);
                    bindUpsertParameters(ps, key, entity, data);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.savedBatch(tableName(), count, System.currentTimeMillis() - startMs);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<V> entities, WriteMode mode) {
        if (mode == null || mode == WriteMode.UPSERT) {
            return saveAll(entities);
        }
        if (entities.isEmpty()) return CompletableFuture.completedFuture(null);
        for (V entity : entities) {
            K key;
            try {
                key = descriptor.keyExtractor().apply(entity);
            } catch (RuntimeException e) {
                return StorageKeys.failedFuture(e);
            }
            CompletableFuture<Void> reject = StorageKeys.rejectIfTooLong(key, tableName());
            if (reject != null) return reject;
        }

        if (versioningActive()) {
            // Each entity's guarded update is atomic on the shared connection; an absent row is a no-op
            // (never an INSERT) and a version mismatch throws OptimisticLockException.
            return withConnection(StorageOp.SAVE_BATCH, conn -> {
                boolean autoCommit = conn.getAutoCommit();
                if (autoCommit) conn.setAutoCommit(false);
                try {
                    for (V entity : entities) {
                        updateOnlyVersionedOnConn(conn, entity);
                    }
                    if (autoCommit) conn.commit();
                    return null;
                } catch (Exception e) {
                    if (autoCommit) { try { conn.rollback(); } catch (SQLException ignored) {} }
                    throw e;
                } finally {
                    if (autoCommit) conn.setAutoCommit(true);
                }
            });
        }

        long startMs = System.currentTimeMillis();
        long count = entities.size();
        String sql = buildUpdateOnlySql();
        return withConnection(StorageOp.SAVE_BATCH, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (V entity : entities) {
                    K key = descriptor.keyExtractor().apply(entity);
                    byte[] data = descriptor.codec().encode(entity);
                    bindUpdateOnlyParameters(ps, key, entity, data);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.savedBatch(tableName(), count, System.currentTimeMillis() - startMs);
            return null;
        });
    }

    /**
     * {@code UPDATE_ONLY} for a versioned descriptor: guarded update, no INSERT. An absent row is a
     * no-op (the maintenance pass must never resurrect a concurrently deleted row); a version mismatch
     * or a lost update raises {@link OptimisticLockException}.
     */
    private void updateOnlyVersionedOnConn(Connection conn, V entity) throws SQLException, CodecException {
        K key = descriptor.keyExtractor().apply(entity);
        Long boxedVersion = descriptor.versionGetter().apply(entity);
        long incomingVersion = boxedVersion != null ? boxedVersion : 0L;
        Long dbVersion = selectVersion(conn, key);

        if (dbVersion == null) {
            return; // row gone - UPDATE_ONLY never inserts
        }
        if (dbVersion == incomingVersion) {
            long newVersion = incomingVersion + 1;
            descriptor.versionSetter().accept(entity, newVersion);
            byte[] data = descriptor.codec().encode(entity);
            String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            int rows = updateVersioned(conn, key, dataStr, entity, incomingVersion);
            if (rows == 0) {
                descriptor.versionSetter().accept(entity, incomingVersion); // undo
                log.optimisticLockConflict(tableName(), key, incomingVersion, dbVersion);
                throw new OptimisticLockException(descriptor.type(), key, incomingVersion, dbVersion);
            }
        } else {
            log.optimisticLockConflict(tableName(), key, incomingVersion, dbVersion);
            throw new OptimisticLockException(descriptor.type(), key, incomingVersion, dbVersion);
        }
    }

    /** {@code UPDATE ... SET storage_data=?, _idx_*=? WHERE storage_key=?} - plain (non-versioned) update. */
    private String buildUpdateOnlySql() {
        StringBuilder sb = new StringBuilder("UPDATE ").append(q(tableName())).append(" SET ");
        sb.append(q(COL_DATA)).append(" = ?");
        for (IndexHint hint : indexes) {
            sb.append(", ").append(q(hint.indexColumnName())).append(" = ?");
        }
        sb.append(" WHERE ").append(q(COL_KEY)).append(" = ?");
        return sb.toString();
    }

    /** Binds {@code (storage_data, _idx_a, _idx_b, ..., storage_key)} for {@link #buildUpdateOnlySql}. */
    private void bindUpdateOnlyParameters(PreparedStatement ps, K key, V entity, byte[] data) throws SQLException {
        int slot = 1;
        setDataParam(ps, slot++, new String(data, StandardCharsets.UTF_8));
        if (!indexes.isEmpty()) {
            JsonNode tree = IndexValueExtractor.toTree(entity, descriptor.codec());
            for (IndexHint hint : indexes) {
                Object value = IndexValueExtractor.extract(tree, hint);
                ps.setObject(slot++, toJdbcValue(value, hint));
            }
        }
        ps.setString(slot, key.toString());
    }

    /**
     * Performs a versioned save of a single entity on an already-open connection (no own tx mgmt).
     * Used by {@link #saveAll} for versioned descriptors.
     *
     * <p>Version is applied to the entity BEFORE encoding so the stored JSON blob is correct.
     */
    private void saveVersionedOnConn(Connection conn, V entity) throws SQLException, CodecException {
        K key = descriptor.keyExtractor().apply(entity);
        Long boxedVersion = descriptor.versionGetter().apply(entity);
        long incomingVersion = boxedVersion != null ? boxedVersion : 0L;   // a never-persisted Long reads as version 0
        Long dbVersion = selectVersion(conn, key);

        if (dbVersion == null) {
            descriptor.versionSetter().accept(entity, 0L);
            byte[] data = descriptor.codec().encode(entity);
            String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            insertVersioned(conn, key, dataStr, entity, 0L);
        } else if (dbVersion == incomingVersion) {
            long newVersion = incomingVersion + 1;
            descriptor.versionSetter().accept(entity, newVersion);
            byte[] data = descriptor.codec().encode(entity);
            String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            int rows = updateVersioned(conn, key, dataStr, entity, incomingVersion);
            if (rows == 0) {
                descriptor.versionSetter().accept(entity, incomingVersion); // undo
                log.optimisticLockConflict(tableName(), key, incomingVersion, dbVersion);
                throw new OptimisticLockException(
                    descriptor.type(), key, incomingVersion, dbVersion);
            }
        } else {
            log.optimisticLockConflict(tableName(), key, incomingVersion, dbVersion);
            throw new OptimisticLockException(
                descriptor.type(), key, incomingVersion, dbVersion);
        }
    }

    /** Binds {@code (storage_key, storage_data, _idx_a, _idx_b, ...)} parameters. */
    private void bindUpsertParameters(PreparedStatement ps, K key, V entity, byte[] data) throws SQLException {
        ps.setString(1, key.toString());
        setDataParam(ps, 2, new String(data, StandardCharsets.UTF_8));
        if (!indexes.isEmpty()) {
            JsonNode tree = IndexValueExtractor.toTree(entity, descriptor.codec());
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
        return withConnection(StorageOp.DELETE, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key.toString());
                boolean existed = ps.executeUpdate() > 0;
                log.deleted(tableName(), key, existed);
                return existed;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(K key) {
        String sql = "SELECT 1 FROM " + q(tableName())
            + " WHERE " + q(COL_KEY) + " = ? LIMIT 1";
        return withConnection(StorageOp.EXISTS, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }

    @Override
    public CompletableFuture<Long> count() {
        String sql = "SELECT COUNT(*) FROM " + q(tableName());
        return withConnection(StorageOp.COUNT, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public CompletableFuture<Long> count(Query query) {
        if (query == null) {
            throw new IllegalArgumentException("query cannot be null");
        }
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
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(q(tableName()));
        if (where.length() > 0) sql.append(" WHERE ").append(where);
        return withConnection(StorageOp.COUNT, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Stream<V>> all() {
        String sql = "SELECT " + q(COL_KEY) + ", " + q(COL_DATA) + " FROM " + q(tableName());
        return withConnection(StorageOp.SCAN_ALL, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                return readEntities(ps).stream();
            }
        });
    }

    @Override
    public CompletableFuture<Slice<ScanRow<V>>> scanAll(Cursor cursor, int limit) {
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        StringBuilder sql = new StringBuilder("SELECT ").append(q(COL_KEY)).append(", ").append(q(COL_DATA))
            .append(" FROM ").append(q(tableName()));
        List<Object> params = new ArrayList<>(2);
        if (!cursor.isStart()) {
            sql.append(" WHERE ").append(q(COL_KEY)).append(" > ?");
            params.add(cursor.lastKey());
        }
        sql.append(" ORDER BY ").append(q(COL_KEY)).append(" ASC LIMIT ?");
        int probe = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;   // one extra to detect hasNext
        params.add(probe);
        return withConnection(StorageOp.SCAN_ALL, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                List<ScanRow<V>> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key  = rs.getString(1);
                        String json = rs.getString(2);
                        try {
                            V value = descriptor.codec().decode(json.getBytes(StandardCharsets.UTF_8));
                            rows.add(ScanRow.ok(key, value));
                        } catch (CodecException e) {
                            // Unlike readEntities, surface the failure to the caller AS WELL AS logging it.
                            log.skippedCorruptedRow(tableName(), key, e);
                            rows.add(ScanRow.failed(key, e));
                        }
                    }
                }
                return sliceOf(rows, cursor, limit);
            }
        });
    }

    @Override
    public CompletableFuture<Slice<String>> keys(Cursor cursor, int limit) {
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        // Only the key column, so the primary-key index answers this without touching a row.
        StringBuilder sql = new StringBuilder("SELECT ").append(q(COL_KEY))
            .append(" FROM ").append(q(tableName()));
        List<Object> params = new ArrayList<>(2);
        if (!cursor.isStart()) {
            sql.append(" WHERE ").append(q(COL_KEY)).append(" > ?");
            params.add(cursor.lastKey());
        }
        sql.append(" ORDER BY ").append(q(COL_KEY)).append(" ASC LIMIT ?");
        int probe = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;   // one extra to detect hasNext
        params.add(probe);
        return withConnection(StorageOp.SCAN_ALL, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                List<String> found = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.add(rs.getString(1));
                }
                return Slices.keyPage(found, cursor, limit);
            }
        });
    }

    /** Trims the probe row and builds the key-ordered continuation cursor from the last content row. */
    private Slice<ScanRow<V>> sliceOf(List<ScanRow<V>> rows, Cursor cursor, int limit) {
        boolean hasNext = rows.size() > limit;
        List<ScanRow<V>> content = hasNext ? new ArrayList<>(rows.subList(0, limit)) : rows;
        Cursor next = null;
        if (hasNext && !content.isEmpty()) {
            String lastKey = content.get(content.size() - 1).key();
            next = Cursor.after(cursor.orderBy(), cursor.direction(), lastKey, lastKey);
        }
        return Slice.ofCursor(content, QueryOptions.none(), hasNext, next);
    }

    // ------------------------------------------------------------------
    //  Index queries
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<List<V>> findBy(String fieldPath, Object value) {
        return query(Query.eq(fieldPath, value));
    }

    @Override
    public CompletableFuture<List<V>> query(Query query, QueryOptions options) {
        if (query == null) {
            throw new IllegalArgumentException("query cannot be null");
        }
        if (options == null) {
            options = QueryOptions.none();
        }
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

        StringBuilder sql = new StringBuilder("SELECT ")
            .append(q(COL_KEY))
            .append(", ")
            .append(q(COL_DATA))
            .append(" FROM ")
            .append(q(tableName()));
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where);
        }
        applyQueryOptions(sql, params, options);
        long startMs = System.currentTimeMillis();

        return withConnection(StorageOp.QUERY, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                List<V> result = readEntities(ps);
                log.queried(tableName(), query, result.size(), System.currentTimeMillis() - startMs);
                return result;
            }
        });
    }

    private void applyQueryOptions(StringBuilder sql, List<Object> params, QueryOptions options) {
        boolean paginating = options.hasLimit() || options.hasOffset();
        if (options.hasOrder()) {
            IndexHint orderHint = hintsByPath.get(options.orderBy());
            if (orderHint == null) {
                throw new IllegalArgumentException(
                    "SQL: order field '" + options.orderBy() + "' is not indexed. "
                    + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
            }
            boolean ascending = options.order() != IndexHint.Order.DESCENDING;
            String col = q(orderHint.indexColumnName());
            // NULL sorts as the smallest value (first ascending, last descending) on every dialect via
            // the boolean (col IS NULL) flag, then a stable tie-break by key - so a paged result is
            // identical across backends, matching the in-memory and Mongo ordering contract.
            sql.append(" ORDER BY (").append(col).append(" IS NULL) ").append(ascending ? "DESC" : "ASC")
                .append(", ").append(col).append(ascending ? " ASC" : " DESC")
                .append(", ").append(q(COL_KEY)).append(" ASC");
        } else if (paginating) {
            // Stable pagination needs a deterministic order even without an explicit sort field.
            sql.append(" ORDER BY ").append(q(COL_KEY)).append(" ASC");
        }
        if (paginating) {
            sql.append(" LIMIT ?");
            params.add(options.hasLimit() ? options.limit() : Integer.MAX_VALUE);
        }
        if (options.hasOffset()) {
            sql.append(" OFFSET ?");
            params.add(options.offset());
        }
    }

    @Override
    public CompletableFuture<Slice<V>> queryAfter(Query query, Cursor cursor, int limit) {
        if (query == null)  throw new IllegalArgumentException("query cannot be null");
        if (cursor == null) throw new IllegalArgumentException("cursor cannot be null");
        if (limit < 1)      throw new IllegalArgumentException("limit must be >= 1: " + limit);
        IndexHint orderHint = hintsByPath.get(cursor.orderBy());
        if (orderHint == null) {
            throw new IllegalArgumentException(
                "SQL: order field '" + cursor.orderBy() + "' is not indexed. "
                + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
        }
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Query.Condition c : query.conditions()) {
            IndexHint hint = hintsByPath.get(c.fieldPath());
            if (hint == null) {
                throw new IllegalArgumentException(
                    "SQL: field '" + c.fieldPath() + "' is not indexed. "
                    + "Declare it on the EntityDescriptor with .index(IndexHint.<type>(\"...\")).");
            }
            if (where.length() > 0) where.append(" AND ");
            appendCondition(where, params, c, hint);
        }
        if (!cursor.isStart()) {
            if (where.length() > 0) where.append(" AND ");
            appendKeysetPredicate(where, params, orderHint, cursor);
        }
        boolean ascending = cursor.direction() != IndexHint.Order.DESCENDING;
        String col = q(orderHint.indexColumnName());
        StringBuilder sql = new StringBuilder("SELECT ").append(q(COL_KEY)).append(", ").append(q(COL_DATA))
            .append(" FROM ").append(q(tableName()));
        if (where.length() > 0) sql.append(" WHERE ").append(where);
        sql.append(" ORDER BY (").append(col).append(" IS NULL) ").append(ascending ? "DESC" : "ASC")
            .append(", ").append(col).append(ascending ? " ASC" : " DESC")
            .append(", ").append(q(COL_KEY)).append(" ASC LIMIT ?");
        params.add(limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1);   // probe one extra for hasNext
        long startMs = System.currentTimeMillis();
        return withConnection(StorageOp.QUERY, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                List<V> rows = readEntities(ps);
                boolean hasNext = rows.size() > limit;
                List<V> content = hasNext ? new ArrayList<>(rows.subList(0, limit)) : rows;
                Cursor next = (hasNext && !content.isEmpty())
                    ? QueryResultOrdering.nextCursorFrom(content.get(content.size() - 1), orderHint,
                        cursor.direction(), descriptor.keyExtractor(), descriptor.codec())
                    : null;
                QueryOptions order = QueryOptions.builder()
                    .orderBy(cursor.orderBy(), cursor.direction()).limit(limit).build();
                log.queried(tableName(), query, content.size(), System.currentTimeMillis() - startMs);
                return Slice.ofCursor(content, order, hasNext, next);
            }
        });
    }

    /** Appends the keyset "strictly after the cursor" predicate, matching the NULL=least, key-asc total order. */
    private void appendKeysetPredicate(StringBuilder where, List<Object> params, IndexHint hint, Cursor cursor) {
        String col = q(hint.indexColumnName());
        String key = q(COL_KEY);
        Object cv = QueryResultOrdering.coerce(cursor.lastValue(), hint);
        String ck = cursor.lastKey();
        boolean descending = cursor.direction() == IndexHint.Order.DESCENDING;
        if (cv == null) {
            if (descending) {   // already in the NULL tail
                where.append('(').append(col).append(" IS NULL AND ").append(key).append(" > ?)");
                params.add(ck);
            } else {            // NULL prefix: anything non-null, or a later NULL by key
                where.append('(').append(col).append(" IS NOT NULL OR (")
                    .append(col).append(" IS NULL AND ").append(key).append(" > ?))");
                params.add(ck);
            }
            return;
        }
        Object jdbcVal = toJdbcValue(cv, hint);
        if (descending) {       // F < v, or tie by key, or the NULL tail
            where.append('(').append(col).append(" < ? OR (").append(col).append(" = ? AND ")
                .append(key).append(" > ?) OR ").append(col).append(" IS NULL)");
        } else {                // F > v, or tie by key (NULLs are before v, excluded)
            where.append('(').append(col).append(" > ? OR (").append(col).append(" = ? AND ")
                .append(key).append(" > ?))");
        }
        params.add(jdbcVal);
        params.add(jdbcVal);
        params.add(ck);
    }

    private void appendCondition(StringBuilder where, List<Object> params, Query.Condition c, IndexHint hint) {
        String column = q(hint.indexColumnName());
        switch (c.op()) {
            case EQ:
                where.append(column).append(" = ?");
                params.add(toJdbcParam(c.value(), hint));
                break;
            case IN:
                where.append(column).append(" IN (")
                    .append(repeat("?", c.inValues().size(), ","))
                    .append(')');
                for (Object v : c.inValues()) params.add(toJdbcParam(v, hint));
                break;
            case RANGE:
                // Which ends are open is read off the condition, not off the converted bound: a
                // bound that converts to NULL is one nothing can match, and treating it as an open
                // end would silently widen the range instead.
                boolean hasFrom = c.rangeFrom() != null;
                boolean hasTo   = c.rangeTo()   != null;
                if (hasFrom && hasTo) {
                    where.append(column).append(" BETWEEN ? AND ?");
                    params.add(toJdbcParam(c.rangeFrom(), hint));
                    params.add(toJdbcParam(c.rangeTo(),   hint));
                } else if (hasFrom) {
                    where.append(column).append(" >= ?");
                    params.add(toJdbcParam(c.rangeFrom(), hint));
                } else if (hasTo) {
                    where.append(column).append(" <= ?");
                    params.add(toJdbcParam(c.rangeTo(), hint));
                } else {
                    where.append(column).append(" IS NOT NULL");
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    //  Utility
    // ------------------------------------------------------------------

    /**
     * Reads all entities from the given prepared statement's result set.
     * The statement must project {@code (storage_key, storage_data)} in that order -
     * every caller ({@code findMany}, {@code all}, {@code query}) includes the key column
     * so corrupted-row WARN entries can name the offending key.
     * Rows whose JSON cannot be decoded emit a WARN log entry and are silently skipped
     * (consistent with the codec-tolerant "skip corrupted" contract).
     */
    private List<V> readEntities(PreparedStatement ps) throws SQLException {
        List<V> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key  = rs.getString(1);
                String json = rs.getString(2);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                try {
                    result.add(descriptor.codec().decode(data));
                } catch (CodecException e) {
                    log.skippedCorruptedRow(tableName(), key, e);
                }
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
