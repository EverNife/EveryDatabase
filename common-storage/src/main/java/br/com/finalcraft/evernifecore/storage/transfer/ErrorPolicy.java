package br.com.finalcraft.evernifecore.storage.transfer;

/**
 * Controls how {@link StorageTransfer} reacts to exceptions during the write phase.
 */
public enum ErrorPolicy {

    /**
     * The first exception immediately aborts the entire transfer.
     *
     * <p>The returned {@link TransferReport} will have {@code success=false} and
     * exactly one {@link TransferError} describing the failure. Collections that were
     * not yet started are left untouched on the target.
     *
     * <p>This is the default and the safest choice: it prevents partial writes from
     * silently producing an inconsistent target.
     */
    FAIL_FAST,

    /**
     * Exceptions are collected in {@link TransferReport#errors()} but the transfer
     * continues with the remaining batches and collections.
     *
     * <p>Useful when you prefer a "best-effort" transfer and can inspect failures
     * afterwards. {@link TransferReport#success()} will be {@code false} if any error
     * was recorded.
     */
    CONTINUE,

    /**
     * Writes entity-by-entity (never as a batch upsert), checking
     * {@link br.com.finalcraft.evernifecore.storage.Repository#exists(Object)} for each key
     * first and skipping any key already present on the target. Slower than the batch path
     * of the other policies (two round-trips per entity), but never overwrites target data.
     *
     * <p>Useful when {@code failIfTargetCollectionNotEmpty=false} and you want a
     * non-destructive merge: existing target data is preserved, only new entries are
     * written.
     *
     * <p>Note: the count verification ({@code verifyCounts=true}) is relaxed to
     * {@code entitiesWritten <= sourceCount} when this policy is active, because
     * skipped entities are expected.
     */
    SKIP_EXISTING
}
