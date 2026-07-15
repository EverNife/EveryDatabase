package br.com.finalcraft.everydatabase.manager.entityschema;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.CachingManager;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.entityschema.EntitySchemaSweeper.SweepReport;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Talisman;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-runner guarantee on a backend that actually enforces it. Everywhere else the sweep's
 * lease is advisory - those stores are physically single-instance, so no second sweeper exists to
 * race - but MariaDB is shared, and the marker's optimistic lock is what keeps two instances from
 * sweeping the same collection at once.
 *
 * <p>Two storage instances on the same database stand in for two application instances. Self-skips
 * when the server is down ({@code docker compose up -d mariadb}).
 */
@DisplayName("EntitySchemaSweeper - single-runner lease on MariaDB")
class MariaDbEntitySchemaSweepTest {

    private static final String SERVER = "jdbc:mysql://localhost:39306";
    private static final String DB = "everydatabase_entityschema";

    private final List<Storage> opened = new ArrayList<>();
    private String collection;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(ready(),
                "MariaDB not reachable - run 'docker compose up -d mariadb'. Skipping the sweep lease contract.");
        EntitySchemaMigrations.clear();
        collection = "talismans_" + UUID.randomUUID().toString().replace("-", "");
    }

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
        EntitySchemaMigrations.clear();
    }

    private static boolean ready() {
        DriverManager.setLoginTimeout(3);
        try (Connection connection = DriverManager.getConnection(SERVER + "/", "root", "root");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + DB + "`");
            return true;
        } catch (SQLException unreachable) {
            return false;
        }
    }

    // ==================================================================
    //  Fixture
    // ==================================================================

    /** One application instance: its own storage, its own registry, its own manager. */
    private CachingManager<UUID, Talisman> openInstance() {
        Storage storage = Storages.createSQL(new SqlConfig(SERVER + "/" + DB, "root", "root"));
        storage.init().join();
        opened.add(storage);
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Talisman> descriptor = EntityDescriptor.builder(UUID.class, Talisman.class)
                .collection(collection)
                .keyExtractor(Talisman::getUuid)
                .codec(EntitySchemaMigratingCodec.wrap(Talisman.class,
                        new JacksonJsonCodec<>(Talisman.class), "uuid"))
                .build();
        return registry.manager(descriptor, storage, CachePolicy.always());
    }

    private static void registerSweepChain() {
        EntitySchemaMigrations.register(Talisman.class, 1, EntitySchemaMigrationMode.EAGER,
                node -> node.put("element", "neutral"));
    }

    private static void plantStaleTalismans(CachingManager<UUID, Talisman> manager, int count) {
        List<Talisman> planted = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            planted.add(new Talisman(UUID.randomUUID(), i, null, EntitySchema.INITIAL_SCHEMA_VERSION));
        }
        manager.repository().saveAll(planted).join();
    }

    private static Repository<String, EntitySchemaSweepMarker> markers(CachingManager<?, ?> manager) {
        return manager.storage().repository(EntitySchemaSweeper.MARKER_DESCRIPTOR);
    }

    // ==================================================================
    //  Contention
    // ==================================================================

    @Test
    @DisplayName("a second instance stays out while the first holds a live lease, and skips once it is done")
    void aLiveLeaseKeepsTheSecondInstanceOut() {
        CachingManager<UUID, Talisman> first = openInstance();
        CachingManager<UUID, Talisman> second = openInstance();
        plantStaleTalismans(first, 6);
        registerSweepChain();

        // the second instance boots while the first is mid-scan: abortCheck runs at a batch boundary,
        // which is after the lease was claimed and before the sweep is done
        AtomicReference<SweepReport> whileSweeping = new AtomicReference<>();
        SweepReport firstReport = EntitySchemaSweeper.sweep(first, SweepOptions.builder()
                .runnerId("instance-one")
                .batchSize(2)
                .abortCheck(() -> {
                    if (whileSweeping.get() == null) {
                        whileSweeping.set(EntitySchemaSweeper.sweep(second,
                                SweepOptions.builder().runnerId("instance-two").build()));
                    }
                    return false;
                })
                .build());

        assertTrue(firstReport.markerAdvanced());
        assertEquals(6, firstReport.rewritten());

        SweepReport contended = whileSweeping.get();
        assertNotNull(contended, "the scan loop must have run for this to prove anything");
        assertTrue(contended.note().startsWith("contended"), contended.note());
        assertEquals(0, contended.scanned(), "a contended instance must not touch the rows");

        // once the first instance is done, the second one's next boot is the O(1) skip
        SweepReport afterCompletion = EntitySchemaSweeper.sweep(second,
                SweepOptions.builder().runnerId("instance-two").build());
        assertEquals("already at v2", afterCompletion.note());
    }

    @Test
    @DisplayName("a runner walks back into its own live lease rather than reading it as contention")
    void aRunnerResumesItsOwnLease() {
        CachingManager<UUID, Talisman> instance = openInstance();
        CachingManager<UUID, Talisman> other = openInstance();
        plantStaleTalismans(instance, 3);
        registerSweepChain();
        // a sweep this runner started and never finished - the lease is still ticking
        EntitySchemaSweepMarker abandoned = new EntitySchemaSweepMarker();
        abandoned.setCollection(collection);
        abandoned.setTypeName(Talisman.class.getName());
        abandoned.setInProgressVersion(2);
        abandoned.setRunnerId("instance-one");
        abandoned.setLeaseExpiresAtEpochMs(System.currentTimeMillis() + 60_000L);
        markers(instance).save(abandoned).join();

        SweepReport contended = EntitySchemaSweeper.sweep(other,
                SweepOptions.builder().runnerId("instance-two").build());
        assertTrue(contended.note().startsWith("contended"), contended.note());

        SweepReport resumed = EntitySchemaSweeper.sweep(instance,
                SweepOptions.builder().runnerId("instance-one").build());

        assertEquals(3, resumed.rewritten(), "its own lease must not lock a runner out of its own sweep");
        assertTrue(resumed.markerAdvanced());
    }

    // ==================================================================
    //  The marker's lock is real here
    // ==================================================================

    @Test
    @DisplayName("the marker's optimistic lock is enforced, so two instances cannot both claim it")
    void theMarkerLockIsEnforced() {
        CachingManager<UUID, Talisman> first = openInstance();
        CachingManager<UUID, Talisman> second = openInstance();
        assertTrue(first.storage().enforcesOptimisticLock(),
                "the whole single-runner guarantee rests on this backend enforcing the lock");

        EntitySchemaSweepMarker claim = new EntitySchemaSweepMarker();
        claim.setCollection(collection);
        claim.setTypeName(Talisman.class.getName());
        claim.setInProgressVersion(2);
        claim.setRunnerId("instance-one");
        markers(first).save(claim).join();

        // both instances read the same version of the marker and both try to move it: exactly the
        // race the lease has to settle
        EntitySchemaSweepMarker asSeenByFirst = markers(first).find(collection).join().orElseThrow();
        EntitySchemaSweepMarker asSeenBySecond = markers(second).find(collection).join().orElseThrow();

        asSeenByFirst.setRunnerId("instance-one");
        markers(first).save(asSeenByFirst).join();

        asSeenBySecond.setRunnerId("instance-two");
        Throwable loser = assertThrows(Throwable.class, () -> markers(second).save(asSeenBySecond).join());

        assertTrue(isOptimisticLock(loser), "the slower claim must lose, not silently overwrite: " + loser);
        assertEquals("instance-one", markers(first).find(collection).join().orElseThrow().getRunnerId());
    }

    @Test
    @DisplayName("a sweep on a shared backend leaves the rows rewritten for every instance to read")
    void sweptRowsAreVisibleToTheOtherInstance() {
        CachingManager<UUID, Talisman> first = openInstance();
        CachingManager<UUID, Talisman> second = openInstance();
        plantStaleTalismans(first, 4);
        registerSweepChain();

        EntitySchemaSweeper.sweep(first, SweepOptions.builder().runnerId("instance-one").build());

        // read through the other instance with the chain dropped, so what comes back is what the
        // sweep actually wrote rather than a fresh lazy migration
        EntitySchemaMigrations.clear(Talisman.class);
        List<Talisman> stored = second.repository().all().join().toList();
        assertEquals(4, stored.size());
        for (Talisman talisman : stored) {
            assertEquals(2, talisman.getSchemaVersion());
            assertEquals("neutral", talisman.getElement());
        }
    }

    private static boolean isOptimisticLock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }
}
