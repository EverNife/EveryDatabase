package br.com.finalcraft.everydatabase.log;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Factory for built-in {@link StorageLogSink} implementations.
 *
 * <h3>Default sink ({@link #auto()})</h3>
 * <p>The {@code auto()} sink resolves its destination lazily at event-dispatch time:
 * <ol>
 *   <li>If a host sink has been installed via {@link #installDefault(StorageLogSink)}, it takes priority.</li>
 *   <li>Otherwise, if SLF4J is available on the runtime classpath, events are routed to SLF4J
 *       loggers named {@code evernifecore.storage.<TOPIC>} (e.g. {@code evernifecore.storage.index}).
 *       SLF4J is detected reflectively - no {@link NoClassDefFoundError} is thrown if absent.</li>
 *   <li>If neither is available, the sink is a no-op (silent).</li>
 * </ol>
 *
 * <h3>Host integration (e.g. Bukkit plugin)</h3>
 * <pre>{@code
 * // In plugin onEnable / static block:
 * StorageLogSinks.installDefault(event -> {
 *     java.util.logging.Level jul = toJulLevel(event.level());
 *     if (event.error() != null)
 *         plugin.getLogger().log(jul, event.format(), event.error());
 *     else
 *         plugin.getLogger().log(jul, event.format());
 * });
 * }</pre>
 */
public final class StorageLogSinks {

    private StorageLogSinks() {}

    /** Globally installed host sink, or {@code null} when none has been installed. */
    private static final AtomicReference<StorageLogSink> HOST_SINK = new AtomicReference<>(null);

    /**
     * Installs a global default sink that all {@link #auto()} sinks will delegate to.
     *
     * <p>Typically called once during host startup (e.g. Bukkit {@code onEnable}).
     * Replaces any previously installed sink.
     *
     * @param sink the host-side sink (e.g. a bridge to a plugin logger)
     */
    public static void installDefault(StorageLogSink sink) {
        HOST_SINK.set(sink);
    }

    /**
     * Removes the globally installed host sink. After this call, {@link #auto()} sinks
     * fall back to SLF4J (if present) or no-op.
     */
    public static void uninstallDefault() {
        HOST_SINK.set(null);
    }

    /** Returns the currently installed host sink, or {@code null} if none. */
    public static StorageLogSink getInstalledDefault() {
        return HOST_SINK.get();
    }

    // ------------------------------------------------------------------
    //  Built-in sinks
    // ------------------------------------------------------------------

    /**
     * A sink that discards all events. Useful for tests that don't care about log output.
     */
    public static StorageLogSink noop() {
        return event -> {};
    }

    /**
     * A sink that prints each event as a single line to {@link System#out}.
     * Includes the exception class and message for ERROR events (no stack trace).
     */
    public static StorageLogSink stdout() {
        return event -> {
            System.out.println(event.format());
            if (event.error() != null && event.level() == StorageLogLevel.ERROR) {
                event.error().printStackTrace(System.out);
            }
        };
    }

    /**
     * A sink that passes each formatted log line to the given {@link Consumer}.
     * Useful for capturing log output in tests or routing to a custom destination.
     *
     * @param consumer receives the formatted line for each event
     */
    public static StorageLogSink consumer(Consumer<String> consumer) {
        return event -> consumer.accept(event.format());
    }

    /**
     * A sink that passes each {@link StorageLogEvent} to the given {@link Consumer}.
     * Use this when you need the structured event (e.g. to access {@link StorageLogEvent#error()}).
     *
     * @param consumer receives the raw event for each emitted log entry
     */
    public static StorageLogSink structured(Consumer<StorageLogEvent> consumer) {
        return consumer::accept;
    }

    /**
     * A composite sink that forwards each event to two sinks in order.
     *
     * @param first  primary sink
     * @param second secondary sink
     */
    public static StorageLogSink tee(StorageLogSink first, StorageLogSink second) {
        return event -> {
            try { first.accept(event); } catch (Throwable ignored) {}
            try { second.accept(event); } catch (Throwable ignored) {}
        };
    }

    /**
     * The automatic hybrid sink used by default.
     *
     * <p>Resolution order per event:
     * <ol>
     *   <li>Host sink installed via {@link #installDefault(StorageLogSink)} (if set).</li>
     *   <li>SLF4J (if available on runtime classpath - detected reflectively).</li>
     *   <li>No-op (silent).</li>
     * </ol>
     *
     * <p>SLF4J detection is performed once at class load via {@link Slf4jHolder}.
     * If SLF4J is not on the classpath, no {@link NoClassDefFoundError} is thrown.
     */
    public static StorageLogSink auto() {
        return event -> {
            StorageLogSink host = HOST_SINK.get();
            if (host != null) {
                host.accept(event);
                return;
            }
            if (Slf4jHolder.AVAILABLE) {
                Slf4jHolder.SINK.accept(event);
            }
            // else: no-op (silent)
        };
    }

    // ------------------------------------------------------------------
    //  Lazy SLF4J probe - only loaded when SLF4J is on the classpath
    // ------------------------------------------------------------------

    /**
     * Holds the SLF4J availability flag and sink instance.
     *
     * <p>This class is loaded lazily by the JVM only when {@link #auto()} is first called.
     * The {@link #probe()} method uses reflection so that the outer class never hard-references
     * {@code org.slf4j.*}, preventing {@link NoClassDefFoundError} when SLF4J is absent.
     *
     * <p>{@link Slf4jStorageLogSink} (the only class that imports SLF4J) is instantiated
     * only when {@link #AVAILABLE} is {@code true}.
     */
    static final class Slf4jHolder {

        static final boolean AVAILABLE = probe();
        static final StorageLogSink SINK = AVAILABLE ? new Slf4jStorageLogSink() : event -> {};

        private static boolean probe() {
            try {
                Class.forName("org.slf4j.LoggerFactory");
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private Slf4jHolder() {}
    }
}
