package br.com.finalcraft.evernifecore.storage.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorageLogConfig}: factory defaults, presets, per-topic overrides,
 * the {@code mute} floor, the {@value StorageLogConfig#SYSTEM_PROPERTY_DEFAULT_LEVEL}
 * override, the progress clamp, and concurrent edits.
 *
 * <p>The Gradle test task sets {@code evernifecore.storage.log.level=info} for the whole
 * test JVM, so tests around {@link StorageLogConfig#defaults()} save, set and restore the
 * property explicitly instead of assuming it is absent.
 */
@DisplayName("StorageLogConfig - defaults, presets, overrides and thread-safety")
class StorageLogConfigTest {

    private String savedLevelProperty;

    @BeforeEach
    void savePropertyState() {
        savedLevelProperty = System.getProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL);
    }

    @AfterEach
    void restorePropertyState() {
        if (savedLevelProperty != null) {
            System.setProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL, savedLevelProperty);
        } else {
            System.clearProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL);
        }
    }

    // ------------------------------------------------------------------
    //  Factory defaults and presets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bare constructor: WARN default, privacy flags off, progress on")
    void bareConstructor_hasSpecDefaults() {
        StorageLogConfig cfg = new StorageLogConfig();

        assertEquals(StorageLogLevel.WARN, cfg.defaultLevel(), "Factory default must be WARN (spec 2.2)");
        assertFalse(cfg.includeKeys(),        "includeKeys must default to false (spec 2.4)");
        assertFalse(cfg.includeValues(),      "includeValues must default to false");
        assertFalse(cfg.includeQueryValues(), "includeQueryValues must default to false");
        assertEquals(10,   cfg.maxKeysListed());
        assertEquals(200,  cfg.maxValueLength());
        assertTrue(cfg.isProgressEnabled());
        assertEquals(10,   cfg.progressStepPercent());
        assertEquals(1000, cfg.progressThrottleMs());
        assertEquals(500,  cfg.progressMinTotal());
        assertNotNull(cfg.sink(), "A default sink (auto) must be present");
    }

    @Test
    @DisplayName("defaults() without the system property -> WARN")
    void defaults_withoutSystemProperty_isWarn() {
        System.clearProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL);
        assertEquals(StorageLogLevel.WARN, StorageLogConfig.defaults().defaultLevel());
    }

    @Test
    @DisplayName("defaults() honors the system property override (case-insensitive)")
    void defaults_systemPropertyOverridesLevel() {
        System.setProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL, "debug");
        assertEquals(StorageLogLevel.DEBUG, StorageLogConfig.defaults().defaultLevel());

        System.setProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL, "TRACE");
        assertEquals(StorageLogLevel.TRACE, StorageLogConfig.defaults().defaultLevel());
    }

    @Test
    @DisplayName("defaults() with an invalid system property value falls back to WARN")
    void defaults_invalidSystemProperty_fallsBackToWarn() {
        System.setProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL, "not-a-level");
        assertEquals(StorageLogLevel.WARN, StorageLogConfig.defaults().defaultLevel());
    }

    @Test
    @DisplayName("presets: silent()=OFF, verbose()=DEBUG, trace()=TRACE - independent of the property")
    void presets_setExpectedDefaultLevels() {
        // The presets build on the bare constructor, never on the system property.
        System.setProperty(StorageLogConfig.SYSTEM_PROPERTY_DEFAULT_LEVEL, "info");

        assertEquals(StorageLogLevel.OFF,   StorageLogConfig.silent().defaultLevel());
        assertEquals(StorageLogLevel.DEBUG, StorageLogConfig.verbose().defaultLevel());
        assertEquals(StorageLogLevel.TRACE, StorageLogConfig.trace().defaultLevel());
    }

    @Test
    @DisplayName("silent() still lets ERROR through on every topic (floor)")
    void silent_keepsErrorFloor() {
        StorageLogConfig cfg = StorageLogConfig.silent();
        for (StorageLogTopic topic : StorageLogTopic.values()) {
            assertTrue(cfg.isEnabled(topic, StorageLogLevel.ERROR),
                "silent() must not suppress ERROR on topic " + topic);
            assertFalse(cfg.isEnabled(topic, StorageLogLevel.WARN),
                "silent() must suppress WARN on topic " + topic);
        }
    }

    // ------------------------------------------------------------------
    //  Per-topic overrides
    // ------------------------------------------------------------------

    @Test
    @DisplayName("level(topic, ...) overrides only that topic; others keep the default")
    void level_perTopicOverrideBeatsDefault() {
        StorageLogConfig cfg = new StorageLogConfig()
            .level(StorageLogTopic.INDEX, StorageLogLevel.TRACE);

        assertEquals(StorageLogLevel.TRACE, cfg.effectiveLevel(StorageLogTopic.INDEX));
        assertEquals(StorageLogLevel.WARN,  cfg.effectiveLevel(StorageLogTopic.WRITE),
            "Topics without an override must fall back to defaultLevel");

        assertTrue(cfg.isEnabled(StorageLogTopic.INDEX,  StorageLogLevel.DEBUG));
        assertFalse(cfg.isEnabled(StorageLogTopic.WRITE, StorageLogLevel.DEBUG));
    }

    @Test
    @DisplayName("mute(topic) drops the topic to ERROR - never below the floor")
    void mute_setsTopicToErrorFloor() {
        StorageLogConfig cfg = StorageLogConfig.verbose().mute(StorageLogTopic.WRITE);

        assertEquals(StorageLogLevel.ERROR, cfg.effectiveLevel(StorageLogTopic.WRITE));
        assertFalse(cfg.isEnabled(StorageLogTopic.WRITE, StorageLogLevel.WARN),
            "Muted topic must block WARN and below");
        assertTrue(cfg.isEnabled(StorageLogTopic.WRITE, StorageLogLevel.ERROR),
            "Muted topic must still emit ERROR (floor)");
        assertTrue(cfg.isEnabled(StorageLogTopic.READ, StorageLogLevel.DEBUG),
            "Other topics keep the verbose() default");
    }

    @Test
    @DisplayName("reset(topic) removes the override and falls back to the default")
    void reset_removesOverride() {
        StorageLogConfig cfg = new StorageLogConfig()
            .level(StorageLogTopic.QUERY, StorageLogLevel.TRACE);
        assertEquals(StorageLogLevel.TRACE, cfg.effectiveLevel(StorageLogTopic.QUERY));

        cfg.reset(StorageLogTopic.QUERY);
        assertEquals(StorageLogLevel.WARN, cfg.effectiveLevel(StorageLogTopic.QUERY));
    }

    @Test
    @DisplayName("fluent setters return the same instance for chaining")
    void fluentSetters_returnThis() {
        StorageLogConfig cfg = new StorageLogConfig();
        assertSame(cfg, cfg.defaultLevel(StorageLogLevel.INFO)
            .level(StorageLogTopic.INDEX, StorageLogLevel.DEBUG)
            .mute(StorageLogTopic.READ)
            .reset(StorageLogTopic.READ)
            .includeKeys(true)
            .maxKeysListed(5)
            .includeValues(true)
            .maxValueLength(50)
            .includeQueryValues(true)
            .progress(true, 25, 500, 100)
            .sink(StorageLogSinks.noop()));
    }

    // ------------------------------------------------------------------
    //  Progress clamp
    // ------------------------------------------------------------------

    @Test
    @DisplayName("progress() clamps stepPercent to [1, 100]")
    void progress_clampsStepPercent() {
        StorageLogConfig cfg = new StorageLogConfig();

        cfg.progress(true, 0, 1000, 500);
        assertEquals(1, cfg.progressStepPercent(), "stepPct below 1 must clamp to 1");

        cfg.progress(true, -10, 1000, 500);
        assertEquals(1, cfg.progressStepPercent(), "Negative stepPct must clamp to 1");

        cfg.progress(true, 250, 1000, 500);
        assertEquals(100, cfg.progressStepPercent(), "stepPct above 100 must clamp to 100");

        cfg.progress(true, 25, 1000, 500);
        assertEquals(25, cfg.progressStepPercent(), "In-range stepPct must be kept as-is");
    }

    // ------------------------------------------------------------------
    //  Concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("concurrent edits and reads never throw and end in a deterministic state")
    void concurrentEdits_areSafe() throws Exception {
        StorageLogConfig cfg = new StorageLogConfig();
        int threads = 8;
        int iterations = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                // Each thread owns a distinct topic, so the final reset() per topic is deterministic.
                StorageLogTopic topic = StorageLogTopic.values()[t];
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        cfg.level(topic, StorageLogLevel.DEBUG);
                        cfg.isEnabled(topic, StorageLogLevel.INFO);
                        cfg.mute(topic);
                        cfg.effectiveLevel(topic);
                        cfg.defaultLevel(StorageLogLevel.WARN);
                        cfg.reset(topic);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // propagates any worker exception
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(StorageLogLevel.WARN, cfg.defaultLevel());
        for (StorageLogTopic topic : StorageLogTopic.values()) {
            assertEquals(StorageLogLevel.WARN, cfg.effectiveLevel(topic),
                "After every thread's final reset(), topic " + topic + " must fall back to the default");
        }
    }
}
