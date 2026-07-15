package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.schema.SchemaVersion;

/**
 * An entity that carries its own on-disk payload schema version, so a stored row written by an older
 * build can be lazily upcast on read before the framework hands it to a consumer.
 *
 * <p>The version is a plain persisted integer starting at {@link #INITIAL_SCHEMA_VERSION}. It is
 * <b>per-entity payload evolution</b> - a different axis from both {@link SchemaVersion} (the
 * backend-wide DDL tracker under {@code schema/}) and
 * {@link br.com.finalcraft.everydatabase.versioned.Versioned Versioned} (the per-row optimistic-lock
 * counter under {@code versioned/}). An entity can carry any combination of the three: {@code
 * EntitySchema} controls how its JSON fields evolve, {@code Versioned} guards it against concurrent
 * writes, and {@code SchemaAwareStorage} shapes the collection it lives in.
 *
 * <p>Implementors add a {@code schemaVersion} field and expose it through the two accessors. A
 * decoded entity whose stored version is behind is run through the registered
 * {@link EntitySchemaStep} chain (via {@link EntitySchemaMigratingCodec}) and re-persisted only after
 * it was actually upcast.
 *
 * <p><b>The field MUST be initialized</b> - either to {@link #INITIAL_SCHEMA_VERSION} (an entity
 * whose shape is the original one) or, for a brand-new entity, to
 * {@link EntitySchemaMigrations#currentVersion(Class)} (its shape is already the newest, so no step
 * should ever run on it). A left-at-zero {@code int schemaVersion} persists a version that names no
 * known shape, and every later read of that row fails rather than guess which shape it holds.
 */
public interface EntitySchema {

    /** The version every freshly created entity starts at (nothing has been migrated yet). */
    int INITIAL_SCHEMA_VERSION = 1;

    /** The schema version of the currently held (possibly just-decoded) payload. */
    int getSchemaVersion();

    /** Overwrites the schema version - called by the migration runner after an upcast step. */
    void setSchemaVersion(int schemaVersion);
}
