package br.com.finalcraft.everydatabase.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Turns a file's metadata into the opaque, increasing number the version-polling substrate wants.
 *
 * <p>The file backends enforce no optimistic lock, so they have no {@code lock_version} to report.
 * What the poller actually needs is weaker than a lock version: a number that <b>grows</b> when the
 * content changes. A file already carries one - its modification time - and reading it is a
 * {@code stat}, not an open.
 *
 * <p>The size is folded into the low bits because modification time alone is only as fine as the
 * file system's clock: two writes inside one tick read as one. Modification time still occupies the
 * high bits, so the stamp is strictly increasing in time; within a single tick, a file that grew
 * also stamps higher. It is a hint, not a counter - never compare stamps across backends, and never
 * read one as "how many times this was written".
 */
public final class FileStamps {

    /** Bits reserved for the size; 1 MiB of low bits, which is plenty to separate two writes. */
    private static final int SIZE_BITS = 20;
    private static final long SIZE_MASK = (1L << SIZE_BITS) - 1;

    private FileStamps() {}

    /**
     * The stamp for {@code file}, or {@code null} when it does not exist or cannot be read - the
     * caller reports an absent stamp as an absent key, which the poller reads as a delete.
     */
    public static Long of(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return (attrs.lastModifiedTime().toMillis() << SIZE_BITS) | (attrs.size() & SIZE_MASK);
        } catch (IOException e) {
            return null;
        }
    }
}
