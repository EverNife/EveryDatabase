package br.com.finalcraft.everydatabase.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mutable, thread-safe configuration for storage logging.
 *
 * <p>Holds:
 * <ul>
 *   <li>A global {@link #defaultLevel()} applied to topics without a specific override.</li>
 *   <li>Per-topic level overrides ({@link #level(StorageLogTopic, StorageLogLevel)}).</li>
 *   <li>Privacy settings: whether to include entity keys and/or values in log lines.</li>
 *   <li>Progress reporting settings for long-running operations (backfill, migration).</li>
 *   <li>The active {@link StorageLogSink}.</li>
 * </ul>
 *
 * <p>This object is <b>live</b>: editing it after a storage is created immediately affects
 * all repositories belonging to that storage (they all reference the same config via the
 * storage's {@code getStorageLogConfig()} getter). All fields are {@code volatile};
 * the per-topic map is a {@link ConcurrentHashMap}.
 *
 * <h3>Presets</h3>
 * <ul>
 *   <li>{@link #defaults()} - {@code WARN} global: routine silent, failures visible.</li>
 *   <li>{@link #silent()} - only the {@code ERROR} floor remains.</li>
 *   <li>{@link #verbose()} - {@code DEBUG} global: most ops visible.</li>
 *   <li>{@link #trace()} - {@code TRACE} global: maximum verbosity.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * // See index and migration events; silence reads and queries.
 * StorageLogConfig cfg = StorageLogConfig.defaults()
 *     .level(StorageLogTopic.INDEX,     StorageLogLevel.INFO)
 *     .level(StorageLogTopic.MIGRATION, StorageLogLevel.INFO)
 *     .mute(StorageLogTopic.READ)
 *     .mute(StorageLogTopic.QUERY);
 *
 * // Edit in runtime after creation (affects all repos immediately):
 * storage.getStorageLogConfig().level(StorageLogTopic.WRITE, StorageLogLevel.DEBUG);
 * }</pre>
 */
public final class StorageLogConfig {

    // ---- Thresholds -------------------------------------------------

    /** Global default level for topics without a specific override. */
    private volatile StorageLogLevel defaultLevel = StorageLogLevel.WARN;

    /** Per-topic overrides. Empty = all topics use defaultLevel. */
    private final ConcurrentMap<StorageLogTopic, StorageLogLevel> topicLevels = new ConcurrentHashMap<>();

    // ---- Privacy settings -------------------------------------------

    /**
     * Whether to include entity key strings in log lines.
     * Default: {@code false}. Even when enabled, keys are capped at {@link #maxKeysListed}.
     * Entity field values are NEVER included regardless of this flag.
     */
    private volatile boolean includeKeys = false;

    /**
     * Maximum number of key strings to include per log line when {@link #includeKeys} is true.
     * Additional keys are replaced with a {@code "(+N more)"} suffix.
     */
    private volatile int maxKeysListed = 10;

    /**
     * Whether to include a truncated {@code toString()} of the saved entity in
     * single-entity {@code SAVE} log lines (see {@link StorageLog#saved}).
     *
     * <p>Default: {@code false}. Stronger privacy trade-off than {@link #includeKeys}: this
     * logs entity <b>content</b> (capped at {@link #maxValueLength}), not just its identity.
     * Enable only for local debugging / low-traffic diagnostics - never in production with
     * sensitive data. {@code saveAll}/batch summaries never include per-entity values
     * regardless of this flag (see {@link StorageLog#savedBatch}) - that would reintroduce
     * the same one-line-per-entity noise that batched logging is designed to avoid.
     */
    private volatile boolean includeValues = false;

    /**
     * Maximum number of {@code toString()} characters to include per log line when
     * {@link #includeValues} is true. Longer representations are truncated with an
     * {@code "... (+N chars)"} suffix.
     */
    private volatile int maxValueLength = 200;

    /**
     * Whether to include literal query filter values in QUERY log lines.
     * Default: {@code false} (only field paths and operators are logged, not the values,
     * which may contain sensitive data). Even when enabled, entity data is never printed.
     */
    private volatile boolean includeQueryValues = false;

    // ---- Progress settings ------------------------------------------

    /**
     * Whether to emit progress events (INDEX_BACKFILL ticks, etc.) for long-running ops.
     * Default: {@code true}.
     */
    private volatile boolean progressEnabled = true;

    /**
     * Minimum progress percentage change before emitting a new progress tick.
     * Default: {@code 10} (every 10%). Clipped to [1, 100].
     */
    private volatile int progressStepPercent = 10;

    /**
     * Minimum wall-clock time between progress ticks, in milliseconds.
     * Default: {@code 1000ms}. A tick is emitted when BOTH {@code stepPercent} AND
     * {@code throttleMs} thresholds are met (OR when the operation completes).
     */
    private volatile long progressThrottleMs = 1000;

    /**
     * Minimum total entity/row count for a long-running operation before progress events
     * are emitted. Operations below this size are not reported (too fast to matter).
     * Default: {@code 500}.
     */
    private volatile long progressMinTotal = 500;

    // ---- Sink -------------------------------------------------------

    /** Active sink; default uses SLF4J when present, else no-op. */
    private volatile StorageLogSink sink = StorageLogSinks.auto();

    // ---- Constructors / presets -------------------------------------

    /** Creates a config with all settings at their defaults. Prefer the presets below. */
    public StorageLogConfig() {}

    /**
     * System property that overrides the default log level for all storage instances created
     * with the no-arg / no-log-config factory methods.
     *
     * <p>Set this during testing to increase verbosity without changing any application code:
     * <pre>
     * -Devernifecore.storage.log.level=info      # see lifecycle, index, migration events
     * -Devernifecore.storage.log.level=debug     # see batch sizes, queries, progress ticks
     * -Devernifecore.storage.log.level=trace     # maximum verbosity (per-entity ops)
     * </pre>
     *
     * <p>In production, this property should not be set; the default {@link StorageLogLevel#WARN}
     * applies and routine operations remain silent.
     */
    public static final String SYSTEM_PROPERTY_DEFAULT_LEVEL = "evernifecore.storage.log.level";

    /**
     * Default preset: {@code WARN} global threshold.
     * Routine events (INFO/DEBUG/TRACE) are silent; failures and warnings are visible.
     *
     * <p>If the system property {@value #SYSTEM_PROPERTY_DEFAULT_LEVEL} is set, its value
     * overrides the threshold (e.g. {@code -Devernifecore.storage.log.level=info}).
     */
    public static StorageLogConfig defaults() {
        StorageLogConfig cfg = new StorageLogConfig();
        String override = System.getProperty(SYSTEM_PROPERTY_DEFAULT_LEVEL);
        if (override != null && !override.isEmpty()) {
            try {
                cfg.defaultLevel(StorageLogLevel.valueOf(override.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // invalid value - keep the WARN default
            }
        }
        return cfg;
    }

    /**
     * Silent preset: only the {@code ERROR} floor remains (errors and unhandled failures).
     * Equivalent to {@code defaultLevel = OFF} but the ERROR floor still applies.
     */
    public static StorageLogConfig silent() {
        return new StorageLogConfig().defaultLevel(StorageLogLevel.OFF);
    }

    /**
     * Verbose preset: {@code DEBUG} global threshold.
     * Most operations (saves, deletes, queries, progress) become visible.
     */
    public static StorageLogConfig verbose() {
        return new StorageLogConfig().defaultLevel(StorageLogLevel.DEBUG);
    }

    /**
     * Trace preset: {@code TRACE} global threshold - maximum verbosity.
     * Use only in low-traffic environments for deep diagnostics.
     */
    public static StorageLogConfig trace() {
        return new StorageLogConfig().defaultLevel(StorageLogLevel.TRACE);
    }

    // ---- Fluent setters ---------------------------------------------

    /** Sets the global default level for topics without a specific override. Returns {@code this}. */
    public StorageLogConfig defaultLevel(StorageLogLevel level) {
        this.defaultLevel = level;
        return this;
    }

    /**
     * Sets a specific level override for the given topic. Returns {@code this}.
     *
     * <p>Note: even if you set {@code ERROR} here, the ERROR floor in
     * {@link StorageLogLevel#passes} still applies globally.
     */
    public StorageLogConfig level(StorageLogTopic topic, StorageLogLevel level) {
        topicLevels.put(topic, level);
        return this;
    }

    /**
     * Mutes the given topic to {@link StorageLogLevel#ERROR} (only the ERROR floor remains).
     * Returns {@code this}.
     */
    public StorageLogConfig mute(StorageLogTopic topic) {
        topicLevels.put(topic, StorageLogLevel.ERROR);
        return this;
    }

    /**
     * Removes any per-topic override for the given topic, making it fall back to
     * {@link #defaultLevel()}. Returns {@code this}.
     */
    public StorageLogConfig reset(StorageLogTopic topic) {
        topicLevels.remove(topic);
        return this;
    }

    /**
     * Enables or disables entity key inclusion in log lines.
     * Keys are always capped at {@link #maxKeysListed}; entity values are never included.
     * Returns {@code this}.
     */
    public StorageLogConfig includeKeys(boolean include) {
        this.includeKeys = include;
        return this;
    }

    /**
     * Sets the maximum number of key strings per log line when {@link #includeKeys} is enabled.
     * Returns {@code this}.
     */
    public StorageLogConfig maxKeysListed(int max) {
        this.maxKeysListed = max;
        return this;
    }

    /**
     * Enables or disables including a truncated {@code toString()} of the saved entity in
     * single-entity {@code SAVE} log lines.
     *
     * <p>Unlike {@link #includeKeys} (identity only), this prints entity <b>content</b>
     * (capped at {@link #maxValueLength}) - meant for local debugging, not production use
     * with sensitive data. Default {@code false}. Returns {@code this}.
     */
    public StorageLogConfig includeValues(boolean include) {
        this.includeValues = include;
        return this;
    }

    /**
     * Sets the maximum number of {@code toString()} characters per log line when
     * {@link #includeValues} is enabled. Returns {@code this}.
     */
    public StorageLogConfig maxValueLength(int max) {
        this.maxValueLength = max;
        return this;
    }

    /**
     * Enables or disables printing literal query filter values in QUERY log lines.
     * Default {@code false}: only field paths and operators are shown.
     * Returns {@code this}.
     */
    public StorageLogConfig includeQueryValues(boolean include) {
        this.includeQueryValues = include;
        return this;
    }

    /**
     * Configures progress reporting for long-running operations.
     *
     * @param enabled    whether to emit progress ticks at all
     * @param stepPct    minimum percentage change between ticks; clipped to [1, 100]
     * @param throttleMs minimum wall-clock ms between ticks
     * @param minTotal   minimum total count for progress to be emitted
     * @return {@code this}
     */
    public StorageLogConfig progress(boolean enabled, int stepPct, long throttleMs, long minTotal) {
        this.progressEnabled     = enabled;
        this.progressStepPercent = Math.max(1, Math.min(100, stepPct));
        this.progressThrottleMs  = throttleMs;
        this.progressMinTotal    = minTotal;
        return this;
    }

    /** Sets the active {@link StorageLogSink}. Returns {@code this}. */
    public StorageLogConfig sink(StorageLogSink sink) {
        this.sink = sink;
        return this;
    }

    // ---- Read accessors (used by the dispatcher) --------------------

    /**
     * Resolves the effective {@link StorageLogLevel} for the given topic:
     * the per-topic override if set, otherwise {@link #defaultLevel()}.
     */
    public StorageLogLevel effectiveLevel(StorageLogTopic topic) {
        StorageLogLevel override = topicLevels.get(topic);
        return override != null ? override : defaultLevel;
    }

    /**
     * Returns {@code true} when an event at {@code level} for {@code topic} should be emitted.
     * Delegates to {@link StorageLogLevel#passes(StorageLogLevel)} which enforces the ERROR floor.
     */
    public boolean isEnabled(StorageLogTopic topic, StorageLogLevel level) {
        return level.passes(effectiveLevel(topic));
    }

    /** Returns the global default level. */
    public StorageLogLevel defaultLevel()          { return defaultLevel; }

    /** Returns {@code true} when entity key inclusion is enabled. */
    public boolean includeKeys()                   { return includeKeys; }

    /** Returns the maximum number of keys per log line. */
    public int maxKeysListed()                     { return maxKeysListed; }

    /** Returns {@code true} when entity value (toString()) inclusion is enabled. */
    public boolean includeValues()                 { return includeValues; }

    /** Returns the maximum number of entity toString() characters per log line. */
    public int maxValueLength()                    { return maxValueLength; }

    /** Returns {@code true} when query filter literal values should be included. */
    public boolean includeQueryValues()            { return includeQueryValues; }

    /** Returns {@code true} when progress tick events should be emitted. */
    public boolean isProgressEnabled()             { return progressEnabled; }

    /** Returns the minimum percentage change between progress ticks. */
    public int progressStepPercent()               { return progressStepPercent; }

    /** Returns the minimum wall-clock ms between progress ticks. */
    public long progressThrottleMs()               { return progressThrottleMs; }

    /** Returns the minimum total count for progress reporting to activate. */
    public long progressMinTotal()                 { return progressMinTotal; }

    /** Returns the active {@link StorageLogSink}. */
    public StorageLogSink sink()                   { return sink; }
}
