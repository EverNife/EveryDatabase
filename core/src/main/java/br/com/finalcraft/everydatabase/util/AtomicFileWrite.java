package br.com.finalcraft.everydatabase.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Publishes a file whole: the bytes go to a sibling {@code .tmp}, which is then renamed over the
 * target. A crash mid-write leaves the previous file intact and at worst an orphan {@code .tmp}, and
 * a reader sees either the old content or the new one - never a half-written file.
 *
 * <p><b>Windows loses this rename to a concurrent reader.</b> Renaming over a file another thread
 * holds open fails there with {@link AccessDeniedException}: measured at roughly half of the writes
 * to a directory being scanned at the same time, and at none of them when nothing reads it. POSIX
 * does not care about open readers, so it never sees this. The blocking handle only lives as long as
 * one read, so the rename is retried for a few milliseconds instead of being reported - long enough
 * that the reader finishes, short enough that a genuine permission error still surfaces promptly.
 */
public final class AtomicFileWrite {

    /** Retries are for a reader that is about to close, not for a lock somebody else is holding. */
    private static final int MOVE_ATTEMPTS = 20;
    /** Yield while the window is likely microseconds; only back off to a real sleep after that. */
    private static final int SPIN_ATTEMPTS = 8;

    private AtomicFileWrite() {}

    /**
     * Writes {@code data} to {@code target}, creating its directory when missing - a write must still
     * land after the directory was removed underneath a running storage.
     *
     * @throws IOException if the temporary file cannot be written, or the rename keeps failing
     */
    public static void write(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        moveOver(tmp, target);
    }

    /**
     * Renames {@code tmp} over {@code target}, retrying while the platform reports the target as
     * held open. Falls back to a plain replace on a file system without atomic rename, which gives
     * up the "never truncated" guarantee because there is nothing else on offer there.
     */
    private static void moveOver(Path tmp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= MOVE_ATTEMPTS) throw e;
                pause(attempt);
            }
        }
    }

    private static void pause(int attempt) throws IOException {
        if (attempt <= SPIN_ATTEMPTS) {
            Thread.yield();
            return;
        }
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while retrying an atomic rename");
        }
    }
}
