package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Cache-sync contract on GroupedFile (no Docker). Two storages share one directory.
 *
 * <p>Like LocalFile, this backend has no lock column and does not need one to be polled - the key
 * file's stamp grows when it is rewritten. The memo is deliberately switched off on both sides: the
 * point of this suite is that one storage observes what the <em>other</em> wrote, and a memo
 * validated by a coarse file stamp is exactly the thing that could mask it.
 */
@DisplayName("CacheSync contract - GroupedFile (polling on the file stamp)")
class GroupedFileCacheSyncTest extends AbstractCacheSyncTest {

    @TempDir
    Path sharedDir;

    @Override
    protected Storage openWriter() {
        return Storages.createGroupedFile(new GroupedFileConfig(sharedDir).rootCacheSize(0));
    }

    @Override
    protected Storage openReader() {
        return Storages.createGroupedFile(new GroupedFileConfig(sharedDir).rootCacheSize(0));
    }
}
