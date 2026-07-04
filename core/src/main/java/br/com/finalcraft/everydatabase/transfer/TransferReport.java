package br.com.finalcraft.everydatabase.transfer;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.util.*;

/**
 * Immutable summary of a completed {@link StorageTransfer} execution.
 *
 * <p>Obtain via {@link StorageTransfer#execute()}.
 * Inspect {@link #success()} first; if {@code false}, check {@link #errors()} for details.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * TransferReport report = transfer.execute().join();
 *
 * if (report.success()) {
 *     log.info("Transferred {} entities across {} collections in {}ms",
 *         report.totalEntities(), report.collections().size(), report.durationMs());
 *
 *     for (CollectionStats s : report.collections().values()) {
 *         log.info("  {} -> {}: {} entities, {}ms",
 *             s.sourceCollection(), s.targetCollection(), s.entitiesWritten(), s.durationMs());
 *     }
 * } else {
 *     for (TransferError e : report.errors()) {
 *         log.error("[{}] key={}: {}", e.collection(), e.key(), e.cause().getMessage());
 *     }
 * }
 * }</pre>
 */
public final class TransferReport {

    private final boolean success;
    private final long totalEntities;
    private final long durationMs;
    private final Map<String, CollectionStats> collections;
    private final List<TransferError> errors;
    private final long suppressedErrors;

    private TransferReport(Builder b) {
        this.success          = b.success;
        this.totalEntities    = b.totalEntities;
        this.durationMs       = b.durationMs;
        this.collections      = Collections.unmodifiableMap(new LinkedHashMap<>(b.collections));
        this.errors           = Collections.unmodifiableList(new ArrayList<>(b.errors));
        this.suppressedErrors = b.suppressedErrors;
    }

    /**
     * {@code true} if the transfer completed without errors and all count verifications passed.
     * {@code false} if any {@link TransferError} was recorded.
     */
    public boolean success() { return success; }

    /**
     * Total number of entities passed to {@code targetRepo.saveAll()} across all collections.
     * Sum of {@link CollectionStats#entitiesWritten()} for every collection.
     */
    public long totalEntities() { return totalEntities; }

    /** Wall-clock milliseconds from the first pre-flight check to the last count verification. */
    public long durationMs() { return durationMs; }

    /**
     * Per-collection statistics, keyed by the <em>source</em> collection name.
     * Insertion order matches the order the collections were registered via
     * {@link StorageTransfer.Builder#descriptor(EntityDescriptor)}.
     */
    public Map<String, CollectionStats> collections() { return collections; }

    /**
     * All errors recorded during the transfer.
     * Empty when {@link #success()} is {@code true}.
     * With {@link ErrorPolicy#FAIL_FAST} this list has at most one entry.
     *
     * <p>Bounded: at most {@value Builder#MAX_RETAINED_ERRORS} errors are retained so a huge
     * {@link ErrorPolicy#CONTINUE} run over a failing collection cannot exhaust memory with stack
     * traces. Any beyond that are counted in {@link #suppressedErrors()} but not stored.
     */
    public List<TransferError> errors() { return errors; }

    /**
     * Number of errors that occurred beyond the retention cap and were therefore counted but not
     * kept in {@link #errors()}. {@code 0} in the normal case; positive only on a pathological run
     * with more than {@value Builder#MAX_RETAINED_ERRORS} failures.
     */
    public long suppressedErrors() { return suppressedErrors; }

    @Override
    public String toString() {
        return "TransferReport{success=" + success
            + ", totalEntities=" + totalEntities
            + ", collections=" + collections.size()
            + ", errors=" + errors.size()
            + (suppressedErrors > 0 ? " (+" + suppressedErrors + " suppressed)" : "")
            + ", " + durationMs + "ms}";
    }

    // ------------------------------------------------------------------
    //  Package-private mutable builder (used by StorageTransferImpl)
    // ------------------------------------------------------------------

    static Builder builder(long startMs) {
        return new Builder(startMs);
    }

    static final class Builder {

        /** Cap on retained {@link TransferError}s; extras are counted in {@code suppressedErrors}. */
        static final int MAX_RETAINED_ERRORS = 1000;

        private final long startMs;
        boolean success = true;
        long totalEntities = 0L;
        long durationMs = 0L;
        long suppressedErrors = 0L;
        final Map<String, CollectionStats> collections = new LinkedHashMap<>();
        final List<TransferError> errors = new ArrayList<>();

        private Builder(long startMs) {
            this.startMs = startMs;
        }

        Builder addCollectionStats(CollectionStats stats) {
            collections.put(stats.sourceCollection(), stats);
            totalEntities += stats.entitiesWritten();
            return this;
        }

        Builder addError(TransferError error) {
            if (errors.size() < MAX_RETAINED_ERRORS) {
                errors.add(error);
            } else {
                suppressedErrors++;   // bound memory on a pathological CONTINUE run
            }
            this.success = false;
            return this;
        }

        Builder fail() {
            this.success = false;
            return this;
        }

        TransferReport build() {
            this.durationMs = System.currentTimeMillis() - startMs;
            return new TransferReport(this);
        }
    }
}
