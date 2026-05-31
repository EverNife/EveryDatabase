package br.com.finalcraft.evernifecore.storage.transfer;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryStorage;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2/F3 - Basic transfer scenarios using InMemory -> InMemory (no external dependencies).
 *
 * <p>Covers:
 * <ul>
 *   <li>Single descriptor transfer with N entities</li>
 *   <li>Source is unmodified after transfer</li>
 *   <li>Multi-descriptor transfer</li>
 *   <li>Partial transfer (not all source collections registered)</li>
 *   <li>Different source/target descriptors (collection rename)</li>
 *   <li>Batch size variants: batchSize=1, exact N, N+1</li>
 *   <li>TransferReport fields: success, totalEntities, durationMs, collections map</li>
 * </ul>
 */
@DisplayName("StorageTransfer - Basic execution")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageTransferBasicTest {

    // ------------------------------------------------------------------
    //  Descriptors
    // ------------------------------------------------------------------

    static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR = AbstractStorageTest.DESCRIPTOR;

    static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR_B =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("economy")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    static final EntityDescriptor<UUID, TestPlayer> ALT_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("alt_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    // ------------------------------------------------------------------
    //  Fixed UUIDs
    // ------------------------------------------------------------------

    static final UUID UUID_A = AbstractStorageTest.UUID_ALICE;
    static final UUID UUID_B = AbstractStorageTest.UUID_BOB;
    static final UUID UUID_C = AbstractStorageTest.UUID_CAROL;

    // ------------------------------------------------------------------
    //  Per-test setup
    // ------------------------------------------------------------------

    InMemoryStorage source;
    InMemoryStorage target;

    @BeforeEach
    void setUp() {
        source = new InMemoryStorage();
        source.init().join();
        target = new InMemoryStorage();
        target.init().join();
    }

    @AfterEach
    void tearDown() {
        source.close().join();
        target.close().join();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    TestPlayer alice() { return new TestPlayer(UUID_A, "Alice", 100); }
    TestPlayer bob()   { return new TestPlayer(UUID_B, "Bob",    50); }
    TestPlayer carol() { return new TestPlayer(UUID_C, "Carol", 200); }

    TransferReport transfer(int batchSize) {
        return StorageTransfer.builder()
            .from(source).to(target)
            .descriptor(DESCRIPTOR)
            .batchSize(batchSize)
            .failIfTargetCollectionNotEmpty(false)
            .verifyCounts(true)
            .build()
            .execute().join();
    }

    // ------------------------------------------------------------------
    //  Single descriptor - basic round-trip
    // ------------------------------------------------------------------

    @Test @Order(10)
    @DisplayName("Transfer 3 entities: all appear in target")
    void basic_threeEntities_allInTarget() {
        Repository<UUID, TestPlayer> srcRepo = source.repository(DESCRIPTOR);
        srcRepo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        TransferReport report = transfer(500);

        assertTrue(report.success(), "Report must be successful: " + report.errors());
        assertEquals(3L, report.totalEntities());

        Repository<UUID, TestPlayer> tgtRepo = target.repository(DESCRIPTOR);
        assertEquals(3L, tgtRepo.count().join());
        assertEquals(alice(), tgtRepo.find(UUID_A).join().orElseThrow(AssertionError::new));
        assertEquals(bob(),   tgtRepo.find(UUID_B).join().orElseThrow(AssertionError::new));
        assertEquals(carol(), tgtRepo.find(UUID_C).join().orElseThrow(AssertionError::new));
    }

    @Test @Order(11)
    @DisplayName("Source is unmodified after transfer")
    void basic_sourceUnmodifiedAfterTransfer() {
        Repository<UUID, TestPlayer> srcRepo = source.repository(DESCRIPTOR);
        srcRepo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        transfer(500);

        // Source must still have all 3 entities untouched
        assertEquals(3L, srcRepo.count().join());
        assertEquals(alice(), srcRepo.find(UUID_A).join().orElseThrow(AssertionError::new));
    }

    @Test @Order(12)
    @DisplayName("Transfer 0 entities (empty source): success, totalEntities=0")
    void basic_emptySource_success() {
        TransferReport report = transfer(500);

        assertTrue(report.success());
        assertEquals(0L, report.totalEntities());
        assertEquals(1, report.collections().size(), "One CollectionStats even for empty source");
        assertEquals(0L, report.collections().get(DESCRIPTOR.collection()).entitiesWritten());
    }

    // ------------------------------------------------------------------
    //  Report fields
    // ------------------------------------------------------------------

    @Test @Order(20)
    @DisplayName("Report: success=true, durationMs >= 0, collections keyed by source name")
    void report_fields_areCorrect() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();

        TransferReport report = transfer(500);

        assertTrue(report.success());
        assertTrue(report.durationMs() >= 0);
        assertTrue(report.errors().isEmpty());
        assertTrue(report.collections().containsKey(DESCRIPTOR.collection()));

        CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
        assertEquals(DESCRIPTOR.collection(), stats.sourceCollection());
        assertEquals(DESCRIPTOR.collection(), stats.targetCollection());
        assertEquals(2L, stats.sourceCount());
        assertEquals(0L, stats.targetCountBefore());
        assertEquals(2L, stats.targetCountAfter());
        assertEquals(2L, stats.entitiesWritten());
        assertTrue(stats.durationMs() >= 0);
    }

    // ------------------------------------------------------------------
    //  Multi-descriptor
    // ------------------------------------------------------------------

    @Test @Order(30)
    @DisplayName("Multi-descriptor: both collections transferred independently")
    void multiDescriptor_bothCollectionsTransferred() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();
        source.repository(DESCRIPTOR_B).save(carol()).join();

        TransferReport report = StorageTransfer.builder()
            .from(source).to(target)
            .descriptor(DESCRIPTOR)
            .descriptor(DESCRIPTOR_B)
            .failIfTargetCollectionNotEmpty(false)
            .build()
            .execute().join();

        assertTrue(report.success(), "Report must be successful: " + report.errors());
        assertEquals(3L, report.totalEntities());
        assertEquals(2, report.collections().size());

        assertEquals(2L, target.repository(DESCRIPTOR).count().join());
        assertEquals(1L, target.repository(DESCRIPTOR_B).count().join());
    }

    @Test @Order(31)
    @DisplayName("Multi-descriptor: collections map preserves registration order")
    void multiDescriptor_collectionsMapPreservesOrder() {
        source.repository(DESCRIPTOR).save(alice()).join();
        source.repository(DESCRIPTOR_B).save(bob()).join();

        TransferReport report = StorageTransfer.builder()
            .from(source).to(target)
            .descriptor(DESCRIPTOR)
            .descriptor(DESCRIPTOR_B)
            .failIfTargetCollectionNotEmpty(false)
            .build()
            .execute().join();

        List<String> keys = new ArrayList<>(report.collections().keySet());
        assertEquals(DESCRIPTOR.collection(),   keys.get(0));
        assertEquals(DESCRIPTOR_B.collection(), keys.get(1));
    }

    // ------------------------------------------------------------------
    //  Partial transfer
    // ------------------------------------------------------------------

    @Test @Order(40)
    @DisplayName("Partial: only registered descriptors appear in target; others untouched")
    void partial_onlyRegisteredDescriptorsTransferred() {
        // Source has data for 2 collections; we only transfer 1
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();
        source.repository(DESCRIPTOR_B).save(carol()).join();

        TransferReport report = StorageTransfer.builder()
            .from(source).to(target)
            .descriptor(DESCRIPTOR)       // only DESCRIPTOR registered
            // DESCRIPTOR_B intentionally omitted
            .failIfTargetCollectionNotEmpty(false)
            .build()
            .execute().join();

        assertTrue(report.success());
        assertEquals(2L, report.totalEntities(), "Only DESCRIPTOR entities counted");
        assertEquals(2L, target.repository(DESCRIPTOR).count().join());
        assertEquals(0L, target.repository(DESCRIPTOR_B).count().join(),
            "DESCRIPTOR_B must not appear in target");
    }

    // ------------------------------------------------------------------
    //  Different descriptors (collection rename)
    // ------------------------------------------------------------------

    @Test @Order(50)
    @DisplayName("descriptor(src, tgt): entities land in target collection, not source collection")
    void differentDescriptors_entitiesLandInTargetCollection() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();

        // Transfer test_players -> alt_players
        TransferReport report = StorageTransfer.builder()
            .from(source).to(target)
            .descriptor(DESCRIPTOR, ALT_DESCRIPTOR)
            .failIfTargetCollectionNotEmpty(false)
            .build()
            .execute().join();

        assertTrue(report.success(), "Report must be successful: " + report.errors());
        assertEquals(2L, report.totalEntities());

        // Entities are in alt_players, not test_players
        assertEquals(2L, target.repository(ALT_DESCRIPTOR).count().join());
        assertEquals(0L, target.repository(DESCRIPTOR).count().join(),
            "test_players in target must remain empty");

        // CollectionStats keyed by source collection, target collection correctly set
        CollectionStats stats = report.collections().get(DESCRIPTOR.collection());
        assertNotNull(stats);
        assertEquals(DESCRIPTOR.collection(),     stats.sourceCollection());
        assertEquals(ALT_DESCRIPTOR.collection(), stats.targetCollection());
    }

    // ------------------------------------------------------------------
    //  Batch size variants
    // ------------------------------------------------------------------

    @Test @Order(60)
    @DisplayName("batchSize=1: each entity in its own batch, all entities transferred")
    void batchSize_one_allEntitiesTransferred() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob(), carol())).join();

        TransferReport report = transfer(1); // batchSize=1

        assertTrue(report.success());
        assertEquals(3L, report.totalEntities());
        assertEquals(3L, target.repository(DESCRIPTOR).count().join());
    }

    @Test @Order(61)
    @DisplayName("batchSize=N (exact): single batch covers all entities")
    void batchSize_exact_singleBatch() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob(), carol())).join();

        TransferReport report = transfer(3); // exactly 3 entities, 3 batch size -> 1 batch

        assertTrue(report.success());
        assertEquals(3L, report.totalEntities());
    }

    @Test @Order(62)
    @DisplayName("batchSize=N+1 (larger than count): single batch, all entities")
    void batchSize_largerThanCount_singleBatch() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob(), carol())).join();

        TransferReport report = transfer(100); // batchSize > entity count

        assertTrue(report.success());
        assertEquals(3L, report.totalEntities());
    }

    @Test @Order(63)
    @DisplayName("Large transfer with small batchSize: all entities written, multiple batches")
    void batchSize_small_multipleEntities_allWritten() {
        // 10 entities, batchSize=3 -> 4 batches (3+3+3+1)
        List<TestPlayer> players = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            players.add(new TestPlayer(new UUID(0, i + 1), "Player_" + i, i * 10));
        }
        source.repository(DESCRIPTOR).saveAll(players).join();

        TransferReport report = transfer(3);

        assertTrue(report.success());
        assertEquals(10L, report.totalEntities());
        assertEquals(10L, target.repository(DESCRIPTOR).count().join());
    }

    // ------------------------------------------------------------------
    //  Idempotency
    // ------------------------------------------------------------------

    @Test @Order(70)
    @DisplayName("Running transfer twice (failIfTargetNotEmpty=false): second run upserts, no error")
    void idempotent_secondRunUpserts() {
        source.repository(DESCRIPTOR).saveAll(Arrays.asList(alice(), bob())).join();

        TransferReport first  = transfer(500);
        TransferReport second = transfer(500); // same source, same target, flag=false

        assertTrue(first.success());
        assertTrue(second.success(), "Second run must not fail with failIfTargetNotEmpty=false");
        assertEquals(2L, target.repository(DESCRIPTOR).count().join(),
            "Upsert must not duplicate entities");
    }
}
