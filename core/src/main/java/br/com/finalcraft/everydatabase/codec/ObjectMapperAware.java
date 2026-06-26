package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Capability a {@link Codec} implements when it serialises through a Jackson
 * {@link ObjectMapper} that it is willing to expose.
 *
 * <p>This lets Jackson-tree consumers reuse the <em>same</em> mapper the codec
 * serialises with - notably {@code IndexValueExtractor}, which builds the
 * secondary-index tree from an entity. When the codec carries custom modules,
 * date formats, or serialisers, the indexed form of a field would otherwise
 * disagree with the persisted form; sharing the mapper makes them agree by
 * construction. Non-Jackson codecs (protobuf, kryo, ...) simply do not implement
 * this interface, and consumers fall back to a default mapper.
 *
 * <p>This mirrors the project's capabilities-as-interfaces idiom (see
 * {@code TransactionalStorage}, {@code ChangeFeedStorage}): callers
 * {@code instanceof}-check rather than reading a boolean flag.
 */
public interface ObjectMapperAware {

    /**
     * The Jackson {@link ObjectMapper} this codec serialises with. Never {@code null}.
     */
    ObjectMapper objectMapper();
}
