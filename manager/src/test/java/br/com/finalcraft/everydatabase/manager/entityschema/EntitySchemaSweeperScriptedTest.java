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
import br.com.finalcraft.everydatabase.versioned.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep's write-failure paths, driven by scripting a real store's writes: a whole batch losing
 * at once, one row losing to a concurrent writer, the lease heartbeat failing mid-run, and the
 * completion marker write failing after the data is already swept.
 *
 * <p>None of these are reachable on an unscripted embedded backend - none of them enforce the
 * optimistic lock, and none can be told to drop one specific write - yet each one is a path the
 * sweep must survive without throwing at its caller.
 */
@DisplayName("EntitySchemaSweeper - scripted write failures")
class EntitySchemaSweeperScriptedTest {

    private ScriptedStorage storage;
    private String collection;

    @BeforeEach
    void setUp() {
        EntitySchemaMigrations.clear();
        collection = "talismans_" + UUID.randomUUID().toString().replace("-", "");
        storage = new ScriptedStorage(Storages.createInMemory());
        storage.init().join();
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
        EntitySchemaMigrations.clear();
    }

    // ==================================================================
    //  Fixture
    // ==================================================================

    private CachingManager<UUID, Talisman> talismans() {
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

    /** Plants rows the way an older build left them, before any script is armed. */
    private static List<Talisman> plantStaleTalismans(CachingManager<UUID, Talisman> manager, int count) {
        List<Talisman> planted = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            planted.add(new Talisman(UUID.randomUUID(), i, null, EntitySchema.INITIAL_SCHEMA_VERSION));
        }
        manager.repository().saveAll(planted).join();
        return planted;
    }

    private EntitySchemaSweepMarker marker() {
        Repository<String, EntitySchemaSweepMarker> markers =
                storage.repository(EntitySchemaSweeper.MARKER_DESCRIPTOR);
        return markers.find(collection).join().orElse(null);
    }

    // ==================================================================
    //  The data write
    // ==================================================================

    @Test
    @DisplayName("a batch that loses as a unit is retried row by row, so one bad row never loses the rest")
    void batchFailureFallsBackToOneRowAtATime() {
        CachingManager<UUID, Talisman> manager = talismans();
        List<Talisman> planted = plantStaleTalismans(manager, 4);
        registerSweepChain();

        UUID conflicted = planted.get(1).getUuid();
        UUID broken = planted.get(2).getUuid();
        storage.script(collection, entities -> {
            if (entities.size() > 1) {
                // a versioned SQL backend rolls the whole batch back when any row loses
                return new RuntimeException("the batch lost as a unit");
            }
            Talisman only = (Talisman) entities.iterator().next();
            if (only.getUuid().equals(conflicted)) {
                return new OptimisticLockException(Talisman.class, conflicted, 0L, 1L);
            }
            if (only.getUuid().equals(broken)) {
                return new RuntimeException("the store dropped this row");
            }
            return null;
        });

        SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

        assertEquals(4, report.scanned());
        assertEquals(2, report.rewritten(), "the two healthy rows must survive the batch failure");
        assertEquals(1, report.conflicted(), "a live write won that row - a lazy read is its backstop");
        assertEquals(1, report.failed());
        assertFalse(report.markerAdvanced(), "a failed row leaves the collection unswept");
        assertEquals("incomplete", report.note());
    }

    @Test
    @DisplayName("a row lost only to a concurrent write is a conflict, and the sweep still completes")
    void conflictsAloneDoNotBlockCompletion() {
        CachingManager<UUID, Talisman> manager = talismans();
        List<Talisman> planted = plantStaleTalismans(manager, 3);
        registerSweepChain();

        UUID conflicted = planted.get(0).getUuid();
        storage.script(collection, entities -> {
            if (entities.size() > 1) {
                return new RuntimeException("the batch lost as a unit");
            }
            Talisman only = (Talisman) entities.iterator().next();
            return only.getUuid().equals(conflicted)
                    ? new OptimisticLockException(Talisman.class, conflicted, 0L, 1L)
                    : null;
        });

        SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

        assertEquals(2, report.rewritten());
        assertEquals(1, report.conflicted());
        assertEquals(0, report.failed());
        assertTrue(report.markerAdvanced(),
                "the winning write already persisted the migrated shape - nothing is left behind");
    }

    // ==================================================================
    //  The marker writes
    // ==================================================================

    @Test
    @DisplayName("losing the claim leaves the collection to whoever won it")
    void claimFailureSkipsTheSweep() {
        CachingManager<UUID, Talisman> manager = talismans();
        plantStaleTalismans(manager, 3);
        registerSweepChain();

        AtomicInteger markerWrites = new AtomicInteger();
        storage.script(EntitySchemaSweeper.MARKER_COLLECTION,
                entities -> markerWrites.incrementAndGet() == 1
                        ? new OptimisticLockException(EntitySchemaSweepMarker.class, "x", 0L, 1L)
                        : null);

        SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

        assertEquals("lease not claimed", report.note());
        assertFalse(report.markerAdvanced());
        assertEquals(0, report.scanned(), "no scan may start without the lease");
    }

    @Test
    @DisplayName("losing the lease mid-run stops the sweep instead of trampling the winner")
    void heartbeatFailureAbandonsTheSweep() {
        CachingManager<UUID, Talisman> manager = talismans();
        plantStaleTalismans(manager, 6);
        registerSweepChain();

        // the claim lands, the first per-batch heartbeat does not
        AtomicInteger markerWrites = new AtomicInteger();
        storage.script(EntitySchemaSweeper.MARKER_COLLECTION,
                entities -> markerWrites.incrementAndGet() == 2
                        ? new OptimisticLockException(EntitySchemaSweepMarker.class, "x", 1L, 2L)
                        : null);

        SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.builder().batchSize(2).build());

        assertEquals("lease lost", report.note());
        assertFalse(report.markerAdvanced());
        assertEquals(1, marker().getCompletedVersion(), "an abandoned sweep never marks the collection done");
    }

    @Test
    @DisplayName("a completion write that fails is reported, never thrown - the data is swept either way")
    void completionWriteFailureIsReportedNotThrown() {
        CachingManager<UUID, Talisman> manager = talismans();
        List<Talisman> planted = plantStaleTalismans(manager, 3);
        registerSweepChain();

        // the completion write is the one that hands the in-progress slot back
        storage.script(EntitySchemaSweeper.MARKER_COLLECTION, entities -> {
            EntitySchemaSweepMarker marker = (EntitySchemaSweepMarker) entities.iterator().next();
            return marker.getInProgressVersion() == 0
                    ? new OptimisticLockException(EntitySchemaSweepMarker.class, "x", 1L, 2L)
                    : null;
        });

        SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

        assertEquals("completion write failed", report.note());
        assertFalse(report.markerAdvanced(), "only the O(1) skip hint was lost");
        assertEquals(3, report.rewritten());
        assertEquals(0, report.failed());
        assertEquals(2, report.targetVersion());

        // the rows really are swept: the next boot re-scans and finds nothing to do
        EntitySchemaMigrations.clear(Talisman.class);
        for (Talisman original : planted) {
            Talisman stored = manager.repository().find(original.getUuid()).join().orElseThrow();
            assertEquals(2, stored.getSchemaVersion());
            assertEquals("neutral", stored.getElement());
        }
    }

    @Test
    @DisplayName("a failed completion write leaves the marker re-scannable rather than falsely complete")
    void aFailedCompletionLeavesTheMarkerBehind() {
        CachingManager<UUID, Talisman> manager = talismans();
        plantStaleTalismans(manager, 2);
        registerSweepChain();
        storage.script(EntitySchemaSweeper.MARKER_COLLECTION, entities -> {
            EntitySchemaSweepMarker marker = (EntitySchemaSweepMarker) entities.iterator().next();
            return marker.getInProgressVersion() == 0
                    ? new OptimisticLockException(EntitySchemaSweepMarker.class, "x", 1L, 2L)
                    : null;
        });
        EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

        EntitySchemaSweepMarker persisted = marker();

        assertNotNull(persisted, "the claim itself did land");
        assertEquals(1, persisted.getCompletedVersion(),
                "a marker claiming completion the write never made would skip a sweep that never finished");
    }
}
