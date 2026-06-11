package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * {@link Codec} implementation that serializes entities to/from JSON using Jackson.
 *
 * <p>The default output is <b>compact</b> (no indentation): the database backends
 * (SQL, Mongo, InMemory) persist or re-parse the payload verbatim, so pretty-print
 * whitespace would only inflate storage and I/O. For human-readable per-entity files
 * in {@code LocalFileStorage}, use the {@link #pretty(Class)} factory instead.
 *
 * <p>Usage:
 * <pre>{@code
 * Codec<PlayerData> codec = new JacksonJsonCodec<>(PlayerData.class);   // compact
 * Codec<PlayerData> nice  = JacksonJsonCodec.pretty(PlayerData.class);  // indented
 * }</pre>
 *
 * @param <V> the entity type
 */
public final class JacksonJsonCodec<V> implements Codec<V> {

    private static final ObjectMapper COMPACT_MAPPER = JsonMapper.builder()
        .build();

    private static final ObjectMapper PRETTY_MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final ObjectMapper mapper;
    private final Class<V> type;

    /**
     * Creates a codec for {@code type} using a default (compact-output) Jackson
     * {@link ObjectMapper}.
     */
    public JacksonJsonCodec(Class<V> type) {
        this(type, COMPACT_MAPPER);
    }

    /**
     * Creates a codec for {@code type} using a custom {@link ObjectMapper}.
     * Use this constructor when you need custom serialisers, date formats, etc.
     */
    public JacksonJsonCodec(Class<V> type, ObjectMapper mapper) {
        this.type   = type;
        this.mapper = mapper;
    }

    /**
     * Creates a codec whose output is pretty-printed (indented).
     *
     * <p>Intended for {@code LocalFileStorage}, where a human may open the per-entity
     * files. Database backends should keep the compact default - they persist the
     * payload as-is, whitespace included.
     */
    public static <V> JacksonJsonCodec<V> pretty(Class<V> type) {
        return new JacksonJsonCodec<>(type, PRETTY_MAPPER);
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
