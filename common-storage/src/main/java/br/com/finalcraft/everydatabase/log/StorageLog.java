package br.com.finalcraft.everydatabase.log;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Internal event dispatcher for a single {@link Storage}
 * instance and all repositories it owns.
 *
 * <p>All backend code uses this class to emit log events. It reads the
 * {@link StorageLogConfig} <em>live</em> from the storage's {@code volatile} field
 * (via the {@code cfg} {@link Supplier}), so runtime config changes take effect immediately.
 *
 * <p>The typical usage pattern in a backend:
 * <pre>{@code
 * // Free guard check before expensive lambda allocation (hot path):
 * if (log.isEnabled(StorageOp.SAVE, StorageLogLevel.TRACE)) {
 *     log.emit(StorageOp.SAVE, StorageLogLevel.TRACE, b -> b.collection(coll));
 * }
 *
 * // Named helper for semantic clarity (less-hot paths):
 * log.indexCreated(coll, hint);
 * log.migrationApplied("002", "Add balance index", 45L);
 *
 * // Error logging + re-throw (never suppress exceptions):
 * } catch (SQLException e) {
 *     throw log.errored(StorageOp.SAVE, coll, new RuntimeException("SQL save failed", e));
 * }
 * </pre>
 *
 * <p>Sink failures are always swallowed internally so that a broken logger never
 * disrupts a storage operation.
 */
public final class StorageLog {

    private final String backend;
    final Supplier<StorageLogConfig> cfg;

    /**
     * @param backend short backend name: {@code "sql"}, {@code "postgresql"}, {@code "h2"},
     *                {@code "mongo"}, {@code "localfile"}, {@code "memory"}.
     * @param cfg     live supplier pointing to the storage's {@code volatile logConfig} field.
     */
    public StorageLog(String backend, Supplier<StorageLogConfig> cfg) {
        this.backend = backend;
        this.cfg     = cfg;
    }

    // ------------------------------------------------------------------
    //  Guard
    // ------------------------------------------------------------------

    /**
     * Fast guard check: returns {@code true} when an event at {@code level} for
     * {@code op}'s topic would be emitted with the current config.
     *
     * <p>Use this before allocating a capturing lambda or doing expensive computation
     * that feeds into the log event:
     * <pre>{@code
     * if (log.isEnabled(StorageOp.SCAN_ALL, StorageLogLevel.DEBUG)) {
     *     log.emit(StorageOp.SCAN_ALL, StorageLogLevel.DEBUG, b -> b.collection(coll).affected(count));
     * }
     * }</pre>
     */
    public boolean isEnabled(StorageOp op, StorageLogLevel level) {
        return cfg.get().isEnabled(op.topic(), level);
    }

    // ------------------------------------------------------------------
    //  Core emit
    // ------------------------------------------------------------------

    /**
     * Emits a log event if the current config's effective level for the op's topic passes
     * {@code level}. The {@link Consumer} is invoked only when the event will actually be
     * emitted, avoiding unnecessary object allocation.
     *
     * <p>Sink failures are silently caught - a broken sink never propagates to the caller.
     *
     * @param op    the storage operation
     * @param level the event severity
     * @param fill  populates the event builder (only called when enabled)
     */
    public void emit(StorageOp op, StorageLogLevel level, Consumer<StorageLogEvent.Builder> fill) {
        StorageLogConfig c = cfg.get();
        if (!level.passes(c.effectiveLevel(op.topic()))) return;

        StorageLogEvent.Builder b = StorageLogEvent.builder(backend, op, level);
        fill.accept(b);

        try {
            c.sink().accept(b.build());
        } catch (Throwable ignored) {
            // Sink failures must never crash a storage operation.
        }
    }

    // ------------------------------------------------------------------
    //  Error helper - log ERROR and return the exception for re-throw
    // ------------------------------------------------------------------

    /**
     * Logs a {@link StorageLogLevel#ERROR} event (always emitted - cannot be suppressed)
     * and <b>returns</b> the exception so the caller can throw it.
     *
     * <p>Usage:
     * <pre>{@code
     * } catch (SQLException e) {
     *     throw log.errored(StorageOp.SAVE, collection, new RuntimeException("SQL save failed", e));
     * }
     * </pre>
     *
     * <p><b>Never</b> swallows the exception - this method only adds visibility to a failure
     * that is already propagating.
     *
     * @param op         the operation that failed
     * @param collection the affected collection, or {@code null} for storage-wide failures
     * @param ex         the exception to log and return
     * @param <T>        the exception type
     * @return {@code ex} unchanged, ready to be thrown
     */
    public <T extends RuntimeException> T errored(StorageOp op, String collection, T ex) {
        emit(op, StorageLogLevel.ERROR, b -> b.collection(collection).error(ex));
        return ex;
    }

    // ------------------------------------------------------------------
    //  Progress tracking
    // ------------------------------------------------------------------

    /**
     * Creates a new {@link ProgressTracker} for a long-running operation such as index backfill
     * or schema migration. The tracker emits {@code op} events at {@link StorageLogLevel#DEBUG}
     * according to the current {@link StorageLogConfig} progress settings.
     *
     * @param op         the operation type (e.g. {@link StorageOp#INDEX_BACKFILL})
     * @param collection the affected collection name
     */
    public ProgressTracker newProgressTracker(StorageOp op, String collection) {
        return new ProgressTracker(this, op, collection);
    }

    // ------------------------------------------------------------------
    //  Named semantic helpers
    // ------------------------------------------------------------------

    /**
     * Logs that a B-tree / Mongo / in-memory index was created for the given {@link IndexHint}.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void indexCreated(String collection, IndexHint hint) {
        emit(StorageOp.INDEX_CREATE, StorageLogLevel.INFO, b -> b
            .collection(collection)
            .detail("field=" + hint.fieldPath()
                + " order=" + hint.order().name()
                + " column=" + hint.indexColumnName()));
    }

    /**
     * Logs that a B-tree / Mongo index was dropped because it is no longer declared.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void indexDropped(String collection, String columnOrIndexName) {
        emit(StorageOp.INDEX_DROP, StorageLogLevel.INFO, b -> b
            .collection(collection)
            .detail("removed=" + columnOrIndexName));
    }

    /**
     * Logs a column (schema-level) addition for a new index hint.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void columnAdded(String collection, IndexHint hint) {
        emit(StorageOp.COLUMN_ADD, StorageLogLevel.INFO, b -> b
            .collection(collection)
            .detail("column=" + hint.indexColumnName() + " type=" + hint.fieldType().name()));
    }

    /**
     * Logs an orphaned index column being dropped.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void columnDropped(String collection, String columnName) {
        emit(StorageOp.COLUMN_DROP, StorageLogLevel.INFO, b -> b
            .collection(collection)
            .detail("column=" + columnName));
    }

    /**
     * Logs the index reconciliation summary at the end of a {@code createTableIfAbsent} or
     * {@code ensureIndexes} call. Emits nothing if all lists are empty and backfilled==0.
     * Level: {@link StorageLogLevel#INFO} (when there are changes) or
     * {@link StorageLogLevel#DEBUG} (no-op reconcile).
     *
     * @param collection  affected collection
     * @param created     field paths of newly created indexes
     * @param dropped     column/index names that were removed
     * @param backfilled  number of rows/documents backfilled (0 if none)
     * @param elapsedMs   total duration of the reconcile operation
     */
    public void reconcileSummary(String collection,
                                 List<String> created, List<String> dropped,
                                 long backfilled, long elapsedMs) {
        boolean hasChanges = !created.isEmpty() || !dropped.isEmpty() || backfilled > 0;
        StorageLogLevel level = hasChanges ? StorageLogLevel.INFO : StorageLogLevel.DEBUG;
        emit(StorageOp.INDEX_RECONCILE, level, b -> {
            StringBuilder detail = new StringBuilder();
            if (!created.isEmpty())  detail.append("created=").append(created).append(' ');
            if (!dropped.isEmpty())  detail.append("dropped=").append(dropped).append(' ');
            if (backfilled > 0)      detail.append("backfilled=").append(backfilled);
            b.collection(collection)
             .durationMs(elapsedMs)
             .detail(detail.toString().trim());
        });
    }

    /**
     * Logs a backfill completion (100%) event at {@link StorageLogLevel#INFO}.
     * Called automatically by {@link ProgressTracker#finish}.
     */
    void backfillComplete(StorageOp op, String collection, long total, long elapsedMs) {
        emit(op, StorageLogLevel.INFO, b -> b
            .collection(collection)
            .affected(total).total(total).percent(100)
            .durationMs(elapsedMs));
    }

    /**
     * Logs that a table/collection/directory was created for the first time.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void tableCreated(String collection) {
        emit(StorageOp.TABLE_CREATE, StorageLogLevel.INFO, b -> b.collection(collection));
    }

    /**
     * Logs a pending migration count.
     * Level: {@link StorageLogLevel#INFO} when pending > 0, {@link StorageLogLevel#DEBUG} otherwise.
     */
    public void migrationPending(int count) {
        StorageLogLevel level = count > 0 ? StorageLogLevel.INFO : StorageLogLevel.DEBUG;
        emit(StorageOp.MIGRATION_PENDING, level, b -> b.affected(count).detail("pending migrations"));
    }

    /**
     * Logs a successfully applied migration.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void migrationApplied(String version, String description, long elapsedMs) {
        emit(StorageOp.MIGRATION_APPLY, StorageLogLevel.INFO, b -> b
            .detail("version=" +  version + " description='" + description + "'")
            .durationMs(elapsedMs));
    }

    /**
     * Logs a skipped migration (already applied).
     * Level: {@link StorageLogLevel#DEBUG}.
     */
    public void migrationSkipped(String version) {
        emit(StorageOp.MIGRATION_SKIP, StorageLogLevel.DEBUG, b -> b.detail(version));
    }

    /**
     * Logs the migration complete summary.
     * Level: {@link StorageLogLevel#INFO}.
     */
    public void migrationComplete(int applied, int skipped, String targetVersion) {
        emit(StorageOp.MIGRATION_COMPLETE, StorageLogLevel.INFO, b -> b
            .detail("applied=" + applied + " skipped=" + skipped + " target=" + targetVersion));
    }

    /**
     * Logs a single-entity save (insert or upsert via {@code save}, not {@code saveAll}).
     * Level: {@link StorageLogLevel#DEBUG}.
     *
     * @param collection affected collection
     * @param key        entity key (String.valueOf)
     * @param entity     the saved entity - rendered via {@code toString()} (truncated to
     *                   {@link StorageLogConfig#maxValueLength()}) only when
     *                   {@link StorageLogConfig#includeValues()} is enabled; otherwise ignored
     */
    public void saved(String collection, Object key, Object entity) {
        StorageLogConfig c = cfg.get();
        if (!StorageLogLevel.DEBUG.passes(c.effectiveLevel(StorageLogTopic.WRITE))) return;
        emit(StorageOp.SAVE, StorageLogLevel.DEBUG, b -> {
            b.collection(collection);
            if (c.includeKeys()) {
                List<String> keys = new ArrayList<>(1);
                keys.add(String.valueOf(key));
                b.keys(capKeys(keys, c.maxKeysListed()));
            }
            if (c.includeValues()) {
                b.value(truncate(String.valueOf(entity), c.maxValueLength()));
            }
        });
    }

    /**
     * Caps a key list at {@code max} entries, replacing the overflow with a single
     * {@code "(+N more)"} marker - the {@link StorageLogConfig#maxKeysListed()} contract.
     * Today's emitters only ever list one key, so the cap is effectively future-proofing
     * for multi-key events; the helper keeps the contract testable either way.
     */
    static List<String> capKeys(List<String> keys, int max) {
        if (keys == null || max <= 0 || keys.size() <= max) return keys;
        List<String> capped = new ArrayList<>(keys.subList(0, max));
        capped.add("(+" + (keys.size() - max) + " more)");
        return capped;
    }

    /**
     * Keeps at most the first {@code maxLength} characters of {@code s} and appends an
     * {@code "... (+N chars)"} marker reporting how many characters were cut (so the
     * rendered line is slightly longer than {@code maxLength}, by design - the marker
     * itself needs room). Returns {@code s} unchanged when it already fits within
     * {@code maxLength} or when {@code maxLength} is non-positive (treated as "unlimited").
     *
     * <p>Used by {@link #saved} to bound {@link StorageLogConfig#includeValues()} output -
     * entity {@code toString()}s can be arbitrarily large (nested objects, collections),
     * unlike the short identity strings {@link StorageLogConfig#includeKeys()} deals with.
     */
    private static String truncate(String s, int maxLength) {
        if (s == null || maxLength <= 0 || s.length() <= maxLength) return s;
        return s.substring(0, maxLength) + "... (+" + (s.length() - maxLength) + " chars)";
    }

    /**
     * Logs a batch save (saveAll) summary.
     * Level: {@link StorageLogLevel#DEBUG}.
     *
     * @param collection affected collection
     * @param count      number of entities in the batch
     * @param elapsedMs  duration
     */
    public void savedBatch(String collection, long count, long elapsedMs) {
        emit(StorageOp.SAVE_BATCH, StorageLogLevel.DEBUG, b -> {
            long throughput = elapsedMs > 0 ? count * 1000L / elapsedMs : count;
            b.collection(collection)
             .affected(count)
             .durationMs(elapsedMs)
             .detail("(" + throughput + "/s)");
        });
    }

    /**
     * Logs an optimistic-lock conflict detected during a versioned save.
     * Level: {@link StorageLogLevel#WARN} (the exception still propagates after this).
     */
    public void optimisticLockConflict(String collection, Object key, long incomingVersion, long actualVersion) {
        emit(StorageOp.SAVE, StorageLogLevel.WARN, b -> b
            .collection(collection)
            .detail("optimistic-lock conflict key=" + key
                + " incomingVersion=" + incomingVersion + " actualVersion=" + actualVersion));
    }

    /**
     * Logs a delete operation result.
     * Level: {@link StorageLogLevel#DEBUG}.
     *
     * @param collection affected collection
     * @param key        entity key (String.valueOf)
     * @param existed    whether the entity was actually deleted (vs absent)
     */
    public void deleted(String collection, Object key, boolean existed) {
        StorageLogConfig c = cfg.get();
        if (!StorageLogLevel.DEBUG.passes(c.effectiveLevel(StorageLogTopic.DELETE))) return;
        emit(StorageOp.DELETE, StorageLogLevel.DEBUG, b -> {
            b.collection(collection).detail("existed=" + existed);
            if (c.includeKeys()) {
                List<String> keys = new ArrayList<>(1);
                keys.add(String.valueOf(key));
                b.keys(capKeys(keys, c.maxKeysListed()));
            }
        });
    }

    /**
     * Logs a query execution summary.
     * Level: {@link StorageLogLevel#DEBUG}.
     *
     * @param collection affected collection
     * @param query      the query (conditions printed as field+op, not their values)
     * @param results    number of results returned
     * @param elapsedMs  duration
     */
    public void queried(String collection, Query query, int results, long elapsedMs) {
        emit(StorageOp.QUERY, StorageLogLevel.DEBUG, b -> {
            StorageLogConfig c = cfg.get();
            StringBuilder conditions = new StringBuilder("[");
            List<Query.Condition> conds = query.conditions();
            for (int i = 0; i < conds.size(); i++) {
                if (i > 0) conditions.append(", ");
                Query.Condition cond = conds.get(i);
                if (c.includeQueryValues()) {
                    // cond.toString() gives "field = value", "field BETWEEN a AND b", "field IN [...]"
                    conditions.append(cond.toString());
                } else {
                    // safe default: field path + operator only, no filter values
                    conditions.append(cond.fieldPath()).append(' ').append(cond.op().name());
                }
            }
            conditions.append(']');
            b.collection(collection)
             .affected((long) results)
             .durationMs(elapsedMs)
             .detail("conditions=" + conditions + " results=" + results);
        });
    }

    /**
     * Logs a skipped / corrupted row during a read scan (WARN - always visible by default).
     *
     * @param collection affected collection
     * @param keyOrFile  string identifying the row (key or file name); may be null
     * @param cause      the decode/IO exception that caused the skip
     */
    public void skippedCorruptedRow(String collection, String keyOrFile, Throwable cause) {
        emit(StorageOp.SCAN_ALL, StorageLogLevel.WARN, b -> {
            b.collection(collection).error(cause);
            String detail = "skipped corrupted row";
            if (keyOrFile != null) detail += " key=" + keyOrFile;
            b.detail(detail);
        });
    }

    /**
     * Logs storage init at {@link StorageLogLevel#DEBUG}.
     *
     * @param detail optional detail string (pool name, directory path, etc.)
     */
    public void initialized(String detail) {
        emit(StorageOp.INIT, StorageLogLevel.DEBUG, b -> b.detail(detail));
    }

    /**
     * Logs storage close at {@link StorageLogLevel#DEBUG} (symmetric with {@link #initialized}:
     * lifecycle chatter stays silent under the WARN default and the INFO test preset).
     */
    public void closed() {
        emit(StorageOp.CLOSE, StorageLogLevel.DEBUG, b -> {});
    }

    /**
     * Logs a transaction begin at {@link StorageLogLevel#DEBUG}.
     */
    public void txBegin(String collection) {
        emit(StorageOp.TX_BEGIN, StorageLogLevel.DEBUG, b -> b.collection(collection));
    }

    /**
     * Logs a successful transaction commit at {@link StorageLogLevel#DEBUG}.
     *
     * @param elapsedMs duration from begin to commit
     */
    public void txCommit(String collection, long elapsedMs) {
        emit(StorageOp.TX_COMMIT, StorageLogLevel.DEBUG, b -> b.collection(collection).durationMs(elapsedMs));
    }

    /**
     * Logs a transaction rollback at {@link StorageLogLevel#WARN}.
     *
     * @param elapsedMs duration from begin to rollback
     * @param cause     the exception that triggered the rollback, or {@code null} if explicit
     */
    public void txRollback(String collection, long elapsedMs, Throwable cause) {
        emit(StorageOp.TX_ROLLBACK, StorageLogLevel.WARN, b ->
            b.collection(collection).durationMs(elapsedMs).error(cause));
    }

    // ------------------------------------------------------------------
    //  ProgressTracker inner class
    // ------------------------------------------------------------------

    /**
     * Tracks progress for a single long-running storage operation (e.g. index backfill,
     * schema migration) and emits {@link StorageOp} events at configurable intervals.
     *
     * <p>Thresholds are read from {@link StorageLogConfig} at tracker creation time and
     * remain stable for the operation's lifetime. Obtain via {@link StorageLog#newProgressTracker}.
     *
     * <p>Usage:
     * <pre>{@code
     * ProgressTracker tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, "player_data");
     * long total = countRows(conn);
     * int processed = 0;
     * while (rs.next()) {
     *     // ... process row ...
     *     tracker.tick(++processed, total);
     * }
     * tracker.finish(total);
     * }</pre>
     */
    public static final class ProgressTracker {

        private final StorageLog log;
        private final StorageOp  op;
        private final String     collection;
        private final long       startMs;

        // Thresholds snapshot (stable for the operation's lifetime)
        private final int  stepPercent;
        private final long throttleMs;
        private final long minTotal;

        // State
        private int  lastPercent = -1;
        private long lastEmitMs  = 0;

        ProgressTracker(StorageLog log, StorageOp op, String collection) {
            this.log        = log;
            this.op         = op;
            this.collection = collection;
            this.startMs    = System.currentTimeMillis();

            StorageLogConfig c = log.cfg.get();
            this.stepPercent = c.progressStepPercent();
            this.throttleMs  = c.progressThrottleMs();
            this.minTotal    = c.progressMinTotal();
        }

        /**
         * Reports progress. Emits a {@link StorageLogLevel#DEBUG} event when the step-percent
         * or throttle-ms threshold is crossed, but not more frequently than both allow.
         *
         * <p>Safe to call on every iteration - the check is inexpensive when thresholds are not met.
         *
         * @param done  number of processed items so far
         * @param total total items to process
         */
        public void tick(long done, long total) {
            StorageLogConfig c = log.cfg.get();
            if (!c.isProgressEnabled() || total < minTotal) return;
            if (!log.isEnabled(op, StorageLogLevel.DEBUG)) return;

            long nowMs = System.currentTimeMillis();
            int percent = total == 0 ? 100 : (int) (done * 100L / total);
            boolean stepMet = (percent - lastPercent) >= stepPercent;
            boolean timeMet = (nowMs - lastEmitMs) >= throttleMs;
            if (!stepMet && !timeMet) return;

            lastPercent = percent;
            lastEmitMs  = nowMs;
            long elapsedMs = nowMs - startMs;

            log.emit(op, StorageLogLevel.DEBUG, b -> b
                .collection(collection)
                .affected(done).total(total).percent(percent)
                .durationMs(elapsedMs));
        }

        /**
         * Emits a final completion event at {@link StorageLogLevel#INFO} (100%).
         * Call this once after the processing loop ends, even if no progress ticks were emitted.
         *
         * @param total total items that were processed
         */
        public void finish(long total) {
            StorageLogConfig c = log.cfg.get();
            if (!c.isProgressEnabled() || total < minTotal) return;
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.backfillComplete(op, collection, total, elapsedMs);
        }
    }
}
