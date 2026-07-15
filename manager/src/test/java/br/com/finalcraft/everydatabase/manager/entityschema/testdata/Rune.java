package br.com.finalcraft.everydatabase.manager.entityschema.testdata;

import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchema;
import br.com.finalcraft.everydatabase.util.JsonAutoDetectFieldsOnly;

/**
 * An evolving test entity with <b>no</b> dirty tracking at all - neither the interface nor the
 * annotation form. It still decodes and migrates lazily, but nothing can tell an upcast instance
 * from an untouched one, which is what an eager sweep of it must refuse to accept.
 */
@JsonAutoDetectFieldsOnly
public class Rune implements EntitySchema {

    /** The entity key. */
    private String id;
    private String inscription;

    private int schemaVersion = EntitySchema.INITIAL_SCHEMA_VERSION;

    public Rune() {
    }

    public Rune(String id, String inscription, int schemaVersion) {
        this.id = id;
        this.inscription = inscription;
        this.schemaVersion = schemaVersion;
    }

    public String getId() {
        return id;
    }

    public String getInscription() {
        return inscription;
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
