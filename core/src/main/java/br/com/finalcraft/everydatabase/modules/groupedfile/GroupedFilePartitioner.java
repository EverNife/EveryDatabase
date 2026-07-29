package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.util.FileKeyNames;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Decides which sub-directory of a key space a given key's file goes in.
 *
 * <p>A key space shrinks the small directories and leaves the big one exactly as big. Ten thousand
 * files in one directory already slows listing down on NTFS, a hundred thousand slows it a lot, and
 * {@code Files.list} is O(entries) no matter what you are looking for. Fan-out is the same hook a
 * key space uses, with a function that is not constant:
 *
 * <pre>
 * player/3f/9c1e4f2a-....yml
 * player/a1/7b2d8e05-....yml
 * </pre>
 *
 * <p>A partitioner is a <b>pure function of the sanitised key</b> and must stay stable forever: the
 * path it returns is where the file already is, so changing it strands every file written under the
 * old one. That is why the choice is recorded in the layout and why opening a key space with a
 * different partitioner fails rather than silently finding nothing.
 *
 * <p>Point reads never scan - {@link #directoryFor(String)} resolves the file directly - so fan-out
 * costs nothing on the read path it does not already save on the scan path.
 */
public abstract class GroupedFilePartitioner {

    /** Names used in {@code _schema/layout.json}; parsing them back is {@link #byName(String)}. */
    static final String FLAT = "flat";

    private final String name;

    private GroupedFilePartitioner(String name) {
        this.name = name;
    }

    /**
     * The sub-directory, relative to the key space's own directory, holding {@code sanitizedKey}'s
     * file. Empty string means the key space directory itself. Never {@code null}, never absolute,
     * and never containing {@code ..}.
     */
    public abstract String directoryFor(String sanitizedKey);

    /**
     * How many directory levels {@link #directoryFor(String)} can produce. It bounds the walk a scan
     * does: without it, a scan would descend into whatever else happens to be under the key space.
     */
    public abstract int depth();

    /** The stable name recorded in the layout. */
    public final String partitionerName() {
        return name;
    }

    @Override
    public final String toString() {
        return name;
    }

    // ------------------------------------------------------------------
    //  Implementations
    // ------------------------------------------------------------------

    /** Every file directly in the key space's directory - the layout with no fan-out. */
    public static GroupedFilePartitioner flat() {
        return FlatPartitioner.INSTANCE;
    }

    /**
     * {@code levels} directories of two hex digits each, taken from a digest of the key. Each level
     * divides the directory by 256, so one level suits tens of thousands of keys and two suits
     * millions.
     *
     * @throws IllegalArgumentException if {@code levels} is outside 1..8
     */
    public static GroupedFilePartitioner hashFanout(int levels) {
        return new HashFanoutPartitioner(levels);
    }

    /**
     * The key's own first {@code chars} characters, so the tree stays readable and a key's directory
     * is guessable by eye. Uneven by construction - real keys are not uniformly distributed - so
     * prefer {@link #hashFanout(int)} unless browsing the tree by hand matters.
     *
     * <p>Keys shorter than {@code chars} are padded on the right with {@code _}, which keeps the
     * function total: a write must never fail because a key was short.
     *
     * @throws IllegalArgumentException if {@code chars} is outside 1..8
     */
    public static GroupedFilePartitioner prefix(int chars) {
        return new PrefixPartitioner(chars);
    }

    /** Rebuilds a partitioner from the name stored in the layout, or {@code null} if unknown. */
    static GroupedFilePartitioner byName(String name) {
        if (name == null || FLAT.equals(name))    return flat();
        if (name.startsWith("hashFanout:"))       return parsed(name, "hashFanout:", true);
        if (name.startsWith("prefix:"))           return parsed(name, "prefix:", false);
        return null;
    }

    private static GroupedFilePartitioner parsed(String name, String prefix, boolean fanout) {
        try {
            int n = Integer.parseInt(name.substring(prefix.length()));
            return fanout ? hashFanout(n) : prefix(n);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class FlatPartitioner extends GroupedFilePartitioner {

        static final FlatPartitioner INSTANCE = new FlatPartitioner();

        private FlatPartitioner() {
            super(FLAT);
        }

        @Override public String directoryFor(String sanitizedKey) { return ""; }
        @Override public int    depth()                           { return 0; }
    }

    private static final class HashFanoutPartitioner extends GroupedFilePartitioner {

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private final int levels;

        HashFanoutPartitioner(int levels) {
            super("hashFanout:" + levels);
            if (levels < 1 || levels > 8) {
                throw new IllegalArgumentException("hashFanout levels must be between 1 and 8: " + levels);
            }
            this.levels = levels;
        }

        @Override
        public String directoryFor(String sanitizedKey) {
            byte[] digest = digest(sanitizedKey);
            StringBuilder path = new StringBuilder(levels * 3);
            for (int level = 0; level < levels; level++) {
                if (level > 0) path.append('/');
                int b = digest[level] & 0xFF;
                path.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
            }
            return path.toString();
        }

        @Override
        public int depth() {
            return levels;
        }

        /**
         * SHA-1 of the key's UTF-8 bytes. It is not used for security here, only for a spread that
         * is identical on every JVM, version and operating system - which rules out
         * {@code String.hashCode} (unspecified for arrays, and free to change) and anything derived
         * from {@code Object.hashCode}. A file's location is permanent, so the function that
         * decided it has to be too.
         */
        private static byte[] digest(String sanitizedKey) {
            try {
                return MessageDigest.getInstance("SHA-1").digest(sanitizedKey.getBytes("UTF-8"));
            } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
                throw new IllegalStateException("SHA-1 over UTF-8 is required by every JVM", e);
            }
        }
    }

    private static final class PrefixPartitioner extends GroupedFilePartitioner {

        private static final char PAD = '_';

        private final int chars;

        PrefixPartitioner(int chars) {
            super("prefix:" + chars);
            if (chars < 1 || chars > 8) {
                throw new IllegalArgumentException("prefix length must be between 1 and 8: " + chars);
            }
            this.chars = chars;
        }

        @Override
        public String directoryFor(String sanitizedKey) {
            StringBuilder bucket = new StringBuilder(chars);
            for (int i = 0; i < chars; i++) {
                bucket.append(i < sanitizedKey.length() ? sanitizedKey.charAt(i) : PAD);
            }
            // A safe key stem is not automatically a safe directory name: its first characters can
            // spell a reserved Windows device (a key starting "CONfig" gives the bucket "CON"), or
            // differ from a sibling bucket only in case. The stem rules already answer both.
            return FileKeyNames.safeStem(bucket.toString());
        }

        @Override
        public int depth() {
            return 1;
        }
    }
}
