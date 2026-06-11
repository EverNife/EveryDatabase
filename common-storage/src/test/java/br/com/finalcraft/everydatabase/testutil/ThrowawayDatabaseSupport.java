package br.com.finalcraft.everydatabase.testutil;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared throwaway-database infrastructure for integration test suites that need a real
 * server (MariaDB/MySQL, PostgreSQL, MongoDB).
 *
 * <p>Encapsulates the boilerplate that used to be copied between every concrete suite:
 * <ol>
 *   <li><b>Availability probe</b> ({@link #assumeAvailable(String)}) - pings the server with
 *       short timeouts and JUnit-{@code assumeTrue}s the whole class away when it is down,
 *       so the build never fails because a Docker container isn't running.</li>
 *   <li><b>Run numbering</b> - scans existing {@code enc_NNN_*} database names and picks
 *       {@code max + 1}, so every database of one test run shares a numeric prefix.</li>
 *   <li><b>Per-test databases</b> ({@link #newDatabase(String)}) - creates (SQL) or just
 *       names (Mongo - databases are created lazily) a database called
 *       {@code enc_NNN_<tag>_<methodName>} and registers it for cleanup.</li>
 *   <li><b>Cleanup</b> ({@link #dropAll(String)}) - drops everything created during the run,
 *       unless {@code TEST_KEEP_DATABASES=true} (env var or {@code -D} system property,
 *       see {@link DotEnvTestUtil}) asks to keep them for inspection.</li>
 * </ol>
 *
 * <p>Typical usage in a concrete suite:
 * <pre>{@code
 * static final ThrowawayDatabaseSupport DBS =
 *     ThrowawayDatabaseSupport.mysql(MARIADB_SERVER_URL, MARIADB_USER, MARIADB_PASS, "my");
 *
 * @BeforeAll static void probe()   { DBS.assumeAvailable("MariaDbStorageTest"); }
 * @AfterAll  static void cleanup() { DBS.dropAll("MariaDbStorageTest"); }
 *
 * @Override protected Storage createStorage(String testMethodName) {
 *     String dbName = DBS.newDatabase(testMethodName);
 *     return new SqlStorage(new SqlConfig(MARIADB_SERVER_URL + "/" + dbName, ...));
 * }
 * }</pre>
 */
public final class ThrowawayDatabaseSupport {

    /** Set {@code TEST_KEEP_DATABASES=true} to keep all test databases after the run. */
    public static boolean keepDatabases() {
        return Boolean.parseBoolean(DotEnvTestUtil.getOrDefault("TEST_KEEP_DATABASES", "false"));
    }

    private enum Flavor { MYSQL, POSTGRES, MONGO }

    private final Flavor flavor;
    /** Server URL without a database component (JDBC) or full mongodb:// URL. */
    private final String serverUrl;
    private final String user;
    private final String pass;
    /** Short backend tag used in database names: "my", "pg", "mg", "mv", ... */
    private final String tag;

    private volatile int runNumber = 1;
    private final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    private ThrowawayDatabaseSupport(Flavor flavor, String serverUrl, String user, String pass, String tag) {
        this.flavor    = flavor;
        this.serverUrl = serverUrl;
        this.user      = user;
        this.pass      = pass;
        this.tag       = tag;
    }

    /** MariaDB/MySQL support. {@code serverUrl} must NOT contain a database name. */
    public static ThrowawayDatabaseSupport mysql(String serverUrl, String user, String pass, String tag) {
        return new ThrowawayDatabaseSupport(Flavor.MYSQL, serverUrl, user, pass, tag);
    }

    /** PostgreSQL support. {@code serverUrl} must NOT contain a database name. */
    public static ThrowawayDatabaseSupport postgres(String serverUrl, String user, String pass, String tag) {
        return new ThrowawayDatabaseSupport(Flavor.POSTGRES, serverUrl, user, pass, tag);
    }

    /** MongoDB support. {@code mongoUrl} is the full {@code mongodb://user:pass@host:port} URL. */
    public static ThrowawayDatabaseSupport mongo(String mongoUrl, String tag) {
        return new ThrowawayDatabaseSupport(Flavor.MONGO, mongoUrl, null, null, tag);
    }

    // ------------------------------------------------------------------
    //  Naming helpers (formerly on AbstractStorageTest)
    // ------------------------------------------------------------------

    /**
     * Scans {@code existingDbNames} for names matching {@code enc_NNN_*} and returns
     * the next run number ({@code max + 1}, minimum {@code 1}).
     */
    public static int computeRunNumber(Collection<String> existingDbNames) {
        int max = 0;
        Pattern p = Pattern.compile("^enc_(\\d+)_.*");
        for (String name : existingDbNames) {
            Matcher m = p.matcher(name);
            if (m.matches()) {
                try { max = Math.max(max, Integer.parseInt(m.group(1))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    /**
     * Builds a database name of the form {@code enc_NNN_backend_methodName} - at most 61
     * characters, within PostgreSQL's 63-char and MySQL/MariaDB's 64-char limits.
     */
    public static String buildDbName(String backend, int runNumber, String methodName) {
        String safe = methodName.length() > 50 ? methodName.substring(0, 50) : methodName;
        return String.format("enc_%03d_%s_%s", runNumber, backend, safe);
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Probes the server with short timeouts; {@code assumeTrue(false)}s the calling suite away
     * when the server is unreachable. On success, computes the run number for this execution.
     * Call once from {@code @BeforeAll}.
     */
    public void assumeAvailable(String suiteName) {
        try {
            List<String> existing = listEncDatabases();
            runNumber = computeRunNumber(existing);
        } catch (Exception e) {
            assumeTrue(false,
                backendLabel() + " not available at " + serverUrl + " - skipping all " + suiteName + ". "
                + "Start the server (e.g. 'docker compose up -d') to run these tests. "
                + "Cause: " + e.getMessage());
        }
        System.out.println("[" + suiteName + "] Run number: " + runNumber
            + "  (databases will be prefixed enc_" + String.format("%03d", runNumber) + "_" + tag + "_*)");
    }

    /**
     * Creates a fresh database named {@code enc_NNN_<tag>_<methodName>} and registers it for
     * {@link #dropAll(String)}. For Mongo the database is only named (Mongo creates lazily).
     *
     * @return the database name
     */
    public String newDatabase(String testMethodName) {
        String dbName = buildDbName(tag, runNumber, testMethodName);
        switch (flavor) {
            case MYSQL:
                executeAdmin("CREATE DATABASE `" + dbName + "`", dbName);
                break;
            case POSTGRES:
                executeAdmin("CREATE DATABASE \"" + dbName + "\"", dbName);
                break;
            case MONGO:
                // Mongo creates databases lazily on first write - nothing to do here.
                break;
        }
        createdDbs.add(dbName);
        return dbName;
    }

    /**
     * Drops every database created during this run. Honors {@code TEST_KEEP_DATABASES=true}
     * (keeps everything and prints the names instead). Best-effort: cleanup failures never
     * fail the build. Call once from {@code @AfterAll}.
     */
    public void dropAll(String suiteName) {
        if (keepDatabases()) {
            System.out.println("[" + suiteName + "] TEST_KEEP_DATABASES=true - keeping databases for inspection:");
            createdDbs.forEach(name -> System.out.println("  -> " + name));
            return;
        }
        if (createdDbs.isEmpty()) return;

        if (flavor == Flavor.MONGO) {
            try (MongoClient client = MongoClients.create(serverUrl)) {
                createdDbs.forEach(name -> client.getDatabase(name).drop());
            } catch (Exception ignored) {
                // best-effort: cleanup failure must not break the build
            }
            return;
        }

        try (Connection conn = DriverManager.getConnection(adminUrl(), user, pass);
             Statement stmt = conn.createStatement()) {
            for (String name : createdDbs) {
                try {
                    if (flavor == Flavor.MYSQL) {
                        stmt.execute("DROP DATABASE IF EXISTS `" + name + "`");
                    } else {
                        // WITH (FORCE) terminates lingering connections (PostgreSQL 13+).
                        stmt.execute("DROP DATABASE IF EXISTS \"" + name + "\" WITH (FORCE)");
                    }
                } catch (SQLException ignored) {
                    // best-effort: cleanup failure must not break the build
                }
            }
        } catch (SQLException ignored) {
            // best-effort: cleanup failure must not break the build
        }
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    private String backendLabel() {
        switch (flavor) {
            case MYSQL:    return "MariaDB/MySQL";
            case POSTGRES: return "PostgreSQL";
            default:       return "MongoDB";
        }
    }

    /** JDBC URL targeted by admin commands (CREATE/DROP DATABASE, probe). */
    private String adminUrl() {
        return flavor == Flavor.POSTGRES
            ? serverUrl + "/postgres"   // PG requires a database in every URL - use the built-in one
            : serverUrl + "/";
    }

    /** Probes the server and lists existing {@code enc_*} database names. */
    private List<String> listEncDatabases() throws Exception {
        List<String> existing = new ArrayList<>();
        if (flavor == Flavor.MONGO) {
            MongoClientSettings probe = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(serverUrl))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
            try (MongoClient client = MongoClients.create(probe)) {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
                for (String name : client.listDatabaseNames()) {
                    if (name.startsWith("enc_")) existing.add(name);
                }
            }
            return existing;
        }

        Properties props = new Properties();
        props.setProperty("user",     user);
        props.setProperty("password", pass);
        if (flavor == Flavor.MYSQL) {
            props.setProperty("connectTimeout", "3000"); // mysql driver expects millis
            props.setProperty("socketTimeout",  "3000");
        } else {
            props.setProperty("connectTimeout", "3");    // pg driver expects SECONDS
            props.setProperty("socketTimeout",  "3");
        }

        String listSql = flavor == Flavor.MYSQL
            ? "SHOW DATABASES"
            : "SELECT datname FROM pg_database WHERE datname LIKE 'enc_%'";

        try (Connection conn = DriverManager.getConnection(adminUrl(), props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(listSql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && name.startsWith("enc_")) existing.add(name);
            }
        }
        return existing;
    }

    private void executeAdmin(String sql, String dbName) {
        try (Connection conn = DriverManager.getConnection(adminUrl(), user, pass);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE for test: " + dbName, e);
        }
    }
}
