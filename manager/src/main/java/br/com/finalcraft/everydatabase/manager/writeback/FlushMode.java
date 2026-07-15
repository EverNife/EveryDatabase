package br.com.finalcraft.everydatabase.manager.writeback;

/**
 * How a write-back flush reports a failure to its caller.
 *
 * <p>The distinction exists because the two flush paths want opposite things: the periodic pass has
 * nobody to report to and must keep running, while an explicit flush is a durability request whose
 * caller has to learn that the write did not land.
 */
public enum FlushMode {

    /** Periodic/shutdown pass: failures are only logged; the returned future always completes normally. */
    BACKGROUND,

    /** Caller-initiated pass: any failure completes the returned future exceptionally. */
    FORCED
}
