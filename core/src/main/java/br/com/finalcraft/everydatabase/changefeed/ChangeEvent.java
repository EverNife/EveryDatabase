package br.com.finalcraft.everydatabase.changefeed;

import java.util.Objects;

/**
 * An immutable notification that an entity changed in a backend - the unit a
 * {@link ChangeFeedStorage} pushes to its {@link ChangeListener}s so other instances can
 * invalidate their caches.
 *
 * <p>It deliberately sits <b>below</b> the typed layer and carries no entity content, only enough
 * to locate the changed entity and decide whether to act on it:
 * <ul>
 *   <li>{@link #collection()} - the collection (table/collection/dir) the entity lives in.</li>
 *   <li>{@link #key()} - the key in its <b>persisted form</b> ({@code key.toString()}); the
 *       cross-backend key contract already guarantees this is the canonical string form. A
 *       consumer parses it back to the typed key.</li>
 *   <li>{@link #op()} - {@link ChangeOp#SAVE} or {@link ChangeOp#DELETE}.</li>
 *   <li>{@link #version()} - the optimistic-lock version after the change, or {@code -1} when
 *       unknown / the entity is not versioned. Informational only - a reload reads authoritative
 *       state regardless.</li>
 *   <li>{@link #originId()} - the {@link ChangeFeedStorage#originId()} of the instance that
 *       produced the change, or {@code null}/empty when the source cannot attribute it (a Mongo
 *       oplog event, a database trigger). Lets a consumer skip its own writes.</li>
 * </ul>
 *
 * <p>Carries counts/identifiers, never entity values - the same privacy posture as the logging
 * system.
 */
public final class ChangeEvent {

    /** Sentinel {@link #version()} value meaning "unknown / not versioned". */
    public static final long UNKNOWN_VERSION = -1L;

    private final String collection;
    private final String key;
    private final ChangeOp op;
    private final long version;
    private final String originId;

    public ChangeEvent(String collection, String key, ChangeOp op, long version, String originId) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.key        = Objects.requireNonNull(key, "key");
        this.op         = Objects.requireNonNull(op, "op");
        this.version    = version;
        this.originId   = originId;
    }

    /** A {@link ChangeOp#SAVE} event with an unknown version and no origin. */
    public static ChangeEvent save(String collection, String key) {
        return new ChangeEvent(collection, key, ChangeOp.SAVE, UNKNOWN_VERSION, null);
    }

    /** A {@link ChangeOp#DELETE} event with an unknown version and no origin. */
    public static ChangeEvent delete(String collection, String key) {
        return new ChangeEvent(collection, key, ChangeOp.DELETE, UNKNOWN_VERSION, null);
    }

    public String collection() { return collection; }
    public String key()        { return key; }
    public ChangeOp op()       { return op; }
    public long version()      { return version; }

    /** The producing instance's origin id, or {@code null}/empty when unattributed. */
    public String originId()   { return originId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeEvent)) return false;
        ChangeEvent that = (ChangeEvent) o;
        return version == that.version
                && collection.equals(that.collection)
                && key.equals(that.key)
                && op == that.op
                && Objects.equals(originId, that.originId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collection, key, op, version, originId);
    }

    @Override
    public String toString() {
        return "ChangeEvent{" + op + " " + collection + "/" + key
                + (version >= 0 ? " v" + version : "")
                + (originId != null && !originId.isEmpty() ? " from " + originId : "")
                + "}";
    }
}
