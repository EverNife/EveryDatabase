package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.AbstractTransactionalStorageTest;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import br.com.finalcraft.evernifecore.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Concrete test suite for the MariaDB storage backend ({@link SqlStorage}
 * with the default MySQL-compatible dialect: backtick identifiers, {@code MEDIUMTEXT},
 * {@code ON DUPLICATE KEY UPDATE}).
 *
 * <p>MariaDB is wire-compatible with MySQL - the same {@code mysql-connector-j} JDBC
 * driver and the same SQL dialect work for both, so this suite implicitly also covers
 * MySQL servers.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} plus the shared
 * transactional/lifecycle/schema-evolution suite from {@link AbstractTransactionalStorageTest}.
 *
 * <h3>Running these tests</h3>
 * <p>A MariaDB 10.x+ (or MySQL 8.x) server must be reachable; otherwise the class is
 * skipped automatically (see {@link ThrowawayDatabaseSupport}).
 *
 * <h3>Configuration - via env var or {@code -Dkey=value} (see {@link DotEnvTestUtil})</h3>
 * <pre>
 * MARIADB_USER  - default: root
 * MARIADB_PASS  - default: root
 * MARIADB_HOST  - default: localhost
 * MARIADB_PORT  - default: 39306
 * MARIADB_URL   - overrides host+port construction (e.g. jdbc:mysql://host:port).
 *                 Must NOT include a database name; the suite creates one per test.
 * TEST_KEEP_DATABASES - "true" keeps the throwaway databases for inspection
 * </pre>
 *
 * <pre>
 * docker compose up -d mariadb
 * ./gradlew :common-storage:test --tests "*MariaDbStorageTest"
 * </pre>
 */
@DisplayName("MariaDbStorage (requires MariaDB 10.x+ or MySQL 8.x)")
class MariaDbStorageTest extends AbstractTransactionalStorageTest {

    static final String MARIADB_USER = DotEnvTestUtil.getOrDefault("MARIADB_USER", "root");
    static final String MARIADB_PASS = DotEnvTestUtil.getOrDefault("MARIADB_PASS", "root");
    static final String MARIADB_HOST = DotEnvTestUtil.getOrDefault("MARIADB_HOST", "localhost");
    static final String MARIADB_PORT = DotEnvTestUtil.getOrDefault("MARIADB_PORT", "39306");

    /** Server URL WITHOUT a database component (probe + CREATE/DROP DATABASE target). */
    static final String MARIADB_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "MARIADB_URL",
        "jdbc:mysql://" + MARIADB_HOST + ":" + MARIADB_PORT
    );

    /** Small pool drains quickly on close() so the database can be dropped right after. */
    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS =
        ThrowawayDatabaseSupport.mysql(MARIADB_SERVER_URL, MARIADB_USER, MARIADB_PASS, "my");

    /** JDBC URL of the database created for the current test - used by schema-drift tests. */
    private String currentTestDbUrl;

    @BeforeAll
    static void assumeMariaDbAvailable() {
        DBS.assumeAvailable("MariaDbStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MariaDbStorageTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        currentTestDbUrl = MARIADB_SERVER_URL + "/" + dbName;
        return new SqlStorage(new SqlConfig(currentTestDbUrl, MARIADB_USER, MARIADB_PASS, TEST_POOL));
    }

    @Override
    protected Storage openExtraStorageOnSameDatabase() {
        return new SqlStorage(new SqlConfig(currentTestDbUrl, MARIADB_USER, MARIADB_PASS, TEST_POOL));
    }
}
