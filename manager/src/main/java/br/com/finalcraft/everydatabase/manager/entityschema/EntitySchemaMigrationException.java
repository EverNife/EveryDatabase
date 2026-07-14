package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.codec.CodecException;

/**
 * Raised when a raw-payload schema migration fails: a step threw, or the stored payload is
 * malformed (not a JSON object, or a non-integral {@code "schemaVersion"}).
 *
 * <p>Extends {@link CodecException} so it rides the exact failure path a broken decode already
 * takes - the repository future completes exceptionally, the caching manager installs no cell, and
 * the caller's resolve future fails - without any new plumbing. The stored row is never written
 * mid-migration, so a failed row is safe to leave in place and retries on its next read.
 */
public class EntitySchemaMigrationException extends CodecException {

    /** A migration step threw while upgrading {@code type} from {@code fromVersion}. */
    public EntitySchemaMigrationException(Class<?> type, int fromVersion, Throwable cause) {
        super("Entity-schema migration of " + type.getName() + " from v" + fromVersion + " to v"
                + (fromVersion + 1) + " failed", cause);
    }

    /** The stored payload is malformed (structure or version field). */
    public EntitySchemaMigrationException(Class<?> type, String problem) {
        super("Entity-schema migration of " + type.getName() + ": " + problem);
    }
}
