package br.com.finalcraft.everydatabase.manager.entityschema.testdata;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchema;
import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * An evolving test entity carrying all three schema axes at once: a payload schema version
 * ({@link EntitySchema}), an optimistic-lock counter, and write-back dirty tracking through the
 * {@link IDirtyable} interface form.
 *
 * <p>Its lock field is deliberately named {@code revision} rather than {@code lockVersion}: the
 * migration runner must protect whatever the {@code @OptimisticLock} field is called, not a
 * hard-coded name.
 *
 * <p>The shape it models across versions:
 * <ul>
 *   <li>v1 - {@code {uuid, power}}</li>
 *   <li>v2 - {@code {uuid, power, element}} (a new field, backfilled with a default)</li>
 *   <li>v3 - {@code {uuid, might, element}} ({@code power} renamed, and gone from this class)</li>
 * </ul>
 * The class holds only the current (v3) shape - reading {@code power} is the migration step's job.
 *
 * <p>Jackson binds the fields directly (no accessors), so decoding never trips the dirtying mutator
 * and a freshly loaded instance is clean.
 */
@JsonAutoDetectFieldsOnly
public class Talisman implements EntitySchema, IDirtyable {

    /** The entity key, and the identity field the migration runner is told to protect. */
    private UUID uuid;
    private int might;
    private String element;

    @OptimisticLock
    private Long revision;

    private int schemaVersion = EntitySchema.INITIAL_SCHEMA_VERSION;

    @JsonIgnore
    private transient boolean dirty;

    public Talisman() {
    }

    /** A talisman stamped at an explicit schema version - how a row written by an older build looks. */
    public Talisman(UUID uuid, int might, String element, int schemaVersion) {
        this.uuid = uuid;
        this.might = might;
        this.element = element;
        this.schemaVersion = schemaVersion;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getMight() {
        return might;
    }

    public String getElement() {
        return element;
    }

    public Long getRevision() {
        return revision;
    }

    /** A domain mutation marks the talisman dirty (write-back). */
    public void empower(int amount) {
        this.might += amount;
        markDirty();
    }

    @Override
    public int getSchemaVersion() {
        return schemaVersion;
    }

    @Override
    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markClean() {
        this.dirty = false;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }
}
