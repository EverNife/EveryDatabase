package br.com.finalcraft.everydatabase;

import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

/**
 * Marker interface for storage backend configurations.
 *
 * <p>Each storage module provides its own typed implementation:
 * <ul>
 *   <li>{@link InMemoryConfig}</li>
 *   <li>{@link LocalFileConfig}</li>
 *   <li>{@link SqlConfig}</li>
 *   <li>{@link MongoConfig}</li>
 * </ul>
 *
 * <p>Use {@link Storages#create(StorageConfig)} to obtain a {@link Storage} instance.
 */
public interface StorageConfig {

}
