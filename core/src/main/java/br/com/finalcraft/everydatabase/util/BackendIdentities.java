package br.com.finalcraft.everydatabase.util;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Derives the stable identity of a physical store from a backend's own coordinates - the algorithm
 * behind {@code Storage.backendIdentity()}.
 *
 * <p>Pure string/path work: no connection is opened, no name is resolved per call, nothing is
 * written anywhere. Every method is a function of its arguments, so a caller can reason about the
 * result without knowing the machine it runs on.
 *
 * <h3>Shareable vs machine-local coordinates</h3>
 * A coordinate that <em>routes</em> - a real host name or a non-loopback address - already names one
 * store worldwide: two processes configured with {@code db.example.com:3306/mc} genuinely talk to the
 * same database. A coordinate that does NOT route is only meaningful on the machine that reads it:
 * {@code localhost:3306/mc} names a different database on every server, and any file system path
 * names a different directory on every machine. Such coordinates get a <b>machine discriminator</b>
 * appended (see {@link #localMachine()}), which is what keeps two identically-configured servers from
 * claiming one identity.
 *
 * <p>The classification is textual on purpose: only literal loopback forms are treated as
 * machine-local. Resolving a name to decide would put a DNS round-trip on a call path that runs while
 * a cache-sync binds, and would answer differently depending on the resolver's mood.
 *
 * <h3>Credentials</h3>
 * A JDBC URL or a Mongo connection string may embed a user and a password ({@code //user:pass@host},
 * {@code ?user=..&password=..}). Both forms are stripped before anything else, because the identity
 * is stamped on change events and may be logged.
 *
 * <h3>Shape of the result</h3>
 * {@code <type>:<normalized-coordinate>} for a shareable coordinate, and
 * {@code <type>:<normalized-coordinate>@<machine>} for a machine-local one. The type prefix
 * ({@code sql}, {@code mongo}, {@code localfile}, ...) makes two different backend kinds distinct by
 * construction rather than by luck of the coordinate space.
 */
public final class BackendIdentities {

    /** Separator between the coordinate and the machine discriminator. */
    private static final String MACHINE_SEPARATOR = "@";

    /** Discriminator used when the local host name cannot be determined. */
    private static final String UNKNOWN_MACHINE = "unknown-host";

    /** Host forms that name "this machine" and therefore mean a different store on every machine. */
    private static final Set<String> LOOPBACK_HOSTS = new HashSet<>(Arrays.asList(
        "", "localhost", "localhost.localdomain",
        "127.0.0.1", "0.0.0.0", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]"));

    private BackendIdentities() {
    }

    // ------------------------------------------------------------------
    //  Machine discriminator
    // ------------------------------------------------------------------

    /** Holder so the host name is resolved at most once, lazily, and never on a hot path. */
    private static final class MachineHolder {
        static final String VALUE = resolveLocalMachine();

        private static String resolveLocalMachine() {
            try {
                String host = InetAddress.getLocalHost().getHostName();
                return host == null || host.isEmpty() ? UNKNOWN_MACHINE : host.toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return UNKNOWN_MACHINE;
            }
        }
    }

    /** Holder for the per-process id, initialised only if something actually asks for it. */
    private static final class JvmHolder {
        static final String VALUE = UUID.randomUUID().toString();
    }

    /**
     * The discriminator that distinguishes this machine from another one running an identical
     * configuration - the local host name, lower-cased, or {@value #UNKNOWN_MACHINE} when it cannot
     * be determined. Resolved once per process.
     *
     * <p>Known limitation: a host name that changes between restarts (a recreated container gets a
     * fresh random one) changes the identity with it, and cross-instance invalidation for that store
     * silently stops matching. Deployments where that happens should pin the identity explicitly
     * through the backend config instead of relying on the derived one.
     */
    public static String localMachine() {
        return MachineHolder.VALUE;
    }

    /**
     * A random id, stable for the lifetime of this JVM. Used to build per-instance identities that
     * cannot accidentally match another process's.
     */
    public static String jvmId() {
        return JvmHolder.VALUE;
    }

    // ------------------------------------------------------------------
    //  Composition
    // ------------------------------------------------------------------

    /**
     * Joins a type prefix and an already normalized coordinate, appending {@code machine} only when
     * the coordinate is machine-local.
     */
    public static String compose(String type, String coordinate, boolean machineLocal, String machine) {
        String identity = type + ":" + coordinate;
        return machineLocal ? identity + MACHINE_SEPARATOR + machine : identity;
    }

    /** Whether {@code host} names the machine reading it rather than a routable peer. */
    public static boolean isMachineLocalHost(String host) {
        return host == null || LOOPBACK_HOSTS.contains(host.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    //  Per-coordinate derivations
    // ------------------------------------------------------------------

    /**
     * The identity of the store a JDBC URL points at, under the given type prefix.
     *
     * <p>The URL's query string and any embedded user info are dropped (they carry credentials and
     * tuning, not store identity); the scheme and host are lower-cased, the rest is preserved as
     * written, since a database name may be case-sensitive. A URL without a {@code //authority} - an
     * embedded H2 or SQLite file - is machine-local by definition.
     */
    public static String jdbc(String type, String jdbcUrl, String machine) {
        String url = stripQuery(jdbcUrl);
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return compose(type, url.toLowerCase(Locale.ROOT), true, machine);
        }
        String scheme    = url.substring(0, schemeEnd + 3).toLowerCase(Locale.ROOT);
        String rest      = url.substring(schemeEnd + 3);
        int pathStart    = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String tail      = pathStart < 0 ? "" : trimTrailingSlash(rest.substring(pathStart));
        String hostPart  = stripUserInfo(authority).toLowerCase(Locale.ROOT);
        return compose(type, scheme + hostPart + tail, isMachineLocalHost(hostOf(hostPart)), machine);
    }

    /**
     * The identity of the database a Mongo connection string plus database name point at. All hosts
     * of a replica-set seed list must be machine-local for the identity to be treated as such - a
     * single routable seed already names one deployment.
     */
    public static String mongo(String type, String connectionString, String database, String machine) {
        String url = stripQuery(connectionString);
        int schemeEnd = url.indexOf("://");
        String scheme = schemeEnd < 0 ? "" : url.substring(0, schemeEnd + 3).toLowerCase(Locale.ROOT);
        String rest   = schemeEnd < 0 ? url : url.substring(schemeEnd + 3);
        int pathStart = rest.indexOf('/');
        String authority = stripUserInfo(pathStart < 0 ? rest : rest.substring(0, pathStart))
                .toLowerCase(Locale.ROOT);
        boolean machineLocal = true;
        for (String seed : authority.split(",")) {
            if (!isMachineLocalHost(hostOf(seed))) {
                machineLocal = false;
                break;
            }
        }
        return compose(type, scheme + authority + "/" + (database == null ? "" : database),
                machineLocal, machine);
    }

    /**
     * Whether a JDBC URL points at a machine-local store - the same classification {@link #jdbc}
     * applies internally, exposed on its own so a caller can learn it without also composing an
     * identity. A URL without a {@code //authority} (an embedded H2 or SQLite file) is machine-local
     * by definition; otherwise the host is classified by {@link #isMachineLocalHost}.
     */
    public static boolean jdbcIsMachineLocal(String jdbcUrl) {
        String url = stripQuery(jdbcUrl);
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return true;
        }
        String rest      = url.substring(schemeEnd + 3);
        int pathStart    = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String hostPart  = stripUserInfo(authority).toLowerCase(Locale.ROOT);
        return isMachineLocalHost(hostOf(hostPart));
    }

    /**
     * Whether a Mongo connection string points at a machine-local deployment - the same
     * classification {@link #mongo} applies internally, exposed on its own. All hosts of the seed
     * list must be machine-local; a single routable seed already names a shareable deployment.
     */
    public static boolean mongoIsMachineLocal(String connectionString) {
        String url = stripQuery(connectionString);
        int schemeEnd = url.indexOf("://");
        String rest   = schemeEnd < 0 ? url : url.substring(schemeEnd + 3);
        int pathStart = rest.indexOf('/');
        String authority = stripUserInfo(pathStart < 0 ? rest : rest.substring(0, pathStart))
                .toLowerCase(Locale.ROOT);
        for (String seed : authority.split(",")) {
            if (!isMachineLocalHost(hostOf(seed))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The identity of a directory-backed store. Always machine-local: the same absolute path names a
     * different directory on every machine.
     */
    public static String directory(String type, Path baseDirectory, String machine) {
        String path;
        try {
            path = baseDirectory.toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            path = baseDirectory.toString();
        }
        return compose(type, trimTrailingSlash(path.replace('\\', '/')), true, machine);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static String stripQuery(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        int q = trimmed.indexOf('?');
        return q < 0 ? trimmed : trimmed.substring(0, q);
    }

    /** Drops a {@code user:password@} prefix from an authority, keeping only the host list. */
    private static String stripUserInfo(String authority) {
        int at = authority.lastIndexOf('@');
        return at < 0 ? authority : authority.substring(at + 1);
    }

    /** The host of a {@code host[:port]} pair, with an IPv6 literal's brackets kept intact. */
    private static String hostOf(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            return close < 0 ? hostAndPort : hostAndPort.substring(0, close + 1);
        }
        int colon = hostAndPort.indexOf(':');
        return colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
    }

    private static String trimTrailingSlash(String s) {
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
