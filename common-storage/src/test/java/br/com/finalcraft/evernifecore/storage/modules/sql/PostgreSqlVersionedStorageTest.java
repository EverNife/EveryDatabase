package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optimistic-locking (versioned) tests for the PostgreSQL backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest}. PostgreSQL is
 * a real networked server that supports concurrent multi-process writes, making optimistic
 * locking genuinely useful here.
 *
 * <p>Requires a running PostgreSQL 12+ server. If none is available the entire class is
 * <em>skipped</em> automatically.
 *
 * <h3>Configuration</h3>
 * {@code POSTGRES_USER}, {@code POSTGRES_PASS}, {@code POSTGRES_HOST}, {@code POSTGRES_PORT},
 * {@code POSTGRES_URL}. Defaults: {@code root/root @ localhost:39307}.
 *
 * <pre>
 * docker compose up -d postgres
 * ./gradlew :common-storage:test --tests "*PostgreSqlVersionedStorageTest"
 * </pre>
 */
@DisplayName("PostgreSqlStorage - Optimistic Locking (versioned)")
class PostgreSqlVersionedStorageTest extends AbstractVersionedStorageTest {

    static final String POSTGRES_USER = DotEnvTestUtil.getOrDefault("POSTGRES_USER", "root");
    static final String POSTGRES_PASS = DotEnvTestUtil.getOrDefault("POSTGRES_PASS", "root");
    static final String POSTGRES_HOST = DotEnvTestUtil.getOrDefault("POSTGRES_HOST", "localhost");
    static final String POSTGRES_PORT = DotEnvTestUtil.getOrDefault("POSTGRES_PORT", "39307");

    static final String POSTGRES_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "POSTGRES_URL",
        "jdbc:postgresql://" + POSTGRES_HOST + ":" + POSTGRES_PORT
    );

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1,
        5,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)
    );

    static final boolean CLEAN_TEST_RESIDUALS = false;

    private static int runNumber = 1;
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void assumePostgresAvailable() {
        Properties props = new Properties();
        props.setProperty("user",             POSTGRES_USER);
        props.setProperty("password",         POSTGRES_PASS);
        props.setProperty("loginTimeout",     "3");
        props.setProperty("socketTimeout",    "3");
        props.setProperty("connectTimeout",   "3");

        // Connect to the default "postgres" maintenance database for the probe.
        try (Connection conn = DriverManager.getConnection(POSTGRES_SERVER_URL + "/postgres", props);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (Exception e) {
            assumeTrue(false,
                "PostgreSQL not available at " + POSTGRES_SERVER_URL + " - skipping PostgreSqlVersionedStorageTest. "
                + "Cause: " + e.getMessage());
        }

        try (Connection conn = DriverManager.getConnection(
                 POSTGRES_SERVER_URL + "/postgres", POSTGRES_USER, POSTGRES_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT datname FROM pg_database WHERE datname LIKE 'enc_%'")) {
            List<String> existing = new ArrayList<>();
            while (rs.next()) existing.add(rs.getString(1));
            runNumber = AbstractStorageTest.computeRunNumber(existing);
        } catch (SQLException ignored) {
            runNumber = 1;
        }
    }

    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS || createdDbs.isEmpty()) return;
        try (Connection conn = DriverManager.getConnection(
                 POSTGRES_SERVER_URL + "/postgres", POSTGRES_USER, POSTGRES_PASS);
             Statement stmt = conn.createStatement()) {
            for (String name : createdDbs) {
                try { stmt.execute("DROP DATABASE IF EXISTS \"" + name + "\""); }
                catch (SQLException ignored) {}
            }
        } catch (SQLException ignored) {}
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = AbstractStorageTest.buildDbName("pv", runNumber, testMethodName);

        try (Connection conn = DriverManager.getConnection(
                 POSTGRES_SERVER_URL + "/postgres", POSTGRES_USER, POSTGRES_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE for test: " + dbName, e);
        }
        createdDbs.add(dbName);

        return new PostgreSqlStorage(new SqlConfig(
            POSTGRES_SERVER_URL + "/" + dbName, POSTGRES_USER, POSTGRES_PASS, TEST_POOL, Optional.empty()));
    }
}
