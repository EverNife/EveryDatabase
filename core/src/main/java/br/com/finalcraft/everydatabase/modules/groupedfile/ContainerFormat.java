package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.codec.Codec;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * The physical format of the aggregate documents in one base directory: JSON or YAML, plus the
 * mapper and file extension that follow from it.
 *
 * <p>It lives above the key-file stores rather than inside one because the format is a property of
 * the <em>directory tree</em>, not of any single group of files: every collection stored under one
 * base directory writes the same documents and must agree on how they are written. Keeping the
 * decision here also means the format can be reconciled against what is already on disk (see
 * {@link GroupedFileLayout}) before any store touches a file.
 *
 * <p>The format is resolved lazily, from the first descriptor's {@link Codec}, and is then locked
 * for the storage's lifetime.
 */
final class ContainerFormat {

    static final String JSON = "json";
    static final String YAML = "yaml";

    static final String JSON_EXTENSION = ".json";
    static final String YAML_EXTENSION = ".yml";

    private volatile ObjectMapper mapper;
    private volatile String       extension;
    private volatile Boolean      yaml;       // null until resolved

    /**
     * Resolves and locks the format from a descriptor's codec. JSON ({@link Codec#isJsonCodec()})
     * and YAML (content-type) are supported; any other codec (opaque/binary) cannot be embedded into
     * a structured aggregate document and is rejected.
     *
     * @throws IllegalArgumentException if the codec is neither JSON nor YAML
     * @throws IllegalStateException    if a later codec resolves to a different format than the first
     */
    synchronized void resolve(Codec<?> codec) {
        boolean wantYaml = isYaml(codec);
        if (yaml == null) {
            yaml      = wantYaml;
            extension = wantYaml ? YAML_EXTENSION : JSON_EXTENSION;
            mapper    = wantYaml ? newYamlMapper() : newJsonMapper();
        } else if (yaml != wantYaml) {
            throw new IllegalStateException(
                "GroupedFileStorage: all collections in one base directory must share a container format, "
                + "but got both " + nameOf(yaml) + " and " + nameOf(wantYaml)
                + " codecs. Use a single format (all JSON or all YAML) per base directory.");
        }
    }

    boolean isResolved() {
        return yaml != null;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /** {@code ".json"} or {@code ".yml"}; {@code null} until the format is resolved. */
    String extension() {
        return extension;
    }

    /** {@code "json"} or {@code "yaml"} - the name as written into the layout file. */
    String name() {
        return yaml == null ? null : nameOf(yaml);
    }

    static String nameOf(boolean yaml) {
        return yaml ? YAML : JSON;
    }

    /** The extension a format name implies, or {@code null} when the name is not one this knows. */
    static String extensionOf(String formatName) {
        if (JSON.equals(formatName)) return JSON_EXTENSION;
        if (YAML.equals(formatName)) return YAML_EXTENSION;
        return null;
    }

    private static boolean isYaml(Codec<?> codec) {
        if (codec.isJsonCodec()) return false;
        String ct = codec.contentType().toLowerCase();
        if (ct.contains("yaml") || ct.contains("yml")) return true;
        throw new IllegalArgumentException(
            "GroupedFileStorage requires a JSON or YAML codec (the aggregate file is a structured "
            + "document); got contentType=" + codec.contentType());
    }

    private static ObjectMapper newJsonMapper() {
        // Local files are meant to be human-inspectable - keep the aggregate document indented.
        return exactNumbers(JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build());
    }

    private static ObjectMapper newYamlMapper() {
        // Drop the leading "---" document-start marker so files read like a plain config.
        return exactNumbers(YAMLMapper.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build());
    }

    /**
     * The aggregate document is a tree, and every value in it makes a round trip through that tree on
     * its way to and from the file. Parsing a decimal the default way (as a {@code double}) would
     * rewrite the number the entity was saved with - so this mapper reads every fractional number as
     * a {@code BigDecimal}, scale included. It never binds a POJO, so nothing else is affected.
     */
    private static ObjectMapper exactNumbers(ObjectMapper mapper) {
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        return mapper;
    }
}
