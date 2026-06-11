package br.com.finalcraft.everydatabase.log;

/**
 * Severity/verbosity levels for storage log events, ordered from least to most verbose.
 *
 * <p>Rank (ordinal): {@code OFF(0) < ERROR(1) < WARN(2) < INFO(3) < DEBUG(4) < TRACE(5)}.
 *
 * <p><b>ERROR floor:</b> {@code ERROR}-level events are <em>always</em> emitted regardless of the
 * configured threshold - even when a topic is set to {@code OFF}. This guarantees that failures
 * are never silenced by configuration.
 */
public enum StorageLogLevel {

    /** Disable all events for a topic (except the ERROR floor). */
    OFF,

    /**
     * Failure / fatal condition. Always emitted - cannot be suppressed by topic configuration.
     * Used for I/O errors, codec failures, and any exception that will propagate to the caller.
     */
    ERROR,

    /**
     * Degraded condition that does not abort the operation: a corrupted row was skipped,
     * an optimistic-lock conflict was detected, a backfill yielded unexpected results.
     */
    WARN,

    /**
     * Normal but noteworthy milestones: storage init/close, index created/dropped,
     * migration applied, batch write completed, reconcile summary.
     */
    INFO,

    /**
     * Operational detail useful for debugging: individual save/delete, query with result count,
     * transaction begin/commit, progress ticks.
     */
    DEBUG,

    /**
     * Maximum verbosity: per-entity find/exists, very low-level internal detail.
     * Only enable when diagnosing specific issues in a low-traffic environment.
     */
    TRACE;

    /**
     * Returns {@code true} when an event at this level should be emitted under the given
     * {@code threshold}.
     *
     * <p><b>ERROR floor:</b> {@code ERROR} always returns {@code true} regardless of
     * {@code threshold} (even {@code OFF}).
     *
     * @param threshold the effective level configured for the topic
     */
    public boolean passes(StorageLogLevel threshold) {
        if (this == ERROR) return true;          // floor: ERROR always passes
        if (threshold == OFF) return false;
        return this.ordinal() <= threshold.ordinal();
    }
}
