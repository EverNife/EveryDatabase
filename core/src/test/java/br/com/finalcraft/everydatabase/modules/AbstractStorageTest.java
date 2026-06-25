package br.com.finalcraft.everydatabase.modules;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.HealthStatus;
import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.log.StorageLogEvent;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import br.com.finalcraft.everydatabase.query.IndexHint;
import br.com.finalcraft.everydatabase.query.Query;
import br.com.finalcraft.everydatabase.query.QueryOptions;
import br.com.finalcraft.everydatabase.testutil.CapturingSink;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test suite for {@link Storage} and {@link Repository}.
 *
 * <p>Every storage backend should pass all of these tests by extending this class
 * and implementing {@link #createStorage(String)}.
 *
 * <p>Tests are ordered for readability; they are <em>not</em> interdependent -
 * each test starts from a clean storage via {@link #setUp(TestInfo)}.
 *
 * <p>All test display names are prefixed with {@code [base]} to distinguish them
 * from backend-specific tests in the JUnit report.
 *
 * <p>The heavyweight 10k-record stress scenario lives in the separate
 * {@link AbstractStorageStressTest} hierarchy (tagged {@code stress}).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractStorageTest {

    // ------------------------------------------------------------------
    //  Fixed test data
    // ------------------------------------------------------------------

    public static final UUID UUID_ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID UUID_BOB   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID UUID_CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID UUID_GHOST = UUID.fromString("00000000-0000-0000-0000-000000000099");

    /** Shared descriptor - all tests use this collection. */
    public static final EntityDescriptor<UUID, TestPlayer> DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("test_players")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .index(IndexHint.string("world"))
            .index(IndexHint.bool("active"))
            .index(IndexHint.timestamp("createdAt"))
            .build();

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    protected Storage storage;
    protected Repository<UUID, TestPlayer> repo;

    /**
     * Implement to provide the concrete {@link Storage} instance under test.
     *
     * @param testMethodName the JUnit test method name - SQL/Mongo backends use it to
     *                       build a human-readable database name; others may ignore it.
     */
    protected abstract Storage createStorage(String testMethodName);

    /**
     * Whether this backend retains data and supports reuse across a full {@code close()}/{@code init()}
     * cycle on the same instance. {@code true} for every persistent backend (SQL, Mongo, LocalFile,
     * GroupedFile, and H2's mem DB which uses {@code DB_CLOSE_DELAY=-1}). Overridden to {@code false}
     * by the InMemory suite, whose data lives only for the instance's lifetime (a fresh
     * {@code init()} after {@code close()} starts empty).
     */
    protected boolean survivesReopen() {
        return true;
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String methodName = testInfo.getTestMethod().map(Method::getName).orElse("unknown");
        storage = createStorage(methodName);
        storage.init().join();
        repo = storage.repository(DESCRIPTOR);
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    public TestPlayer alice() { return new TestPlayer(UUID_ALICE, "Alice", 100); }
    public TestPlayer bob()   { return new TestPlayer(UUID_BOB,   "Bob",    50); }
    public TestPlayer carol() { return new TestPlayer(UUID_CAROL, "Carol", 200); }

    // ------------------------------------------------------------------
    //  Health
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("[base] health() after init() -> connected=true")
    void health_afterInit_isConnected() {
        HealthStatus h = storage.health().join();
        assertTrue(h.isConnected(), "Expected storage to be connected after init()");
    }

    @Test
    @Order(2)
    @DisplayName("[base] data + delete survive repeated close()/init() cycles (join at each step)")
    void data_survivesRepeatedCloseInitCycles() {
        Assumptions.assumeTrue(survivesReopen(),
            "backend does not retain data across a full close()/init() cycle (e.g. in-memory)");

        // open (init in setUp) + save
        repo.save(alice()).join();

        // close -> init -> still present
        storage.close().join();
        storage.init().join();
        assertTrue(storage.repository(DESCRIPTOR).find(UUID_ALICE).join().isPresent(),
            "entity must survive the first close/init cycle");

        // close -> init -> still present
        storage.close().join();
        storage.init().join();
        assertTrue(storage.repository(DESCRIPTOR).find(UUID_ALICE).join().isPresent(),
            "entity must survive the second close/init cycle");

        // close -> init -> delete
        storage.close().join();
        storage.init().join();
        assertTrue(storage.repository(DESCRIPTOR).delete(UUID_ALICE).join(),
            "delete must report the entity existed");

        // close -> init -> gone (the delete persisted too)
        storage.close().join();
        storage.init().join();
        assertFalse(storage.repository(DESCRIPTOR).find(UUID_ALICE).join().isPresent(),
            "entity must stay deleted across a close/init cycle");
    }

    // ------------------------------------------------------------------
    //  Empty-state reads
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("[base] find() on empty repo -> Optional.empty()")
    void find_emptyRepo_returnsEmpty() {
        Optional<TestPlayer> result = repo.find(UUID_GHOST).join();
        assertFalse(result.isPresent());
    }

    @Test
    @Order(11)
    @DisplayName("[base] exists() on empty repo -> false")
    void exists_emptyRepo_returnsFalse() {
        assertFalse(repo.exists(UUID_GHOST).join());
    }

    @Test
    @Order(12)
    @DisplayName("[base] count() on empty repo -> 0")
    void count_emptyRepo_returnsZero() {
        assertEquals(0L, repo.count().join());
    }

    @Test
    @Order(13)
    @DisplayName("[base] all() on empty repo -> empty stream")
    void all_emptyRepo_returnsEmptyStream() {
        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertTrue(all.isEmpty());
    }

    @Test
    @Order(14)
    @DisplayName("[base] findMany() with empty key set -> empty list")
    void findMany_emptyKeys_returnsEmptyList() {
        List<TestPlayer> found = repo.findMany(Collections.emptyList()).join();
        assertTrue(found.isEmpty());
    }

    @Test
    @Order(15)
    @DisplayName("[base] delete() on non-existent key -> false")
    void delete_nonExistent_returnsFalse() {
        assertFalse(repo.delete(UUID_GHOST).join());
    }

    // ------------------------------------------------------------------
    //  save + find
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("[base] save() then find() returns equal entity")
    void save_thenFind_returnsEqualEntity() {
        TestPlayer alice = alice();
        repo.save(alice).join();

        Optional<TestPlayer> found = repo.find(UUID_ALICE).join();
        assertTrue(found.isPresent(), "Entity should be present after save()");
        assertEquals(alice, found.get());
    }

    @Test
    @Order(21)
    @DisplayName("[base] save() same key twice -> upsert (no duplicate, last value wins)")
    void save_sameKeyTwice_upserts() {
        repo.save(alice()).join();

        TestPlayer updated = new TestPlayer(UUID_ALICE, "Alice", 999);
        repo.save(updated).join();

        TestPlayer found = repo.find(UUID_ALICE).join().orElseThrow(AssertionError::new);
        assertEquals(999, found.getScore(), "Score should reflect the last saved value");
        assertEquals(1L,  repo.count().join(), "count() must not grow after upsert");
    }

    // ------------------------------------------------------------------
    //  exists + count
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("[base] exists() returns true after save()")
    void exists_afterSave_returnsTrue() {
        repo.save(alice()).join();
        assertTrue(repo.exists(UUID_ALICE).join());
    }

    @Test
    @Order(31)
    @DisplayName("[base] count() reflects number of distinct entities")
    void count_afterMultipleSaves_isCorrect() {
        repo.save(alice()).join();
        repo.save(bob()).join();
        assertEquals(2L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  delete
    // ------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("[base] delete() existing key -> true, then find() -> empty")
    void delete_existingKey_removesEntity() {
        repo.save(alice()).join();

        boolean deleted = repo.delete(UUID_ALICE).join();
        assertTrue(deleted, "delete() should return true for existing key");
        assertFalse(repo.find(UUID_ALICE).join().isPresent(), "find() should return empty after delete()");
    }

    @Test
    @Order(41)
    @DisplayName("[base] delete() does not affect other entities")
    void delete_doesNotAffectOtherEntities() {
        repo.save(alice()).join();
        repo.save(bob()).join();

        repo.delete(UUID_ALICE).join();

        assertFalse(repo.find(UUID_ALICE).join().isPresent());
        assertTrue(repo.find(UUID_BOB).join().isPresent(), "Bob should still exist");
        assertEquals(1L, repo.count().join());
    }

    // ------------------------------------------------------------------
    //  saveAll + findMany
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("[base] saveAll() then findMany() returns all entities")
    void saveAll_thenFindMany_returnsAll() {
        List<TestPlayer> players = Arrays.asList(alice(), bob(), carol());
        repo.saveAll(players).join();

        List<TestPlayer> found = repo.findMany(Arrays.asList(UUID_ALICE, UUID_BOB, UUID_CAROL)).join();
        assertEquals(3, found.size());
        assertTrue(found.containsAll(players), "All saved entities should be returned");
    }

    @Test
    @Order(51)
    @DisplayName("[base] findMany() silently omits missing keys")
    void findMany_missingKeysSilentlyOmitted() {
        repo.save(alice()).join();
        // bob is NOT saved

        List<TestPlayer> found = repo.findMany(Arrays.asList(UUID_ALICE, UUID_BOB)).join();
        assertEquals(1, found.size());
        assertEquals(alice(), found.get(0));
    }

    // ------------------------------------------------------------------
    //  all()
    // ------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("[base] all() after saveAll() returns all entities")
    void all_afterSaveAll_returnsAllEntities() {
        repo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        List<TestPlayer> all = repo.all().join().collect(Collectors.toList());
        assertEquals(3, all.size());
        assertTrue(all.containsAll(Arrays.asList(alice(), bob(), carol())));
    }

    // ------------------------------------------------------------------
    //  Codec round-trip
    // ------------------------------------------------------------------

    @Test
    @Order(80)
    @DisplayName("[base] all fields survive encode -> decode round-trip")
    void codec_roundTrip_allFieldsSurvive() {
        TestPlayer original = new TestPlayer(UUID_ALICE, "Alice With Spaces & Simbolos", Integer.MAX_VALUE);
        repo.save(original).join();

        TestPlayer loaded = repo.find(UUID_ALICE).join().orElseThrow(AssertionError::new);
        assertEquals(original.getUuid(),  loaded.getUuid());
        assertEquals(original.getName(),  loaded.getName());
        assertEquals(original.getScore(), loaded.getScore());
    }

    // ------------------------------------------------------------------
    //  findBy / query  (secondary-index contract)
    // ------------------------------------------------------------------

    /** Populate three players with distinct worlds and active flags. */
    private void seedIndexData() {
        // alice  - world="world",       score=100, active=true
        // bob    - world="world_nether", score=50,  active=true
        // carol  - world="world",       score=200, active=false
        repo.saveAll(Arrays.asList(
            new TestPlayer(UUID_ALICE, "Alice", 100, "world",        true),
            new TestPlayer(UUID_BOB,   "Bob",    50, "world_nether", true),
            new TestPlayer(UUID_CAROL, "Carol", 200, "world",        false)
        )).join();
    }

    @Test
    @Order(90)
    @DisplayName("[base] findBy('name', 'Alice') -> returns exactly Alice")
    void findBy_name_returnsExactMatch() {
        seedIndexData();
        List<TestPlayer> found = repo.findBy("name", "Alice").join();
        assertEquals(1, found.size(), "Expected exactly one match for name=Alice");
        assertEquals(UUID_ALICE, found.get(0).getUuid());
    }

    @Test
    @Order(91)
    @DisplayName("[base] findBy() with no matching value -> empty list")
    void findBy_noMatch_returnsEmpty() {
        seedIndexData();
        List<TestPlayer> found = repo.findBy("name", "Nobody").join();
        assertTrue(found.isEmpty(), "Expected empty list when no entity matches");
    }

    @Test
    @Order(92)
    @DisplayName("[base] query(eq('world', 'world_nether')) -> returns only Bob")
    void query_eq_world_returnsCorrectSubset() {
        seedIndexData();
        List<TestPlayer> found = repo.query(Query.eq("world", "world_nether")).join();
        assertEquals(1, found.size());
        assertEquals(UUID_BOB, found.get(0).getUuid());
    }

    @Test
    @Order(93)
    @DisplayName("[base] query(range('score', 50, 150)) -> returns Alice and Bob")
    void query_range_score_returnsInRange() {
        seedIndexData();
        List<TestPlayer> found = repo.query(Query.range("score", 50, 150)).join();
        assertEquals(2, found.size(), "Expected Alice (100) and Bob (50)");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_ALICE));
        assertTrue(uuids.contains(UUID_BOB));
    }

    @Test
    @Order(94)
    @DisplayName("[base] query(in('name', 'Alice', 'Bob')) -> returns Alice and Bob")
    void query_in_names_returnsBoth() {
        seedIndexData();
        List<TestPlayer> found = repo.query(Query.in("name", "Alice", "Bob")).join();
        assertEquals(2, found.size(), "Expected Alice and Bob");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_ALICE));
        assertTrue(uuids.contains(UUID_BOB));
    }

    @Test
    @Order(95)
    @DisplayName("[base] query(eq('active', false)) -> returns only Carol")
    void query_eq_boolean_returnsInactive() {
        seedIndexData();
        List<TestPlayer> found = repo.query(Query.eq("active", false)).join();
        assertEquals(1, found.size(), "Expected only Carol (active=false)");
        assertEquals(UUID_CAROL, found.get(0).getUuid());
    }

    @Test
    @Order(96)
    @DisplayName("[base] query(eq('world','world') AND eq('active',true)) -> returns only Alice")
    void query_and_world_active_returnsAlice() {
        seedIndexData();
        Query q = Query.eq("world", "world").and(Query.eq("active", true));
        List<TestPlayer> found = repo.query(q).join();
        // world="world" matches Alice (active=true) and Carol (active=false)
        // AND active=true filters Carol out -> only Alice
        assertEquals(1, found.size(), "Expected only Alice matching world=world AND active=true");
        assertEquals(UUID_ALICE, found.get(0).getUuid());
    }

    @Test
    @Order(97)
    @DisplayName("[base] query(range('score', 100, null)) -> open upper bound returns Alice and Carol")
    void query_range_openUpper_returnsHighScores() {
        seedIndexData();
        // score >= 100, no upper bound -> Alice (100) and Carol (200)
        List<TestPlayer> found = repo.query(Query.range("score", 100, null)).join();
        assertEquals(2, found.size(), "Expected Alice (100) and Carol (200)");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_ALICE));
        assertTrue(uuids.contains(UUID_CAROL));
    }

    @Test
    @Order(98)
    @DisplayName("[base] query on undeclared field -> throws IllegalArgumentException")
    void query_undeclaredField_throwsIllegalArgument() {
        seedIndexData();
        assertThrows(IllegalArgumentException.class, (Executable) () ->
            repo.query(Query.eq("nonExistentField", "value")).join()
        );
    }

    @Test
    @Order(99)
    @DisplayName("[base] query(all, order by score desc limit 2) -> returns highest scores")
    void query_all_orderDesc_limit_returnsTopScores() {
        seedIndexData();
        List<TestPlayer> found = repo.query(
            Query.all(),
            QueryOptions.builder()
                .descending("score")
                .limit(2)
                .build()
        ).join();

        assertEquals(2, found.size());
        assertEquals(UUID_CAROL, found.get(0).getUuid());
        assertEquals(UUID_ALICE, found.get(1).getUuid());
    }

    @Test
    @Order(99)
    @DisplayName("[base] query(all, order by score asc offset 1 limit 1) -> returns page")
    void query_all_orderAsc_offsetLimit_returnsPage() {
        seedIndexData();
        List<TestPlayer> found = repo.query(
            Query.all(),
            QueryOptions.builder()
                .ascending("score")
                .offset(1)
                .limit(1)
                .build()
        ).join();

        assertEquals(1, found.size());
        assertEquals(UUID_ALICE, found.get(0).getUuid());
    }

    @Test
    @Order(99)
    @DisplayName("[base] query(eq + order desc limit) -> filters before ordering")
    void query_filter_orderDesc_limit_returnsTopFiltered() {
        seedIndexData();
        List<TestPlayer> found = repo.query(
            Query.eq("world", "world"),
            QueryOptions.builder()
                .descending("score")
                .limit(1)
                .build()
        ).join();

        assertEquals(1, found.size());
        assertEquals(UUID_CAROL, found.get(0).getUuid());
    }

    @Test
    @Order(99)
    @DisplayName("[base] query order by undeclared field -> throws IllegalArgumentException")
    void query_orderByUndeclaredField_throwsIllegalArgument() {
        seedIndexData();
        assertThrows(IllegalArgumentException.class, (Executable) () ->
            repo.query(
                Query.all(),
                QueryOptions.builder()
                    .ascending("randomUuid")
                    .limit(1)
                    .build()
            ).join()
        );
    }

    // ------------------------------------------------------------------
    //  query - TIMESTAMP / createdAt index
    // ------------------------------------------------------------------

    // Fixed reference points used across all createdAt query tests.
    // T_OLD  = 30 days ago
    // T_MID  = 10 days ago
    // T_NEW  = 5 days ago
    // Queries use boundaries between these points to select subsets.

    private static final Instant T_OLD = Instant.now().minus(30, ChronoUnit.DAYS);
    private static final Instant T_MID = Instant.now().minus(10, ChronoUnit.DAYS);
    private static final Instant T_NEW = Instant.now().minus( 5, ChronoUnit.DAYS);

    /** Seeds three players with distinct createdAt timestamps. */
    private void seedTimestampData() {
        repo.saveAll(Arrays.asList(
            new TestPlayer(UUID_ALICE, "Alice", 100, "world", true,  T_OLD.toEpochMilli()),
            new TestPlayer(UUID_BOB,   "Bob",    50, "world", true,  T_MID.toEpochMilli()),
            new TestPlayer(UUID_CAROL, "Carol", 200, "world", false, T_NEW.toEpochMilli())
        )).join();
    }

    @Test
    @Order(100)
    @DisplayName("[base] query(range('createdAt', from, to)) -> returns players within time window")
    void query_range_createdAt_instantBounds_returnsPlayersInWindow() {
        seedTimestampData();
        // boundary: T_OLD - 1day  to  T_MID + 1day  -> should match Alice and Bob only
        Instant from = T_OLD.minus(1, ChronoUnit.DAYS);
        Instant to   = T_MID.plus( 1, ChronoUnit.DAYS);

        List<TestPlayer> found = repo.query(Query.range("createdAt", from, to)).join();
        assertEquals(2, found.size(), "Expected Alice and Bob within [T_OLD-1d, T_MID+1d]");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_ALICE));
        assertTrue(uuids.contains(UUID_BOB));
    }

    @Test
    @Order(101)
    @DisplayName("[base] query(range('createdAt', from, null)) -> open upper bound returns newer players")
    void query_range_createdAt_openUpper_returnsNewerPlayers() {
        seedTimestampData();
        // from = T_MID - 1day, no upper bound -> Bob and Carol
        Instant from = T_MID.minus(1, ChronoUnit.DAYS);

        List<TestPlayer> found = repo.query(Query.range("createdAt", from, null)).join();
        assertEquals(2, found.size(), "Expected Bob and Carol after T_MID-1d");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_BOB));
        assertTrue(uuids.contains(UUID_CAROL));
    }

    @Test
    @Order(102)
    @DisplayName("[base] query(range('createdAt', null, to)) -> open lower bound returns older players")
    void query_range_createdAt_openLower_returnsOlderPlayers() {
        seedTimestampData();
        // no lower, to = T_MID + 1day -> Alice and Bob only
        Instant to = T_MID.plus(1, ChronoUnit.DAYS);

        List<TestPlayer> found = repo.query(Query.range("createdAt", null, to)).join();
        assertEquals(2, found.size(), "Expected Alice and Bob before T_MID+1d");
        List<UUID> uuids = found.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        assertTrue(uuids.contains(UUID_ALICE));
        assertTrue(uuids.contains(UUID_BOB));
    }

    @Test
    @Order(103)
    @DisplayName("[base] query(eq('createdAt', epochMillis)) -> exact epoch millis returns single match")
    void query_eq_createdAt_epochMillis_returnsExactMatch() {
        seedTimestampData();
        List<TestPlayer> found = repo.query(Query.eq("createdAt", T_OLD.toEpochMilli())).join();
        assertEquals(1, found.size(), "Expected only Alice when querying by exact epoch millis");
        assertEquals(UUID_ALICE, found.get(0).getUuid());
    }

    @Test
    @Order(104)
    @DisplayName("[base] query(range('createdAt', future, future)) -> future window returns empty list")
    void query_range_createdAt_noMatch_returnsEmpty() {
        seedTimestampData();
        // Future window - no player was created in the future
        Instant futureFrom = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant futureTo   = Instant.now().plus(2, ChronoUnit.DAYS);

        List<TestPlayer> found = repo.query(Query.range("createdAt", futureFrom, futureTo)).join();
        assertTrue(found.isEmpty(), "Expected empty list for a future time window");
    }

    // ------------------------------------------------------------------
    //  Storage log events (universal contract - specs/SPEC_storage_logging.md, secao 12)
    // ------------------------------------------------------------------

    /**
     * Installs a {@link CapturingSink} on this storage's <em>live</em> log config and raises
     * the default level to DEBUG so write/delete/query events become visible. Only affects
     * the current test's storage instance (fresh per test, closed in tearDown).
     */
    protected CapturingSink captureStorageLogs() {
        CapturingSink capture = new CapturingSink();
        storage.getStorageLogConfig()
            .defaultLevel(StorageLogLevel.DEBUG)
            .sink(capture);
        return capture;
    }

    @Test
    @Order(200)
    @DisplayName("[base] log: saveAll() emits exactly one SAVE_BATCH with affected=N")
    void log_saveAll_emitsSaveBatchWithCount() {
        CapturingSink capture = captureStorageLogs();

        repo.saveAll(Arrays.asList(alice(), bob(), carol())).join();

        List<StorageLogEvent> batches = capture.byOp(StorageOp.SAVE_BATCH);
        assertEquals(1, batches.size(), "saveAll() must emit exactly one SAVE_BATCH summary");
        assertEquals(3L, batches.get(0).affected(), "SAVE_BATCH must report the batch size");
    }

    @Test
    @Order(201)
    @DisplayName("[base] log: delete() emits DELETE with existed=true/false")
    void log_delete_emitsDeleteWithExisted() {
        repo.save(alice()).join();
        CapturingSink capture = captureStorageLogs();

        repo.delete(UUID_ALICE).join();   // present -> removed
        repo.delete(UUID_GHOST).join();   // absent

        List<StorageLogEvent> deletes = capture.byOp(StorageOp.DELETE);
        assertEquals(2, deletes.size(), "Each delete() must emit one DELETE event");
        assertTrue(deletes.get(0).detail().contains("existed=true"),
            "DELETE of an existing key must carry existed=true: " + deletes.get(0).format());
        assertTrue(deletes.get(1).detail().contains("existed=false"),
            "DELETE of an absent key must carry existed=false: " + deletes.get(1).format());
    }

    @Test
    @Order(202)
    @DisplayName("[base] log: privacy defaults - no event carries entity content, keys or query literals")
    void log_privacyDefaults_noEntityDataInEvents() {
        CapturingSink capture = captureStorageLogs();

        repo.save(alice()).join();
        repo.saveAll(Arrays.asList(bob(), carol())).join();
        repo.find(UUID_BOB).join();
        repo.query(Query.eq("name", "Carol")).join();
        repo.delete(UUID_ALICE).join();

        assertFalse(capture.events().isEmpty(), "DEBUG level must have produced events");
        for (StorageLogEvent event : capture.events()) {
            assertNull(event.keys(),  "includeKeys=false (default) must keep keys() null: " + event.format());
            assertNull(event.value(), "includeValues=false (default) must keep value() null: " + event.format());
            String line = event.format();
            assertFalse(line.contains("Alice") || line.contains("Bob") || line.contains("Carol"),
                "No log line may contain entity content or query literals by default: " + line);
            assertFalse(line.contains(UUID_ALICE.toString()) || line.contains(UUID_BOB.toString()),
                "No log line may contain entity keys by default: " + line);
        }
    }

    @Test
    @Order(203)
    @DisplayName("[base] log: includeKeys(true) opts the key into SAVE/DELETE events (still no content)")
    void log_includeKeys_addsKeyToWriteEvents() {
        CapturingSink capture = captureStorageLogs();
        storage.getStorageLogConfig().includeKeys(true);

        repo.save(alice()).join();
        repo.delete(UUID_ALICE).join();

        List<StorageLogEvent> saves = capture.byOp(StorageOp.SAVE);
        assertEquals(1, saves.size(), "save() must emit one SAVE event at DEBUG");
        assertNotNull(saves.get(0).keys(), "includeKeys=true must populate keys()");
        assertTrue(saves.get(0).keys().contains(UUID_ALICE.toString()));
        assertTrue(saves.get(0).format().contains(UUID_ALICE.toString()),
            "The rendered SAVE line must show the key when includeKeys=true");

        List<StorageLogEvent> deletes = capture.byOp(StorageOp.DELETE);
        assertEquals(1, deletes.size());
        assertTrue(deletes.get(0).keys().contains(UUID_ALICE.toString()),
            "The DELETE event must carry the key when includeKeys=true");

        // Keys are identity only - entity content must never leak, even opted in.
        for (StorageLogEvent event : capture.events()) {
            assertFalse(event.format().contains("Alice"),
                "includeKeys must never leak entity content: " + event.format());
        }
    }

    @Test
    @Order(204)
    @DisplayName("[base] log: query() emits QUERY with result count; literals only via includeQueryValues")
    void log_query_emitsQuerySummary() {
        seedIndexData();
        CapturingSink capture = captureStorageLogs();

        repo.query(Query.eq("world", "world_nether")).join();

        List<StorageLogEvent> queries = capture.byOp(StorageOp.QUERY);
        assertEquals(1, queries.size(), "query() must emit one QUERY event at DEBUG");
        StorageLogEvent event = queries.get(0);
        assertEquals(1L, event.affected(), "QUERY must report the result count");
        assertTrue(event.detail().contains("world EQ"),
            "QUERY detail must show fieldPath + operator: " + event.detail());
        assertFalse(event.format().contains("world_nether"),
            "Filter literals must stay hidden unless includeQueryValues=true: " + event.format());

        // Opt-in: literals become visible with includeQueryValues(true).
        storage.getStorageLogConfig().includeQueryValues(true);
        capture.clear();
        repo.query(Query.eq("world", "world_nether")).join();
        assertTrue(capture.byOp(StorageOp.QUERY).get(0).format().contains("world_nether"),
            "includeQueryValues=true must render the filter literal");
    }
}
