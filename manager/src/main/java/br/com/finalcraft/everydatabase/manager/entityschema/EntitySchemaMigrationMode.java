package br.com.finalcraft.everydatabase.manager.entityschema;

/**
 * How an {@link EntitySchemaStep} is applied.
 *
 * <p>{@link #LAZY} (the default) upcasts a stored payload only when it is read - a row that is
 * never accessed keeps its old-schema shape forever. {@link #EAGER} additionally asks the framework
 * to sweep the whole collection at boot and rewrite every stale row.
 *
 * <p><b>Cascade cost.</b> A step being {@code EAGER} pulls its predecessors along: because a
 * decoded payload must always reach {@link EntitySchemaMigrations#currentVersion(Class)
 * currentVersion} in one pass, any row the sweep touches runs ALL its pending steps - the earlier
 * ones AND any {@code LAZY} steps registered above the last eager one. In other words, declaring
 * step N eager makes every step up to N effectively eager for the rows still behind it. This is a
 * deliberate, documented cost.
 */
public enum EntitySchemaMigrationMode {

    /** Upcast on read only (no boot-time mass rewrite). */
    LAZY,

    /** Upcast on read AND sweep the whole collection at boot (see the cascade note above). */
    EAGER
}
