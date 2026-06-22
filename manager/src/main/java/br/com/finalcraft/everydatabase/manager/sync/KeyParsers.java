package br.com.finalcraft.everydatabase.manager.sync;

import java.util.UUID;
import java.util.function.Function;

/**
 * Built-in {@code String -> K} parsers for the common key-contract types, used by {@link CacheSync}
 * to turn a {@link br.com.finalcraft.everydatabase.changefeed.ChangeEvent#key()} (the persisted
 * {@code toString()} form) back into a cache key.
 *
 * <p>Covers {@code String}, {@code UUID}, {@code Long}, and {@code Integer} - the value-based key
 * types the cross-backend key contract lists. Composite, {@code record}, or wrapper keys have no
 * general inverse of {@code toString()}, so bind those with an explicit parser:
 * {@code cacheSync.bind(manager, keyString -> myKeyFrom(keyString))}.
 */
public final class KeyParsers {

    private KeyParsers() {}

    /**
     * Returns the built-in parser for {@code keyType}.
     *
     * @throws IllegalArgumentException if {@code keyType} is {@code null} (the manager was built
     *         without a descriptor) or has no built-in parser - bind an explicit one instead.
     */
    @SuppressWarnings("unchecked")
    public static <K> Function<String, K> forType(Class<K> keyType) {
        if (keyType == null) {
            throw new IllegalArgumentException(
                "Cannot derive a key parser: the manager has no key type (it was built via the bare "
                + "constructor). Bind it with an explicit parser: bind(manager, keyParser).");
        }
        if (keyType == String.class) {
            return s -> (K) s;
        }
        if (keyType == UUID.class) {
            return s -> (K) UUID.fromString(s);
        }
        if (keyType == Long.class || keyType == long.class) {
            return s -> (K) Long.valueOf(s);
        }
        if (keyType == Integer.class || keyType == int.class) {
            return s -> (K) Integer.valueOf(s);
        }
        throw new IllegalArgumentException(
            "No built-in key parser for key type '" + keyType.getName() + "'. Bind the manager with "
            + "an explicit parser: bind(manager, keyParser). (Built-in: String, UUID, Long, Integer.)");
    }
}
