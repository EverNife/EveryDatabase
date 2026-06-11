package br.com.finalcraft.everydatabase.transfer;

/**
 * Snapshot of transfer progress for a single collection, delivered to
 * {@link StorageTransfer.Builder#progressListener(java.util.function.Consumer)} after
 * each batch write.
 *
 * <p>Values are monotonically non-decreasing within a collection: {@code done} only grows,
 * {@code total} is fixed for the lifetime of the collection transfer.
 */
public final class TransferProgress {

    private final String collection;
    private final long done;
    private final long total;
    private final long elapsedMs;

    public TransferProgress(String collection, long done, long total, long elapsedMs) {
        this.collection = collection;
        this.done       = done;
        this.total      = total;
        this.elapsedMs  = elapsedMs;
    }

    /** Name of the source collection being transferred. */
    public String collection() { return collection; }

    /** Number of entities written to the target so far in this collection. */
    public long done() { return done; }

    /**
     * Total number of entities read from the source for this collection
     * ({@code source.count()} snapshot taken at transfer start).
     */
    public long total() { return total; }

    /** Wall-clock milliseconds elapsed since the transfer of this collection started. */
    public long elapsedMs() { return elapsedMs; }

    @Override
    public String toString() {
        return "TransferProgress{collection='" + collection + "', done=" + done
            + ", total=" + total + ", elapsed=" + elapsedMs + "ms}";
    }
}
