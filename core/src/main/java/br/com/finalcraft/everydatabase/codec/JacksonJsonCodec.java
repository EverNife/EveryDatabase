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
public final class JacksonJsonCodec<V> implements Codec<V>, ObjectMapperAware {

    private static final ObjectMapper STORAGE_SAFE_MAPPER =
        JacksonConfig.storageSafe(new JsonMapper());

    private static final ObjectMapper STORAGE_SAFE_PRETTY_MAPPER =
        JacksonConfig.storageSafe(new JsonMapper()).enable(SerializationFeature.INDENT_OUTPUT);

    private final ObjectMapper mapper;
    private final Class<V> type;

    /**
     * Creates a codec for {@code type} using a default (compact-output) Jackson
     * {@link ObjectMapper}.
     */
    public JacksonJsonCodec(Class<V> type) {
        this(type, STORAGE_SAFE_MAPPER);
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
        return new JacksonJsonCodec<>(type, STORAGE_SAFE_PRETTY_MAPPER);
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

    /**
     * Exposes the underlying mapper so index/tree consumers can serialise entities
     * with the exact same configuration this codec persists them with.
     */
    @Override
    public ObjectMapper objectMapper() {
        return mapper;
    }

}
