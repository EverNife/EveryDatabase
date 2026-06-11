package br.com.finalcraft.evernifecore.storage.log;

import br.com.finalcraft.evernifecore.testutil.CapturingSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link StorageLog} dispatcher: emit gating, the ERROR floor,
 * {@link StorageLog#errored} re-throw contract, sink-failure isolation,
 * {@link StorageLog#capKeys} and the {@link StorageLog.ProgressTracker} thresholds.
 */
@DisplayName("StorageLog dispatcher - gating, error floor, capKeys and ProgressTracker")
class StorageLogTest {

    /** Builds a dispatcher over a live config + capturing sink pair. */
    private static StorageLog newLog(StorageLogConfig cfg, CapturingSink capture) {
        cfg.sink(capture);
        return new StorageLog("test", () -> cfg);
    }

    // ------------------------------------------------------------------
    //  emit() gating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("emit() below the threshold neither builds the event nor reaches the sink")
    void emit_belowThreshold_skipsBuilderAndSink() {
        CapturingSink capture = new CapturingSink();
        StorageLog log = newLog(new StorageLogConfig(), capture); // WARN default

        AtomicBoolean fillCalled = new AtomicBoolean(false);
        log.emit(StorageOp.SAVE, StorageLogLevel.DEBUG, b -> fillCalled.set(true));

        assertFalse(fillCalled.get(), "The fill lambda must not run when the event is suppressed");
        assertEquals(0, capture.size());
    }

    @Test
    @DisplayName("emit() at the threshold builds the event and dispatches it")
    void emit_atThreshold_dispatches() {
        CapturingSink capture = new CapturingSink();
        StorageLog log = newLog(StorageLogConfig.verbose(), capture); // DEBUG default

        log.emit(StorageOp.SAVE, StorageLogLevel.DEBUG, b -> b.collection("players").affected(1));

        assertEquals(1, capture.size());
        StorageLogEvent event = capture.events().get(0);
        assertEquals("test", event.backend());
        assertEquals(StorageOp.SAVE, event.op());
        assertEquals(StorageLogTopic.WRITE, event.topic());
        assertEquals(StorageLogLevel.DEBUG, event.level());
        assertEquals("players", event.collection());
        assertEquals(1L, event.affected());
    }

    @Test
    @DisplayName("emit() at ERROR passes even with the topic at OFF (floor)")
    void emit_errorLevel_passesThroughOffTopic() {
        CapturingSink capture = new CapturingSink();
        StorageLog log = newLog(StorageLogConfig.silent(), capture); // OFF default

        log.emit(StorageOp.SAVE, StorageLogLevel.ERROR, b -> b.collection("players"));
        log.emit(StorageOp.SAVE, StorageLogLevel.WARN,  b -> b.collection("players"));

        assertEquals(1, capture.size(), "Only the ERROR event may pass under silent()");
        assertEquals(StorageLogLevel.ERROR, capture.events().get(0).level());
    }

    @Test
    @DisplayName("isEnabled() reads the live config - runtime edits apply immediately")
    void isEnabled_reflectsLiveConfigChanges() {
        StorageLogConfig cfg = new StorageLogConfig(); // WARN
        StorageLog log = newLog(cfg, new CapturingSink());

        assertFalse(log.isEnabled(StorageOp.SAVE, StorageLogLevel.DEBUG));
        cfg.defaultLevel(StorageLogLevel.DEBUG);
        assertTrue(log.isEnabled(StorageOp.SAVE, StorageLogLevel.DEBUG),
            "The dispatcher must observe config edits without re-creation");
    }

    // ------------------------------------------------------------------
    //  errored() - log and re-throw contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("errored() returns the same exception and emits an ERROR event with it")
    void errored_returnsSameExceptionAndEmits() {
        CapturingSink capture = new CapturingSink();
        StorageLog log = newLog(StorageLogConfig.silent(), capture); // even fully muted

        RuntimeException failure = new RuntimeException("simulated I/O failure");
        RuntimeException returned = log.errored(StorageOp.SAVE, "players", failure);

        assertSame(failure, returned, "errored() must hand the exception back for the caller's throw");
        assertEquals(1, capture.size());
        StorageLogEvent event = capture.events().get(0);
        assertEquals(StorageLogLevel.ERROR, event.level());
        assertSame(failure, event.error());
        assertTrue(event.format().contains("FAILED"), "ERROR lines must render the FAILED marker");
        assertTrue(event.format().contains("simulated I/O failure"));
    }

    @Test
    @DisplayName("a sink that throws never breaks the emitting operation")
    void emit_sinkFailure_isSwallowed() {
        StorageLogConfig cfg = StorageLogConfig.verbose()
            .sink(event -> { throw new RuntimeException("broken sink"); });
        StorageLog log = new StorageLog("test", () -> cfg);

        assertDoesNotThrow(() ->
            log.emit(StorageOp.SAVE, StorageLogLevel.INFO, b -> b.collection("players")));
        assertDoesNotThrow(() ->
            log.errored(StorageOp.SAVE, "players", new RuntimeException("op failure")));
    }

    // ------------------------------------------------------------------
    //  capKeys()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("capKeys(): lists within the cap pass through unchanged")
    void capKeys_underLimit_returnsSameList() {
        List<String> keys = Arrays.asList("a", "b", "c");
        assertSame(keys, StorageLog.capKeys(keys, 3), "Exactly at the cap: no copy, no marker");
        assertSame(keys, StorageLog.capKeys(keys, 10));
        assertNull(StorageLog.capKeys(null, 10));
    }

    @Test
    @DisplayName("capKeys(): overflow is replaced by a single '(+N more)' marker")
    void capKeys_overLimit_capsWithMarker() {
        List<String> keys = Arrays.asList("k1", "k2", "k3", "k4", "k5");

        List<String> capped = StorageLog.capKeys(keys, 3);

        assertEquals(4, capped.size(), "3 kept keys + 1 marker");
        assertEquals(Arrays.asList("k1", "k2", "k3", "(+2 more)"), capped);
    }

    @Test
    @DisplayName("capKeys(): non-positive cap means unlimited")
    void capKeys_nonPositiveMax_passesThrough() {
        List<String> keys = Arrays.asList("k1", "k2", "k3");
        assertSame(keys, StorageLog.capKeys(keys, 0));
        assertSame(keys, StorageLog.capKeys(keys, -1));
    }

    // ------------------------------------------------------------------
    //  ProgressTracker
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ProgressTracker: ticks respect the step-percent threshold and finish() emits 100%")
    void progressTracker_stepGatingAndFinish() {
        CapturingSink capture = new CapturingSink();
        StorageLogConfig cfg = StorageLogConfig.verbose()
            // Huge throttle isolates the percent-step gate; minTotal=0 always reports.
            .progress(true, 10, 60_000, 0);
        StorageLog log = newLog(cfg, capture);

        StorageLog.ProgressTracker tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, "players");
        tracker.tick(10, 100);  // 10% - first tick always crosses the step from -1
        tracker.tick(15, 100);  // +5%  - below the 10% step, suppressed
        tracker.tick(20, 100);  // +10% - emitted
        tracker.finish(100);    // completion - always emitted at INFO

        List<StorageLogEvent> events = capture.byOp(StorageOp.INDEX_BACKFILL);
        assertEquals(3, events.size(), "Expected ticks at 10% and 20% plus the completion event");

        assertEquals(10, events.get(0).percent());
        assertEquals(StorageLogLevel.DEBUG, events.get(0).level());
        assertEquals(20, events.get(1).percent());

        StorageLogEvent completion = events.get(2);
        assertEquals(100, completion.percent());
        assertEquals(StorageLogLevel.INFO, completion.level());
        assertEquals(100L, completion.affected());
        assertEquals(100L, completion.total());
    }

    @Test
    @DisplayName("ProgressTracker: operations below progressMinTotal stay silent (even finish)")
    void progressTracker_belowMinTotal_staysSilent() {
        CapturingSink capture = new CapturingSink();
        StorageLogConfig cfg = StorageLogConfig.verbose()
            .progress(true, 10, 0, 1_000); // minTotal=1000
        StorageLog log = newLog(cfg, capture);

        StorageLog.ProgressTracker tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, "players");
        tracker.tick(50, 100);
        tracker.finish(100);

        assertEquals(0, capture.size(), "A 100-row operation is below minTotal=1000 - no progress events");
    }

    @Test
    @DisplayName("ProgressTracker: progressEnabled=false suppresses all progress events")
    void progressTracker_disabled_staysSilent() {
        CapturingSink capture = new CapturingSink();
        StorageLogConfig cfg = StorageLogConfig.verbose()
            .progress(false, 10, 0, 0);
        StorageLog log = newLog(cfg, capture);

        StorageLog.ProgressTracker tracker = log.newProgressTracker(StorageOp.INDEX_BACKFILL, "players");
        tracker.tick(500, 1_000);
        tracker.finish(1_000);

        assertEquals(0, capture.size());
    }

    // ------------------------------------------------------------------
    //  format() rendering spot-checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("format(): progress events render as 'N% (done/total)'")
    void format_progressRendering() {
        StorageLogEvent event = StorageLogEvent.builder("sql", StorageOp.INDEX_BACKFILL, StorageLogLevel.DEBUG)
            .collection("player_data")
            .affected(720).total(1200).percent(60)
            .durationMs(210)
            .build();

        String line = event.format();
        assertTrue(line.contains("[storage:sql] INDEX_BACKFILL player_data"), line);
        assertTrue(line.contains("60% (720/1200)"), line);
        assertTrue(line.contains("in 210ms"), line);
    }

    @Test
    @DisplayName("format(): keys render as a bracketed list only when present")
    void format_keysRendering() {
        StorageLogEvent withKeys = StorageLogEvent.builder("sql", StorageOp.SAVE, StorageLogLevel.DEBUG)
            .collection("player_data")
            .keys(Arrays.asList("k1", "k2"))
            .build();
        assertTrue(withKeys.format().contains("keys=[k1, k2]"), withKeys.format());

        StorageLogEvent withoutKeys = StorageLogEvent.builder("sql", StorageOp.SAVE, StorageLogLevel.DEBUG)
            .collection("player_data")
            .build();
        assertFalse(withoutKeys.format().contains("keys="), withoutKeys.format());
    }
}
