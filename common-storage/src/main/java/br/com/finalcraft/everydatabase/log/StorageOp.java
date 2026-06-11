package br.com.finalcraft.everydatabase.log;

/**
 * Fine-grained operation identifier for a storage log event.
 *
 * <p>Each constant belongs to exactly one {@link StorageLogTopic} (returned by {@link #topic()}).
 * Backends use these constants to emit events; the topic is used by {@link StorageLogConfig} to
 * look up the effective threshold.
 *
 * <p>Common usage in a backend:
 * <pre>{@code
 * // Guard check - avoids lambda allocation on the hot path:
 * if (log.isEnabled(StorageOp.SAVE, StorageLogLevel.TRACE)) {
 *     log.emit(StorageOp.SAVE, StorageLogLevel.TRACE, b -> b.collection(coll));
 * }
 *
 * // For less-frequent ops the guard is inside emit() itself:
 * log.emit(StorageOp.INDEX_CREATE, StorageLogLevel.INFO, b ->
 *     b.collection(coll).detail("field=" + hint.fieldPath()));
 * }</pre>
 */
public enum StorageOp {

    // ---- LIFECYCLE -------------------------------------------------------
    /** Storage {@code init()} - connection pool / file handle opened. */
    INIT(StorageLogTopic.LIFECYCLE),
    /** Storage {@code close()} - connection pool / file handle released. */
    CLOSE(StorageLogTopic.LIFECYCLE),
    /** {@code health()} probe result. */
    HEALTH(StorageLogTopic.HEALTH),

    // ---- SCHEMA ----------------------------------------------------------
    /** A new table / collection / directory was created for a collection. */
    TABLE_CREATE(StorageLogTopic.SCHEMA),
    /** An {@code _idx_*} sibling column was added to an existing table. */
    COLUMN_ADD(StorageLogTopic.SCHEMA),
    /** An orphaned {@code _idx_*} column was dropped from an existing table. */
    COLUMN_DROP(StorageLogTopic.SCHEMA),

    // ---- INDEX -----------------------------------------------------------
    /** A B-tree / Mongo / in-memory index was created for a declared {@code IndexHint}. */
    INDEX_CREATE(StorageLogTopic.INDEX),
    /** An index that is no longer declared was dropped. */
    INDEX_DROP(StorageLogTopic.INDEX),
    /**
     * Backfill of a newly added index column across pre-existing rows.
     * Progress events use this op; the completion event also uses it at INFO.
     */
    INDEX_BACKFILL(StorageLogTopic.INDEX),
    /**
     * Summary emitted at the end of index reconciliation (after create/drop/backfill).
     * Contains totals: indexes created, dropped, rows backfilled, duration.
     */
    INDEX_RECONCILE(StorageLogTopic.INDEX),

    // ---- MIGRATION -------------------------------------------------------
    /** Pending migration check: N migrations not yet applied. */
    MIGRATION_PENDING(StorageLogTopic.MIGRATION),
    /** A single migration version was applied successfully. */
    MIGRATION_APPLY(StorageLogTopic.MIGRATION),
    /** A migration version was skipped because it was already applied. */
    MIGRATION_SKIP(StorageLogTopic.MIGRATION),
    /** All pending migrations have been processed (summary). */
    MIGRATION_COMPLETE(StorageLogTopic.MIGRATION),

    // ---- WRITE -----------------------------------------------------------
    /** Single {@code save()} / upsert of one entity. */
    SAVE(StorageLogTopic.WRITE),
    /** Batch {@code saveAll()} / upsert of N entities. */
    SAVE_BATCH(StorageLogTopic.WRITE),

    // ---- DELETE ----------------------------------------------------------
    /** Single {@code delete()} of one entity. */
    DELETE(StorageLogTopic.DELETE),
    /** Batch deletion of multiple entities (if/when a bulk-delete API exists). */
    DELETE_BATCH(StorageLogTopic.DELETE),

    // ---- READ ------------------------------------------------------------
    /** Single {@code find(key)} lookup. */
    FIND(StorageLogTopic.READ),
    /** Multi-key {@code findMany(keys)} lookup. */
    FIND_MANY(StorageLogTopic.READ),
    /** {@code count()} aggregate. */
    COUNT(StorageLogTopic.READ),
    /** {@code exists(key)} check. */
    EXISTS(StorageLogTopic.READ),
    /** Full scan via {@code all()}. */
    SCAN_ALL(StorageLogTopic.READ),

    // ---- QUERY -----------------------------------------------------------
    /** {@code findBy(field, value)} or {@code query(Query)} with index conditions. */
    QUERY(StorageLogTopic.QUERY),

    // ---- TRANSACTION -----------------------------------------------------
    /** An {@code inTransaction} scope was opened. */
    TX_BEGIN(StorageLogTopic.TRANSACTION),
    /** A transaction committed successfully. */
    TX_COMMIT(StorageLogTopic.TRANSACTION),
    /** A transaction was rolled back (explicit or due to error). */
    TX_ROLLBACK(StorageLogTopic.TRANSACTION),

    // ---- TRANSFER --------------------------------------------------------
    /** A {@code StorageTransfer} execution started. */
    TRANSFER_BEGIN(StorageLogTopic.TRANSFER),
    /** Progress for a single collection within a transfer. */
    TRANSFER_PROGRESS(StorageLogTopic.TRANSFER),
    /** A single collection within a transfer completed. */
    TRANSFER_COLLECTION(StorageLogTopic.TRANSFER),
    /** The entire transfer completed (summary). */
    TRANSFER_COMPLETE(StorageLogTopic.TRANSFER);

    private final StorageLogTopic topic;

    StorageOp(StorageLogTopic topic) {
        this.topic = topic;
    }

    /** Returns the broad topic this operation belongs to. */
    public StorageLogTopic topic() {
        return topic;
    }
}
