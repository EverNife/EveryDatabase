package br.com.finalcraft.evernifecore.storage.transfer;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileConfig;
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileStorage;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryStorage;
import br.com.finalcraft.evernifecore.storage.modules.sql.PoolTuning;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-backend transfer tests: verifies that the Storage Transfer works correctly
 * when source and target are different backend types.
 *
 * <p>All 6 pairs below run without Docker (H2 embedded, LocalFile, InMemory):
 *
 * <pre>
 * InMemory   -> LocalFile  (write JSON to disk, verify files)
 * LocalFile  -> InMemory   (read from disk, load into RAM)
 * InMemory   -> H2         (write to SQL, verify via JDBC)
 * H2         -> InMemory   (read from SQL into RAM)
 * LocalFile  -> H2         (UC1 simulated: file migration to SQL)
 * H2         -> LocalFile  (UC5 simulated: backup snapshot to file)
 * </pre>
 *
 * <p>Each test is independent: fresh storages and temp dirs created per test.
 * H2 uses distinct named in-memory databases per test to avoid state leakage.
 *
 * <h3>Running</h3>
 * <pre>
 * ./gradlew :common-storage:test --tests "*StorageTransferCrossBackendTest"
 * </pre>
 */
@DisplayName("StorageTransfer - Cross-backend pairs")
@TestMethodOrder(MethodOrderer.DisplayName.class)
class StorageTransferCrossBackendTest {

    static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR = AbstractStorageTest.DESCRIPTOR;

    static final PoolTuning H2_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    static final UUID UUID_A = AbstractStorageTest.UUID_ALICE;
    static final UUID UUID_B = AbstractStorageTest.UUID_BOB;
    static final UUID UUID_C = AbstractStorageTest.UUID_CAROL;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    //  Storage factories
    // ------------------------------------------------------------------

    private InMemoryStorage inMemory() {
        InMemoryStorage s = new InMemoryStorage();
        s.init().join();
        return s;
    }

    private LocalFileStorage localFile(String subDir) {
        Path dir = tempDir.resolve(subDir);
        dir.toFile().mkdirs();
        LocalFileStorage s = new LocalFileStorage(new LocalFileConfig(dir));
        s.init().join();
        return s;
    }

    private H2SqlStorage h2(String dbName) {
        String url = "jdbc:h2:mem:" + dbName + "_xbackend"
            + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        H2SqlStorage s = new H2SqlStorage(new SqlConfig(url, "sa", "", H2_POOL));
        s.init().join();
        return s;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    TestPlayer alice() { return new TestPlayer(UUID_A, "Alice", 100); }
    TestPlayer bob()   { return new TestPlayer(UUID_B, "Bob",    50); }
    TestPlayer carol() { return new TestPlayer(UUID_C, "Carol", 200); }

    List<TestPlayer> threeEntities() {
        return Arrays.asList(alice(), bob(), carol());
    }

    void seedSource(Storage src) {
        src.repository(DESCRIPTOR).saveAll(threeEntities()).join();
    }

    TransferReport doTransfer(Storage src, Storage tgt) {
        return StorageTransfer.builder()
            .from(src).to(tgt)
            .descriptor(DESCRIPTOR)
            .failIfTargetCollectionNotEmpty(false)
            .verifyCounts(true)
            .build()
            .execute().join();
    }

    void assertAllEntitiesInTarget(Storage tgt) {
        Repository<UUID, TestPlayer> repo = tgt.repository(DESCRIPTOR);
        assertEquals(3L, repo.count().join(), "All 3 entities must be in target");
        assertEquals(alice(), repo.find(UUID_A).join().orElseThrow(AssertionError::new));
        assertEquals(bob(),   repo.find(UUID_B).join().orElseThrow(AssertionError::new));
        assertEquals(carol(), repo.find(UUID_C).join().orElseThrow(AssertionError::new));
    }

    // ------------------------------------------------------------------
    //  InMemory -> LocalFile
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[InMemory -> LocalFile] 3 entities survive codec round-trip through disk")
    void inMemory_to_localFile() {
        InMemoryStorage  src = inMemory();
        LocalFileStorage tgt = localFile("inMemory_to_localFile");

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "Transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertAllEntitiesInTarget(tgt);

        // LocalFile sanity: files actually exist on disk
        Path collectionDir = tempDir.resolve("inMemory_to_localFile")
            .resolve(DESCRIPTOR.collection());
        assertTrue(Files.isDirectory(collectionDir),
            "LocalFile must create a directory for the collection");
        long fileCount;
        try {
            fileCount = Files.list(collectionDir).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals(3L, fileCount, "LocalFile must create one file per entity");

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  LocalFile -> InMemory
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[LocalFile -> InMemory] 3 entities loaded from disk into RAM correctly")
    void localFile_to_inMemory() {
        LocalFileStorage src = localFile("localFile_to_inMemory_src");
        InMemoryStorage  tgt = inMemory();

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "Transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertAllEntitiesInTarget(tgt);

        // Source files must be intact (transfer copies, not moves)
        assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
            "Source LocalFile must remain intact after transfer");

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  InMemory -> H2
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[InMemory -> H2] 3 entities written to SQL, queryable by indexed field")
    void inMemory_to_h2() {
        InMemoryStorage src = inMemory();
        H2SqlStorage    tgt = h2("inMemory_to_h2");

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "Transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertAllEntitiesInTarget(tgt);

        // H2-specific: verify the entities are findable via indexed query
        List<TestPlayer> found = tgt.repository(DESCRIPTOR)
            .findBy("name", "Alice").join();
        assertEquals(1, found.size());
        assertEquals(alice(), found.get(0));

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  H2 -> InMemory
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[H2 -> InMemory] 3 entities read from SQL into RAM with correct values")
    void h2_to_inMemory() {
        H2SqlStorage    src = h2("h2_to_inMemory");
        InMemoryStorage tgt = inMemory();

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "Transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertAllEntitiesInTarget(tgt);

        // Source H2 must remain intact
        assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
            "Source H2 must remain intact after transfer");

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  LocalFile -> H2  (UC1 simulated: "grew out of files, migrate to SQL")
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[LocalFile -> H2] UC1: file-backed storage migrated to SQL (indexes populated)")
    void localFile_to_h2_uc1() {
        LocalFileStorage src = localFile("localFile_to_h2_src");
        H2SqlStorage     tgt = h2("localFile_to_h2");

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "UC1 transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());

        CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
        assertEquals(3L, stats.sourceCount());
        assertEquals(0L, stats.targetCountBefore(), "Target was empty before transfer");
        assertEquals(3L, stats.targetCountAfter());
        assertEquals(3L, stats.entitiesWritten());

        assertAllEntitiesInTarget(tgt);

        // Verify indexed queries work on the SQL target (indexes were populated by saveAll)
        List<TestPlayer> byScore = tgt.repository(DESCRIPTOR)
            .query(br.com.finalcraft.evernifecore.storage.query.Query.range("score", 50, 100)).join();
        assertEquals(2, byScore.size(), "Alice (100) and Bob (50) must be findable by score range");

        // Source files must still be intact
        assertEquals(3L, src.repository(DESCRIPTOR).count().join(),
            "LocalFile source must be intact - transfer copies, not moves");

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  H2 -> LocalFile  (UC5 simulated: "backup snapshot to file")
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[H2 -> LocalFile] UC5: SQL backup snapshot written to local files")
    void h2_to_localFile_uc5() {
        H2SqlStorage     src = h2("h2_to_localFile");
        LocalFileStorage tgt = localFile("h2_to_localFile_tgt");

        seedSource(src);
        TransferReport report = doTransfer(src, tgt);

        assertTrue(report.success(), "UC5 transfer must succeed: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertAllEntitiesInTarget(tgt);

        // LocalFile backup: files are on disk and readable as standalone entities
        Path backupDir = tempDir.resolve("h2_to_localFile_tgt")
            .resolve(DESCRIPTOR.collection());
        assertTrue(Files.isDirectory(backupDir), "Backup directory must exist");
        long fileCount;
        try {
            fileCount = Files.list(backupDir).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals(3L, fileCount, "One backup file per entity");

        // Open a fresh LocalFileStorage pointing at the backup and read entities back
        LocalFileStorage verify = new LocalFileStorage(
            new LocalFileConfig(tempDir.resolve("h2_to_localFile_tgt")));
        verify.init().join();
        assertAllEntitiesInTarget(verify);
        verify.close().join();

        src.close().join();
        tgt.close().join();
    }

    // ------------------------------------------------------------------
    //  Codec fidelity: all fields survive each cross-backend round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[Codec fidelity] Full TestPlayer (all fields) survives InMemory -> H2 -> LocalFile round-trip")
    void codecFidelity_fullRoundTrip_inMemory_h2_localFile() {
        // Entity with all fields populated
        TestPlayer full = new TestPlayer(
            UUID_A, "Alice With Spaces & Símbolos", Integer.MAX_VALUE,
            "world_nether", true, System.currentTimeMillis()
        );

        // Leg 1: InMemory -> H2
        InMemoryStorage mem = inMemory();
        H2SqlStorage    h2  = h2("codecFidelity_mem_h2");

        mem.repository(DESCRIPTOR).save(full).join();
        TransferReport leg1 = doTransfer(mem, h2);
        assertTrue(leg1.success(), "Leg 1 (Mem->H2) failed: " + leg1.errors());

        TestPlayer afterH2 = h2.repository(DESCRIPTOR).find(UUID_A).join()
            .orElseThrow(() -> new AssertionError("Entity missing in H2 after leg 1"));
        assertEquals(full.getUuid(),      afterH2.getUuid(),      "uuid after H2");
        assertEquals(full.getName(),      afterH2.getName(),      "name after H2");
        assertEquals(full.getScore(),     afterH2.getScore(),     "score after H2");
        assertEquals(full.getWorld(),     afterH2.getWorld(),     "world after H2");
        assertEquals(full.isActive(),     afterH2.isActive(),     "active after H2");
        assertEquals(full.getCreatedAt(), afterH2.getCreatedAt(), "createdAt after H2");

        // Leg 2: H2 -> LocalFile
        LocalFileStorage file = localFile("codecFidelity_h2_file");
        TransferReport leg2 = doTransfer(h2, file);
        assertTrue(leg2.success(), "Leg 2 (H2->File) failed: " + leg2.errors());

        TestPlayer afterFile = file.repository(DESCRIPTOR).find(UUID_A).join()
            .orElseThrow(() -> new AssertionError("Entity missing in LocalFile after leg 2"));
        assertEquals(full.getUuid(),      afterFile.getUuid(),      "uuid after LocalFile");
        assertEquals(full.getName(),      afterFile.getName(),      "name after LocalFile");
        assertEquals(full.getScore(),     afterFile.getScore(),     "score after LocalFile");
        assertEquals(full.getWorld(),     afterFile.getWorld(),     "world after LocalFile");
        assertEquals(full.isActive(),     afterFile.isActive(),     "active after LocalFile");
        assertEquals(full.getCreatedAt(), afterFile.getCreatedAt(), "createdAt after LocalFile");

        mem.close().join();
        h2.close().join();
        file.close().join();
    }

    // ------------------------------------------------------------------
    //  Large dataset: verify count across all pairs
    // ------------------------------------------------------------------

    /**
     * Arguments for the parametrized large dataset test.
     * Each row is (sourceName, targetName, source, target).
     * Storages are created lazily inside the test via suppliers to avoid
     * JUnit arg serialization issues with AutoCloseable instances.
     */
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @Nested
    @DisplayName("Large dataset (100 entities) across all pairs")
    class LargeDataset {

        @TempDir
        Path largeDir;

        private H2SqlStorage    h2Src, h2Tgt;
        private LocalFileStorage fileSrc, fileTgt;
        private InMemoryStorage  memSrc, memTgt;

        @AfterEach
        void closeAll() {
            closeQuietly(h2Src, h2Tgt, fileSrc, fileTgt, memSrc, memTgt);
        }

        private void closeQuietly(Storage... storages) {
            for (Storage s : storages) {
                if (s != null) try { s.close().join(); } catch (Exception ignored) {}
            }
        }

        private List<TestPlayer> hundred() {
            List<TestPlayer> list = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                list.add(new TestPlayer(new UUID(0, i + 1), "Player_" + i, i * 10));
            }
            return list;
        }

        private void assertHundredInTarget(Storage tgt) {
            long count = tgt.repository(DESCRIPTOR).count().join();
            assertEquals(100L, count, "All 100 entities must be in target");

            // Spot-check a few
            TestPlayer first = tgt.repository(DESCRIPTOR)
                .find(new UUID(0, 1)).join()
                .orElseThrow(() -> new AssertionError("Entity 1 missing"));
            assertEquals("Player_0", first.getName());
            assertEquals(0, first.getScore());

            TestPlayer last = tgt.repository(DESCRIPTOR)
                .find(new UUID(0, 100)).join()
                .orElseThrow(() -> new AssertionError("Entity 100 missing"));
            assertEquals("Player_99", last.getName());
            assertEquals(990, last.getScore());
        }

        @Test
        @DisplayName("[InMemory -> H2] 100 entities, batchSize=30")
        void large_inMemory_to_h2() {
            memSrc = new InMemoryStorage();
            memSrc.init().join();

            h2Tgt  = new H2SqlStorage(new SqlConfig(
                "jdbc:h2:mem:large_mem_h2;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
                "sa", "", H2_POOL));
            h2Tgt.init().join();

            memSrc.repository(DESCRIPTOR).saveAll(hundred()).join();

            TransferReport report = StorageTransfer.builder()
                .from(memSrc).to(h2Tgt)
                .descriptor(DESCRIPTOR)
                .batchSize(30)
                .verifyCounts(true)
                .build().execute().join();

            assertTrue(report.success(), report.errors().toString());
            assertEquals(100L, report.totalEntities());
            assertHundredInTarget(h2Tgt);
        }

        @Test
        @DisplayName("[H2 -> InMemory] 100 entities, batchSize=30")
        void large_h2_to_inMemory() {
            h2Src  = new H2SqlStorage(new SqlConfig(
                "jdbc:h2:mem:large_h2_mem;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
                "sa", "", H2_POOL));
            h2Src.init().join();
            memTgt = new InMemoryStorage(); memTgt.init().join();

            h2Src.repository(DESCRIPTOR).saveAll(hundred()).join();

            TransferReport report = StorageTransfer.builder()
                .from(h2Src).to(memTgt)
                .descriptor(DESCRIPTOR)
                .batchSize(30)
                .verifyCounts(true)
                .build().execute().join();

            assertTrue(report.success(), report.errors().toString());
            assertEquals(100L, report.totalEntities());
            assertHundredInTarget(memTgt);
        }

        @Test
        @DisplayName("[LocalFile -> H2] 100 entities, batchSize=30 (UC1 large)")
        void large_localFile_to_h2() {
            Path srcDir = largeDir.resolve("src");
            srcDir.toFile().mkdirs();
            fileSrc = new LocalFileStorage(new LocalFileConfig(srcDir));
            fileSrc.init().join();
            h2Tgt  = new H2SqlStorage(new SqlConfig(
                "jdbc:h2:mem:large_file_h2;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
                "sa", "", H2_POOL));
            h2Tgt.init().join();

            fileSrc.repository(DESCRIPTOR).saveAll(hundred()).join();

            TransferReport report = StorageTransfer.builder()
                .from(fileSrc).to(h2Tgt)
                .descriptor(DESCRIPTOR)
                .batchSize(30)
                .verifyCounts(true)
                .build().execute().join();

            assertTrue(report.success(), report.errors().toString());
            assertEquals(100L, report.totalEntities());
            assertHundredInTarget(h2Tgt);
        }

        @Test
        @DisplayName("[H2 -> LocalFile] 100 entities, batchSize=30 (UC5 large)")
        void large_h2_to_localFile() {
            h2Src  = new H2SqlStorage(new SqlConfig(
                "jdbc:h2:mem:large_h2_file;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
                "sa", "", H2_POOL));
            h2Src.init().join();
            Path tgtDir = largeDir.resolve("tgt");
            tgtDir.toFile().mkdirs();
            fileTgt = new LocalFileStorage(new LocalFileConfig(tgtDir));
            fileTgt.init().join();

            h2Src.repository(DESCRIPTOR).saveAll(hundred()).join();

            TransferReport report = StorageTransfer.builder()
                .from(h2Src).to(fileTgt)
                .descriptor(DESCRIPTOR)
                .batchSize(30)
                .verifyCounts(true)
                .build().execute().join();

            assertTrue(report.success(), report.errors().toString());
            assertEquals(100L, report.totalEntities());
            assertHundredInTarget(fileTgt);
        }
    }
}
