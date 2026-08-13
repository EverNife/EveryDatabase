package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** {@link AbstractGenerationSwapTest} on the local-file backend. */
class LocalFileGenerationSwapTest extends AbstractGenerationSwapTest {

    @TempDir
    Path baseDirectory;

    @Override
    protected Storage openGeneration() {
        // each generation is a NEW storage over the same directory - exactly what a reload opens
        Storage storage = Storages.createLocalFile(new LocalFileConfig(baseDirectory));
        storage.init().join();
        opened.add(storage);
        return storage;
    }
}
