package br.com.finalcraft.everydatabase.transfer;

/**
 * Per-collection statistics produced at the end of a {@link StorageTransfer}.
 *
 * <p>One entry is added to {@link TransferReport#collections()} for every
 * {@code (sourceDescriptor, targetDescriptor)} pair that was registered,
 * keyed by the source collection name.
 *
 * <p>The count fields allow the caller to verify the transfer:
 * <pre>
 * long expected = stats.sourceCount();
 * long actual   = stats.targetCountAfter() - stats.targetCountBefore();
 * // actual == expected means a clean transfer; actual < expected with SKIP_EXISTING is normal.
 * </pre>
 */
public final class CollectionStats {

    private final String sourceCollection;
    private final String targetCollection;
    private final long sourceCount;
    private final long targetCountBefore;
    private final long targetCountAfter;
    private final long entitiesWritten;
    private final long durationMs;

    public CollectionStats(
            String sourceCollection,
            String targetCollection,
            long sourceCount,
            long targetCountBefore,
            long targetCountAfter,
            long entitiesWritten,
            long durationMs) {
        this.sourceCollection  = sourceCollection;
        this.targetCollection  = targetCollection;
        this.sourceCount       = sourceCount;
        this.targetCountBefore = targetCountBefore;
        this.targetCountAfter  = targetCountAfter;
        this.entitiesWritten   = entitiesWritten;
        this.durationMs        = durationMs;
    }

    /** Logical name of the source collection (from the source {@code EntityDescriptor}). */
    public String sourceCollection() { return sourceCollection; }

    /** Logical name of the target collection (from the target {@code EntityDescriptor}). */
    public String targetCollection() { return targetCollection; }

    /**
     * Number of entities in the source collection at the moment the transfer started.
     * Snapshot taken via {@code sourceRepo.count()} before any writes.
     */
    public long sourceCount() { return sourceCount; }

    /**
     * Number of entities already in the target collection <em>before</em> this transfer wrote anything.
     * Expected to be 0 when {@code failIfTargetCollectionNotEmpty=true}.
     */
    public long targetCountBefore() { return targetCountBefore; }

    /**
     * Number of entities in the target collection <em>after</em> all batches completed.
     * Snapshot taken via {@code targetRepo.count()} after the last batch.
     */
    public long targetCountAfter() { return targetCountAfter; }

    /**
     * Number of entities actually passed to {@code targetRepo.saveAll()} across all batches.
     * With {@link ErrorPolicy#SKIP_EXISTING} this may be less than {@link #sourceCount()}.
     */
    public long entitiesWritten() { return entitiesWritten; }

    /** Wall-clock milliseconds from first batch start to last batch completion for this collection. */
    public long durationMs() { return durationMs; }

    @Override
    public String toString() {
        return "CollectionStats{" + sourceCollection + " -> " + targetCollection
            + ", written=" + entitiesWritten + "/" + sourceCount
            + ", targetBefore=" + targetCountBefore + ", targetAfter=" + targetCountAfter
            + ", " + durationMs + "ms}";
    }
}
