package br.com.finalcraft.everydatabase.manager.writeback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Signals that a {@link FlushMode#FORCED} write-back flush could NOT persist one or more entities
 * because the write itself failed (storage down, I/O error) - as opposed to an
 * {@link OptimisticConflictException}, where the write was beaten by another instance.
 *
 * <p>The failed entities were re-marked dirty, so the periodic background flush keeps retrying them;
 * this exception only tells the CALLER that its explicit durability request did not land. A
 * {@link FlushMode#BACKGROUND} flush never raises this - it logs and retries.
 */
public final class StorageWriteException extends RuntimeException {

    private final String what;
    private final List<Object> failedKeys;

    public StorageWriteException(String what, Collection<?> failedKeys) {
        super("Failed to persist " + what + " " + failedKeys + " (storage down?). The entities stay"
                + " dirty and the background flush will retry them, but this force-save did NOT land.");
        this.what = what;
        this.failedKeys = Collections.unmodifiableList(new ArrayList<>(failedKeys));
    }

    /** Human-readable id of the entity kind whose write failed, as passed to the flush. */
    public String getWhat() {
        return what;
    }

    /** The keys whose write failed (re-marked dirty; retried by the background flush). */
    public List<Object> getFailedKeys() {
        return failedKeys;
    }
}
