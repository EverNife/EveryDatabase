package br.com.finalcraft.everydatabase.log;

/**
 * Broad category of a storage log event. Each topic can be assigned its own
 * {@link StorageLogLevel} threshold in {@link StorageLogConfig}, independently of the
 * global default.
 *
 * <p>Example: enable only index and migration logging while keeping writes silent:
 * <pre>{@code
 * StorageLogConfig cfg = StorageLogConfig.defaults()
 *     .level(StorageLogTopic.INDEX,     StorageLogLevel.INFO)
 *     .level(StorageLogTopic.MIGRATION, StorageLogLevel.INFO)
 *     .mute(StorageLogTopic.WRITE)
 *     .mute(StorageLogTopic.READ);
 * }</pre>
 */
public enum StorageLogTopic {

    /**
     * Storage lifecycle: {@code init}, {@code close}, connection pool opened/closed.
     * Also covers {@code health()} results.
     */
    LIFECYCLE,

    /**
     * Schema-level operations: creating tables/collections/directories, adding or dropping
     * {@code _idx_*} sibling columns. These operations change the persisted schema structure
     * without involving data migration.
     */
    SCHEMA,

    /**
     * Index management: creating or dropping B-tree / MongoDB / in-memory indexes,
     * reconciliation of declared vs. persisted indexes, and backfill of new index columns
     * across existing rows (including progress reporting).
     */
    INDEX,

    /**
     * Schema migration: {@code pending()} check, individual migration {@code apply/skip},
     * and the final {@code complete} summary.
     */
    MIGRATION,

    /**
     * Write operations: {@code save} (single upsert) and {@code saveAll} (batch upsert).
     */
    WRITE,

    /**
     * Delete operations: {@code delete} (single or batch entity removal).
     */
    DELETE,

    /**
     * Read operations: {@code find}, {@code findMany}, {@code exists}, {@code count},
     * {@code all} (full scan).
     */
    READ,

    /**
     * Indexed queries: {@code findBy} and {@code query} with conditions.
     * Separate from {@link #READ} so read-heavy workloads can enable query diagnostics
     * independently.
     */
    QUERY,

    /**
     * Transaction lifecycle: begin, commit, and rollback of {@code inTransaction} scopes.
     */
    TRANSACTION,

    /**
     * Cross-backend transfer operations ({@code StorageTransfer}): begin, per-collection
     * progress, and completion summary.
     */
    TRANSFER,

    /**
     * Health checks ({@code health()}). Subset of {@link #LIFECYCLE} but splittable when
     * frequent health polls need separate control.
     */
    HEALTH
}
