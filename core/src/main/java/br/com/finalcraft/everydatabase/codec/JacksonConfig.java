package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Centralised Jackson configuration for EveryDatabase codecs, expressed as named
 * <b>profiles</b> instead of loose flags (mirroring {@code StorageLogConfig}'s
 * {@code defaults()}/{@code silent()}/{@code verbose()} presets). The
 * {@link JacksonJsonCodec} and {@link JacksonYamlCodec} default mappers are built
 * with {@link #storageSafe(ObjectMapper)}.
 *
 * <h2>Shared foundation: the read contract</h2>
 *
 * <p>Every profile first applies {@link #baseReadContract(ObjectMapper)}, which sets the
 * frozen <b>read contract</b> - the {@code java.time} ({@link JavaTimeModule}) and
 * {@code Optional} ({@link Jdk8Module}) datatype modules, plus tolerance of unknown
 * properties.
 *
 * <h2>Numbers keep the shape they were written with</h2>
 *
 * <p>No profile rounds a number: a {@link java.math.BigDecimal} keeps its scale, an undeclared
 * number (a field typed {@code Object}, a {@code Map} value) is read as a {@code BigDecimal} rather
 * than a {@code double}, and {@link #exactTreeReader(ObjectMapper)} parses one back without a detour
 * through {@code double} even under a caller's own mapper. A number's scale is data as often as a
 * map's order is - {@code 2.50} is a price, {@code 2.5} is a measurement - and nothing persists the
 * original alongside a rounded copy.
 *
 * <h2>Dates keep their zone</h2>
 *
 * <p>A {@link java.time.ZonedDateTime} is written with its zone id and read back in that zone; an
 * {@link java.time.OffsetDateTime} keeps its offset. Jackson's defaults rewrite both to UTC on read
 * - the same instant, a different local time - which loses the half of the value that is usually the
 * reason the type was chosen over {@link java.time.Instant}.
 *
 * <h2>Map entries keep their insertion order</h2>
 *
 * <p>No profile reorders {@code Map} entries: they are written in the order the map
 * iterates, which for a {@link java.util.LinkedHashMap} is the insertion order. That
 * matters because a map's order is frequently part of the data itself (numbered text
 * segments, ordered menu slots, step sequences) and nothing ever persists the original
 * order alongside a reordered payload - so sorting on write destroys it permanently, on
 * the very first save.
 *
 * <p>Because the read contract is fixed - and the date module reads <em>both</em> the
 * numeric (epoch) and ISO-8601 forms on input - any profile can read what any other
 * profile wrote. Switching the write profile of an existing collection therefore never
 * breaks reads of its existing data; the profiles differ only in how they <b>write</b>
 * dates and whether they omit null/absent properties.
 *
 * <h2>Mutate-and-return</h2>
 *
 * <p>Each method mutates and returns the given mapper, so it composes over any
 * Jackson mapper - JSON or YAML alike, since {@code YAMLMapper extends
 * ObjectMapper}: {@code JacksonConfig.storageSafe(new YAMLMapper())}. The generic
 * {@code <M extends ObjectMapper>} preserves the concrete mapper type. Mutation
 * happens once at construction, so the result is safe for concurrent use
 * afterwards (standard Jackson contract).
 *
 * <p>{@code MapperFeature}-based knobs (e.g. alphabetical <em>property</em> sorting) are
 * deliberately omitted: mutating them on an already-built {@link ObjectMapper} is
 * deprecated in Jackson 2.x.
 */
public final class JacksonConfig {

    private JacksonConfig() {
        // Static utility class.
    }

    /**
     * The shared foundation every profile builds on. Sets the frozen <b>read contract</b>
     * - registers the {@code java.time} ({@link JavaTimeModule}) and {@code Optional}
     * ({@link Jdk8Module}) datatype modules and disables
     * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} so data written by an
     * older schema (carrying since-removed fields) still deserialises.
     *
     * <p>It also fixes the fidelity invariants every profile inherits: {@code Map} entries are never
     * reordered, a {@link java.math.BigDecimal} never loses its scale, an undeclared number is read
     * as a {@code BigDecimal} rather than a {@code double}, and a date keeps the zone it was written
     * in instead of being rewritten to UTC.
     *
     * @param mapper the mapper to configure (mutated in place)
     * @return the same {@code mapper}, for chaining
     */
    public static <M extends ObjectMapper> M baseReadContract(M mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // A BigDecimal keeps the scale it was built with: 2.50 stays 2.50, not 2.5, and 100 stays
        // 100, not 1E+2. Jackson strips those zeros when it builds a tree node, and the tree is what
        // the index extractor reads and what the aggregate-file and in-memory backends store - so
        // leaving it on rewrites the number on its way to disk. (The node factory, rather than
        // JsonNodeFeature: the feature can only be set while the mapper is being built.)
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        // A number with no declared Java type to land in - a field typed Object, a Map value - is
        // read as a BigDecimal instead of a double, so it survives the same way a declared one does.
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // A date keeps the zone it was written in. Jackson's default rewrites every offset date to
        // the context zone (UTC) on read, which turns 10:15-03:00 into 13:15Z: the same instant, a
        // different local time - and the local time is usually the reason the type was chosen.
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return mapper;
    }

    /**
     * A reader over {@code mapper} that parses JSON numbers <b>losslessly</b> into a tree: a
     * fractional number becomes a {@code DecimalNode} carrying every digit it was written with,
     * instead of the {@code double} Jackson parses by default (which caps a number at ~17
     * significant digits and cannot hold {@code 2.50} apart from {@code 2.5}).
     *
     * <p>The profiles here already set both on the mappers they build; this exists for the mapper
     * they did not build - a caller's own, passed to {@code new JacksonJsonCodec<>(Type.class,
     * mapper)}. The paths that hop through an intermediate tree (the aggregate file store, the Mongo
     * document bridge, a scan backend filtering before it decodes) read through this, so a custom
     * mapper cannot silently put a {@code double} where the caller saved an exact number.
     *
     * @param mapper the mapper whose modules and configuration the reader inherits
     * @return a reader for tree parsing; the mapper itself is not modified
     */
    public static ObjectReader exactTreeReader(ObjectMapper mapper) {
        return mapper.reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    /**
     * The default profile: round-trip fidelity and schema-evolution tolerance. On top of
     * {@link #baseReadContract(ObjectMapper)}, dates and durations serialise as ISO-8601
     * text (not numeric arrays/epochs) with the zone id where the type carries one, so the output is
     * portable and human-readable. Null properties are kept, and {@code Map} entries keep their
     * insertion order, so a decoded entity re-encodes to the same bytes it was read from.
     *
     * <p>This is the mapper used by {@link JacksonJsonCodec} and {@link JacksonYamlCodec}
     * when the caller supplies no custom mapper.
     *
     * @param mapper the mapper to configure (mutated in place)
     * @return the same {@code mapper}, for chaining
     */
    public static <M extends ObjectMapper> M storageSafe(M mapper) {
        baseReadContract(mapper);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        // A ZonedDateTime writes its zone id ("...-03:00[America/Sao_Paulo]"), not just the offset
        // it happened to have that day. Without the id the zone cannot be recovered, and a value
        // saved in a zone with daylight saving reads back as a fixed offset - right for that
        // instant, wrong for arithmetic on any other. An OffsetDateTime is unaffected: its offset
        // is all there is to it.
        mapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);
        return mapper;
    }

    /**
     * The space-saving profile: identical to {@link #storageSafe(ObjectMapper)} - same
     * ISO-8601 dates and same map order - but drops every property whose value is
     * {@code null} or an <em>absent</em> {@code Optional} ({@link JsonInclude.Include#NON_ABSENT}).
     * Empty collections, empty strings and {@code 0} are kept; only "no value" is omitted.
     *
     * <p>Because it keeps the same dates and ordering, it is fully interchange-compatible
     * with {@code storageSafe}: a collection can switch between the two without a data
     * migration, and a reader recovers identical objects either way (an omitted property
     * simply deserialises back to {@code null}/{@code Optional.empty()}).
     *
     * @param mapper the mapper to configure (mutated in place)
     * @return the same {@code mapper}, for chaining
     */
    public static <M extends ObjectMapper> M compact(M mapper) {
        storageSafe(mapper);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
        return mapper;
    }
}
