package br.com.finalcraft.everydatabase.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, structured snapshot of a single storage log event.
 *
 * <p><b>Privacy by default:</b> this class carries no entity data unless explicitly opted
 * into via {@link StorageLogConfig}. The only such fields are {@link #keys()} (entity key
 * strings, opt-in via {@link StorageLogConfig#includeKeys()}, capped by
 * {@link StorageLogConfig#maxKeysListed()}) and {@link #value()} (a single entity's
 * {@code toString()}, opt-in via {@link StorageLogConfig#includeValues()}, capped by
 * {@link StorageLogConfig#maxValueLength()}). Both are {@code null} unless their flag is
 * enabled - by default this class stays free of entity content.
 *
 * <p>Obtain instances via {@link Builder}; create a builder with
 * {@link #builder(String, StorageOp, StorageLogLevel)}.
 *
 * <p>Rendered as a single human-readable line by {@link #format()} / {@link #toString()}.
 *
 * <p>Format example:
 * <pre>
 * [storage:sql] INDEX_RECONCILE player_data created=2 dropped=1 backfilled=1200 in 340ms
 * [storage:sql] INDEX_BACKFILL  player_data 60% (720/1200) in 210ms
 * [storage:mongo] INDEX_CREATE  player_data field=location.world order=ASC
 * [storage:sql] SAVE_BATCH      player_data entities=5000 in 1200ms (4166/s)
 * [storage:sql] DELETE          player_data existed=true
 * [storage:sql] QUERY           player_data conditions=[level RANGE, world EQ] results=37 in 8ms
 * [storage:sql] MIGRATION_APPLY 002_add_balance_index in 45ms
 * [storage:sql] TX_ROLLBACK     player_data in 12ms
 * [storage:sql] SAVE            player_data FAILED - SQL save failed
 * </pre>
 */
public final class StorageLogEvent {

    private final long            timestamp;
    private final String          backend;
    private final StorageOp       op;
    private final StorageLogLevel level;
    private final String          collection;   // nullable
    private final Long            affected;     // nullable - entities/rows/indexes affected
    private final Long            total;        // nullable - denominator for affected (batch size, row total)
    private final Integer         percent;      // nullable - 0..100 (progress)
    private final Long            durationMs;   // nullable
    private final List<String>    keys;         // nullable - only when includeKeys, already truncated
    private final String          value;        // nullable - only when includeValues, single entity toString(), already truncated
    private final String          detail;       // nullable - short context string, no entity data
    private final Throwable       error;        // nullable

    private StorageLogEvent(Builder b) {
        this.timestamp   = b.timestamp;
        this.backend     = b.backend;
        this.op          = b.op;
        this.level       = b.level;
        this.collection  = b.collection;
        this.affected    = b.affected;
        this.total       = b.total;
        this.percent     = b.percent;
        this.durationMs  = b.durationMs;
        this.keys        = b.keys == null ? null : Collections.unmodifiableList(new ArrayList<>(b.keys));
        this.value       = b.value;
        this.detail      = b.detail;
        this.error       = b.error;
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    /** Wall-clock timestamp when the event was created, in epoch milliseconds. */
    public long timestamp()          { return timestamp; }

    /** Short backend name: {@code "sql"}, {@code "postgresql"}, {@code "h2"},
     *  {@code "mongo"}, {@code "localfile"}, {@code "memory"}. */
    public String backend()          { return backend; }

    /** The storage operation that produced this event. */
    public StorageOp op()            { return op; }

    /** Severity of this event. */
    public StorageLogLevel level()   { return level; }

    /** Broad category - derived from {@link #op()}. */
    public StorageLogTopic topic()   { return op.topic(); }

    /** Collection (table / Mongo collection / directory) name, or {@code null} for storage-wide events. */
    public String collection()       { return collection; }

    /** Number of entities/rows/indexes affected by the operation, or {@code null} if not applicable. */
    public Long affected()           { return affected; }

    /** Denominator for {@link #affected()} (e.g. total rows in a backfill), or {@code null}. */
    public Long total()              { return total; }

    /** Progress percentage 0..100 for long-running operations, or {@code null}. */
    public Integer percent()         { return percent; }

    /** Wall-clock milliseconds the operation (or the reporting interval) took, or {@code null}. */
    public Long durationMs()         { return durationMs; }

    /**
     * Entity key strings if {@link StorageLogConfig#includeKeys()} is enabled, already
     * truncated to {@link StorageLogConfig#maxKeysListed()}. {@code null} otherwise.
     * Never contains entity field values - only identity keys.
     */
    public List<String> keys()       { return keys; }

    /**
     * Truncated {@code toString()} of the saved entity if
     * {@link StorageLogConfig#includeValues()} is enabled, already capped to
     * {@link StorageLogConfig#maxValueLength()} characters. {@code null} otherwise
     * (the default - entity content is opt-in only).
     */
    public String value()            { return value; }

    /**
     * Short, pre-formatted context string. Contains human-readable details such as
     * index field names, migration version/description, query conditions.
     * Never contains entity field values.
     */
    public String detail()           { return detail; }

    /** The exception that caused this event, or {@code null} for non-error events. */
    public Throwable error()         { return error; }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    /**
     * Renders a single human-readable log line. Contains entity identity/content
     * only when explicitly opted into - see {@link #keys()} / {@link #value()}.
     *
     * <p>Format: {@code [storage:<backend>] <OP> [<collection>] [progress] [detail] [keys] [value] [in Nms] [FAILED - message]}
     */
    public String format() {
        StringBuilder sb = new StringBuilder(80);
        sb.append("[storage:").append(backend).append("] ");
        sb.append(op.name());
        if (collection != null) {
            sb.append(' ').append(collection);
        }
        // Progress: "60% (720/1200)"
        if (percent != null && affected != null && total != null) {
            sb.append(' ').append(percent).append("% (").append(affected).append('/').append(total).append(')');
        } else if (percent != null) {
            sb.append(' ').append(percent).append('%');
        } else if (affected != null && total != null) {
            sb.append(" affected=").append(affected).append('/').append(total);
        } else if (affected != null) {
            sb.append(" affected=").append(affected);
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(' ').append(detail);
        }
        if (keys != null && !keys.isEmpty()) {
            sb.append(" keys=[");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(keys.get(i));
            }
            sb.append(']');
        }
        if (value != null) {
            sb.append(" value=").append(value);
        }
        if (durationMs != null) {
            sb.append(" in ").append(durationMs).append("ms");
        }
        if (error != null) {
            sb.append(" FAILED");
            String msg = error.getMessage();
            if (msg != null && !msg.isEmpty()) sb.append(" - ").append(msg);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }

    // ------------------------------------------------------------------
    //  Builder factory
    // ------------------------------------------------------------------

    /**
     * Creates a new {@link Builder} for an event in the given backend, for the given operation
     * and severity level.
     *
     * @param backend short backend name ({@code "sql"}, {@code "mongo"}, etc.)
     * @param op      the operation that produced this event
     * @param level   the severity of this event
     */
    public static Builder builder(String backend, StorageOp op, StorageLogLevel level) {
        return new Builder(backend, op, level);
    }

    // ------------------------------------------------------------------
    //  Mutable builder
    // ------------------------------------------------------------------

    /**
     * Mutable builder for {@link StorageLogEvent}.
     *
     * <p><b>Privacy contract:</b> this builder has no setter accepting the raw entity object
     * ({@code V}) - callers must pre-render to {@code String} themselves, and the only setter
     * documented to legitimately carry entity field data is {@link #value} (opt-in,
     * pre-truncated {@code toString()}, gated by {@link StorageLogConfig#includeValues()}).
     * {@link #keys} carries identity strings only (gated by
     * {@link StorageLogConfig#includeKeys()}); {@link #detail} must stay free of entity data
     * regardless of config (see its own javadoc). Both privacy flags default to {@code false}.
     */
    public static final class Builder {

        private final long            timestamp = System.currentTimeMillis();
        private final String          backend;
        private final StorageOp       op;
        private final StorageLogLevel level;

        private String        collection;
        private Long          affected;
        private Long          total;
        private Integer       percent;
        private Long          durationMs;
        private List<String>  keys;
        private String        value;
        private String        detail;
        private Throwable     error;

        private Builder(String backend, StorageOp op, StorageLogLevel level) {
            this.backend = backend;
            this.op      = op;
            this.level   = level;
        }

        /** Sets the collection (table / directory) name. */
        public Builder collection(String collection)   { this.collection  = collection;  return this; }

        /** Sets the number of affected entities/rows/indexes. */
        public Builder affected(long affected)         { this.affected    = affected;     return this; }

        /** Sets the total denominator (e.g. total rows for a backfill). */
        public Builder total(long total)               { this.total       = total;        return this; }

        /** Sets a progress percentage (0..100). */
        public Builder percent(int percent)            { this.percent     = percent;      return this; }

        /** Sets the elapsed wall-clock duration. */
        public Builder durationMs(long durationMs)     { this.durationMs  = durationMs;   return this; }

        /**
         * Sets entity key strings. Should already be truncated to
         * {@link StorageLogConfig#maxKeysListed()} by the caller.
         * Never pass entity field values here - keys only.
         */
        public Builder keys(List<String> keys)         { this.keys        = keys;         return this; }

        /**
         * Sets a pre-rendered, pre-truncated string representation of the saved entity
         * (its {@code toString()}, capped to {@link StorageLogConfig#maxValueLength()}).
         * Opt-in - only set this when {@link StorageLogConfig#includeValues()} is enabled.
         */
        public Builder value(String value)             { this.value       = value;        return this; }

        /**
         * Sets a short pre-formatted context string (index name, migration version,
         * query conditions, etc.). Must not contain entity field values.
         */
        public Builder detail(String detail)           { this.detail      = detail;       return this; }

        /** Sets the exception for ERROR-level events. */
        public Builder error(Throwable error)          { this.error       = error;        return this; }

        /** Builds the immutable {@link StorageLogEvent}. */
        public StorageLogEvent build() {
            return new StorageLogEvent(this);
        }
    }
}
