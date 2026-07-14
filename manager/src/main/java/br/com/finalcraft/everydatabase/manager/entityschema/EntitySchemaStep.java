package br.com.finalcraft.everydatabase.manager.entityschema;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One payload-schema upgrade step applied to the RAW stored payload (a Jackson {@link ObjectNode})
 * BEFORE it is bound to the POJO. Because the step reads and edits the tree directly, the POJO no
 * longer has to keep legacy fields around just to migrate them: delete the field from the class
 * and read it from the node.
 *
 * <p>The step upgrades the payload from its {@code fromVersion} to {@code fromVersion + 1}; the
 * framework (not the step) owns the {@code "schemaVersion"} field and re-stamps it after the step
 * returns. The framework also protects the caller-supplied identity key(s) (e.g. {@code "uuid"} or
 * {@code "accountId"}) and {@code "lockVersion"}: they are snapshotted before each step and restored
 * after, so a step cannot re-key or unlock a row.
 *
 * <p><b>Steps must be pure tree transforms:</b> no I/O, no host-platform API, no shared mutable
 * state. A step runs on decode threads, on any flush/conflict-resolution thread, on cross-backend
 * transfers, and - for an {@link EntitySchemaMigrationMode#EAGER} step - on a background boot sweep
 * over the whole collection (see the cascade note on {@link EntitySchemaMigrationMode}).
 *
 * <p>Not writing a field leaves it absent, so the POJO's own field initializer supplies the default
 * at bind time (JsonNode steps get defaults for free, unlike a POJO-typed step).
 */
@FunctionalInterface
public interface EntitySchemaStep {

    /**
     * Mutates {@code node} in place, upgrading it one schema version. May throw; a throwing step
     * fails the decode of that one row (wrapped in an {@link EntitySchemaMigrationException}),
     * leaving the stored row untouched so the next read retries.
     */
    void upgrade(ObjectNode node) throws Exception;
}
