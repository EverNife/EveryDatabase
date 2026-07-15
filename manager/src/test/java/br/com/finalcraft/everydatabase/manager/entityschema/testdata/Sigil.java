package br.com.finalcraft.everydatabase.manager.entityschema.testdata;

import br.com.finalcraft.everydatabase.manager.cache.DirtyFlag;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchema;
import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * An evolving test entity whose write-back opts into the {@link DirtyFlag @DirtyFlag} annotation
 * form instead of the {@link br.com.finalcraft.everydatabase.manager.cache.IDirtyable IDirtyable}
 * interface - the flavor an {@code instanceof IDirtyable} check cannot see. Keyed by a
 * {@code String}, so it also proves the key type is not fixed to {@code UUID}.
 */
@JsonAutoDetectFieldsOnly
public class Sigil implements EntitySchema {

    /** The entity key. */
    private String name;
    private String glyph;
    private int potency;

    private int schemaVersion = EntitySchema.INITIAL_SCHEMA_VERSION;

    @DirtyFlag
    @JsonIgnore
    private transient boolean touched;

    public Sigil() {
    }

    /** A sigil stamped at an explicit schema version - how a row written by an older build looks. */
    public Sigil(String name, String glyph, int potency, int schemaVersion) {
        this.name = name;
        this.glyph = glyph;
        this.potency = potency;
        this.schemaVersion = schemaVersion;
    }

    public String getName() {
        return name;
    }

    public String getGlyph() {
        return glyph;
    }

    public int getPotency() {
        return potency;
    }

    public boolean isTouched() {
        return touched;
    }

    @Override
    public int getSchemaVersion() {
        return schemaVersion;
    }

    @Override
    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }
}
