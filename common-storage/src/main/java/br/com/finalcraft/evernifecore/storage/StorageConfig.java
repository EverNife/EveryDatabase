package br.com.finalcraft.evernifecore.storage;

/**
 * Marker interface for storage backend configurations.
 *
 * <p>Each storage module provides its own typed implementation:
 * <ul>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryConfig}</li>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileConfig}</li>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig}</li>
 *   <li>{@link br.com.finalcraft.evernifecore.storage.modules.mongo.MongoConfig}</li>
 * </ul>
 *
 * <p>Use {@link Storages#create(StorageConfig)} to obtain a {@link Storage} instance.
 */
public interface StorageConfig {

}
