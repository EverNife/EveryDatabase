package br.com.finalcraft.evernifecore.storage.schema;

/**
 * The currently applied schema version of a {@link SchemaAwareStorage}.
 */
public final class SchemaVersion {

    private final String version;
    private final long appliedAt;

    public SchemaVersion(String version, long appliedAt) {
        this.version   = version;
        this.appliedAt = appliedAt;
    }

    /** Sentinel for "no migrations have been applied yet". */
    public static SchemaVersion none() {
        return new SchemaVersion("0", 0L);
    }

    /** The version identifier of the last applied migration. */
    public String version()  { return version; }

    /** Unix epoch millis when the migration was applied. */
    public long appliedAt()  { return appliedAt; }

    @Override
    public String toString() {
        return "SchemaVersion{" + version + "}";
    }
}
