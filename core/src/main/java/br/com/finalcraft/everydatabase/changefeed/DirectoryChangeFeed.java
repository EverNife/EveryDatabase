package br.com.finalcraft.everydatabase.changefeed;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The event <em>source</em> a file-backed {@link ChangeFeedStorage} composes, next to the
 * {@link ChangeFeedSupport} that fans events out.
 *
 * <p>Where Mongo has change streams and PostgreSQL has {@code LISTEN/NOTIFY}, a directory has the
 * operating system's own file-watch notification. One daemon thread per storage watches a tree and
 * reports what changed in it; the storage decides what each file means, because only it knows how
 * its layout maps paths to collections and keys.
 *
 * <p>This buys something none of the other feeds can: a change made <b>outside the application</b> -
 * an administrator editing a YAML file by hand - is observed exactly like one made through the API.
 *
 * <p>Directories created later are picked up: a {@code CREATE} of a directory registers it too, so a
 * collection whose directory did not exist when the watch started is still watched. The reserved
 * {@code _schema} directory never is, and neither are the {@code .tmp} files the atomic write path
 * leaves behind mid-write.
 *
 * <p><b>Platform note.</b> Linux and Windows have real kernel notification. On macOS the JDK falls
 * back to an internal polling watcher whose latency is measured in seconds - the feed still works,
 * it is just no faster than the polling it replaces. Pick {@code PollingCacheSync} explicitly there
 * if the cadence needs to be under your control.
 */
public final class DirectoryChangeFeed implements Closeable {

    /** What the watcher saw, before anything has decided what it means. */
    public interface FileEventSink {
        /**
         * @param directory the watched directory holding the file
         * @param fileName  the file's own name, extension included
         * @param op        {@link ChangeOp#SAVE} for a create or modify, {@link ChangeOp#DELETE} otherwise
         */
        void onFileEvent(Path directory, String fileName, ChangeOp op);
    }

    private static final String RESERVED_DIRECTORY = "_schema";
    private static final String TEMP_SUFFIX        = ".tmp";

    private final Path              root;
    private final String            threadName;
    private final FileEventSink     sink;
    private final Consumer<Throwable> errorSink;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed  = new AtomicBoolean();

    private volatile WatchService watchService;
    private volatile Thread       thread;

    public DirectoryChangeFeed(String threadName, Path root, FileEventSink sink,
                               Consumer<Throwable> errorSink) {
        this.threadName = threadName;
        this.root       = root;
        this.sink       = sink;
        this.errorSink  = errorSink;
    }

    /**
     * Starts watching, once. Safe to call on every subscription - the first call wins and the rest
     * are no-ops, which is what lets a storage start the feed lazily from {@code subscribe}.
     *
     * @throws IllegalStateException if the watch service cannot be created
     */
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerTree(root);
        } catch (IOException e) {
            started.set(false);
            throw new IllegalStateException("Failed to start watching '" + root + "' for changes", e);
        }
        Thread worker = new Thread(this::run, threadName);
        worker.setDaemon(true);         // a watcher must never hold the JVM open
        thread = worker;
        worker.start();
    }

    public boolean isRunning() {
        Thread worker = thread;
        return worker != null && worker.isAlive();
    }

    /**
     * Stops the thread and releases the watch service. Idempotent, like every {@code close()} here:
     * closing the service is what wakes the blocked thread, so a second call finds nothing to do.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();        // makes the blocked take() throw, which ends the loop
            } catch (IOException ignored) {
                // closing twice, or a service already gone - nothing left to release either way
            }
        }
        Thread worker = thread;
        if (worker != null) {
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------
    //  Watching
    // ------------------------------------------------------------------

    private void registerTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        register(directory);
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : (Iterable<Path>) children.filter(Files::isDirectory)::iterator) {
                if (isReserved(child)) continue;
                registerTree(child);
            }
        }
    }

    private void register(Path directory) throws IOException {
        try {
            directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        } catch (NotDirectoryException e) {
            // It was a directory a moment ago and is not one now; nothing to watch.
        }
    }

    private static boolean isReserved(Path directory) {
        return RESERVED_DIRECTORY.equals(String.valueOf(directory.getFileName()));
    }

    private void run() {
        while (!closed.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;                 // close() or a shutdown - both mean stop, neither is an error
            }
            Path directory = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                try {
                    dispatch(directory, event);
                } catch (Throwable t) {
                    report(t);          // one bad event must not end the watch
                }
            }
            if (!key.reset()) {
                // The directory is gone; nothing more will come from this key.
                if (directory.equals(root)) return;
            }
        }
    }

    private void dispatch(Path directory, WatchEvent<?> event) throws IOException {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            // Events were dropped by the OS buffer. There is no "everything changed" event to
            // publish, so this is reported rather than guessed at - a poll is the way to recover.
            report(new IllegalStateException(
                "Change feed overflowed while watching '" + directory + "': some changes were not "
                + "delivered. Poll the affected collections, or lower the write rate."));
            return;
        }
        String fileName = String.valueOf(event.context());
        Path resolved = directory.resolve(fileName);

        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(resolved)) {
            // A directory appearing after the watch started - a collection opened just now.
            if (!isReserved(resolved)) registerTree(resolved);
            return;
        }
        if (fileName.endsWith(TEMP_SUFFIX) || isReserved(directory)) return;

        sink.onFileEvent(directory, fileName,
            event.kind() == StandardWatchEventKinds.ENTRY_DELETE ? ChangeOp.DELETE : ChangeOp.SAVE);
    }

    private void report(Throwable t) {
        if (errorSink == null) return;
        try {
            errorSink.accept(t);
        } catch (Throwable ignored) {
            // an error sink must never end the watch either
        }
    }
}
