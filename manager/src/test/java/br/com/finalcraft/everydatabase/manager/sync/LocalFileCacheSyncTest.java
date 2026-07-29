package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Cache-sync contract on LocalFile (no Docker). Two storages share one directory.
 *
 * <p>This backend has a change feed of its own now, so {@code CacheSync.attach} routes it through
 * push rather than polling - which is what this suite ends up exercising. Its polling substrate is
 * covered separately by {@code FilePollingCacheSyncTest}.
 */
@DisplayName("CacheSync contract - LocalFile (watch-service feed)")
class LocalFileCacheSyncTest extends AbstractCacheSyncTest {

    @TempDir
    Path sharedDir;

    @Override
    protected Storage openWriter() {
        return Storages.createLocalFile(new LocalFileConfig(sharedDir));
    }

    @Override
    protected Storage openReader() {
        return Storages.createLocalFile(new LocalFileConfig(sharedDir));
    }
}
