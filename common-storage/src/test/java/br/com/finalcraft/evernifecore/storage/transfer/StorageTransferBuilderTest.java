package br.com.finalcraft.evernifecore.storage.transfer;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.memory.InMemoryStorage;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1 - Validates the public API surface of {@link StorageTransfer}:
 * the builder, all POJOs, {@link ErrorPolicy} and {@link DescriptorPair} semantics.
 *
 * <p>Does NOT call {@code execute()} - that is implemented in F2.
 */
@DisplayName("StorageTransfer - Builder & types (F1)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageTransferBuilderTest {

    private static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR =
        AbstractStorageTest.DESCRIPTOR;

    private static final EntityDescriptor<UUID, TestPlayer> ALT_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("alt_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .build();

    private InMemoryStorage source;
    private InMemoryStorage target;

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
    //  Builder - happy path
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("builder() returns a non-null Builder instance")
    void builder_returnsNonNull() {
        assertNotNull(StorageTransfer.builder());
    }

    @Test
    @Order(11)
    @DisplayName("build() with minimum config returns a StorageTransfer instance")
    void build_minimumConfig_returnsInstance() {
        StorageTransfer transfer = StorageTransfer.builder()
            .from(source)
            .to(target)
            .descriptor(DESCRIPTOR)
            .build();

        assertNotNull(transfer, "build() must return a non-null StorageTransfer");
        assertInstanceOf(StorageTransfer.class, transfer);
    }

    @Test
    @Order(12)
    @DisplayName("build() with all options set returns a StorageTransfer instance")
    void build_allOptions_returnsInstance() {
        StorageTransfer transfer = StorageTransfer.builder()
            .from(source)
            .to(target)
            .descriptor(DESCRIPTOR)
            .batchSize(100)
            .errorPolicy(ErrorPolicy.CONTINUE)
            .applyTargetMigrations(false)
            .failIfTargetCollectionNotEmpty(false)
            .verifyCounts(false)
            .progressListener(p -> {})
            .build();

        assertNotNull(transfer);
    }

    @Test
    @Order(13)
    @DisplayName("descriptor(src, tgt) overload with different descriptors builds successfully")
    void build_differentDescriptors_buildsSuccessfully() {
        StorageTransfer transfer = StorageTransfer.builder()
            .from(source)
            .to(target)
            .descriptor(DESCRIPTOR, ALT_DESCRIPTOR)  // rename collection during transfer
            .build();

        assertNotNull(transfer);
    }

    @Test
    @Order(14)
    @DisplayName("multiple descriptor() calls accumulate in registration order")
    void build_multipleDescriptors_allRegistered() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source)
            .to(target)
            .descriptor(DESCRIPTOR)
            .descriptor(DESCRIPTOR, ALT_DESCRIPTOR)
            .build();

        assertEquals(2, impl.descriptors.size(), "Both descriptor pairs must be registered");
    }

    // ------------------------------------------------------------------
    //  Builder - validation (missing required fields)
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("build() without from() throws IllegalStateException")
    void build_missingSource_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
            StorageTransfer.builder()
                .to(target)
                .descriptor(DESCRIPTOR)
                .build()
        );
    }

    @Test
    @Order(21)
    @DisplayName("build() without to() throws IllegalStateException")
    void build_missingTarget_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
            StorageTransfer.builder()
                .from(source)
                .descriptor(DESCRIPTOR)
                .build()
        );
    }

    @Test
    @Order(22)
    @DisplayName("build() without any descriptor() throws IllegalStateException")
    void build_noDescriptors_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
            StorageTransfer.builder()
                .from(source)
                .to(target)
                .build()
        );
    }

    @Test
    @Order(23)
    @DisplayName("batchSize(0) throws IllegalArgumentException")
    void batchSize_zero_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
            StorageTransfer.builder().batchSize(0)
        );
    }

    @Test
    @Order(24)
    @DisplayName("batchSize(-1) throws IllegalArgumentException")
    void batchSize_negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
            StorageTransfer.builder().batchSize(-1)
        );
    }

    // ------------------------------------------------------------------
    //  Builder - default values
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("default batchSize is 500")
    void defaults_batchSize_is500() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertEquals(500, impl.batchSize);
    }

    @Test
    @Order(31)
    @DisplayName("default errorPolicy is FAIL_FAST")
    void defaults_errorPolicy_isFailFast() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertEquals(ErrorPolicy.FAIL_FAST, impl.errorPolicy);
    }

    @Test
    @Order(32)
    @DisplayName("default applyTargetMigrations is true")
    void defaults_applyTargetMigrations_isTrue() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertTrue(impl.applyTargetMigrations);
    }

    @Test
    @Order(33)
    @DisplayName("default failIfTargetCollectionNotEmpty is true")
    void defaults_failIfTargetCollectionNotEmpty_isTrue() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertTrue(impl.failIfTargetCollectionNotEmpty);
    }

    @Test
    @Order(34)
    @DisplayName("default verifyCounts is true")
    void defaults_verifyCounts_isTrue() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertTrue(impl.verifyCounts);
    }

    @Test
    @Order(35)
    @DisplayName("default progressListener is null")
    void defaults_progressListener_isNull() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();
        assertNull(impl.progressListener);
    }

    // ------------------------------------------------------------------
    //  ErrorPolicy enum
    // ------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("ErrorPolicy has exactly 3 values: FAIL_FAST, CONTINUE, SKIP_EXISTING")
    void errorPolicy_hasThreeValues() {
        ErrorPolicy[] values = ErrorPolicy.values();
        assertEquals(3, values.length);
        assertEquals(ErrorPolicy.FAIL_FAST,    values[0]);
        assertEquals(ErrorPolicy.CONTINUE,     values[1]);
        assertEquals(ErrorPolicy.SKIP_EXISTING, values[2]);
    }

    // ------------------------------------------------------------------
    //  TransferProgress POJO
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("TransferProgress stores all fields correctly")
    void transferProgress_storesFields() {
        TransferProgress p = new TransferProgress("players", 100, 500, 1234L);
        assertEquals("players", p.collection());
        assertEquals(100L,      p.done());
        assertEquals(500L,      p.total());
        assertEquals(1234L,     p.elapsedMs());
    }

    // ------------------------------------------------------------------
    //  TransferError POJO
    // ------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("TransferError stores all fields correctly (with key)")
    void transferError_withKey_storesFields() {
        RuntimeException cause = new RuntimeException("boom");
        UUID key = UUID.randomUUID();
        TransferError e = new TransferError("players", key, cause);
        assertEquals("players", e.collection());
        assertEquals(key,       e.key());
        assertSame(cause,       e.cause());
    }

    @Test
    @Order(61)
    @DisplayName("TransferError with null key represents a global/collection-level error")
    void transferError_nullKey_isGlobalError() {
        TransferError e = new TransferError("players", null, new RuntimeException("global"));
        assertNull(e.key(), "null key signals a global error, not per-entity");
    }

    // ------------------------------------------------------------------
    //  CollectionStats POJO
    // ------------------------------------------------------------------

    @Test
    @Order(70)
    @DisplayName("CollectionStats stores all fields correctly")
    void collectionStats_storesFields() {
        CollectionStats s = new CollectionStats(
            "players", "alt_players", 100L, 0L, 100L, 100L, 250L);

        assertEquals("players",     s.sourceCollection());
        assertEquals("alt_players", s.targetCollection());
        assertEquals(100L,          s.sourceCount());
        assertEquals(0L,            s.targetCountBefore());
        assertEquals(100L,          s.targetCountAfter());
        assertEquals(100L,          s.entitiesWritten());
        assertEquals(250L,          s.durationMs());
    }

    // ------------------------------------------------------------------
    //  TransferReport
    // ------------------------------------------------------------------

    @Test
    @Order(80)
    @DisplayName("TransferReport.Builder starts with success=true and no errors")
    void transferReport_initialState_isSuccessful() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        TransferReport report = rb.build();

        assertTrue(report.success(), "Initial report must be successful");
        assertTrue(report.errors().isEmpty());
        assertTrue(report.collections().isEmpty());
        assertEquals(0L, report.totalEntities());
    }

    @Test
    @Order(81)
    @DisplayName("TransferReport.Builder.addError() sets success=false")
    void transferReport_addError_setsSuccessFalse() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        rb.addError(new TransferError("players", null, new RuntimeException("oops")));
        TransferReport report = rb.build();

        assertFalse(report.success());
        assertEquals(1, report.errors().size());
    }

    @Test
    @Order(82)
    @DisplayName("TransferReport.Builder.addCollectionStats() accumulates totalEntities")
    void transferReport_addCollectionStats_accumulatesTotalEntities() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        rb.addCollectionStats(new CollectionStats("a", "a", 50L, 0L, 50L, 50L, 10L));
        rb.addCollectionStats(new CollectionStats("b", "b", 30L, 0L, 30L, 30L, 5L));
        TransferReport report = rb.build();

        assertEquals(80L, report.totalEntities(), "totalEntities must be sum of entitiesWritten");
        assertEquals(2,   report.collections().size());
        assertTrue(report.success(), "No errors added -> success must be true");
    }

    @Test
    @Order(83)
    @DisplayName("TransferReport.collections() is keyed by source collection name")
    void transferReport_collections_keyedBySourceCollection() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        rb.addCollectionStats(new CollectionStats("players", "alt_players", 10L, 0L, 10L, 10L, 1L));
        TransferReport report = rb.build();

        assertTrue(report.collections().containsKey("players"),
            "Map must be keyed by source collection name");
        assertEquals("alt_players", report.collections().get("players").targetCollection());
    }

    @Test
    @Order(84)
    @DisplayName("TransferReport is immutable: collections() and errors() cannot be modified")
    void transferReport_isImmutable() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        rb.addCollectionStats(new CollectionStats("players", "players", 5L, 0L, 5L, 5L, 1L));
        TransferReport report = rb.build();

        assertThrows(UnsupportedOperationException.class,
            () -> report.collections().put("x", null));
        assertThrows(UnsupportedOperationException.class,
            () -> report.errors().add(null));
    }

    @Test
    @Order(85)
    @DisplayName("TransferReport.durationMs() is non-negative")
    void transferReport_durationMs_isNonNegative() {
        TransferReport.Builder rb = TransferReport.builder(System.currentTimeMillis());
        TransferReport report = rb.build();
        assertTrue(report.durationMs() >= 0, "durationMs must be >= 0");
    }

    // ------------------------------------------------------------------
    //  DescriptorPair (package-private - test via builder reflection on impl)
    // ------------------------------------------------------------------

    @Test
    @Order(100)
    @DisplayName("DescriptorPair.same: source == target when same descriptor is used")
    void descriptorPair_sameDescriptor_sourceEqualsTarget() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR).build();

        @SuppressWarnings("unchecked")
        DescriptorPair<UUID, TestPlayer> pair =
            (DescriptorPair<UUID, TestPlayer>) impl.descriptors.get(0);

        assertSame(DESCRIPTOR, pair.source);
        assertSame(DESCRIPTOR, pair.target);
    }

    @Test
    @Order(101)
    @DisplayName("DescriptorPair.different: source != target when two descriptors are used")
    void descriptorPair_differentDescriptors_sourceAndTargetDistinct() {
        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR, ALT_DESCRIPTOR).build();

        @SuppressWarnings("unchecked")
        DescriptorPair<UUID, TestPlayer> pair =
            (DescriptorPair<UUID, TestPlayer>) impl.descriptors.get(0);

        assertSame(DESCRIPTOR,     pair.source);
        assertSame(ALT_DESCRIPTOR, pair.target);
    }

    // ------------------------------------------------------------------
    //  progressListener wiring
    // ------------------------------------------------------------------

    @Test
    @Order(110)
    @DisplayName("progressListener registered in builder is stored in impl")
    void progressListener_registered_storedInImpl() {
        List<TransferProgress> received = new ArrayList<>();
        Consumer<TransferProgress> listener = received::add;

        StorageTransferImpl impl = (StorageTransferImpl) StorageTransfer.builder()
            .from(source).to(target).descriptor(DESCRIPTOR)
            .progressListener(listener)
            .build();

        assertSame(listener, impl.progressListener,
            "The progress listener must be stored as-is in the impl");
    }
}
