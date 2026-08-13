package br.com.finalcraft.everydatabase.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing primitive the file backends scan through.
 *
 * <p>The failure it exists to stop is not a corner case: every atomic write creates a sibling
 * {@code .tmp} and renames it away, so a scan running next to a write routinely walks entries that
 * are disappearing. A directory stream reports that as an {@link UncheckedIOException}, which is not
 * an {@link IOException} and therefore escapes the {@code catch (IOException)} the scans are wrapped
 * in - reaching the caller as a raw stream failure instead of the backend's own error.
 */
@DisplayName("DirectoryListing")
class DirectoryListingTest {

    @TempDir Path dir;

    /**
     * The translation itself, without depending on a race to produce it: the caller must receive the
     * carried {@link IOException}, which is what the scans above already know how to handle.
     */
    @Test
    @DisplayName("collect unwraps an UncheckedIOException into the IOException it carries")
    void collectUnwrapsIntoTheCause() {
        IOException cause = new NoSuchFileException("6f1e.yml.tmp");
        Stream<Path> exploding = Stream.of(dir).map(p -> {
            throw new UncheckedIOException(cause);
        });

        IOException thrown = assertThrows(IOException.class, () -> DirectoryListing.collect(exploding));

        assertSame(cause, thrown, "the carried cause must reach the caller, not a new wrapper");
    }

    @Test
    @DisplayName("the .tmp half of an atomic write is never a listed file")
    void tmpSiblingIsNotListed() throws IOException {
        Files.write(dir.resolve("alice.yml"), "a: 1".getBytes());
        Files.write(dir.resolve("bob.yml.tmp"), "b: 2".getBytes());
        Files.createDirectory(dir.resolve("nested.yml"));   // a directory wearing the extension

        List<Path> listed = DirectoryListing.regularFilesEndingWith(dir, ".yml");

        assertEquals(1, listed.size(), "only the regular .yml file counts, got: " + listed);
        assertEquals("alice.yml", listed.get(0).getFileName().toString());
    }

    @Test
    @DisplayName("an absent directory lists as empty rather than failing")
    void absentDirectoryListsEmpty() throws IOException {
        assertTrue(DirectoryListing.regularFilesEndingWith(dir.resolve("nope"), ".yml").isEmpty());
    }

    /**
     * The race as it actually happens. On a file system that stats each entry separately (Linux) an
     * unhardened listing raises {@code UncheckedIOException: NoSuchFileException: <name>.yml.tmp}
     * here; on one that carries attributes out of the directory read (Windows) it does not, so this
     * is a guard rather than a reproduction everywhere.
     */
    @Test
    @DisplayName("listing survives writes renaming .tmp files underneath it")
    void listingSurvivesConcurrentAtomicWrites() throws Exception {
        for (int i = 0; i < 200; i++) {
            Files.write(dir.resolve("seed-" + i + ".yml"), "seed: 1".getBytes());
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> listerFailure = new AtomicReference<>();
        AtomicReference<Path> leakedTmp = new AtomicReference<>();
        CountDownLatch writing = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            byte[] payload = new byte[8192];
            long n = 0;
            while (!stop.get()) {
                Path target = dir.resolve("key-" + (n++ % 64) + ".yml");
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                try {
                    Files.write(tmp, payload, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // the writer racing itself is not what this test is about
                }
                writing.countDown();
            }
        }, "listing-race-writer");

        Thread lister = new Thread(() -> {
            try {
                writing.await(5, TimeUnit.SECONDS);
                while (!stop.get()) {
                    for (Path listed : DirectoryListing.regularFilesEndingWith(dir, ".yml")) {
                        if (listed.getFileName().toString().endsWith(".tmp")) leakedTmp.set(listed);
                    }
                }
            } catch (Throwable t) {
                listerFailure.set(t);
            }
        }, "listing-race-lister");

        writer.start();
        lister.start();
        Thread.sleep(3_000);
        stop.set(true);
        writer.join(10_000);
        lister.join(10_000);

        assertFalse(writer.isAlive(), "the writer must have stopped");
        assertFalse(lister.isAlive(), "the lister must have stopped");
        assertNull(leakedTmp.get(), "a half-written .tmp must never be handed out as a key file");
        assertNull(listerFailure.get(), () ->
            "a listing next to a write must not fail, got: " + listerFailure.get());
    }
}
