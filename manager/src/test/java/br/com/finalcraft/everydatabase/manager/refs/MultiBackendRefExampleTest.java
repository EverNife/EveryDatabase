package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.*;
import br.com.finalcraft.everydatabase.manager.testdata.tworegistries.Cosmetics;
import br.com.finalcraft.everydatabase.manager.testdata.tworegistries.LobbyProfile;
import br.com.finalcraft.everydatabase.manager.testdata.tworegistries.SurvivalProfile;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoStorage;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Two examples of the {@code Ref}/{@link RefRegistry}/manager layer fanning out across heterogeneous
 * databases and key types - and, crucially, across <b>independent registries</b>.
 *
 * <h3>1. One root, six databases (a single registry)</h3>
 * A {@link Profile} (keyed by {@code UUID}) holds five references - each with a <b>different key
 * type</b> ({@code String}, {@code Long}, {@code Integer}, a composite {@code record}, a wrapper
 * {@code record}) - that resolve through managers backed by <b>different databases</b>, all wired
 * through one {@link RefRegistry}.
 *
 * <h3>2. Two subsystems, the same types, two registries</h3>
 * {@link #two_subsystems_resolve_the_same_types_through_their_own_registries()} models two
 * independent authors/plugins. Each owns its own {@link RefRegistry} and its own stores, and both
 * register a manager for the <b>same</b> types ({@code Player}, {@code Wallet}). The same player id
 * and wallet account resolve to <b>different data</b> in each registry - which a single global
 * registry (one resolver per type, last-writer-wins) could never express.
 *
 * <p>The network backends require Docker and self-skip when down; the embedded variants always run.
 */
class MultiBackendRefExampleTest {

    private static final String EXAMPLE_DB     = "everydatabase_example";
    private static final String MARIADB_SERVER = "jdbc:mysql://localhost:39306";
    private static final String PG_SERVER      = "jdbc:postgresql://localhost:39307";
    private static final String MONGO_URL      = "mongodb://localhost:39308/?directConnection=true";
    private static final String USER = "root";
    private static final String PASS = "root";

    @TempDir
    Path localFileDir;

    private final List<Storage> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Storage storage : opened) {
            try {
                storage.close().join();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        opened.clear();
    }

    // ==================================================================
    //  1. One root, six databases (a single registry)
    // ==================================================================

    @Test
    void a_root_entity_fans_out_across_six_different_databases() {
        // --- the three embedded backends are always available ---
        InMemoryStorage inMemory = open(Storages.createInMemory());
        H2SqlStorage h2 = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:" + EXAMPLE_DB + "_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        var localFile = open(Storages.createLocalFile(new LocalFileConfig(localFileDir)));

        // --- the three network backends require Docker; skip cleanly if they are down ---
        Assumptions.assumeTrue(networkUp(),
                "MariaDB/PostgreSQL/MongoDB not reachable - run 'docker compose up -d'. "
                + "Skipping the multi-backend example.");

        SqlStorage mariadb = open(Storages.createSQL(new SqlConfig(MARIADB_SERVER + "/" + EXAMPLE_DB, USER, PASS)));
        PostgreSqlStorage postgres = open(Storages.createPostgreSQL(new SqlConfig(PG_SERVER + "/" + EXAMPLE_DB, USER, PASS)));
        MongoStorage mongo = open(Storages.createMongo(new MongoConfig(MONGO_URL, EXAMPLE_DB)));
        Assumptions.assumeTrue(
                mariadb.health().join().isConnected()
                        && postgres.health().join().isConnected()
                        && mongo.health().join().isConnected(),
                "A required database is down - skipping the multi-backend example.");

        // root -> MariaDB, clan -> PostgreSQL, wallet -> MongoDB, stats -> H2,
        // home -> LocalFile, session -> InMemory
        fanOut(new RefRegistry(), mariadb, postgres, mongo, h2, localFile, inMemory);
    }

    @Test
    void the_same_fan_out_resolves_across_embedded_backends() {
        // No Docker needed: exercise the heterogeneous keys on real embedded persistence -
        // H2 (SQL string keys via toString), LocalFile (sanitized record-key filenames),
        // InMemory (equals/hashCode keys).
        Storage sql = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:" + EXAMPLE_DB + "_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        Storage file = open(Storages.createLocalFile(new LocalFileConfig(localFileDir)));
        Storage mem = open(Storages.createInMemory());

        // profiles + clans + stats -> H2; home -> LocalFile; wallet + session -> InMemory
        fanOut(new RefRegistry(), sql, sql, mem, sql, file, mem);
    }

    /** The single-registry fan-out, shared by both tests above (only the backends differ). */
    private void fanOut(RefRegistry refRegistry,
                        Storage profilesStore, Storage clansStore, Storage walletsStore,
                        Storage statsStore, Storage homesStore, Storage sessionsStore) {

        // one manager per entity type, each backed by its given store, each with its own key type,
        // all registered in the same registry.
        CachingManager<String, Clan> clans = refRegistry.manager(
                desc(refRegistry, String.class, Clan.class, "clans", Clan::getTag), clansStore, CachePolicy.always());
        CachingManager<Long, Wallet> wallets = refRegistry.manager(
                desc(refRegistry, Long.class, Wallet.class, "wallets", Wallet::getAccountNumber), walletsStore, CachePolicy.always());
        CachingManager<Integer, Stats> stats = refRegistry.manager(
                desc(refRegistry, Integer.class, Stats.class, "stats", Stats::getId), statsStore, CachePolicy.always());
        CachingManager<Home.Key, Home> homes = refRegistry.manager(
                desc(refRegistry, Home.Key.class, Home.class, "homes", Home::getKey), homesStore, CachePolicy.always());
        CachingManager<Session.Id, Session> sessions = refRegistry.manager(
                desc(refRegistry, Session.Id.class, Session.class, "sessions", Session::getId), sessionsStore, CachePolicy.always());
        CachingManager<UUID, Profile> profiles = refRegistry.manager(
                desc(refRegistry, UUID.class, Profile.class, "profiles", Profile::getUuid), profilesStore, CachePolicy.always());

        // write each inner entity into its own store, under its own key type
        clans.saveAndCache(new Clan("KNIGHTS", "The Knights")).join();          // String key
        wallets.saveAndCache(new Wallet(100_000_001L, 1_000L)).join();          // Long key
        stats.saveAndCache(new Stats(7, 42, 3)).join();                         // Integer key
        Home.Key homeKey = new Home.Key(UUID.randomUUID(), "spawn");
        homes.saveAndCache(new Home(homeKey, "world_nether")).join();           // composite record key
        Session.Id sessionId = new Session.Id("tok-abc");
        sessions.saveAndCache(new Session(sessionId, "lobby-1")).join();        // wrapper record key

        // the root references all five, each via a different key type
        UUID profileId = UUID.randomUUID();
        profiles.saveAndCache(new Profile(
            profileId,
            refRegistry.ref("KNIGHTS", Clan.class),
            refRegistry.ref(100_000_001L, Wallet.class),
            refRegistry.ref(7, Stats.class),
            refRegistry.ref(homeKey, Home.class),
            refRegistry.ref(sessionId, Session.class)
        )).join();

        // reload the root (force a decode), then resolve every reference across stores/key types
        profiles.evict(profileId);
        Profile loaded = profiles.resolve(profileId).join().orElseThrow(AssertionError::new);

        assertEquals("The Knights", loaded.getClan().resolve().join().orElseThrow(AssertionError::new).getName());     // String -> Clan
        assertEquals(1_000L, loaded.getWallet().resolve().join().orElseThrow(AssertionError::new).getBalance());       // Long -> Wallet
        assertEquals(42, loaded.getStats().resolve().join().orElseThrow(AssertionError::new).getKills());              // Integer -> Stats
        assertEquals("world_nether", loaded.getHome().resolve().join().orElseThrow(AssertionError::new).getWorld());   // record -> Home
        assertEquals("lobby-1", loaded.getSession().resolve().join().orElseThrow(AssertionError::new).getServer());    // record -> Session

        System.out.println("loaded: " + loaded);
    }

    // ==================================================================
    //  2. Two subsystems, the same types, two registries
    // ==================================================================

    @Test
    void two_subsystems_resolve_the_same_types_through_their_own_registries() {
        // Two independent registries - think two plugins by two authors. Each has its OWN registry and
        // its OWN stores. Both register a manager for Player and for Wallet (the same types!), which is
        // impossible to do safely with one global registry.
        H2SqlStorage survivalDb = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:survival_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        H2SqlStorage lobbyWalletDb = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:lobby_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        Storage survivalWalletFiles = open(Storages.createLocalFile(new LocalFileConfig(localFileDir)));
        InMemoryStorage lobbyMem = open(Storages.createInMemory());

        // --- Survival registry: champion + clan in H2, wallet on disk ---
        RefRegistry survival = new RefRegistry();
        CachingManager<UUID, Player> survivalChampions = survival.manager(
                desc(survival, UUID.class, Player.class, "champions", Player::getUuid), survivalDb, CachePolicy.always());
        CachingManager<String, Clan> survivalClans = survival.manager(
                desc(survival, String.class, Clan.class, "clans", Clan::getTag), survivalDb, CachePolicy.always());
        CachingManager<Long, Wallet> survivalWallets = survival.manager(
                desc(survival, Long.class, Wallet.class, "wallets", Wallet::getAccountNumber), survivalWalletFiles, CachePolicy.always());
        CachingManager<UUID, SurvivalProfile> survivalProfiles = survival.manager(
                desc(survival, UUID.class, SurvivalProfile.class, "profiles", SurvivalProfile::getUuid), survivalDb, CachePolicy.always());

        // --- Lobby registry: champion + cosmetics in memory, wallet in a DIFFERENT H2 ---
        RefRegistry lobby = new RefRegistry();
        CachingManager<UUID, Player> lobbyChampions = lobby.manager(
                desc(lobby, UUID.class, Player.class, "champions", Player::getUuid), lobbyMem, CachePolicy.always());
        CachingManager<Integer, Cosmetics> lobbyCosmetics = lobby.manager(
                desc(lobby, Integer.class, Cosmetics.class, "cosmetics", Cosmetics::getId), lobbyMem, CachePolicy.always());
        CachingManager<Long, Wallet> lobbyWallets = lobby.manager(
                desc(lobby, Long.class, Wallet.class, "wallets", Wallet::getAccountNumber), lobbyWalletDb, CachePolicy.always());
        CachingManager<UUID, LobbyProfile> lobbyProfiles = lobby.manager(
                desc(lobby, UUID.class, LobbyProfile.class, "profiles", LobbyProfile::getUuid), lobbyWalletDb, CachePolicy.always());

        // The SAME ids in both registries - deliberately, to prove they never collide.
        UUID championId = UUID.randomUUID();
        Long walletAccount = 555_000L;
        UUID profileId = UUID.randomUUID();

        survivalChampions.saveAndCache(new Player(championId, "Aragorn the Survivor", 80)).join();
        survivalClans.saveAndCache(new Clan("RANGERS", "Rangers of the North")).join();
        survivalWallets.saveAndCache(new Wallet(walletAccount, 9_999L)).join();
        survivalProfiles.saveAndCache(new SurvivalProfile(profileId,
                survival.ref(championId, Player.class),
                survival.ref("RANGERS", Clan.class),
                survival.ref(walletAccount, Wallet.class))).join();

        lobbyChampions.saveAndCache(new Player(championId, "Aragorn in the Lobby", 1)).join();
        lobbyCosmetics.saveAndCache(new Cosmetics(7, "golden_cape")).join();
        lobbyWallets.saveAndCache(new Wallet(walletAccount, 25L)).join();
        lobbyProfiles.saveAndCache(new LobbyProfile(profileId,
                lobby.ref(championId, Player.class),
                lobby.ref(7, Cosmetics.class),
                lobby.ref(walletAccount, Wallet.class))).join();

        // Force a decode of both roots so their refs are recovered (and re-bound to their registry).
        survivalProfiles.evict(profileId);
        lobbyProfiles.evict(profileId);
        SurvivalProfile sp = survivalProfiles.resolve(profileId).join().orElseThrow(AssertionError::new);
        LobbyProfile lp = lobbyProfiles.resolve(profileId).join().orElseThrow(AssertionError::new);

        // THE POINT: the same champion id resolves to different entities, because each profile's ref is
        // bound to its own registry (and thus its own store).
        Player survivalChampion = sp.getChampion().resolve().join().orElseThrow(AssertionError::new);
        Player lobbyChampion = lp.getChampion().resolve().join().orElseThrow(AssertionError::new);
        assertEquals("Aragorn the Survivor", survivalChampion.getName());
        assertEquals(80, survivalChampion.getLevel());
        assertEquals("Aragorn in the Lobby", lobbyChampion.getName());
        assertEquals(1, lobbyChampion.getLevel());
        assertNotSame(survivalChampion, lobbyChampion, "the two registries hand out independent instances");

        // The same wallet account resolves to different balances, across different backends.
        assertEquals(9_999L, sp.getWallet().resolve().join().orElseThrow(AssertionError::new).getBalance()); // -> LocalFile
        assertEquals(25L, lp.getWallet().resolve().join().orElseThrow(AssertionError::new).getBalance());    // -> H2

        // Registry-specific references resolve only within their own registry.
        assertEquals("Rangers of the North", sp.getClan().resolve().join().orElseThrow(AssertionError::new).getName());
        assertEquals("golden_cape", lp.getCosmetics().resolve().join().orElseThrow(AssertionError::new).getActiveSkin());

        // And the registries really are isolated: neither knows the other's root type.
        org.junit.jupiter.api.Assertions.assertTrue(survival.isRegistered(SurvivalProfile.class));
        org.junit.jupiter.api.Assertions.assertFalse(survival.isRegistered(LobbyProfile.class));
        org.junit.jupiter.api.Assertions.assertFalse(lobby.isRegistered(SurvivalProfile.class));

        System.out.println("survival: " + sp + "\nlobby: " + lp);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private <S extends Storage> S open(S storage) {
        storage.init().join();
        opened.add(storage);
        return storage;
    }

    private static <K, T> EntityDescriptor<K, T> desc(RefRegistry refRegistry, Class<K> keyType, Class<T> type,
                                                      String collection, Function<T, K> key) {
        return EntityDescriptor.builder(keyType, type)
                .collection(collection)
                .keyExtractor(key)
                .codec(refRegistry.codec(type))     // ref-aware codec bound to this registry
                .build();
    }

    /**
     * Creates the example databases on MariaDB and PostgreSQL (idempotent) and pings MongoDB,
     * returning whether all three network servers are reachable. Uses short timeouts so a down
     * server fails fast rather than hanging the suite.
     */
    private static boolean networkUp() {
        DriverManager.setLoginTimeout(3);
        try {
            try (Connection c = DriverManager.getConnection(MARIADB_SERVER + "/", USER, PASS);
                 Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + EXAMPLE_DB + "`");
            }
            try (Connection c = DriverManager.getConnection(PG_SERVER + "/postgres", USER, PASS);
                 Statement st = c.createStatement()) {
                boolean exists;
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + EXAMPLE_DB + "'")) {
                    exists = rs.next();
                }
                if (!exists) {
                    st.execute("CREATE DATABASE \"" + EXAMPLE_DB + "\"");
                }
            }
        } catch (SQLException e) {
            return false;
        }
        return mongoReachable();
    }

    private static boolean mongoReachable() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO_URL))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
