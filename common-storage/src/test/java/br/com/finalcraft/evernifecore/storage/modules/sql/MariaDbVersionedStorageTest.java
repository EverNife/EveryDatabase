package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optimistic-locking (versioned) tests for the MariaDB/MySQL backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest}. MariaDB is
 * one of only two backends where versioning is supported (the other being MongoDB); it runs
 * multi-process writes against a real networked server and therefore benefits from
 * optimistic locking.
 *
 * <p>Requires a running MariaDB 10.x+ (or MySQL 8.x) server. If none is available the
 * entire class is <em>skipped</em> automatically.
 *
 * <h3>Configuration</h3>
 * Same env vars as {@link MariaDbStorageTest}:
 * {@code MARIADB_USER}, {@code MARIADB_PASS}, {@code MARIADB_HOST}, {@code MARIADB_PORT},
 * {@code MARIADB_URL}. Defaults: {@code root/root @ localhost:39306}.
 *
 * <pre>
 * docker compose up -d mariadb
 * ./gradlew :common-storage:test --tests "*MariaDbVersionedStorageTest"
 * </pre>
 */
@DisplayName("MariaDbStorage - Optimistic Locking (versioned)")
class MariaDbVersionedStorageTest extends AbstractVersionedStorageTest {

    static final String MARIADB_USER = DotEnvTestUtil.getOrDefault("MARIADB_USER", "root");
    static final String MARIADB_PASS = DotEnvTestUtil.getOrDefault("MARIADB_PASS", "root");
    static final String MARIADB_HOST = DotEnvTestUtil.getOrDefault("MARIADB_HOST", "localhost");
    static final String MARIADB_PORT = DotEnvTestUtil.getOrDefault("MARIADB_PORT", "39306");

    static final String MARIADB_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "MARIADB_URL",
        "jdbc:mysql://" + MARIADB_HOST + ":" + MARIADB_PORT
    );

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1,
        5,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)
    );

    static final boolean CLEAN_TEST_RESIDUALS = true;

    private static int runNumber = 1;
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void assumeMariaDbAvailable() {
        Properties props = new Properties();
        props.setProperty("user",           MARIADB_USER);
        props.setProperty("password",       MARIADB_PASS);
        props.setProperty("connectTimeout", "3000");
        props.setProperty("socketTimeout",  "3000");

        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", props);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (Exception e) {
            assumeTrue(false,
                "MariaDB not available at " + MARIADB_SERVER_URL + " - skipping MariaDbVersionedStorageTest. "
                + "Cause: " + e.getMessage());
        }

        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            List<String> existing = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (name.startsWith("enc_")) existing.add(name);
            }
            runNumber = AbstractStorageTest.computeRunNumber(existing);
        } catch (SQLException ignored) {
            runNumber = 1;
        }
    }

    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS || createdDbs.isEmpty()) return;
        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement()) {
            for (String name : createdDbs) {
                try { stmt.execute("DROP DATABASE IF EXISTS `" + name + "`"); }
                catch (SQLException ignored) {}
            }
        } catch (SQLException ignored) {}
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = AbstractStorageTest.buildDbName("mv", runNumber, testMethodName);

        try (Connection conn = DriverManager.getConnection(MARIADB_SERVER_URL + "/", MARIADB_USER, MARIADB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + dbName + "`");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to CREATE DATABASE for test: " + dbName, e);
        }
        createdDbs.add(dbName);

        return new SqlStorage(new SqlConfig(
            MARIADB_SERVER_URL + "/" + dbName, MARIADB_USER, MARIADB_PASS, TEST_POOL, Optional.empty()));
    }
}
