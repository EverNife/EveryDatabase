package br.com.finalcraft.everydatabase.manager.writeback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Signals that a {@link FlushMode#FORCED} write-back flush lost an optimistic-lock race: another
 * instance saved a newer version, so the local changes were discarded and the stored winner
 * re-adopted into the live instance (ADOPT_WINNER). The flusher fabricates this from the batch
 * report's conflicted keys, because {@code CachingManager.saveAllAndCache} never rethrows.
 *
 * <p>A {@link FlushMode#BACKGROUND} flush does NOT surface this - it only logs loudly. It is raised
 * only on the caller-initiated path, whose returned future completes exceptionally so the caller can
 * react to the lost write. The entity kind and the conflicted keys are exposed as structured state
 * ({@link #getWhat()} / {@link #getConflictedKeys()}) so a caller never has to parse the message.
 */
public final class OptimisticConflictException extends RuntimeException {

    private final String what;
    private final List<Object> conflictedKeys;

    public OptimisticConflictException(String what, Collection<?> conflictedKeys) {
        super("Optimistic lock conflict while force-saving " + what + " " + conflictedKeys
                + ": another instance saved a newer version. The stored winner was re-adopted into the"
                + " live instance and the local changes were discarded (ADOPT_WINNER).");
        this.what = what;
        this.conflictedKeys = Collections.unmodifiableList(new ArrayList<>(conflictedKeys));
    }

    /** Human-readable id of the entity kind that conflicted, as passed to the flush. */
    public String getWhat() {
        return what;
    }

    /** The keys whose save lost the optimistic-lock race (winner already adopted). */
    public List<Object> getConflictedKeys() {
        return conflictedKeys;
    }
}
