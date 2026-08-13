package br.com.finalcraft.everydatabase.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists the files of one directory without letting a concurrent write escape unchecked.
 *
 * <p>A directory stream reports a mid-iteration failure as an {@link UncheckedIOException}, and
 * {@link Files#walk} adds a second source of them: it stats every entry it hands out, so an entry that
 * disappears between the directory read and the stat raises one naming that entry. The atomic write
 * path produces exactly that entry - it creates a sibling {@code .tmp} and renames it away - so a scan
 * running next to a write is the ordinary case, not a corner one.
 *
 * <p>{@link UncheckedIOException} is not an {@link IOException}, so it slips past the
 * {@code catch (IOException)} the scans are wrapped in and surfaces as a raw stream failure instead of
 * the backend's own error. Listing through this class turns it back into the checked failure those
 * scans already handle.
 */
public final class DirectoryListing {

    private DirectoryListing() {}

    /**
     * The regular files directly under {@code directory} whose name ends with {@code extension}, or an
     * empty list when the directory does not exist. One level deep, so nothing nested is mistaken for
     * a file of this directory.
     *
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> regularFilesEndingWith(Path directory, String extension) throws IOException {
        if (!Files.isDirectory(directory)) return Collections.emptyList();
        try (Stream<Path> entries = Files.list(directory)) {
            return collect(entries
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(extension)));
        }
    }

    /**
     * Every regular file directly under {@code directory}, or an empty list when it does not exist.
     *
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> regularFiles(Path directory) throws IOException {
        return regularFilesEndingWith(directory, "");
    }

    /**
     * The directories directly under {@code directory}, or an empty list when it does not exist.
     *
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> subdirectories(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return Collections.emptyList();
        try (Stream<Path> entries = Files.list(directory)) {
            return collect(entries.filter(Files::isDirectory));
        }
    }

    /**
     * The first regular file anywhere below {@code directory} that {@code match} accepts, or
     * {@code null} when there is none. Walks the whole sub-tree but stops at the first hit, so a
     * "is there any such file down there" question does not pay for the rest of it.
     *
     * @throws IOException if the sub-tree cannot be walked
     */
    public static Path firstRegularFileBelow(Path directory, Predicate<Path> match) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        try (Stream<Path> below = Files.walk(directory)) {
            return below.filter(Files::isRegularFile).filter(match).findFirst().orElse(null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Collects {@code stream}, unwrapping the {@link UncheckedIOException} a directory stream raises
     * mid-iteration back into the {@link IOException} it carries. The caller closes the stream.
     *
     * @throws IOException the cause of the {@link UncheckedIOException}, if one was raised
     */
    public static List<Path> collect(Stream<Path> stream) throws IOException {
        try {
            return stream.collect(Collectors.toList());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
