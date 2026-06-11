package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import br.com.finalcraft.everydatabase.testutil.DotEnvTestUtil;
import br.com.finalcraft.everydatabase.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Optimistic-locking (versioned) tests for the PostgreSQL backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest}.
 * Requires a running PostgreSQL 13+ server; skipped automatically otherwise.
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
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    private static final ThrowawayDatabaseSupport DBS =
        ThrowawayDatabaseSupport.postgres(POSTGRES_SERVER_URL, POSTGRES_USER, POSTGRES_PASS, "pv");

    @BeforeAll
    static void assumePostgresAvailable() {
        DBS.assumeAvailable("PostgreSqlVersionedStorageTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("PostgreSqlVersionedStorageTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = DBS.newDatabase(testMethodName);
        return new PostgreSqlStorage(new SqlConfig(
            POSTGRES_SERVER_URL + "/" + dbName, POSTGRES_USER, POSTGRES_PASS, TEST_POOL));
    }
}
