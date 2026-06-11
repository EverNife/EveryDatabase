package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageStressTest;
import br.com.finalcraft.evernifecore.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * 10k-record stress run against MariaDB/MySQL. Requires the server from
 * {@code docker compose up -d mariadb}; skipped automatically when unreachable.
 * Skip explicitly with {@code -PskipStress}.
 */
@DisplayName("MariaDbStorage - stress (requires MariaDB 10.x+ or MySQL 8.x)")
class MariaDbStorageStressTest extends AbstractStorageStressTest {

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS = ThrowawayDatabaseSupport.mysql(
        MariaDbStorageTest.MARIADB_SERVER_URL,
        MariaDbStorageTest.MARIADB_USER,
        MariaDbStorageTest.MARIADB_PASS,
        "mys");

    @BeforeAll
    static void assumeMariaDbAvailable() {
        DBS.assumeAvailable("MariaDbStorageStressTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MariaDbStorageStressTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        return new SqlStorage(new SqlConfig(
            MariaDbStorageTest.MARIADB_SERVER_URL + "/" + dbName,
            MariaDbStorageTest.MARIADB_USER, MariaDbStorageTest.MARIADB_PASS, TEST_POOL));
    }
}
