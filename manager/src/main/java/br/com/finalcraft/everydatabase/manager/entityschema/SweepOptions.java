package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.manager.log.ManagerLog;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * How one {@link EntitySchemaSweeper#sweep} run behaves: who is running it, how big its batches
 * are, when it should give up, and where its progress goes. Immutable; every knob has a default
 * that suits an unattended boot-time sweep, so {@link #defaults()} is a valid choice.
 *
 * <p>Not generic on the entity: the key parser is the only key-typed input a sweep takes, and it
 * stays a separate argument so one options instance can be reused across collections.
 */
public final class SweepOptions {

    /** Default lease-renewal window - one heartbeat per batch, expires after this if not renewed. */
    public static final long DEFAULT_LEASE_MILLIS = 60_000L;

    /** Default throttle for the "still going" progress line. */
    public static final long DEFAULT_PROGRESS_LOG_MILLIS = 5_000L;

    /** Default rows pulled through the codec per batch. */
    public static final int DEFAULT_BATCH_SIZE = 256;

    private final String runnerId;
    private final int batchSize;
    private final BooleanSupplier abortCheck;
    private final ManagerLog logger;
    private final long leaseMillis;
    private final long progressLogMillis;

    private SweepOptions(Builder builder) {
        this.runnerId          = builder.runnerId;
        this.batchSize         = builder.batchSize;
        this.abortCheck        = builder.abortCheck;
        this.logger            = builder.logger;
        this.leaseMillis       = builder.leaseMillis;
        this.progressLogMillis = builder.progressLogMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every knob at its default, with a fresh random runner id. */
    public static SweepOptions defaults() {
        return builder().build();
    }

    public String runnerId()         { return runnerId; }
    public int batchSize()           { return batchSize; }
    public BooleanSupplier abortCheck() { return abortCheck; }
    public ManagerLog logger()       { return logger; }
    public long leaseMillis()        { return leaseMillis; }
    public long progressLogMillis()  { return progressLogMillis; }

    public static final class Builder {

        private String runnerId = UUID.randomUUID().toString();
        private int batchSize = DEFAULT_BATCH_SIZE;
        private BooleanSupplier abortCheck = () -> false;
        private ManagerLog logger = ManagerLog.SILENT;
        private long leaseMillis = DEFAULT_LEASE_MILLIS;
        private long progressLogMillis = DEFAULT_PROGRESS_LOG_MILLIS;

        private Builder() {
        }

        /**
         * Identifies the instance running the sweep, so a marker's live lease can tell "mine,
         * resume it" from "someone else's, stay out". Must be stable for one process and distinct
         * across processes sharing the backend; defaults to a random id per builder, which is both.
         */
        public Builder runnerId(String runnerId) {
            if (runnerId == null || runnerId.trim().isEmpty()) {
                throw new IllegalArgumentException("runnerId must be a non-empty identifier of the sweeping instance");
            }
            this.runnerId = runnerId;
            return this;
        }

        /** Rows pulled through the codec per batch; also the lease-renewal and abort-poll granularity. Clamped to >= 1. */
        public Builder batchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
            return this;
        }

        /**
         * Polled at every batch boundary; {@code true} cuts the sweep short (the marker is left
         * un-advanced and the lease released, so the next boot resumes it). The caller's
         * freeze/kill-switch/shutdown policy lives behind this.
         */
        public Builder abortCheck(BooleanSupplier abortCheck) {
            this.abortCheck = abortCheck == null ? () -> false : abortCheck;
            return this;
        }

        /** Where progress and per-collection notices go; {@link ManagerLog#SILENT} by default. */
        public Builder logger(ManagerLog logger) {
            this.logger = logger == null ? ManagerLog.SILENT : logger;
            return this;
        }

        /**
         * How long a claimed lease stays valid without a heartbeat. Long enough to outlive one
         * batch by a wide margin: an expired lease invites another instance to start sweeping the
         * same collection.
         */
        public Builder leaseMillis(long leaseMillis) {
            this.leaseMillis = leaseMillis;
            return this;
        }

        /** Minimum spacing between "still going" progress lines. */
        public Builder progressLogMillis(long progressLogMillis) {
            this.progressLogMillis = progressLogMillis;
            return this;
        }

        public SweepOptions build() {
            return new SweepOptions(this);
        }
    }
}
