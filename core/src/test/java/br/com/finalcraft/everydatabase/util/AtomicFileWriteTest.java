package br.com.finalcraft.everydatabase.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write half of the same race the scans sit on: publishing a file while somebody reads it.
 *
 * <p>Windows refuses to rename over a file another thread holds open and reports
 * {@link java.nio.file.AccessDeniedException}. Measured on this repository's own workload it hit
 * about half of the writes to a directory being read at the same time, and none of them when nothing
 * read it - so it is the reader's handle, not a permission problem, and it clears as soon as that
 * read finishes. POSIX never sees it.
 */
@DisplayName("AtomicFileWrite")
class AtomicFileWriteTest {

    @TempDir Path dir;

    @Test
    @DisplayName("publishes the whole file and leaves no .tmp behind")
    void publishesWholeFile() throws IOException {
        Path target = dir.resolve("key.yml");

        AtomicFileWrite.write(target, "first: 1".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("first: 1".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        AtomicFileWrite.write(target, "second: 2".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("second: 2".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        assertFalse(Files.exists(target.resolveSibling("key.yml.tmp")), "the .tmp must not survive the write");
        assertEquals(1, DirectoryListing.regularFiles(dir).size());
    }

    @Test
    @DisplayName("creates the directory a write lands in when it is missing")
    void createsMissingParent() throws IOException {
        Path target = dir.resolve("gone").resolve("key.yml");

        AtomicFileWrite.write(target, "a: 1".getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.isRegularFile(target));
    }

    /**
     * A reader holding the target open must not cost the writer its publish. Without the retry this
     * fails on Windows roughly half the time and passes everywhere else, which is exactly the kind of
     * defect that only shows up on somebody else's machine.
     */
    @Test
    @DisplayName("a reader holding the file open never costs a write")
    void writeSurvivesAConcurrentReader() throws Exception {
        Path target = dir.resolve("key.yml");
        AtomicFileWrite.write(target, new byte[4096]);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        CountDownLatch reading = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Files.readAllBytes(target);
                    reads.incrementAndGet();
                } catch (IOException ignored) {
                    // the reader losing a race is not what this pins down
                }
                reading.countDown();
            }
        }, "atomic-write-reader");

        Thread writer = new Thread(() -> {
            try {
                reading.await(5, TimeUnit.SECONDS);
                byte[] payload = new byte[4096];
                while (!stop.get()) {
                    AtomicFileWrite.write(target, payload);
                    writes.incrementAndGet();
                }
            } catch (Throwable t) {
                writeFailure.set(t);
            }
        }, "atomic-write-writer");

        reader.start();
        writer.start();
        Thread.sleep(3_000);
        stop.set(true);
        reader.join(10_000);
        writer.join(10_000);

        assertFalse(reader.isAlive(), "the reader must have stopped");
        assertFalse(writer.isAlive(), "the writer must have stopped");
        assertNull(writeFailure.get(), () ->
            "a write next to a reader must not fail, got: " + writeFailure.get());
        assertTrue(writes.get() > 0, "the writer never published, so nothing was raced against");
        assertTrue(reads.get() > 0, "the reader never read, so nothing held the file open");
    }
}
