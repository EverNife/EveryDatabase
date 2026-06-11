package br.com.finalcraft.everydatabase.modules.localfile;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageStressTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 10k-record stress run against the local-file backend. Skip with {@code -PskipStress}. */
@DisplayName("LocalFileStorage - stress")
class LocalFileStorageStressTest extends AbstractStorageStressTest {

    static final Path RESIDUALS_ROOT = Path.of("build/test-residuals/LocalFileStorageStress");

    @Override
    protected Storage createStorage(String testMethodName) {
        Path tempDir = RESIDUALS_ROOT.resolve(testMethodName);
        deleteQuietly(tempDir);
        tempDir.toFile().mkdirs();
        return new LocalFileStorage(new LocalFileConfig(tempDir));
    }

    @AfterAll
    static void cleanResiduals() {
        deleteQuietly(RESIDUALS_ROOT);
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
