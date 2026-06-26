package br.com.finalcraft.everydatabase.codec;

import br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@link Codec} implementation that serialises entities to/from YAML using Jackson
 * ({@code jackson-dataformat-yaml}).
 *
 * <p>Files written by this codec use the {@code .yml} extension when stored by
 * {@link LocalFileStorage}.
 *
 * <p>Usage:
 * <pre>{@code
 * Codec<PlayerData> codec = new JacksonYamlCodec<>(PlayerData.class);
 * }</pre>
 *
 * @param <V> the entity type
 */
public final class JacksonYamlCodec<V> implements Codec<V>, ObjectMapperAware {

    private static final ObjectMapper DEFAULT_MAPPER = JacksonConfig.storageSafe(new YAMLMapper());

    private final ObjectMapper mapper;
    private final Class<V>     type;

    /**
     * Creates a codec for {@code type} using the default {@link YAMLMapper}.
     */
    public JacksonYamlCodec(Class<V> type) {
        this(type, DEFAULT_MAPPER);
    }

    /**
     * Creates a codec for {@code type} using a custom {@link ObjectMapper}.
     * Use this constructor when you need custom serialisers, date formats, etc.
     * The mapper should be a {@link YAMLMapper} or compatible YAML-producing mapper.
     */
    public JacksonYamlCodec(Class<V> type, ObjectMapper mapper) {
        this.type   = type;
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(V value) throws CodecException {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new CodecException("Failed to encode " + type.getSimpleName() + " to YAML", e);
        }
    }

    @Override
    public V decode(byte[] data) throws CodecException {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new CodecException("Failed to decode " + type.getSimpleName() + " from YAML", e);
        }
    }

    @Override
    public String contentType() {
        return "application/yaml";
    }

    @Override
    public String fileExtension() {
        return "yml";
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
