package br.com.finalcraft.everydatabase.testutil;

import br.com.finalcraft.everydatabase.log.StorageLogEvent;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageLogSink;
import br.com.finalcraft.everydatabase.log.StorageOp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test {@link StorageLogSink} that records every {@link StorageLogEvent} it receives,
 * in arrival order.
 *
 * <p>Install it on a live config and assert on what was captured:
 * <pre>{@code
 * CapturingSink capture = new CapturingSink();
 * storage.getStorageLogConfig()
 *     .defaultLevel(StorageLogLevel.DEBUG)
 *     .sink(capture);
 *
 * repo.saveAll(players).join();
 *
 * assertEquals(1, capture.byOp(StorageOp.SAVE_BATCH).size());
 * }</pre>
 *
 * <p>Thread-safe: events may arrive from {@code StorageExecutors} worker threads. Tests
 * that {@code join()} the storage future before asserting get a consistent view (the
 * future's completion establishes the happens-before edge).
 */
public final class CapturingSink implements StorageLogSink {

    private final List<StorageLogEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void accept(StorageLogEvent event) {
        events.add(event);
    }

    /** Snapshot of all captured events, in arrival order. */
    public List<StorageLogEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** Captured events for the given operation, in arrival order. */
    public List<StorageLogEvent> byOp(StorageOp op) {
        synchronized (events) {
            return events.stream().filter(e -> e.op() == op).collect(Collectors.toList());
        }
    }

    /** Captured events at the given level, in arrival order. */
    public List<StorageLogEvent> byLevel(StorageLogLevel level) {
        synchronized (events) {
            return events.stream().filter(e -> e.level() == level).collect(Collectors.toList());
        }
    }

    /** Rendered {@link StorageLogEvent#format()} line of every captured event, in arrival order. */
    public List<String> formats() {
        synchronized (events) {
            return events.stream().map(StorageLogEvent::format).collect(Collectors.toList());
        }
    }

    /** Discards all captured events. */
    public void clear() {
        events.clear();
    }

    /** Number of captured events so far. */
    public int size() {
        return events.size();
    }
}
