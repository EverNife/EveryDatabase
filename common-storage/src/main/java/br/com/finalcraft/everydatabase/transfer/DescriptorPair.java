package br.com.finalcraft.everydatabase.transfer;

import br.com.finalcraft.everydatabase.EntityDescriptor;

/**
 * Pairs a source {@link EntityDescriptor} with a target {@link EntityDescriptor}.
 *
 * <p>In the common case both descriptors are the same object (same collection name,
 * same codec). In the advanced case they differ - e.g. a collection rename or a
 * codec change - but they must have the same {@code <K, V>} type parameters so the
 * entities decoded from the source can be encoded directly into the target.
 *
 * <p>Package-private: this is an internal implementation detail of
 * {@link StorageTransfer.Builder} and {@link StorageTransferImpl}.
 *
 * @param <K> the entity key type
 * @param <V> the entity value type
 */
final class DescriptorPair<K, V> {

    final EntityDescriptor<K, V> source;
    final EntityDescriptor<K, V> target;

    DescriptorPair(EntityDescriptor<K, V> source, EntityDescriptor<K, V> target) {
        this.source = source;
        this.target = target;
    }

    @Override
    public String toString() {
        if (source == target || source.collection().equals(target.collection())) {
            return "DescriptorPair{" + source.collection() + "}";
        }
        return "DescriptorPair{" + source.collection() + " -> " + target.collection() + "}";
    }
}
