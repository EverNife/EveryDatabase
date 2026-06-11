package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.AbstractTransactionalStorageTest;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.everydatabase.testutil.DotEnvTestUtil;
import br.com.finalcraft.everydatabase.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Concrete test suite for the PostgreSQL storage backend ({@link PostgreSqlStorage}:
 * double-quote identifiers, {@code TEXT}, {@code INSERT ... ON CONFLICT DO UPDATE}).
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} plus the shared
 * transactional/lifecycle/schema-evolution suite from {@link AbstractTransactionalStorageTest}.
 *
 * <h3>Running these tests</h3>
 * <p>A PostgreSQL 13+ server must be reachable; otherwise the class is skipped
 * automatically (see {@link ThrowawayDatabaseSupport}).
 *
 * <h3>Configuration - via env var or {@code -Dkey=value} (see {@link DotEnvTestUtil})</h3>
 * <pre>
 * POSTGRES_USER  - default: root
 * POSTGRES_PASS  - default: root
 * POSTGRES_HOST  - default: localhost
 * POSTGRES_PORT  - default: 39307
 * POSTGRES_URL   - overrides host+port construction (e.g. jdbc:postgresql://host:port).
 *                  Must NOT include a database name; the suite creates one per test.
 * TEST_KEEP_DATABASES - "true" keeps the throwaway databases for inspection
 * </pre>
 *
 * <pre>
 * docker compose up -d postgres
 * ./gradlew :common-storage:test --tests "*PostgreSqlStorageTest"
 * </pre>
 */
@DisplayName("PostgreSqlStorage (requires PostgreSQL 13+)")
class PostgreSqlStorageTest extends AbstractTransactionalStorageTest {

    static final String PG_USER = DotEnvTestUtil.getOrDefault("POSTGRES_USER", "root");
    static final String PG_PASS = DotEnvTestUtil.getOrDefault("POSTGRES_PASS", "root");
    static final String PG_HOST = DotEnvTestUtil.getOrDefault("POSTGRES_HOST", "localhost");
    static final String PG_PORT = DotEnvTestUtil.getOrDefault("POSTGRES_PORT", "39307");

    /** Server URL WITHOUT a database name (admin ops target the built-in 'postgres' DB). */
    static final String PG_SERVER_URL = DotEnvTestUtil.getOrDefault(
        "POSTGRES_URL",
        "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT
    );

    /** Small pool drains quickly on close() so the database can be dropped right after. */
    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS =
        ThrowawayDatabaseSupport.postgres(PG_SERVER_URL, PG_USER, PG_PASS, "pg");

    /** JDBC URL of the database created for the current test - used by schema-drift tests. */
    private String currentTestDbUrl;

    @BeforeAll
    static void assumePostgreSqlAvailable() {
        DBS.assumeAvailable("PostgreSqlStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("PostgreSqlStorageTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        currentTestDbUrl = PG_SERVER_URL + "/" + dbName;
        return new PostgreSqlStorage(new SqlConfig(currentTestDbUrl, PG_USER, PG_PASS, TEST_POOL));
    }

    @Override
    protected Storage openExtraStorageOnSameDatabase() {
        return new PostgreSqlStorage(new SqlConfig(currentTestDbUrl, PG_USER, PG_PASS, TEST_POOL));
    }
}
