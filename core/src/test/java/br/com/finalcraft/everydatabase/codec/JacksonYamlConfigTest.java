package br.com.finalcraft.everydatabase.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The YAML mirror of {@link JacksonConfigTest}: the {@link JacksonConfig} profiles apply
 * the same rules to a {@link YAMLMapper} (since {@code YAMLMapper extends ObjectMapper}),
 * so dates, {@code Optional}, null/absent dropping and canonical key ordering behave
 * identically - only the surface syntax differs (YAML block style vs JSON).
 *
 * <p>Reuses {@link JacksonConfigTest#sample()} / {@link JacksonConfigTest.EventDTO} so the
 * exact same object is compared across both formats.
 */
@DisplayName("JacksonConfig - YAML profile serialisation matrix")
class JacksonYamlConfigTest {

    private static String yaml(ObjectMapper mapper) throws Exception {
        return mapper.writeValueAsString(JacksonConfigTest.sample());
    }

    // ================================================================================
    //  Exact YAML output per profile (the "visibly desired" shape)
    // ================================================================================

    @Test
    @DisplayName("baseReadContract -> numeric/array dates, nulls kept, map ordered by key")
    void baseReadContract_yaml() throws Exception {
        assertEquals("""
            ---
            name: "Deploy"
            id: "00000000-0000-0000-0000-000000000001"
            count: 7
            instant: 1782397800.000000000
            localDate:
            - 2026
            - 6
            - 25
            localDateTime:
            - 2026
            - 6
            - 25
            - 14
            - 30
            duration: 90.000000000
            date: 1782397800000
            timestamp: 1782397800000
            optionalPresent: "yes"
            optionalEmpty: null
            optInt: 5
            nullName: null
            counts:
              errors: 2
              ok: 40
            """.stripTrailing(),
            yaml(JacksonConfig.baseReadContract(new YAMLMapper())).stripTrailing());
    }

    @Test
    @DisplayName("storageSafe -> ISO-8601 dates, nulls kept, map ordered by key")
    void storageSafe_yaml() throws Exception {
        assertEquals("""
            ---
            name: "Deploy"
            id: "00000000-0000-0000-0000-000000000001"
            count: 7
            instant: "2026-06-25T14:30:00Z"
            localDate: "2026-06-25"
            localDateTime: "2026-06-25T14:30:00"
            duration: "PT1M30S"
            date: "2026-06-25T14:30:00.000+00:00"
            timestamp: "2026-06-25T14:30:00.000+00:00"
            optionalPresent: "yes"
            optionalEmpty: null
            optInt: 5
            nullName: null
            counts:
              errors: 2
              ok: 40
            """.stripTrailing(),
            yaml(JacksonConfig.storageSafe(new YAMLMapper())).stripTrailing());
    }

    @Test
    @DisplayName("compact -> ISO dates, drops null and absent (Optional.empty), map ordered by key")
    void compact_yaml() throws Exception {
        assertEquals("""
            ---
            name: "Deploy"
            id: "00000000-0000-0000-0000-000000000001"
            count: 7
            instant: "2026-06-25T14:30:00Z"
            localDate: "2026-06-25"
            localDateTime: "2026-06-25T14:30:00"
            duration: "PT1M30S"
            date: "2026-06-25T14:30:00.000+00:00"
            timestamp: "2026-06-25T14:30:00.000+00:00"
            optionalPresent: "yes"
            optInt: 5
            counts:
              errors: 2
              ok: 40
            """.stripTrailing(),
            yaml(JacksonConfig.compact(new YAMLMapper())).stripTrailing());
    }

    // ================================================================================
    //  Cross-format: YAML and JSON carry identical logical content per profile
    // ================================================================================

    @Test
    @DisplayName("YAML and JSON produce the same logical tree under every profile")
    void yamlEqualsJsonStructurally() throws Exception {
        record Profiles(java.util.function.Function<ObjectMapper, ObjectMapper> apply, String name) {}
        Profiles[] profiles = {
            new Profiles(JacksonConfig::baseReadContract, "base"),
            new Profiles(JacksonConfig::storageSafe, "storageSafe"),
            new Profiles(JacksonConfig::compact, "compact"),
        };
        for (Profiles p : profiles) {
            String json = p.apply.apply(new JsonMapper()).writeValueAsString(JacksonConfigTest.sample());
            String yaml = p.apply.apply(new YAMLMapper()).writeValueAsString(JacksonConfigTest.sample());
            assertEquals(new JsonMapper().readTree(json), new YAMLMapper().readTree(yaml),
                () -> "YAML and JSON must agree for profile " + p.name + "\nJSON: " + json + "\nYAML: " + yaml);
        }
    }

    // ================================================================================
    //  Targeted rule checks + round trip
    // ================================================================================

    @Test
    @DisplayName("Instant: numeric in base, ISO in storageSafe/compact (same as JSON)")
    void instant_dateForm() throws Exception {
        assertTrue(yaml(JacksonConfig.baseReadContract(new YAMLMapper())).contains("instant: 1782397800.000000000"));
        assertTrue(yaml(JacksonConfig.storageSafe(new YAMLMapper())).contains("instant: \"2026-06-25T14:30:00Z\""));
        assertTrue(yaml(JacksonConfig.compact(new YAMLMapper())).contains("instant: \"2026-06-25T14:30:00Z\""));
    }

    @Test
    @DisplayName("compact YAML drops null and empty Optional; map keys stay ordered")
    void compact_dropsAndOrders() throws Exception {
        String out = yaml(JacksonConfig.compact(new YAMLMapper()));
        assertFalse(out.contains("nullName"), out);
        assertFalse(out.contains("optionalEmpty"), out);
        assertTrue(out.indexOf("errors:") < out.indexOf("ok:"), out);
    }

    @Test
    @DisplayName("YAML codec round-trips every field back to byte-identical YAML")
    void storageSafe_yamlRoundTrips() throws Exception {
        ObjectMapper m = JacksonConfig.storageSafe(new YAMLMapper());
        String out = m.writeValueAsString(JacksonConfigTest.sample());
        JacksonConfigTest.EventDTO back = m.readValue(out, JacksonConfigTest.EventDTO.class);
        assertEquals(out, m.writeValueAsString(back), "re-encoding the decoded DTO must reproduce the same YAML");
    }
}
