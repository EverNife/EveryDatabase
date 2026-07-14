package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import lombok.Getter;
import lombok.Setter;

/**
 * One row of the framework-owned {@code entity_schema_sweeps} meta collection: records how far the
 * eager schema sweep has progressed for ONE data collection, on the SAME backend as that data.
 * Keyed by the data collection's name.
 *
 * <p>The {@link OptimisticLock} version doubles as a cross-instance CAS lease on enforcing backends
 * (MariaDB/MySQL/PostgreSQL/Mongo): only one instance claims the in-progress slot, and it
 * heartbeats the lease per batch. On non-enforcing backends (LocalFile/GroupedFile/H2/InMemory) the
 * CAS is advisory - but those backends are physically single-instance, so no second sweeper exists
 * to race.
 *
 * <p><b>The marker is a hint, never authority.</b> The lazy decode-time migration
 * ({@link EntitySchemaMigratingCodec}) never consults it; per-row {@code schemaVersion} + the chain
 * remain the source of truth. It only lets a completed sweep skip the full re-scan on subsequent
 * boots.
 */
@JsonAutoDetectFieldsOnly
@Getter
@Setter
public class EntitySchemaSweepMarker {

    /** The data collection this marker describes (the storage key). */
    private String collection;
    /** FQCN of the swept entity type (informational). */
    private String typeName;
    /** Highest eager target version fully swept; {@link EntitySchema#INITIAL_SCHEMA_VERSION} initially. */
    private int completedVersion = EntitySchema.INITIAL_SCHEMA_VERSION;
    /** Non-zero while a sweep is in progress (the version it is sweeping towards). */
    private int inProgressVersion = 0;
    /** The per-boot id of the instance currently holding the lease. */
    private String runnerId;
    /** Epoch millis the current lease expires at (renewed per batch). */
    private long leaseExpiresAtEpochMs = 0L;
    /** Epoch millis of the last completed sweep. */
    private long lastSweepEpochMs = 0L;

    // last-run telemetry
    private long scanned = 0L;
    private long rewritten = 0L;
    private long failed = 0L;

    @OptimisticLock
    private Long lockVersion;

    public EntitySchemaSweepMarker() {
        // Jackson no-arg constructor
    }
}
