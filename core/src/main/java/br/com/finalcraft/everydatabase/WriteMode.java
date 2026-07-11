package br.com.finalcraft.everydatabase;

/**
 * Controls whether a batch write may create rows or only update existing ones.
 *
 * <p>The default for every {@code save}/{@code saveAll} is {@link #UPSERT}. {@link #UPDATE_ONLY} exists
 * for maintenance passes that must never resurrect a row deleted concurrently (e.g. a full-collection
 * rewrite running while other work deletes rows): an update against an absent key is a silent no-op
 * instead of an insert.
 *
 * @see Repository#saveAll(java.util.Collection, WriteMode)
 */
public enum WriteMode {

    /** Insert when absent, replace when present (the normal {@code save} semantics). */
    UPSERT,

    /**
     * Update when present, no-op when absent - never inserts. On a versioned descriptor the update is
     * still guarded by the optimistic-lock version, so a concurrent update is reported as a conflict
     * while a concurrent delete is absorbed as a no-op.
     */
    UPDATE_ONLY
}
