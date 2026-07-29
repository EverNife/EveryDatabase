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
 * <p>LocalFile enforces no optimistic lock, but it does not need one to be polled: the file's own
 * stamp grows when the file is rewritten, which is all the poller compares. Updates propagate here.
 */
@DisplayName("CacheSync contract - LocalFile (polling on the file stamp)")
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
