package br.com.finalcraft.evernifecore.storage.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorageLogSinks}: the {@code auto()} resolution order
 * (host > SLF4J > no-op), {@code installDefault}/{@code uninstallDefault}, and the
 * built-in {@code noop}/{@code consumer}/{@code structured}/{@code tee} sinks.
 *
 * <p>Classpath note: the test runtime ships {@code slf4j-api} (via HikariCP) plus the
 * {@code slf4j-simple} binding, so {@code Slf4jHolder.AVAILABLE} is {@code true} here.
 * The "no SLF4J -> no-op" leg of {@code auto()} cannot be exercised in this JVM; what the
 * suite locks down instead is that the probe is reflective (no {@code NoClassDefFoundError}
 * at class-load) and that an installed host sink always wins over SLF4J.
 */
@DisplayName("StorageLogSinks - auto() resolution order and built-in sinks")
class StorageLogSinksTest {

    private StorageLogSink previousHost;

    @BeforeEach
    void saveInstalledHost() {
        previousHost = StorageLogSinks.getInstalledDefault();
    }

    @AfterEach
    void restoreInstalledHost() {
        if (previousHost != null) {
            StorageLogSinks.installDefault(previousHost);
        } else {
            StorageLogSinks.uninstallDefault();
        }
    }

    private static StorageLogEvent sampleEvent() {
        return StorageLogEvent.builder("test", StorageOp.SAVE_BATCH, StorageLogLevel.INFO)
            .collection("players")
            .affected(5)
            .durationMs(12)
            .build();
    }

    // ------------------------------------------------------------------
    //  SLF4J probe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Slf4jHolder probe detects SLF4J on the test classpath without linking errors")
    void slf4jProbe_detectsSlf4jReflectively() {
        // Touching the holder forces its class-load; with a non-reflective probe this would
        // be the place a NoClassDefFoundError surfaces.
        assertTrue(StorageLogSinks.Slf4jHolder.AVAILABLE,
            "slf4j-api is on the test classpath, the reflective probe must find it");
        assertNotNull(StorageLogSinks.Slf4jHolder.SINK);
        assertTrue(StorageLogSinks.Slf4jHolder.SINK instanceof Slf4jStorageLogSink,
            "With SLF4J present the holder must instantiate the real SLF4J sink");
    }

    // ------------------------------------------------------------------
    //  auto() resolution order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("auto(): an installed host sink takes priority over SLF4J")
    void auto_installedHostTakesPriority() {
        List<StorageLogEvent> received = new ArrayList<>();
        StorageLogSinks.installDefault(received::add);

        StorageLogSinks.auto().accept(sampleEvent());

        assertEquals(1, received.size(), "The host sink must receive the event instead of SLF4J");
    }

    @Test
    @DisplayName("auto() resolves the destination per event, not at creation time")
    void auto_resolvesPerEvent() {
        StorageLogSink auto = StorageLogSinks.auto(); // created BEFORE the host is installed

        List<StorageLogEvent> received = new ArrayList<>();
        StorageLogSinks.installDefault(received::add);
        auto.accept(sampleEvent());
        assertEquals(1, received.size(), "A pre-existing auto() sink must route to the host installed later");

        StorageLogSinks.uninstallDefault();
        auto.accept(sampleEvent()); // falls back to SLF4J - must not reach the uninstalled host
        assertEquals(1, received.size(), "After uninstallDefault() the host must stop receiving events");
    }

    @Test
    @DisplayName("installDefault()/uninstallDefault() round-trip via getInstalledDefault()")
    void installUninstall_roundTrip() {
        StorageLogSink host = event -> {};

        StorageLogSinks.installDefault(host);
        assertSame(host, StorageLogSinks.getInstalledDefault());

        StorageLogSinks.uninstallDefault();
        assertNull(StorageLogSinks.getInstalledDefault());
    }

    // ------------------------------------------------------------------
    //  Built-in sinks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("noop() accepts events without any effect")
    void noop_acceptsSilently() {
        assertDoesNotThrow(() -> StorageLogSinks.noop().accept(sampleEvent()));
    }

    @Test
    @DisplayName("consumer() delivers the formatted line")
    void consumer_receivesFormattedLine() {
        List<String> lines = new ArrayList<>();
        StorageLogEvent event = sampleEvent();

        StorageLogSinks.consumer(lines::add).accept(event);

        assertEquals(1, lines.size());
        assertEquals(event.format(), lines.get(0));
        assertTrue(lines.get(0).contains("[storage:test] SAVE_BATCH players"),
            "Formatted line must carry backend, op and collection: " + lines.get(0));
    }

    @Test
    @DisplayName("structured() delivers the raw event instance")
    void structured_receivesRawEvent() {
        List<StorageLogEvent> received = new ArrayList<>();
        StorageLogEvent event = sampleEvent();

        StorageLogSinks.structured(received::add).accept(event);

        assertEquals(1, received.size());
        assertSame(event, received.get(0), "structured() must hand over the exact event instance");
    }

    @Test
    @DisplayName("tee() forwards to both sinks and isolates a failing one")
    void tee_forwardsToBothAndSwallowsFailures() {
        AtomicInteger secondCalls = new AtomicInteger();
        StorageLogSink failing = event -> { throw new RuntimeException("broken sink"); };
        StorageLogSink counting = event -> secondCalls.incrementAndGet();

        assertDoesNotThrow(() -> StorageLogSinks.tee(failing, counting).accept(sampleEvent()),
            "A failing first sink must not break the tee");
        assertEquals(1, secondCalls.get(), "The second sink must still receive the event");

        // Symmetric: failure in the second sink does not undo the first.
        secondCalls.set(0);
        assertDoesNotThrow(() -> StorageLogSinks.tee(counting, failing).accept(sampleEvent()));
        assertEquals(1, secondCalls.get());
    }
}
