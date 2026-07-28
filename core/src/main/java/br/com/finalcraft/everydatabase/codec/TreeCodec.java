package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Capability a {@link Codec} implements when it can convert directly to and from a Jackson tree,
 * skipping the byte form entirely.
 *
 * <p>A backend that already holds a tree - the key-major file backend embeds each entity as a
 * sub-node of a shared document, the in-memory backend round-trips entities to isolate them -
 * otherwise has to serialise that tree just so the codec can parse it back, or the reverse. The
 * bytes in between are pure overhead: they are never stored, never transmitted, and immediately
 * discarded.
 *
 * <p>This is <em>not</em> a shortcut for backends that persist the encoded bytes themselves. SQL
 * and Mongo write {@link Codec#encode} output into a JSON column or document, so for them the byte
 * form is the storage form and there is no round-trip to remove.
 *
 * <p>Follows the project's capabilities-as-interfaces idiom (see {@code TransactionalStorage},
 * {@code ChangeFeedStorage}, {@link ObjectMapperAware}): consumers {@code instanceof}-check and
 * fall back to {@link Codec#encode}/{@link Codec#decode}. A non-Jackson codec (protobuf, kryo)
 * simply does not implement this, and every backend keeps working.
 *
 * <p><b>Contract:</b> the tree form must be equivalent to the byte form - {@code decodeTree} of
 * {@code encodeTree} must round-trip an entity exactly as {@code decode} of {@code encode} does,
 * and either form must be readable by the other. {@link #encodeTree} must return a fresh tree on
 * every call: callers embed it into documents they go on to mutate.
 *
 * @param <V> the entity type
 */
public interface TreeCodec<V> {

    /**
     * The entity as a Jackson tree, equivalent to parsing {@link Codec#encode}'s output. Never
     * shared between calls - the caller may mutate or embed the result.
     *
     * @throws CodecException on serialisation failure
     */
    JsonNode encodeTree(V value) throws CodecException;

    /**
     * The entity read from a Jackson tree, equivalent to {@link Codec#decode} of that tree's
     * serialised form.
     *
     * @throws CodecException on deserialisation failure
     */
    V decodeTree(JsonNode node) throws CodecException;
}
