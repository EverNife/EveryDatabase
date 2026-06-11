package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import br.com.finalcraft.evernifecore.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Optimistic-locking (versioned) tests for the MariaDB/MySQL backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest}.
 * Requires a running MariaDB 10.x+ (or MySQL 8.x) server; skipped automatically otherwise.
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
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS =
        ThrowawayDatabaseSupport.mysql(MARIADB_SERVER_URL, MARIADB_USER, MARIADB_PASS, "mv");

    @BeforeAll
    static void assumeMariaDbAvailable() {
        DBS.assumeAvailable("MariaDbVersionedStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MariaDbVersionedStorageTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        return new SqlStorage(new SqlConfig(
            MARIADB_SERVER_URL + "/" + dbName, MARIADB_USER, MARIADB_PASS, TEST_POOL));
    }
}
