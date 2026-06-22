package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Cache-sync contract on LocalFile (no Docker). Two storages share one directory. LocalFile does not
 * enforce optimistic locking, so the version poll catches only deletes - the update test self-skips.
 */
@DisplayName("CacheSync contract - LocalFile (polling, delete-only)")
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

    @Override
    protected boolean supportsUpdatePropagation() {
        return false;   // no enforced versioning -> polling detects deletes, not updates
    }
}
