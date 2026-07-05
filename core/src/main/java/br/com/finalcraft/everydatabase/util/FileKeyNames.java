package br.com.finalcraft.everydatabase.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns persisted keys into file-name stems that are safe on every supported file system.
 *
 * <p>Short keys map to safe stems verbatim; very long keys are hash-truncated (see below) so the
 * final file name stays within the file system's name-length limits.
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
 *   <li><b>Very long keys</b>: a key near the cross-backend 255-char limit would, once an
 *       extension and collection directory are appended, exceed the file system's
 *       name-length/path limits. Such a stem is clipped to a readable prefix plus the hash
 *       suffix, so distinct long keys never collide and stay one file per key.</li>
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

    /**
     * Longest stem we emit before falling back to a hash-truncated form. A key may be up to
     * {@code StorageKeys.MAX_KEY_LENGTH} (255) chars, but once an extension and a collection
     * directory are appended the file name can blow past the ~255-byte per-component limit
     * (ext4/NTFS) or Windows MAX_PATH. Kept well under that so there is room for the suffix.
     */
    private static final int MAX_STEM_LENGTH = 200;

    private FileKeyNames() {
    }

    /** The safe file-name stem for {@code raw} (see class doc for when a hash suffix is added). */
    public static String safeStem(String raw) {
        String sanitized = sanitizeSeparators(raw);
        if (sanitized.equals(raw) && !hasUpperCase(sanitized) && !isReservedDeviceName(sanitized)
                && sanitized.length() <= MAX_STEM_LENGTH) {
            return raw;
        }
        return hashSuffixed(sanitized, raw);
    }

    /**
     * A readable prefix of {@code sanitized} plus {@code "_" + 8-hex hash of raw}. When the prefix
     * alone would push the stem over {@link #MAX_STEM_LENGTH}, it is clipped so the whole stem stays
     * within the bound; the hash still makes distinct long keys resolve to distinct files.
     */
    private static String hashSuffixed(String sanitized, String raw) {
        String suffix = "_" + String.format("%08x", raw.hashCode());
        int maxPrefix = MAX_STEM_LENGTH - suffix.length();
        String prefix = sanitized.length() > maxPrefix ? sanitized.substring(0, maxPrefix) : sanitized;
        return prefix + suffix;
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
