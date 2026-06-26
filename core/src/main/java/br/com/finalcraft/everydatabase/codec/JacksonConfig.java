package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Centralised Jackson configuration for EveryDatabase codecs, expressed as named
 * <b>profiles</b> instead of loose flags (mirroring {@code StorageLogConfig}'s
 * {@code defaults()}/{@code silent()}/{@code verbose()} presets). The
 * {@link JacksonJsonCodec} and {@link JacksonYamlCodec} default mappers are built
 * with {@link #storageSafe(ObjectMapper)}.
 *
 * <h2>Shared foundation: read contract + canonical map ordering</h2>
 *
 * <p>Every profile first applies {@link #baseReadContract(ObjectMapper)}, which sets:
 * <ul>
 *   <li>the frozen <b>read contract</b> - the {@code java.time}
 *       ({@link JavaTimeModule}) and {@code Optional} ({@link Jdk8Module}) datatype
 *       modules, plus tolerance of unknown properties; and</li>
 *   <li>canonical <b>map ordering</b> ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}):
 *       map entries are always emitted sorted by key, so the same logical content
 *       produces identical bytes regardless of the {@code Map} implementation or
 *       insertion order (deterministic, diff-friendly output on every profile).</li>
 * </ul>
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
 * deprecated in Jackson 2.x. (Map-entry ordering above is a {@code SerializationFeature},
 * not a {@code MapperFeature}, so it is safe to set this way.)
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
     * older schema (carrying since-removed fields) still deserialises - plus the one
     * universal <b>write</b> default, {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS},
     * so map entries are emitted in a canonical, deterministic key order on every profile.
     *
     * @param mapper the mapper to configure (mutated in place)
     * @return the same {@code mapper}, for chaining
     */
    public static <M extends ObjectMapper> M baseReadContract(M mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return mapper;
    }

    /**
     * The default profile: round-trip fidelity and schema-evolution tolerance. On top of
     * {@link #baseReadContract(ObjectMapper)}, dates and durations serialise as ISO-8601
     * text (not numeric arrays/epochs), so the output is portable and human-readable.
     * Null properties are kept.
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
        return mapper;
    }

    /**
     * The space-saving profile: identical to {@link #storageSafe(ObjectMapper)} - same
     * ISO-8601 dates and canonical key ordering - but drops every property whose value is
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
