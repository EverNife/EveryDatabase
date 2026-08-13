package br.com.finalcraft.everydatabase.modules;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileStorage;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scans of a file backend run next to writes of the same directory: the write publishes through a
 * sibling {@code .tmp} it then renames away, so the scan lists entries that are vanishing under it.
 *
 * <p>What is pinned here is that such a scan fails with the backend's own error or not at all -
 * never with a raw stream failure. A directory listing raises {@link java.io.UncheckedIOException},
 * which is not an {@link java.io.IOException} and so escapes the {@code catch (IOException)} the
 * scans are wrapped in; that is the shape this asserts against, for both file backends.
 */
@DisplayName("File backends - scanning while another thread writes")
class FileScanUnderConcurrentWritesTest {

    @TempDir Path groupedDir;
    @TempDir Path localDir;

    private static final EntityDescriptor<UUID, TestPlayer> PLAYERS = EntityDescriptor
        .builder(UUID.class, TestPlayer.class)
        .collection("playerdata")
        .keyExtractor(TestPlayer::getUuid)
        .codec(new JacksonJsonCodec<>(TestPlayer.class))
        .index(IndexHint.integer("score"))
        .build();

    @Test
    @DisplayName("GroupedFile: all() and query() survive concurrent saves")
    void groupedFileScansSurviveConcurrentSaves() throws Exception {
        GroupedFileStorage storage = Storages.createGroupedFile(new GroupedFileConfig(groupedDir));
        storage.init().join();
        try {
            assertScansSurviveWrites(storage);
        } finally {
            storage.close().join();
        }
    }

    @Test
    @DisplayName("LocalFile: all() and query() survive concurrent saves")
    void localFileScansSurviveConcurrentSaves() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(new LocalFileConfig(localDir));
        storage.init().join();
        try {
            assertScansSurviveWrites(storage);
        } finally {
            storage.close().join();
        }
    }

    /**
     * Hammers {@code repository}'s directory with atomic writes while a second thread scans it, and
     * fails on the first throwable the scan lets escape. The scans are the two that skip an unreadable
     * file and keep going, so anything reaching the caller came from the listing, not from a row.
     */
    private void assertScansSurviveWrites(Storage storage) throws Exception {
        Repository<UUID, TestPlayer> repository = storage.repository(PLAYERS);

        //a population worth scanning, so a scan spans many entries and overlaps many writes
        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            UUID uuid = UUID.randomUUID();
            keys.add(uuid);
            repository.save(new TestPlayer(uuid, "P" + i, i)).join();
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger scans = new AtomicInteger();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        AtomicReference<Throwable> scannerFailure = new AtomicReference<>();
        CountDownLatch writing = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            try {
                int n = 0;
                while (!stop.get()) {
                    UUID uuid = keys.get(n % keys.size());
                    repository.save(new TestPlayer(uuid, "P" + n, n)).join();
                    writes.incrementAndGet();
                    writing.countDown();
                    n++;
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                writing.countDown();
            }
        }, "scan-race-writer");

        Thread scanner = new Thread(() -> {
            try {
                writing.await(10, TimeUnit.SECONDS);
                while (!stop.get()) {
                    repository.all().join().close();
                    repository.query(Query.all(), QueryOptions.builder().limit(10).build()).join();
                    scans.incrementAndGet();
                }
            } catch (Throwable t) {
                scannerFailure.set(t);
            }
        }, "scan-race-scanner");

        writer.start();
        scanner.start();
        Thread.sleep(3_000);
        stop.set(true);
        writer.join(20_000);
        scanner.join(20_000);

        assertFalse(writer.isAlive(), "the writer must have stopped");
        assertFalse(scanner.isAlive(), "the scanner must have stopped");
        assertNull(scannerFailure.get(), () ->
            "a scan next to a write must not fail, got: " + scannerFailure.get());
        //Windows refuses to rename over a file the scan still holds open; the write path retries
        //through that window, so a save must not lose to a reader here either.
        assertNull(writerFailure.get(), () ->
            "a save next to a scan must not fail, got: " + writerFailure.get());
        //without both actually running, a green result would mean nothing
        assertTrue(writes.get() > 0, "the writer never landed a save, so nothing was raced against");
        assertTrue(scans.get() > 0, "the scanner never completed a pass");
    }
}
