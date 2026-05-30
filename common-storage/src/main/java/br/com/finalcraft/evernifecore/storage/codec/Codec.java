package br.com.finalcraft.evernifecore.storage.codec;

/**
 * Pluggable serialisation strategy for entity types.
 *
 * <p>A {@code Codec} converts an entity to/from a byte array. The encoding format
 * is identified by {@link #contentType()}, which allows backends that store raw bytes
 * (LocalFile, SQL blob) to log the format and potentially transcode it.</p>
 *
 * <p>Backends with a native representation (MongoDB BSON, SQL columns) may wrap
 * the byte array internally rather than using the Codec directly.</p>
 *
 * @param <V> the entity type
 */
public interface Codec<V> {

    /**
     * Encodes the entity to bytes.
     *
     * @throws CodecException on serialisation failure
     */
    byte[] encode(V value) throws CodecException;

    /**
     * Decodes the entity from bytes.
     *
     * @throws CodecException on deserialisation failure
     */
    V decode(byte[] data) throws CodecException;

    /**
     * MIME content-type of the encoded form, e.g. {@code "application/json"},
     * {@code "application/yaml"}.
     */
    String contentType();

    /**
     * File extension (without the leading dot) used by backends that store one
     * entity per file, e.g. {@code "json"} or {@code "yml"}.
     *
     * <p>The default implementation derives the extension from {@link #contentType()}:
     * {@code "application/json"} → {@code "json"}, {@code "application/yaml"} → {@code "yml"}.
     * Override when the content-type does not map cleanly to a conventional extension.
     */
    default String fileExtension() {
        String ct = contentType();
        if (ct.contains("yaml") || ct.contains("yml")) return "yml";
        if (ct.contains("json"))                        return "json";
        // Fallback: last segment after '/', strip any '+suffix' (e.g. application/x-protobuf -> protobuf)
        String sub = ct.contains("/") ? ct.substring(ct.lastIndexOf('/') + 1) : ct;
        int plus = sub.indexOf('+');
        return plus >= 0 ? sub.substring(plus + 1) : sub;
    }
}
