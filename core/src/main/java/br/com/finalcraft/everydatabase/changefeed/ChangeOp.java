package br.com.finalcraft.everydatabase.changefeed;

/**
 * The kind of change a {@link ChangeEvent} reports.
 *
 * <p>Collection-level resets (e.g. a Mongo {@code drop}/{@code invalidate}) are not modeled as
 * events; a source handles them by re-subscribing rather than emitting a {@code ChangeOp}.
 */
public enum ChangeOp {

    /** An insert, update, or replace - the entity now exists with new state. */
    SAVE,

    /** The entity was removed. */
    DELETE
}
