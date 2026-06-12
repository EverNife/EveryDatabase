package br.com.finalcraft.everydatabase.libby;

import br.com.finalcraft.everydatabase.libby.util.LibraryFactory;
import net.byteflux.libby.Library;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the {@code everydatabase-libby} coordinator. No network is touched:
 * downloads are intercepted by overriding {@link DependencyManager#loadLibrary(Library)}.
 */
@DisplayName("everydatabase-libby coordinator - smoke")
class LibbySmokeTest {

    /** Records which libraries were requested instead of downloading them. */
    static class RecordingManager extends DependencyManager {
        final List<String> loaded = Collections.synchronizedList(new ArrayList<>());
        final List<String> failFor;

        RecordingManager(File rootFolder, String... failFor) {
            super("smoke", rootFolder, "libs", new URLClassLoader(new URL[0], null));
            this.failFor = Arrays.asList(failFor);
        }

        @Override
        public void loadLibrary(Library library) {
            if (failFor.contains(library.getArtifactId())) {
                throw new RuntimeException("simulated failure: " + library.getArtifactId());
            }
            loaded.add(library.getArtifactId());
        }
    }

    @Test
    @DisplayName("LibraryFactory parses group:artifact:version[:checksum] and rejects garbage")
    void libraryFactoryParses() {
        Library lib = LibraryFactory.of("com.zaxxer:HikariCP:5.1.0");
        assertEquals("com.zaxxer", lib.getGroupId());
        assertEquals("HikariCP", lib.getArtifactId());
        assertEquals("5.1.0", lib.getVersion());

        // 4th segment = Base64 SHA-256 checksum
        Library withChecksum = LibraryFactory.of("g:a:1.0:dGVzdA==");
        assertNotNull(withChecksum.getChecksum());

        assertThrows(IllegalArgumentException.class, () -> LibraryFactory.of("only:two"));
    }

    @Test
    @DisplayName("loadAll requests the full flat dependency set (JDBC drivers included), without network")
    void loadAllRequestsEverything(@TempDir File dir) {
        RecordingManager manager = new RecordingManager(dir);

        EveryDatabaseDependencies.loadAll(manager);

        // The 14-jar flat tree of everydatabase-core: the 12 backend jars plus the
        // two JDBC drivers, which ship by default in every distribution flavor.
        List<String> expected = Arrays.asList(
            "jackson-core", "jackson-annotations", "jackson-databind",
            "jackson-dataformat-yaml", "snakeyaml",
            "HikariCP", "slf4j-api",
            "h2",
            "mongodb-driver-sync", "mongodb-driver-core", "bson", "bson-record-codec",
            "mysql-connector-j", "postgresql");
        for (String artifact : expected) {
            assertTrue(manager.loaded.contains(artifact), "loadAll must request " + artifact);
        }
        assertEquals(expected.size(), manager.loaded.size(), "no duplicates, nothing extra");
    }

    @Test
    @DisplayName("a failing library does not prevent the remaining ones from loading")
    void bulkLoadIsolatesFailures(@TempDir File dir) {
        RecordingManager manager = new RecordingManager(dir, "HikariCP");

        EveryDatabaseDependencies.loadAll(manager);

        assertFalse(manager.loaded.contains("HikariCP"), "the failing artifact is not loaded");
        assertTrue(manager.loaded.contains("h2"), "other artifacts still load");
        assertTrue(manager.loaded.contains("jackson-databind"), "other artifacts still load");
        assertEquals(13, manager.loaded.size(), "all artifacts except the failing one");
    }
}
