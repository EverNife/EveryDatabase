package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageStressTest;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.everydatabase.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * 10k-record stress run against PostgreSQL. Requires the server from
 * {@code docker compose up -d postgres}; skipped automatically when unreachable.
 * Skip explicitly with {@code -PskipStress}.
 */
@DisplayName("PostgreSqlStorage - stress (requires PostgreSQL 13+)")
class PostgreSqlStorageStressTest extends AbstractStorageStressTest {

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS = ThrowawayDatabaseSupport.postgres(
        PostgreSqlStorageTest.PG_SERVER_URL,
        PostgreSqlStorageTest.PG_USER,
        PostgreSqlStorageTest.PG_PASS,
        "pgs");

    @BeforeAll
    static void assumePostgreSqlAvailable() {
        DBS.assumeAvailable("PostgreSqlStorageStressTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("PostgreSqlStorageStressTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        return new PostgreSqlStorage(new SqlConfig(
            PostgreSqlStorageTest.PG_SERVER_URL + "/" + dbName,
            PostgreSqlStorageTest.PG_USER, PostgreSqlStorageTest.PG_PASS, TEST_POOL));
    }
}
