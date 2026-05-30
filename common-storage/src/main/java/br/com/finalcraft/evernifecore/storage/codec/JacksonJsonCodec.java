package br.com.finalcraft.evernifecore.storage.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * {@link Codec} implementation that serializes entities to/from JSON using Jackson 3.x.
 *
 * <p>Usage:
 * <pre>{@code
 * Codec<PlayerData> codec = new JacksonJsonCodec<>(PlayerData.class);
 * }</pre>
 *
 * @param <V> the entity type
 */
public final class JacksonJsonCodec<V> implements Codec<V> {

    private static final ObjectMapper PRETTY_MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final ObjectMapper mapper;
    private final Class<V> type;

    /**
     * Creates a codec for {@code type} using a default Jackson {@link ObjectMapper}.
     */
    public JacksonJsonCodec(Class<V> type) {
        this(type, PRETTY_MAPPER);
    }

    /**
     * Creates a codec for {@code type} using a custom {@link ObjectMapper}.
     * Use this constructor when you need custom serialisers, date formats, etc.
     */
    public JacksonJsonCodec(Class<V> type, ObjectMapper mapper) {
        this.type   = type;
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(V value) throws CodecException {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new CodecException("Failed to encode " + type.getSimpleName() + " to JSON", e);
        }
    }

    @Override
    public V decode(byte[] data) throws CodecException {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new CodecException("Failed to decode " + type.getSimpleName() + " from JSON", e);
        }
    }

    @Override
    public String contentType() {
        return "application/json";
    }

}
