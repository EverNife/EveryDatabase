package br.com.finalcraft.everydatabase.changefeed;

import java.util.Objects;
import java.util.function.Function;

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
 *   <li>{@link #version()} - the optimistic-lock version after the change, or {@link #UNKNOWN_VERSION}
 *       ({@code -1}) when unknown / the entity is not versioned. A {@link ChangeOp#DELETE} always
 *       carries {@code UNKNOWN_VERSION} (there is no post-delete version). {@code -1} is the only
 *       negative value ever exposed - any other negative is normalised to it in the constructor, so a
 *       stray negative from a custom version getter cannot masquerade as a real version. Informational
 *       only - a reload reads authoritative state regardless.</li>
 *   <li>{@link #originId()} - the {@link ChangeFeedStorage#originId()} of the instance that
 *       produced the change, or {@code null}/empty when the source cannot attribute it (a Mongo
 *       oplog event, a database trigger). Lets a consumer skip its own writes.</li>
 *   <li>{@link #backendId()} - the identity of the physical store the change happened in
 *       ({@code Storage.backendIdentity()}), or {@code null} when the source does not stamp it. Lets
 *       a consumer reading a shared channel ignore a change that happened in a store it does not
 *       use. A native feed leaves it {@code null}: it is already scoped to one storage.</li>
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
    private final String backendId;

    public ChangeEvent(String collection, String key, ChangeOp op, long version, String originId,
                       String backendId) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.key        = Objects.requireNonNull(key, "key");
        this.op         = Objects.requireNonNull(op, "op");
        // Collapse any negative version to the single UNKNOWN sentinel, so -1 is the only negative a
        // consumer ever sees and can reliably test with `version >= 0` for "real version present".
        this.version    = version < UNKNOWN_VERSION ? UNKNOWN_VERSION : version;
        this.originId   = originId;
        this.backendId  = backendId;
    }

    /** An event from a source that does not name the store it happened in. */
    public ChangeEvent(String collection, String key, ChangeOp op, long version, String originId) {
        this(collection, key, op, version, originId, null);
    }

    /**
     * The version to stamp on a change event for {@code entity}: {@link #UNKNOWN_VERSION} when the
     * descriptor is not versioned ({@code versionGetter == null}), otherwise the entity's version -
     * a never-persisted entity whose {@code Long} version is still {@code null} reads as {@code 0}.
     * Shared by the backends so they all attribute the same version to a change.
     */
    public static <V> long versionFor(Function<V, Long> versionGetter, V entity) {
        if (versionGetter == null) return UNKNOWN_VERSION;
        Long v = versionGetter.apply(entity);
        return v != null ? v : 0L;
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

    /**
     * The identity of the store the change happened in, or {@code null} when the source does not
     * stamp one. A consumer that cannot tell which store an event came from must treat it as
     * relevant to every store it watches - that is the behaviour of every producer written before
     * the field existed.
     */
    public String backendId()  { return backendId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeEvent)) return false;
        ChangeEvent that = (ChangeEvent) o;
        return version == that.version
                && collection.equals(that.collection)
                && key.equals(that.key)
                && op == that.op
                && Objects.equals(originId, that.originId)
                && Objects.equals(backendId, that.backendId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collection, key, op, version, originId, backendId);
    }

    @Override
    public String toString() {
        return "ChangeEvent{" + op + " " + collection + "/" + key
                + (version >= 0 ? " v" + version : "")
                + (originId != null && !originId.isEmpty() ? " from " + originId : "")
                + (backendId != null && !backendId.isEmpty() ? " at " + backendId : "")
                + "}";
    }
}
