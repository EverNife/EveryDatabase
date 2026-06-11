package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageStressTest;
import br.com.finalcraft.evernifecore.testutil.ThrowawayDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

/**
 * 10k-record stress run against MongoDB. Requires the server from
 * {@code docker compose up -d mongo}; skipped automatically when unreachable.
 * Skip explicitly with {@code -PskipStress}.
 */
@DisplayName("MongoStorage - stress (requires MongoDB 4.2+)")
class MongoStorageStressTest extends AbstractStorageStressTest {

    private static final ThrowawayDatabaseSupport DBS =
        ThrowawayDatabaseSupport.mongo(MongoStorageTest.MONGO_URL, "mgs");

    @BeforeAll
    static void assumeMongoAvailable() {
        DBS.assumeAvailable("MongoStorageStressTest");
    }

    @AfterAll
    static void cleanupDatabases() {
        DBS.dropAll("MongoStorageStressTest");
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        return new MongoStorage(new MongoConfig(MongoStorageTest.MONGO_URL, DBS.newDatabase(testMethodName)));
    }
}
