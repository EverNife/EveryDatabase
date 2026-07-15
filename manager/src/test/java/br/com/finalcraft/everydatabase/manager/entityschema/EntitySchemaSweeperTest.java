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
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Rune;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Sigil;
import br.com.finalcraft.everydatabase.manager.entityschema.testdata.Talisman;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eager sweep against real backends, on every store that needs no server. The sweep is a bulk
 * READ job - it pulls each row through the migrating codec and writes back whatever the codec
 * dirtied - so these exercise the whole stack at once: descriptor, codec, repository scan, marker,
 * cache.
 *
 * <p>Every case runs on <b>all three</b> embedded backends ({@link #onEachBackend}): what a sweep
 * promises must not depend on the store underneath, and a failure names the backend it came from.
 */
@DisplayName("EntitySchemaSweeper - eager sweep over a real backend")
class EntitySchemaSweeperTest {

    /** The embedded stores a sweep must behave identically on. */
    private enum Backend {
        IN_MEMORY {
            @Override
            Storage open(Path directory) {
                return Storages.createInMemory();
            }
        },
        H2 {
            @Override
            Storage open(Path directory) {
                return Storages.createH2(new SqlConfig("jdbc:h2:mem:sweep_" + rand() + ";DB_CLOSE_DELAY=-1", "", ""));
            }
        },
        LOCAL_FILE {
            @Override
            Storage open(Path directory) {
                return Storages.createLocalFile(new LocalFileConfig(directory.resolve(rand())));
            }
        };

        abstract Storage open(Path directory);
    }

    @TempDir
    Path directory;

    private final List<Storage> opened = new ArrayList<>();
    private String collection;

    @BeforeEach
    @AfterEach
    void resetTheGlobalRegistry() {
        EntitySchemaMigrations.clear();   // the chain registry is static and process-wide
    }

    @AfterEach
    void closeEveryStorage() {
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
    //  Fixture
    // ==================================================================

    private static String rand() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Runs {@code body} once per embedded backend, each against its own freshly opened store, an
     * empty chain registry and an unused collection name. A failure is re-thrown naming the backend
     * it happened on, so one message pins down which store broke the contract.
     */
    private void onEachBackend(Consumer<Storage> body) {
        for (Backend backend : Backend.values()) {
            EntitySchemaMigrations.clear();
            collection = "talismans_" + rand();
            Storage storage = backend.open(directory);
            storage.init().join();
            opened.add(storage);
            try {
                body.accept(storage);
            } catch (AssertionError failure) {
                throw new AssertionError("[" + backend + "] " + failure.getMessage(), failure);
            }
        }
    }

    private CachingManager<UUID, Talisman> talismans(Storage storage) {
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<UUID, Talisman> descriptor = EntityDescriptor.builder(UUID.class, Talisman.class)
                .collection(collection)
                .keyExtractor(Talisman::getUuid)
                .codec(EntitySchemaMigratingCodec.wrap(Talisman.class,
                        new JacksonJsonCodec<>(Talisman.class), "uuid"))
                .build();
        return registry.manager(descriptor, storage, CachePolicy.always());
    }

    /**
     * The chain the sweep runs against: v2 backfills a field added later (eager - old rows must not
     * have to wait for a read), v3 rebalances a value (lazy). The eager target is therefore v2 while
     * a decode always reaches v3 - the documented cascade.
     */
    private static void registerSweepChain() {
        EntitySchemaMigrations.register(Talisman.class, 1, EntitySchemaMigrationMode.EAGER,
                node -> node.put("element", "neutral"));
        EntitySchemaMigrations.register(Talisman.class, 2, EntitySchemaMigrationMode.LAZY,
                node -> node.put("might", node.path("might").asInt(0) * 2));
    }

    /** Writes rows the way an older build left them: stamped at v1, with no {@code element}. */
    private static List<Talisman> plantStaleTalismans(CachingManager<UUID, Talisman> manager, int count) {
        List<Talisman> planted = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            planted.add(new Talisman(UUID.randomUUID(), i, null, EntitySchema.INITIAL_SCHEMA_VERSION));
        }
        manager.repository().saveAll(planted).join();
        return planted;
    }

    /**
     * Reads a row as it actually sits on disk, by dropping the chain first so the codec delegates
     * straight to the inner one - the only way to tell "the sweep rewrote the row" apart from "a
     * lazy read just migrated it again in memory".
     */
    private static <K, V> V storedShapeOf(CachingManager<K, V> manager, K key) {
        EntitySchemaMigrations.clear(manager.type());
        return manager.repository().find(key).join().orElseThrow(
                () -> new AssertionError("the sweep lost the row for " + key));
    }

    private static EntitySchemaSweepMarker markerOf(Storage storage, String collection) {
        Repository<String, EntitySchemaSweepMarker> markers =
                storage.repository(EntitySchemaSweeper.MARKER_DESCRIPTOR);
        return markers.find(collection).join().orElse(null);
    }

    // ==================================================================
    //  Happy path
    // ==================================================================

    @Test
    @DisplayName("every stale row is rewritten in its migrated shape and the marker advances")
    void sweepRewritesEveryStaleRow() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            List<Talisman> planted = plantStaleTalismans(manager, 5);
            registerSweepChain();

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(5, report.scanned());
            assertEquals(5, report.rewritten());
            assertEquals(0, report.failed());
            assertEquals(0, report.conflicted());
            assertEquals(0, report.skippedDirty());
            assertEquals(0, report.skippedAhead());
            assertTrue(report.markerAdvanced());
            assertEquals("complete", report.note());
            assertEquals(2, report.targetVersion(), "the target is what the last EAGER step upgrades to");
            assertSame(Talisman.class, report.type());

            EntitySchemaSweepMarker marker = markerOf(storage, collection);
            assertEquals(2, marker.getCompletedVersion());
            assertEquals(0, marker.getInProgressVersion(), "a completed sweep holds no slot");
            assertEquals(5, marker.getScanned());
            assertEquals(5, marker.getRewritten());

            for (Talisman original : planted) {
                Talisman stored = storedShapeOf(manager, original.getUuid());
                assertEquals(3, stored.getSchemaVersion(), "a row the sweep touches runs its lazy steps too");
                assertEquals("neutral", stored.getElement());
                assertEquals(original.getMight() * 2, stored.getMight());
            }
        });
    }

    @Test
    @DisplayName("a second sweep of a completed collection costs one marker read and never scans")
    void completedSweepSkipsTheScan() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 3);
            registerSweepChain();
            EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            // abortCheck is polled at the top of every batch, so a poll count of zero is positive
            // proof the scan loop was never entered - the O(1) boot skip the marker exists for
            AtomicInteger batchBoundaries = new AtomicInteger();
            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.builder()
                    .abortCheck(() -> {
                        batchBoundaries.incrementAndGet();
                        return false;
                    })
                    .build());

            assertEquals("already at v2", report.note());
            assertFalse(report.markerAdvanced());
            assertEquals(0, report.scanned());
            assertEquals(0, batchBoundaries.get(), "a completed marker must short-circuit before any scan");
        });
    }

    @Test
    @DisplayName("a chain with no eager step is not the sweep's business")
    void noEagerStepIsANoOp() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 2);
            EntitySchemaMigrations.register(Talisman.class, 1, node -> node.put("element", "neutral"));

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals("no eager step", report.note());
            assertFalse(report.markerAdvanced());
            assertNull(markerOf(storage, collection), "a collection never swept gets no marker row");
        });
    }

    @Test
    @DisplayName("a row written by a newer schema is counted as ahead and left alone")
    void aheadRowsAreLeftAlone() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            Talisman ahead = new Talisman(UUID.randomUUID(), 7, "fire", 9);
            manager.repository().save(ahead).join();
            registerSweepChain();

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(1, report.scanned());
            assertEquals(1, report.skippedAhead());
            assertEquals(0, report.rewritten());
            assertTrue(report.markerAdvanced());

            Talisman stored = storedShapeOf(manager, ahead.getUuid());
            assertEquals(9, stored.getSchemaVersion(), "re-persisting it here would erase the newer fields");
            assertEquals(7, stored.getMight());
        });
    }

    // ==================================================================
    //  The @DirtyFlag flavor
    // ==================================================================

    @Test
    @DisplayName("a @DirtyFlag entity under a String key is swept just like an IDirtyable one")
    void annotationFlavorIsSweptToo() {
        onEachBackend(storage -> {
            CachingManager<String, Sigil> manager = sigils(storage);
            manager.repository().saveAll(Arrays.asList(
                    new Sigil("ward", "old", 1, 1),
                    new Sigil("bind", "old", 2, 1),
                    new Sigil("seal", "old", 3, 1))).join();
            EntitySchemaMigrations.register(Sigil.class, 1, EntitySchemaMigrationMode.EAGER,
                    node -> node.put("glyph", "engraved"));

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(3, report.scanned());
            assertEquals(3, report.rewritten(), "a dirty flavor instanceof cannot see must still be rewritten");
            assertTrue(report.markerAdvanced());

            Sigil stored = storedShapeOf(manager, "ward");
            assertEquals(2, stored.getSchemaVersion());
            assertEquals("engraved", stored.getGlyph());
        });
    }

    @Test
    @DisplayName("sweeping a type with no dirty tracking is refused before it can lie about being done")
    void typeWithoutDirtyTrackingIsRefused() {
        onEachBackend(storage -> {
            String runes = "runes_" + rand();
            RefRegistry registry = new RefRegistry();
            EntityDescriptor<String, Rune> descriptor = EntityDescriptor.builder(String.class, Rune.class)
                    .collection(runes)
                    .keyExtractor(Rune::getId)
                    .codec(EntitySchemaMigratingCodec.wrap(Rune.class, new JacksonJsonCodec<>(Rune.class), "id"))
                    .build();
            CachingManager<String, Rune> manager = registry.manager(descriptor, storage, CachePolicy.always());
            manager.repository().save(new Rune("r1", "old", 1)).join();
            EntitySchemaMigrations.register(Rune.class, 1, EntitySchemaMigrationMode.EAGER,
                    node -> node.put("inscription", "restored"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> EntitySchemaSweeper.sweep(manager, SweepOptions.defaults()));

            assertTrue(error.getMessage().contains("no dirty tracking"), error.getMessage());
            assertNull(markerOf(storage, runes),
                    "the refusal must land before the lease claim, or the marker would lie");
        });
    }

    // ==================================================================
    //  Failure and abort
    // ==================================================================

    @Test
    @DisplayName("one unreadable row keeps the marker back, so the next boot retries the collection")
    void anUnreadableRowBlocksCompletion() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 4);
            // a version below the initial one names no shape the chain can start from, so this row
            // is unreadable until someone repairs it
            manager.repository().save(new Talisman(UUID.randomUUID(), 1, null, 0)).join();
            registerSweepChain();

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(5, report.scanned());
            assertEquals(4, report.rewritten());
            assertEquals(1, report.failed());
            assertFalse(report.markerAdvanced());
            assertEquals("incomplete", report.note());
            assertEquals(1, markerOf(storage, collection).getCompletedVersion(),
                    "a collection holding a row the sweep could not read is not swept");
        });
    }

    @Test
    @DisplayName("an abort hands the lease straight back, so a quick restart resumes immediately")
    void abortReleasesTheLease() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 4);
            registerSweepChain();

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.builder()
                    .batchSize(2)
                    .abortCheck(() -> true)
                    .build());

            assertEquals("aborted", report.note());
            assertFalse(report.markerAdvanced());

            EntitySchemaSweepMarker marker = markerOf(storage, collection);
            assertEquals(0, marker.getLeaseExpiresAtEpochMs(),
                    "a lease left alive would lock the next boot out for nothing");
            assertEquals(2, marker.getInProgressVersion(), "what was attempted stays on record");
            assertEquals(1, marker.getCompletedVersion());
        });
    }

    @Test
    @DisplayName("an aborted sweep resumes on the next run and completes")
    void abortedSweepResumes() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 3);
            registerSweepChain();
            EntitySchemaSweeper.sweep(manager, SweepOptions.builder().abortCheck(() -> true).build());

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(3, report.rewritten());
            assertTrue(report.markerAdvanced());
        });
    }

    // ==================================================================
    //  Cache interaction
    // ==================================================================

    @Test
    @DisplayName("a clean cached cell of a rewritten row goes stale and reloads on the next read")
    void cleanCachedCellIsInvalidatedByTheSweep() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            UUID key = plantStaleTalismans(manager, 1).get(0).getUuid();

            // cached before the chain existed: nothing to upcast, so the cell is clean
            Talisman cached = manager.resolve(key).join().orElseThrow();
            assertFalse(cached.isDirty());
            assertTrue(manager.isCached(key));

            registerSweepChain();
            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());
            assertEquals(1, report.rewritten());

            assertFalse(manager.isCached(key),
                    "the sweep rewrote the row behind the manager's back - a clean cell must not be served");

            Talisman reloaded = manager.resolve(key).join().orElseThrow();
            assertEquals(3, reloaded.getSchemaVersion(), "the next read picks up what the sweep wrote");
            assertEquals("neutral", reloaded.getElement());
        });
    }

    @Test
    @DisplayName("a resident dirty copy is skipped - its own flush carries the migrated shape")
    void residentDirtyCellIsSkipped() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            UUID key = plantStaleTalismans(manager, 1).get(0).getUuid();
            registerSweepChain();

            // reading it upcasts in memory, which marks it dirty: the resident copy IS the migration
            Talisman resident = manager.resolve(key).join().orElseThrow();
            assertTrue(resident.isDirty());
            assertEquals(3, resident.getSchemaVersion());

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults());

            assertEquals(1, report.scanned());
            assertEquals(1, report.skippedDirty());
            assertEquals(0, report.rewritten());
            assertTrue(report.markerAdvanced(), "the resident copy will persist the shape - the marker is a hint");

            manager.flushDirty().join();
            Talisman stored = storedShapeOf(manager, key);
            assertEquals(3, stored.getSchemaVersion());
            assertEquals("neutral", stored.getElement());
        });
    }

    // ==================================================================
    //  The active-sweep registry
    // ==================================================================

    @Test
    @DisplayName("isSweeping answers true only while the scan runs, and only for the swept storage")
    void isSweepingTracksTheRunningScan() {
        onEachBackend(storage -> {
            Storage elsewhere = Storages.createInMemory();
            CachingManager<UUID, Talisman> manager = talismans(storage);
            plantStaleTalismans(manager, 4);
            registerSweepChain();

            List<Boolean> duringScan = new ArrayList<>();
            List<Boolean> elsewhereDuringScan = new ArrayList<>();
            assertFalse(EntitySchemaSweeper.isSweeping(storage, collection));

            EntitySchemaSweeper.sweep(manager, SweepOptions.builder()
                    .batchSize(2)
                    .abortCheck(() -> {
                        duringScan.add(EntitySchemaSweeper.isSweeping(storage, collection));
                        elsewhereDuringScan.add(EntitySchemaSweeper.isSweeping(elsewhere, collection));
                        return false;
                    })
                    .build());

            assertFalse(duringScan.isEmpty(), "the scan loop must have run for this to prove anything");
            assertFalse(duringScan.contains(false), "isSweeping must hold for the whole scan");
            assertFalse(elsewhereDuringScan.contains(true),
                    "a same-named collection on another storage is not the one being swept");
            assertFalse(EntitySchemaSweeper.isSweeping(storage, collection), "and it is released at the end");
        });
    }

    @Test
    @DisplayName("the explicit-parser overload sweeps with a caller-supplied key parser")
    void explicitKeyParserOverload() {
        onEachBackend(storage -> {
            CachingManager<UUID, Talisman> manager = talismans(storage);
            UUID key = plantStaleTalismans(manager, 2).get(0).getUuid();
            registerSweepChain();

            SweepReport report = EntitySchemaSweeper.sweep(manager, SweepOptions.defaults(), UUID::fromString);

            assertEquals(2, report.rewritten());
            assertEquals(3, storedShapeOf(manager, key).getSchemaVersion());
        });
    }

    private CachingManager<String, Sigil> sigils(Storage storage) {
        RefRegistry registry = new RefRegistry();
        EntityDescriptor<String, Sigil> descriptor = EntityDescriptor.builder(String.class, Sigil.class)
                .collection("sigils_" + rand())
                .keyExtractor(Sigil::getName)
                .codec(EntitySchemaMigratingCodec.wrap(Sigil.class, new JacksonJsonCodec<>(Sigil.class), "name"))
                .build();
        return registry.manager(descriptor, storage, CachePolicy.always());
    }
}
