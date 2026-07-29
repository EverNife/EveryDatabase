package br.com.finalcraft.everydatabase.keymajor;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.util.Optional;
import java.util.Set;

/**
 * Everything one key holds, from a single read - the result of
 * {@link KeyMajorStorage#loadKey(Object, EntityDescriptor[])}.
 *
 * <p>It is a snapshot: the collections were read together, so they are consistent with each other in
 * a way N separate {@code find} calls are not. Nothing is read lazily afterwards.
 */
public interface KeyBundle {

    /**
     * The entity this key holds for {@code descriptor}, or empty when the key holds nothing for that
     * collection. Only descriptors passed to {@code loadKey} can be asked for.
     *
     * @throws IllegalArgumentException if the descriptor was not part of the read
     */
    <K, V> Optional<V> get(EntityDescriptor<K, V> descriptor);

    /** The collections that were read, whether or not the key held anything for them. */
    Set<String> collections();

    /** Whether the key held nothing at all - no file, or no requested collection in it. */
    boolean isEmpty();
}
