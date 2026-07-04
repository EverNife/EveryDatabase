package br.com.finalcraft.everydatabase.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns persisted keys into file-name stems that are safe on every supported file system.
 *
 * <p>Shared by the file-per-entity backends (local files, grouped files). The stem must be a
 * pure function of the key so every caller resolves the same file and the same lock for a
 * given key.
 *
 * <p>A stable hash suffix of the <em>original</em> key is appended whenever the raw key cannot
 * be used verbatim ({@link String#hashCode()} is specified by the JLS, so the suffix is stable
 * across JVM restarts):
 * <ul>
 *   <li><b>Path separators</b> ({@code / \ :}) are replaced with {@code _} - and distinct keys
 *       could then collide on disk ({@code "a/b"} vs {@code "a_b"}), so the suffix keeps one
 *       file per key.</li>
 *   <li><b>Upper-case letters</b>: case-insensitive file systems (Windows NTFS, default macOS
 *       APFS) map {@code "Alice"} and {@code "alice"} to the same physical file, while the
 *       per-key locks - keyed by the exact stem - would NOT be shared. The suffix keeps
 *       case-differing keys on distinct files everywhere.</li>
 *   <li><b>Reserved Windows device names</b> ({@code CON}, {@code NUL}, {@code COM1}...):
 *       even with an extension, writing to {@code NUL.json} can silently discard the bytes on
 *       Windows. The suffix turns the stem into a regular file name.</li>
 * </ul>
 *
 * <p>Lower-case/digit-only keys (UUIDs, numeric ids - the overwhelming majority) keep their
 * historical verbatim stems, so files written by older versions are unaffected. For keys the
 * new guards DO rename, readers can fall back to {@link #legacyStem}.
 */
public final class FileKeyNames {

    private static final Set<String> RESERVED_DEVICE_NAMES = new HashSet<>(Arrays.asList(
        "CON", "PRN", "AUX", "NUL",
        "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

    private FileKeyNames() {
    }

    /** The safe file-name stem for {@code raw} (see class doc for when a hash suffix is added). */
    public static String safeStem(String raw) {
        String sanitized = sanitizeSeparators(raw);
        if (sanitized.equals(raw) && !hasUpperCase(sanitized) && !isReservedDeviceName(sanitized)) {
            return raw;
        }
        return sanitized + "_" + String.format("%08x", raw.hashCode());
    }

    /**
     * The stem produced before the case/reserved-name guards existed (hash suffix only when
     * separator sanitisation changed the name). Read paths use it as a fallback so files
     * written by older versions remain reachable; writes always use {@link #safeStem}.
     */
    public static String legacyStem(String raw) {
        String sanitized = sanitizeSeparators(raw);
        if (sanitized.equals(raw)) return raw;
        return sanitized + "_" + String.format("%08x", raw.hashCode());
    }

    private static String sanitizeSeparators(String raw) {
        return raw.replace("/", "_").replace("\\", "_").replace(":", "_");
    }

    private static boolean hasUpperCase(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isReservedDeviceName(String name) {
        int dot = name.indexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        return RESERVED_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT));
    }
}
