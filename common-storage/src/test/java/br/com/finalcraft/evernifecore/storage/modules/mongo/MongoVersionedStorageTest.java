package br.com.finalcraft.evernifecore.storage.modules.mongo;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.AbstractVersionedStorageTest;
import br.com.finalcraft.evernifecore.testutil.DotEnvTestUtil;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optimistic-locking (versioned) tests for the MongoDB backend.
 *
 * <p>Inherits all contract tests from {@link AbstractVersionedStorageTest}. MongoDB is one
 * of only two backends where versioning is supported (the other being MariaDB/MySQL); it
 * may be accessed by multiple JVM processes and therefore benefits from optimistic locking.
 *
 * <p>Requires a running MongoDB 4.2+ server. If none is available the entire class is
 * <em>skipped</em> automatically.
 *
 * <h3>Configuration</h3>
 * Same env vars as {@link MongoStorageTest}:
 * {@code MONGO_USER}, {@code MONGO_PASS}, {@code MONGO_HOST}, {@code MONGO_PORT}.
 * Defaults: {@code root/root @ localhost:27017}.
 *
 * <pre>
 * docker compose up -d mongo
 * ./gradlew :common-storage:test --tests "*MongoVersionedStorageTest"
 * </pre>
 */
@DisplayName("MongoStorage - Optimistic Locking (versioned)")
class MongoVersionedStorageTest extends AbstractVersionedStorageTest {

    static final String MONGO_USER = DotEnvTestUtil.getOrDefault("MONGO_USER", "root");
    static final String MONGO_PASS = DotEnvTestUtil.getOrDefault("MONGO_PASS", "root");
    static final String MONGO_HOST = DotEnvTestUtil.getOrDefault("MONGO_HOST", "localhost");
    static final String MONGO_PORT = DotEnvTestUtil.getOrDefault("MONGO_PORT", "27017");
    static final String MONGO_URL  = "mongodb://" + MONGO_USER + ":" + MONGO_PASS
                                   + "@" + MONGO_HOST + ":" + MONGO_PORT;

    static final boolean CLEAN_TEST_RESIDUALS = false;

    private static int runNumber = 1;
    private static final Set<String> createdDbs = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void assumeMongoAvailable() {
        MongoClientSettings probe = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(MONGO_URL))
            .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
            .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
            .build();

        try (MongoClient client = MongoClients.create(probe)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            assumeTrue(false,
                "MongoDB not available at " + MONGO_URL + " - skipping MongoVersionedStorageTest. "
                + "Cause: " + e.getMessage());
        }

        try (MongoClient client = MongoClients.create(probe)) {
            List<String> existing = new ArrayList<>();
            for (String name : client.listDatabaseNames()) {
                if (name.startsWith("enc_")) existing.add(name);
            }
            runNumber = AbstractStorageTest.computeRunNumber(existing);
        } catch (Exception ignored) {
            runNumber = 1;
        }
    }

    @AfterAll
    static void cleanupDatabases() {
        if (!CLEAN_TEST_RESIDUALS || createdDbs.isEmpty()) return;
        try (MongoClient client = MongoClients.create(MONGO_URL)) {
            createdDbs.forEach(name -> client.getDatabase(name).drop());
        } catch (Exception ignored) {}
    }

    @Override
    protected Storage createStorage(String testMethodName) {
        String dbName = AbstractStorageTest.buildDbName("mgv", runNumber, testMethodName);
        createdDbs.add(dbName);
        return new MongoStorage(new MongoConfig(MONGO_URL, dbName));
    }
}
