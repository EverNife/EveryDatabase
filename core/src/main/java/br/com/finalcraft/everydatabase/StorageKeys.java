package br.com.finalcraft.everydatabase;

import java.util.concurrent.CompletableFuture;

/**
 * The cross-backend contract for entity keys, and the single place that enforces it.
 *
 * <p>A key is persisted by its {@link Object#toString()} - the SQL primary key
 * ({@code storage_key VARCHAR(255)}), the Mongo unique index, the LocalFile filename - and is used
 * directly as a map key by the in-memory backend and the manager cache. For a key to behave
 * <b>identically across every backend</b> it must therefore have:
 * <ul>
 *   <li>a <b>stable, unique</b> {@code toString()} no longer than {@link #MAX_KEY_LENGTH}
 *       characters. The default {@code Object.toString()} (an identity hash like {@code Foo@1a2b3c})
 *       is neither stable across runs nor meaningful, and would corrupt persistence - give key
 *       types a real {@code toString()} ({@code UUID}, {@code String}, {@code Long}, {@code Integer}
 *       and {@code record}s all qualify out of the box);</li>
 *   <li>value-based {@link Object#equals(Object)} / {@link Object#hashCode()} (the in-memory
 *       backend and the manager cache look keys up by equality, not by {@code toString()});</li>
 *   <li>and, when used inside a {@code Ref} (the manager module), JSON (de)serializability.</li>
 * </ul>
 *
 * <p>{@link #MAX_KEY_LENGTH} is the safe intersection across backends: it matches the SQL
 * {@code storage_key} column width and stays within Mongo's index-key limit and the file-system
 * filename limit. {@code save}/{@code saveAll} on every backend reject an oversized key up front via
 * {@link #rejectIfTooLong} - failing fast with a clear message instead of an opaque
 * "Data too long" / "File name too long" / "key too large to index" error later, or (worse on SQL)
 * being <b>silently truncated</b> and colliding with a different key. Reads are not validated: a
 * key longer than the limit simply cannot match anything that was stored.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * Maximum length of a key's {@code toString()} that is portable across every backend.
     * Equal to the SQL {@code storage_key} column width.
     */
    public static final int MAX_KEY_LENGTH = 255;

    /**
     * Returns {@code null} when {@code key}'s {@code toString()} is within {@link #MAX_KEY_LENGTH},
     * or an <b>already-failed</b> future carrying a clear {@link IllegalArgumentException} otherwise.
     *
     * <p>Backends call this at the start of {@code save}/{@code saveAll} and short-circuit on a
     * non-null result, so an oversized key surfaces as an exceptional future (the library's
     * error-propagation contract) rather than a synchronous throw - and never reaches storage.
     *
     * @param key        the entity key (its {@code toString()} is what gets persisted)
     * @param collection the collection name, for a helpful message
     */
    public static CompletableFuture<Void> rejectIfTooLong(Object key, String collection) {
        int length = String.valueOf(key).length();
        if (length <= MAX_KEY_LENGTH) {
            return null;
        }
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException(
            "Storage key for collection '" + collection + "' is too long: " + length
            + " characters (maximum " + MAX_KEY_LENGTH + "). Keys are persisted by their toString() "
            + "across all backends - keep them short, stable and unique "
            + "(see the key contract in StorageKeys / EntityDescriptor)."));
        return failed;
    }
}
