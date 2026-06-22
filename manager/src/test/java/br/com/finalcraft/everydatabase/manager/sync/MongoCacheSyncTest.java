package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoStorage;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.TimeUnit;

/**
 * Cache-sync contract on MongoDB <b>Change Streams</b> (push). Two {@link MongoStorage} instances on
 * the same replica-set DB. Self-skips when Mongo is unreachable or not a replica set (change streams
 * require one). The compose Mongo is a 1-node replica set; override the URL with
 * {@code -Dmongo.changefeed.url} / {@code MONGO_CHANGEFEED_URL} to point elsewhere.
 */
@DisplayName("CacheSync contract - MongoDB (change streams)")
class MongoCacheSyncTest extends AbstractCacheSyncTest {

    private static final String MONGO_URL = resolveMongoUrl();
    private static final String DB = "everydatabase_cachesync";

    @Override
    protected void assumeAvailable() {
        Assumptions.assumeTrue(mongoReachable(),
                "MongoDB not reachable - run 'docker compose up -d mongo'. Skipping change-stream contract.");
        Assumptions.assumeTrue(isReplicaSet(),
                "MongoDB is standalone (change streams need a replica set). Point -Dmongo.changefeed.url "
                + "at a replica set to run this. Skipping.");
    }

    @Override
    protected Storage openWriter() {
        return new MongoStorage(new MongoConfig(MONGO_URL, DB));
    }

    @Override
    protected Storage openReader() {
        return new MongoStorage(new MongoConfig(MONGO_URL, DB));
    }

    private static String resolveMongoUrl() {
        String prop = System.getProperty("mongo.changefeed.url");
        if (prop != null && !prop.isEmpty()) return prop;
        String env = System.getenv("MONGO_CHANGEFEED_URL");
        if (env != null && !env.isEmpty()) return env;
        return "mongodb://localhost:39308/?directConnection=true";
    }

    private static boolean mongoReachable() {
        try (MongoClient client = MongoClients.create(probeSettings())) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isReplicaSet() {
        try (MongoClient client = MongoClients.create(probeSettings())) {
            Document hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
            return hello.getString("setName") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static MongoClientSettings probeSettings() {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO_URL))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
    }
}
