package br.com.finalcraft.evernifecore.storage.modules;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.HealthStatus;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Query;
import lombok.extern.java.Log;
import org.instancio.Instancio;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test suite for {@link Storage} and {@link Repository}.
 *
 * <p>Every storage backend should pass all of these tests by extending this class
 * and implementing {@link #createStorage()}.
 *
 * <p>Tests are ordered for readability; they are <em>not</em> interdependent -
 * each test starts from a clean storage via {@link #setUp()}.
 *
 * <p>All test display names are prefixed with {@code [base]} to distinguish them
 * from backend-specific tests in the JUnit report.
 */
@Log
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
    //  Helpers for named-database backends (SQL, Mongo)
    // ------------------------------------------------------------------

    /**
     * Scans {@code existingDbNames} for names matching {@code enc_NNN_*} and returns
     * the next run number ({@code max + 1}, minimum {@code 1}).
     *
     * <p>Each test run shares one run number so all databases of a given run have the
     * same numeric prefix, making them easy to group and identify in a DB browser.
     *
     * <p>Call once in {@code @BeforeAll} after the server is confirmed reachable.
     */
    public static int computeRunNumber(Collection<String> existingDbNames) {
        int max = 0;
        Pattern p = Pattern.compile("^enc_(\\d+)_.*");
        for (String name : existingDbNames) {
            Matcher m = p.matcher(name);
            if (m.matches()) {
                try { max = Math.max(max, Integer.parseInt(m.group(1))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    /**
     * Builds a database name of the form {@code enc_NNN_backend_methodName}.
     *
     * <ul>
     *   <li>{@code enc_} - project prefix</li>
     *   <li>{@code NNN} - zero-padded 3-digit run number (same for all tests in one run)</li>
     *   <li>{@code backend} - e.g. {@code "pg"}, {@code "my"}, {@code "mg"}</li>
     *   <li>{@code methodName} - JUnit test method name, truncated to 50 chars</li>
     * </ul>
     *
     * <p>Total length is at most 61 characters - within PostgreSQL's 63-char and
     * MySQL/MariaDB's 64-char database-name limits.
     */
    public static String buildDbName(String backend, int runNumber, String methodName) {
        String safe = methodName.length() > 50 ? methodName.substring(0, 50) : methodName;
        return String.format("enc_%03d_%s_%s", runNumber, backend, safe);
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

        for (TestPlayer testPlayer : found) {
            testPlayer.setName("FOUND_" + testPlayer.getName());
            repo.save(testPlayer);
        }
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

    @Test
    @Order(105)
    @DisplayName("[base] Stress Test - Massive Inserts + CRUD + Advanced Range Queries")
    void stressTestMassiveInsertsCRUDAndRangeQueries() {

        final int TOTAL_PLAYERS = 10_000;
        final int BATCH_SIZE    =  1_000;

        System.out.println("\n=================================================");
        System.out.println("STARTING MASSIVE RANGE QUERY TEST");
        System.out.println("=================================================");
        System.out.println("Total Records : " + TOTAL_PLAYERS);

        long globalStart = System.currentTimeMillis();

        // Phase-level timing checkpoints (pN = wall-clock at start of phase N)
        long p3Start = 0, p4Start = 0, p5Start = 0, p6Start = 0, p7Start = 0;
        long p8Start = 0, p9Start = 0, p10Start = 0, p11Start = 0, p11End = 0;
        // Timestamp query times collected in Phase 8 for min/max/avg stats in the summary
        List<Long> timestampQueryTimes = new ArrayList<>();
        // saveAll scenario tracking (Phase 9)
        long saveAllTotalSaved = 0;
        int  saveAllNewInserts = 0;
        // running expected count - starts at TOTAL_PLAYERS, grows when Phase 9D inserts new records
        long expectedCount = TOTAL_PLAYERS;

        // =========================================================
        // PHASE 1: DATASET GENERATION
        // =========================================================

        long stageStart = System.currentTimeMillis();

        List<TestPlayer> testPlayers = Instancio.ofList(TestPlayer.class)
            .size(TOTAL_PLAYERS)
            .create();

        Instant baseDate = Instant.now().minus(TOTAL_PLAYERS, ChronoUnit.DAYS);

        // Assign deterministic fields so all queries below have predictable results:
        //   score    = i            (0..TOTAL_PLAYERS-1)
        //   world    = "world_nether" when i%3==0, else "world"
        //   active   = true when i%2==0, false otherwise
        //   createdAt = baseDate + i days
        for (int i = 0; i < testPlayers.size(); i++) {
            TestPlayer p = testPlayers.get(i);
            p.setScore(i);
            p.setWorld(i % 3 == 0 ? "world_nether" : "world");
            p.setActive(i % 2 == 0);
            p.setCreatedAt(baseDate.plus(i, ChronoUnit.DAYS).toEpochMilli());
        }

        long stageEnd = System.currentTimeMillis();
        System.out.println("\n[DATASET GENERATION]");
        System.out.println("Generated    : " + testPlayers.size() + " players");
        System.out.println("Date range   : " + baseDate + "  ->  " + baseDate.plus(TOTAL_PLAYERS - 1, ChronoUnit.DAYS));
        System.out.println("Score range  : 0  ->  " + (TOTAL_PLAYERS - 1));
        System.out.println("Time         : " + (stageEnd - stageStart) + " ms");

        // Pre-compute expected sizes by streaming the local list (avoids hardcoding counts)
        long expectedWorldNether    = testPlayers.stream().filter(p -> "world_nether".equals(p.getWorld())).count();
        long expectedWorld          = TOTAL_PLAYERS - expectedWorldNether;
        long expectedActive         = testPlayers.stream().filter(TestPlayer::isActive).count();
        long expectedInactive       = TOTAL_PLAYERS - expectedActive;
        long expectedWorldAndActive = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.isActive()).count();
        long expectedWorldAndScore1000_1999 = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.getScore() >= 1000 && p.getScore() <= 1999).count();
        long expectedTripleAnd = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.isActive() && p.getScore() >= 2000 && p.getScore() <= 3999).count();

        System.out.println("world_nether : " + expectedWorldNether + " | world : " + expectedWorld);
        System.out.println("active=true  : " + expectedActive + " | active=false : " + expectedInactive);

        // =========================================================
        // PHASE 2: BULK INSERT (saveAll in batches)
        // =========================================================

        System.out.println("\n--- PHASE 2: BULK INSERT ---");
        stageStart = System.currentTimeMillis();
        int inserted = 0;
        for (int batchStart = 0; batchStart < TOTAL_PLAYERS; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, TOTAL_PLAYERS);
            repo.saveAll(testPlayers.subList(batchStart, batchEnd)).join();
            inserted += (batchEnd - batchStart);
            long elapsed = System.currentTimeMillis() - stageStart;
            System.out.printf("[INSERT] batch %,d-%,d | total=%,d | elapsed=%d ms%n",
                batchStart, batchEnd - 1, inserted, elapsed);
        }
        stageEnd = System.currentTimeMillis();
        long insertDuration = stageEnd - stageStart;
        System.out.printf("[INSERT SUMMARY] records=%,d | time=%d ms | ops/s=%.0f%n",
            inserted, insertDuration, inserted / Math.max(1.0, insertDuration / 1000.0));

        // =========================================================
        // PHASE 3: BASIC READ VERIFICATION
        // =========================================================

        p3Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 3: BASIC READS ---");

        // count()
        stageStart = System.currentTimeMillis();
        long count = repo.count().join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[COUNT] count=%,d | time=%d ms%n", count, (stageEnd - stageStart));
        assertEquals(TOTAL_PLAYERS, count, "count() must equal total inserted");

        // find first (index 0, score=0)
        stageStart = System.currentTimeMillis();
        TestPlayer foundFirst = repo.find(testPlayers.get(0).getUuid()).join()
            .orElseThrow(() -> new AssertionError("First player not found"));
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND FIRST] score=%d | time=%d ms%n", foundFirst.getScore(), (stageEnd - stageStart));
        assertEquals(0, foundFirst.getScore());

        // find middle (index 5000, score=5000)
        stageStart = System.currentTimeMillis();
        TestPlayer foundMiddle = repo.find(testPlayers.get(TOTAL_PLAYERS / 2).getUuid()).join()
            .orElseThrow(() -> new AssertionError("Middle player not found"));
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND MIDDLE] score=%d | time=%d ms%n", foundMiddle.getScore(), (stageEnd - stageStart));
        assertEquals(TOTAL_PLAYERS / 2, foundMiddle.getScore());

        // find last (index 9999, score=9999)
        stageStart = System.currentTimeMillis();
        TestPlayer foundLast = repo.find(testPlayers.get(TOTAL_PLAYERS - 1).getUuid()).join()
            .orElseThrow(() -> new AssertionError("Last player not found"));
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND LAST] score=%d | time=%d ms%n", foundLast.getScore(), (stageEnd - stageStart));
        assertEquals(TOTAL_PLAYERS - 1, foundLast.getScore());

        // exists() - present
        stageStart = System.currentTimeMillis();
        boolean existsAlice = repo.exists(testPlayers.get(42).getUuid()).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[EXISTS present] exists=%b | time=%d ms%n", existsAlice, (stageEnd - stageStart));
        assertTrue(existsAlice);

        // exists() - absent
        stageStart = System.currentTimeMillis();
        boolean existsGhost = repo.exists(UUID_GHOST).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[EXISTS absent] exists=%b | time=%d ms%n", existsGhost, (stageEnd - stageStart));
        assertFalse(existsGhost);

        // findMany - first 10
        List<UUID> first10Keys = testPlayers.subList(0, 10).stream()
            .map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundFirst10 = repo.findMany(first10Keys).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND_MANY 10] found=%d | time=%d ms%n", foundFirst10.size(), (stageEnd - stageStart));
        assertEquals(10, foundFirst10.size());

        // findMany - 100 around midpoint
        List<UUID> mid100Keys = testPlayers.subList(TOTAL_PLAYERS / 2, TOTAL_PLAYERS / 2 + 100).stream()
            .map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundMid100 = repo.findMany(mid100Keys).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND_MANY 100] found=%d | time=%d ms%n", foundMid100.size(), (stageEnd - stageStart));
        assertEquals(100, foundMid100.size());

        // findMany - mix of present + absent keys (ghost UUID is not stored)
        List<UUID> mixedKeys = Arrays.asList(
            testPlayers.get(0).getUuid(), testPlayers.get(1).getUuid(), UUID_GHOST);
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundMixed = repo.findMany(mixedKeys).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND_MANY mixed 3 keys, 1 absent] found=%d | time=%d ms%n",
            foundMixed.size(), (stageEnd - stageStart));
        assertEquals(2, foundMixed.size(), "Missing keys must be silently omitted");

        // all() - full stream
        stageStart = System.currentTimeMillis();
        long allCount = repo.all().join().count();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[ALL] count=%,d | time=%d ms%n", allCount, (stageEnd - stageStart));
        assertEquals(TOTAL_PLAYERS, allCount);

        // =========================================================
        // PHASE 4: SCORE RANGE QUERIES
        // =========================================================

        p4Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 4: SCORE QUERIES ---");

        // Dynamic indices derived from TOTAL_PLAYERS so queries remain valid for any size
        final int SCORE_MID      = TOTAL_PLAYERS / 2;
        final int SCORE_Q1       = TOTAL_PLAYERS / 4;
        final int SCORE_Q3       = TOTAL_PLAYERS * 3 / 4;
        final int SCORE_LAST     = TOTAL_PLAYERS - 1;
        // Open-upper threshold: score >= SCORE_UPPER_THRESHOLD
        // Expected count = TOTAL_PLAYERS - SCORE_UPPER_THRESHOLD (players at indices SCORE_UPPER_THRESHOLD..LAST)
        final int SCORE_UPPER_THRESHOLD = Math.max(1, TOTAL_PLAYERS - 1_000);

        // eq exact - one player per score value
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreEqMid = repo.query(Query.eq("score", SCORE_MID)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE =%d] found=%d | time=%d ms%n", SCORE_MID, scoreEqMid.size(), (stageEnd - stageStart));
        assertEquals(1, scoreEqMid.size(), "Exactly one player with score=" + SCORE_MID);
        assertEquals(SCORE_MID, scoreEqMid.get(0).getScore());

        // range [0, 999] = 1000 players (indices 0..999, always fixed)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> score0_999 = repo.query(Query.range("score", 0, 999)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE 0..999] found=%d | time=%d ms%n", score0_999.size(), (stageEnd - stageStart));
        assertEquals(1000, score0_999.size(), "Score range 0..999 should return 1000 players");

        // range [Q1, Q1+1999] = 2000 players (derived from TOTAL_PLAYERS)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> score2kRange = repo.query(Query.range("score", SCORE_Q1, SCORE_Q1 + 1999)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE %d..%d] found=%d | time=%d ms%n",
            SCORE_Q1, SCORE_Q1 + 1999, score2kRange.size(), (stageEnd - stageStart));
        assertEquals(2000, score2kRange.size(), "Score range Q1..Q1+1999 should return 2000 players");

        // open upper: score >= SCORE_UPPER_THRESHOLD = last 1000 players
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreGteThreshold = repo.query(Query.range("score", SCORE_UPPER_THRESHOLD, null)).join();
        stageEnd = System.currentTimeMillis();
        int expectedGte = TOTAL_PLAYERS - SCORE_UPPER_THRESHOLD;
        System.out.printf("[SCORE >=%d] found=%d | expected=%d | time=%d ms%n",
            SCORE_UPPER_THRESHOLD, scoreGteThreshold.size(), expectedGte, (stageEnd - stageStart));
        assertEquals(expectedGte, scoreGteThreshold.size(),
            "Score >= " + SCORE_UPPER_THRESHOLD + " should return last " + expectedGte + " players");

        // open lower: score <= 999 = first 1000 players (always fixed)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreLte999 = repo.query(Query.range("score", null, 999)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE <=999] found=%d | time=%d ms%n", scoreLte999.size(), (stageEnd - stageStart));
        assertEquals(1000, scoreLte999.size(), "Score <= 999 should return 1000 players");

        // IN list of 3 scores - use 0, midpoint, and last (all guaranteed to exist)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreIn3 = repo.query(Query.in("score", 0, SCORE_MID, SCORE_LAST)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE IN [0,%d,%d]] found=%d | time=%d ms%n",
            SCORE_MID, SCORE_LAST, scoreIn3.size(), (stageEnd - stageStart));
        assertEquals(3, scoreIn3.size(), "IN query for 3 distinct scores should return 3 players");

        // IN list of 5 scores - use quartile boundaries (all guaranteed to exist)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreIn5 = repo.query(
            Query.in("score", Arrays.asList(0, SCORE_Q1, SCORE_MID, SCORE_Q3, SCORE_LAST))).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE IN [0,%d,%d,%d,%d]] found=%d | time=%d ms%n",
            SCORE_Q1, SCORE_MID, SCORE_Q3, SCORE_LAST, scoreIn5.size(), (stageEnd - stageStart));
        assertEquals(5, scoreIn5.size(), "IN query for 5 distinct scores should return 5 players");

        // =========================================================
        // PHASE 5: BOOLEAN QUERIES
        // =========================================================

        p5Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 5: BOOLEAN QUERIES ---");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> activeTrue = repo.query(Query.eq("active", true)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[ACTIVE=true] found=%d | expected=%d | time=%d ms%n",
            activeTrue.size(), expectedActive, (stageEnd - stageStart));
        assertEquals(expectedActive, activeTrue.size(), "active=true count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> activeFalse = repo.query(Query.eq("active", false)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[ACTIVE=false] found=%d | expected=%d | time=%d ms%n",
            activeFalse.size(), expectedInactive, (stageEnd - stageStart));
        assertEquals(expectedInactive, activeFalse.size(), "active=false count mismatch");

        // Combined active+inactive must equal total
        assertEquals(TOTAL_PLAYERS, activeTrue.size() + activeFalse.size(),
            "active=true + active=false must sum to TOTAL_PLAYERS");

        // =========================================================
        // PHASE 6: WORLD STRING QUERIES
        // =========================================================

        p6Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 6: WORLD STRING QUERIES ---");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> qWorldNether = repo.query(Query.eq("world", "world_nether")).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[WORLD=world_nether] found=%d | expected=%d | time=%d ms%n",
            qWorldNether.size(), expectedWorldNether, (stageEnd - stageStart));
        assertEquals(expectedWorldNether, qWorldNether.size(), "world=world_nether count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> qWorld = repo.query(Query.eq("world", "world")).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[WORLD=world] found=%d | expected=%d | time=%d ms%n",
            qWorld.size(), expectedWorld, (stageEnd - stageStart));
        assertEquals(expectedWorld, qWorld.size(), "world=world count mismatch");

        // findBy shorthand must agree with query.eq
        stageStart = System.currentTimeMillis();
        List<TestPlayer> findByWorldNether = repo.findBy("world", "world_nether").join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FINDBY world=world_nether] found=%d | time=%d ms%n",
            findByWorldNether.size(), (stageEnd - stageStart));
        assertEquals(expectedWorldNether, findByWorldNether.size(), "findBy must agree with query.eq");

        // IN both worlds = all players
        stageStart = System.currentTimeMillis();
        List<TestPlayer> worldInBoth = repo.query(
            Query.in("world", Arrays.asList("world", "world_nether"))).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[WORLD IN [world, world_nether]] found=%d | time=%d ms%n",
            worldInBoth.size(), (stageEnd - stageStart));
        assertEquals(TOTAL_PLAYERS, worldInBoth.size(), "IN for both world values must return all players");

        // =========================================================
        // PHASE 7: COMPOUND AND QUERIES
        // =========================================================

        p7Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 7: COMPOUND AND QUERIES ---");

        // world=world AND active=true
        stageStart = System.currentTimeMillis();
        List<TestPlayer> worldAndActive = repo.query(
            Query.eq("world", "world").and(Query.eq("active", true))).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[world=world AND active=true] found=%d | expected=%d | time=%d ms%n",
            worldAndActive.size(), expectedWorldAndActive, (stageEnd - stageStart));
        assertEquals(expectedWorldAndActive, worldAndActive.size(), "world AND active count mismatch");

        // world=world AND score [1000, 1999]
        stageStart = System.currentTimeMillis();
        List<TestPlayer> worldAndScoreRange = repo.query(
            Query.eq("world", "world").and(Query.range("score", 1000, 1999))).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[world=world AND score 1000..1999] found=%d | expected=%d | time=%d ms%n",
            worldAndScoreRange.size(), expectedWorldAndScore1000_1999, (stageEnd - stageStart));
        assertEquals(expectedWorldAndScore1000_1999, worldAndScoreRange.size(), "world AND score range count mismatch");

        // Triple AND: world=world AND active=true AND score [2000, 3999]
        stageStart = System.currentTimeMillis();
        List<TestPlayer> tripleAnd = repo.query(
            Query.eq("world", "world").and(Query.eq("active", true)).and(Query.range("score", 2000, 3999))).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[world=world AND active=true AND score 2000..3999] found=%d | expected=%d | time=%d ms%n",
            tripleAnd.size(), expectedTripleAnd, (stageEnd - stageStart));
        assertEquals(expectedTripleAnd, tripleAnd.size(), "triple AND count mismatch");

        // =========================================================
        // PHASE 8: TIMESTAMP (createdAt) RANGE SCENARIOS
        // =========================================================
        //
        // Players have indices 0..TOTAL_PLAYERS-1 with createdAt = baseDate + i days.
        // All fixed offsets below are within range for any TOTAL_PLAYERS >= 10_000.

        p8Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 8: TIMESTAMP RANGE SCENARIOS ---");

        // Dataset spans: [baseDate, baseDate + (TOTAL_PLAYERS-1) days]
        // "Empty before" and "empty after" are derived from baseDate, NOT from absolute calendar
        // years, so they remain valid regardless of TOTAL_PLAYERS.
        Instant datasetStart = baseDate;
        Instant datasetEnd   = baseDate.plus(TOTAL_PLAYERS - 1L, ChronoUnit.DAYS);

        List<RangeScenario> scenarios = List.of(

            // All TOTAL_PLAYERS records (1 day padding on each side)
            new RangeScenario(
                "FULL DATASET",
                datasetStart.minus(1, ChronoUnit.DAYS),
                datasetEnd.plus(1, ChronoUnit.DAYS),
                TOTAL_PLAYERS
            ),

            // indices 0..99 inclusive -> 100 records
            new RangeScenario(
                "FIRST 100 RECORDS",
                datasetStart,
                datasetStart.plus(99, ChronoUnit.DAYS),
                100
            ),

            // indices TOTAL-100..TOTAL-1 inclusive -> 100 records
            new RangeScenario(
                "LAST 100 RECORDS",
                datasetEnd.minus(99, ChronoUnit.DAYS),
                datasetEnd,
                100
            ),

            // indices 4500..5500 inclusive -> 1001 records
            new RangeScenario(
                "MIDDLE WINDOW 1001 RECORDS",
                datasetStart.plus(4_500, ChronoUnit.DAYS),
                datasetStart.plus(5_500, ChronoUnit.DAYS),
                1_001
            ),

            // single record at index 5000
            new RangeScenario(
                "EXACT SINGLE RECORD",
                datasetStart.plus(5_000, ChronoUnit.DAYS),
                datasetStart.plus(5_000, ChronoUnit.DAYS),
                1
            ),

            // 100 days after dataset end - guaranteed empty for any TOTAL_PLAYERS
            new RangeScenario(
                "FUTURE RANGE EMPTY",
                datasetEnd.plus(1,   ChronoUnit.DAYS),
                datasetEnd.plus(100, ChronoUnit.DAYS),
                0
            ),

            // 100 days before dataset start - guaranteed empty for any TOTAL_PLAYERS
            new RangeScenario(
                "PAST RANGE EMPTY",
                datasetStart.minus(100, ChronoUnit.DAYS),
                datasetStart.minus(1,   ChronoUnit.DAYS),
                0
            ),

            // open upper: index 9000 to end -> TOTAL_PLAYERS - 9_000 records
            new RangeScenario(
                "OPEN UPPER FROM INDEX 9000",
                datasetStart.plus(9_000, ChronoUnit.DAYS),
                null,
                TOTAL_PLAYERS - 9_000
            ),

            // open lower: beginning to index 4999 -> 5000 records
            new RangeScenario(
                "OPEN LOWER TO INDEX 4999",
                null,
                datasetStart.plus(4_999, ChronoUnit.DAYS),
                5_000
            ),

            // tiny range: indices 500..504 = 5 records
            new RangeScenario(
                "TINY RANGE 5 RECORDS",
                datasetStart.plus(500, ChronoUnit.DAYS),
                datasetStart.plus(504, ChronoUnit.DAYS),
                5
            ),

            // large range: indices 2000..6999 = 5000 records
            new RangeScenario(
                "LARGE RANGE 5K",
                datasetStart.plus(2_000, ChronoUnit.DAYS),
                datasetStart.plus(6_999, ChronoUnit.DAYS),
                5_000
            )
        );

        int scenarioNumber = 1;
        for (RangeScenario scenario : scenarios) {
            System.out.println("\n-------------------------------------------------");
            System.out.printf("SCENARIO #%d: %s%n", scenarioNumber++, scenario.name);
            System.out.println("From           : " + scenario.from);
            System.out.println("To             : " + scenario.to);
            System.out.println("Expected Count : " + scenario.expectedCount);

            long queryStart = System.currentTimeMillis();
            List<TestPlayer> found = repo.query(
                Query.range("createdAt", scenario.from, scenario.to)).join();
            long queryEnd = System.currentTimeMillis();
            long scenarioMs = queryEnd - queryStart;
            timestampQueryTimes.add(scenarioMs);

            System.out.println("Returned Count : " + found.size());
            System.out.println("Query Time     : " + scenarioMs + " ms");

            if (!found.isEmpty()) {
                long minTs = found.stream().mapToLong(TestPlayer::getCreatedAt).min().orElse(0);
                long maxTs = found.stream().mapToLong(TestPlayer::getCreatedAt).max().orElse(0);
                System.out.println("Min Timestamp  : " + Instant.ofEpochMilli(minTs));
                System.out.println("Max Timestamp  : " + Instant.ofEpochMilli(maxTs));
            }

            assertEquals(scenario.expectedCount, found.size(),
                "Timestamp scenario '" + scenario.name + "' count mismatch");
            System.out.println("[OK] Passed");
        }

        // =========================================================
        // PHASE 9: saveAll SCENARIOS
        // =========================================================
        //
        // Each scenario follows the same pattern:
        //   1. Read entities via query/findMany
        //   2. Mutate the returned objects (field change)
        //   3. Write back via saveAll (in batches where the set is large)
        //   4. Assert that both old and new index values are correct
        //
        // This exercises the full read->mutate->saveAll->requery lifecycle and
        // confirms secondary index consistency across all backends.

        p9Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 9: saveAll SCENARIOS ---");

        // ---- SCENARIO 9A: world migration  (world_nether -> world_end) ----
        System.out.println("\n  [9A] World migration: world_nether -> world_end");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> netherPlayers = repo.query(Query.eq("world", "world_nether")).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9A] read world_nether=%,d | time=%d ms%n",
            netherPlayers.size(), stageEnd - stageStart);
        assertEquals(expectedWorldNether, netherPlayers.size(), "9A: pre-condition world_nether count");

        for (TestPlayer p : netherPlayers) p.setWorld("world_end");

        stageStart = System.currentTimeMillis();
        for (int batchStart = 0; batchStart < netherPlayers.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, netherPlayers.size());
            repo.saveAll(netherPlayers.subList(batchStart, batchEnd)).join();
        }
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9A] saveAll world_end migration=%,d records | time=%d ms%n",
            netherPlayers.size(), stageEnd - stageStart);
        saveAllTotalSaved += netherPlayers.size();

        assertEquals(0, repo.query(Query.eq("world", "world_nether")).join().size(),
            "9A: world_nether index must be empty after migration");
        assertEquals(expectedWorldNether, repo.query(Query.eq("world", "world_end")).join().size(),
            "9A: world_end index must hold all migrated records");
        assertEquals(expectedCount, repo.count().join(),
            "9A: total count must be unchanged after world migration");
        System.out.println("  [9A] PASSED - world index correctly remapped");

        // ---- SCENARIO 9B: boolean batch toggle  (active=false -> active=true) ----
        System.out.println("\n  [9B] Mass activation: all inactive -> active");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> inactivePlayers = repo.query(Query.eq("active", false)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9B] read active=false=%,d | time=%d ms%n",
            inactivePlayers.size(), stageEnd - stageStart);
        assertEquals(expectedInactive, inactivePlayers.size(), "9B: pre-condition inactive count");

        for (TestPlayer p : inactivePlayers) p.setActive(true);

        stageStart = System.currentTimeMillis();
        for (int batchStart = 0; batchStart < inactivePlayers.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, inactivePlayers.size());
            repo.saveAll(inactivePlayers.subList(batchStart, batchEnd)).join();
        }
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9B] saveAll activate=%,d records | time=%d ms%n",
            inactivePlayers.size(), stageEnd - stageStart);
        saveAllTotalSaved += inactivePlayers.size();

        assertEquals(0, repo.query(Query.eq("active", false)).join().size(),
            "9B: active=false must be empty after mass activation");
        assertEquals(expectedCount, repo.query(Query.eq("active", true)).join().size(),
            "9B: active=true must equal total player count");
        assertEquals(expectedCount, repo.count().join(),
            "9B: total count must be unchanged after active toggle");
        System.out.println("  [9B] PASSED - boolean index correctly updated for all records");

        // ---- SCENARIO 9C: integer score shift  (middle 10% -> +1_000_000) ----
        // Range is derived so it stays within [0, TOTAL_PLAYERS-1] for any dataset size.
        // Must not overlap the Phase 10 bulk-update range [0, 999].
        final int SHIFT_FROM   = TOTAL_PLAYERS / 2;
        final int SHIFT_TO     = SHIFT_FROM + Math.max(100, TOTAL_PLAYERS / 10) - 1;
        final int SCORE_OFFSET = 1_000_000;

        System.out.printf("%n  [9C] Score shift: [%,d, %,d] -> [%,d, %,d]%n",
            SHIFT_FROM, SHIFT_TO, SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET);

        stageStart = System.currentTimeMillis();
        List<TestPlayer> toShift = repo.query(Query.range("score", SHIFT_FROM, SHIFT_TO)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9C] read score[%,d..%,d]=%,d | time=%d ms%n",
            SHIFT_FROM, SHIFT_TO, toShift.size(), stageEnd - stageStart);
        assertEquals(SHIFT_TO - SHIFT_FROM + 1, toShift.size(), "9C: pre-condition shift range count");

        for (TestPlayer p : toShift) p.setScore(p.getScore() + SCORE_OFFSET);

        stageStart = System.currentTimeMillis();
        for (int batchStart = 0; batchStart < toShift.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, toShift.size());
            repo.saveAll(toShift.subList(batchStart, batchEnd)).join();
        }
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9C] saveAll score shift=%,d records | time=%d ms%n",
            toShift.size(), stageEnd - stageStart);
        saveAllTotalSaved += toShift.size();

        stageStart = System.currentTimeMillis();
        List<TestPlayer> oldRangeAfterShift = repo.query(Query.range("score", SHIFT_FROM, SHIFT_TO)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9C] old range [%,d..%,d] after shift=%d | time=%d ms%n",
            SHIFT_FROM, SHIFT_TO, oldRangeAfterShift.size(), stageEnd - stageStart);
        assertEquals(0, oldRangeAfterShift.size(), "9C: old score range must be empty after shift");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> newRangeAfterShift = repo.query(
            Query.range("score", SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9C] new range [%,d..%,d] after shift=%,d | time=%d ms%n",
            SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET, newRangeAfterShift.size(), stageEnd - stageStart);
        assertEquals(SHIFT_TO - SHIFT_FROM + 1, newRangeAfterShift.size(),
            "9C: new score range must hold all shifted records");
        assertTrue(newRangeAfterShift.stream().allMatch(
            p -> p.getScore() >= SHIFT_FROM + SCORE_OFFSET && p.getScore() <= SHIFT_TO + SCORE_OFFSET),
            "9C: all records in new range must have shifted score value");
        assertEquals(expectedCount, repo.count().join(),
            "9C: total count must be unchanged after score shift");
        System.out.println("  [9C] PASSED - integer index correctly remapped after score shift");

        // ---- SCENARIO 9D: mixed saveAll  (500 brand-new + 500 updated existing in one call) ----
        // This specifically tests that saveAll correctly handles a batch that contains both
        // entirely new keys (INSERT) and existing keys (UPDATE) in a single call.

        final int NEW_PLAYER_COUNT    = 500;
        final int NEW_PLAYER_SCORE    = 2_000_000;   // never collides with natural [0,TOTAL_PLAYERS-1]
        final int EXISTING_UPD_SCORE  = 3_000_000;   // never collides with natural scores
        // 40% mark - always below SHIFT_FROM (50%) and above Phase 10 bulk-update range [0,999]
        final int MIXED_RANGE_FROM    = TOTAL_PLAYERS * 4 / 10;
        final int MIXED_RANGE_TO      = MIXED_RANGE_FROM + NEW_PLAYER_COUNT - 1;

        System.out.printf("%n  [9D] Mixed saveAll: %d new (score=%,d) + %d updated existing (score %,d..%,d -> %,d)%n",
            NEW_PLAYER_COUNT, NEW_PLAYER_SCORE, NEW_PLAYER_COUNT,
            MIXED_RANGE_FROM, MIXED_RANGE_TO, EXISTING_UPD_SCORE);

        // Create brand-new players (fresh random UUIDs, not in repo yet)
        List<TestPlayer> newPlayers = Instancio.ofList(TestPlayer.class).size(NEW_PLAYER_COUNT).create();
        for (TestPlayer p : newPlayers) p.setScore(NEW_PLAYER_SCORE);

        // Read 500 existing players from a range that was not touched by 9A/9B/9C
        stageStart = System.currentTimeMillis();
        List<TestPlayer> existingToMix = repo.query(
            Query.range("score", MIXED_RANGE_FROM, MIXED_RANGE_TO)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9D] read %,d existing players | time=%d ms%n",
            existingToMix.size(), stageEnd - stageStart);
        assertEquals(NEW_PLAYER_COUNT, existingToMix.size(),
            "9D: must read exactly " + NEW_PLAYER_COUNT + " existing players for the mix");
        for (TestPlayer p : existingToMix) p.setScore(EXISTING_UPD_SCORE);

        // Single saveAll call with both new and updated-existing entities
        List<TestPlayer> mixedBatch = new ArrayList<>(NEW_PLAYER_COUNT * 2);
        mixedBatch.addAll(newPlayers);
        mixedBatch.addAll(existingToMix);

        long countBeforeMixed = repo.count().join();
        stageStart = System.currentTimeMillis();
        repo.saveAll(mixedBatch).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9D] saveAll %,d mixed entities (%,d new + %,d updated) | time=%d ms%n",
            mixedBatch.size(), NEW_PLAYER_COUNT, existingToMix.size(), stageEnd - stageStart);
        saveAllTotalSaved += mixedBatch.size();
        saveAllNewInserts  = NEW_PLAYER_COUNT;
        expectedCount     += NEW_PLAYER_COUNT;

        long countAfterMixed = repo.count().join();
        System.out.printf("  [9D] count: before=%,d | after=%,d | diff=%d%n",
            countBeforeMixed, countAfterMixed, countAfterMixed - countBeforeMixed);
        assertEquals(expectedCount, countAfterMixed,
            "9D: count must increase by " + NEW_PLAYER_COUNT + " (new inserts only)");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundNew = repo.query(Query.eq("score", NEW_PLAYER_SCORE)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9D] score=%,d -> found=%,d | time=%d ms%n",
            NEW_PLAYER_SCORE, foundNew.size(), stageEnd - stageStart);
        assertEquals(NEW_PLAYER_COUNT, foundNew.size(),
            "9D: new players must be queryable by score=" + NEW_PLAYER_SCORE);

        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundUpdated = repo.query(Query.eq("score", EXISTING_UPD_SCORE)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("  [9D] score=%,d -> found=%,d | time=%d ms%n",
            EXISTING_UPD_SCORE, foundUpdated.size(), stageEnd - stageStart);
        assertEquals(NEW_PLAYER_COUNT, foundUpdated.size(),
            "9D: updated existing players must be at score=" + EXISTING_UPD_SCORE);

        List<TestPlayer> oldMixRange = repo.query(
            Query.range("score", MIXED_RANGE_FROM, MIXED_RANGE_TO)).join();
        assertEquals(0, oldMixRange.size(),
            "9D: old score range [" + MIXED_RANGE_FROM + ".." + MIXED_RANGE_TO + "] must be empty after mixed saveAll");

        System.out.printf("  [9D] PASSED - mixed saveAll correctly inserted %,d new and updated %,d existing records%n",
            NEW_PLAYER_COUNT, NEW_PLAYER_COUNT);
        System.out.printf("%n[PHASE 9 SUMMARY] total saveAll writes=%,d | new inserts=%,d | repo size=%,d%n",
            saveAllTotalSaved, saveAllNewInserts, expectedCount);

        // =========================================================
        // PHASE 10: BULK UPDATE
        // =========================================================

        p10Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 10: BULK UPDATE ---");

        // Sentinel scores must be negative (always outside natural range [0, TOTAL_PLAYERS-1])
        // so they never collide with any existing player score regardless of TOTAL_PLAYERS.
        final int BULK_UPDATE_SCORE   = -1;
        final int SINGLE_UPDATE_SCORE = -2;

        // Fetch players with score in [0, 999] (indices 0..999 -> always 1000 players)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> toUpdate = repo.query(Query.range("score", 0, 999)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[PRE-UPDATE QUERY score 0..999] found=%d | time=%d ms%n",
            toUpdate.size(), (stageEnd - stageStart));
        assertEquals(1000, toUpdate.size(), "Pre-update query must return 1000 players");

        // Update score -> BULK_UPDATE_SCORE (-1) and save in batches
        for (TestPlayer p : toUpdate) {
            p.setScore(BULK_UPDATE_SCORE);
        }
        stageStart = System.currentTimeMillis();
        for (int batchStart = 0; batchStart < toUpdate.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, toUpdate.size());
            repo.saveAll(toUpdate.subList(batchStart, batchEnd)).join();
        }
        stageEnd = System.currentTimeMillis();
        System.out.printf("[BULK UPDATE -> score=%d] updated=%d | time=%d ms%n",
            BULK_UPDATE_SCORE, toUpdate.size(), (stageEnd - stageStart));

        // count must be unchanged (upsert, not insert)
        stageStart = System.currentTimeMillis();
        long countAfterUpdate = repo.count().join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[COUNT AFTER UPDATE] count=%,d | time=%d ms%n", countAfterUpdate, (stageEnd - stageStart));
        assertEquals(expectedCount, countAfterUpdate, "count() must not change after upsert");

        // old range must be empty now (all were moved to BULK_UPDATE_SCORE)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> score0_999afterUpdate = repo.query(Query.range("score", 0, 999)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE 0..999 AFTER UPDATE] found=%d | time=%d ms%n",
            score0_999afterUpdate.size(), (stageEnd - stageStart));
        assertEquals(0, score0_999afterUpdate.size(), "Score 0..999 must be empty after update");

        // sentinel value must return exactly the 1000 updated players
        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreUpdated = repo.query(Query.eq("score", BULK_UPDATE_SCORE)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SCORE=%d AFTER UPDATE] found=%d | time=%d ms%n",
            BULK_UPDATE_SCORE, scoreUpdated.size(), (stageEnd - stageStart));
        assertEquals(1000, scoreUpdated.size(), "score=" + BULK_UPDATE_SCORE + " must return 1000 updated players");

        // spot-check: all returned records actually have BULK_UPDATE_SCORE
        assertTrue(scoreUpdated.stream().allMatch(p -> p.getScore() == BULK_UPDATE_SCORE),
            "All returned players must have score=" + BULK_UPDATE_SCORE);

        // single record update + verify (use midpoint index - guaranteed not in 0..999 batch)
        int singleTargetIdx = TOTAL_PLAYERS / 2;
        TestPlayer singleTarget = testPlayers.get(singleTargetIdx);
        singleTarget.setScore(SINGLE_UPDATE_SCORE);
        stageStart = System.currentTimeMillis();
        repo.save(singleTarget).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[SINGLE UPDATE index %d -> score=%d] time=%d ms%n",
            singleTargetIdx, SINGLE_UPDATE_SCORE, (stageEnd - stageStart));

        stageStart = System.currentTimeMillis();
        TestPlayer reloaded = repo.find(singleTarget.getUuid()).join()
            .orElseThrow(() -> new AssertionError("Updated player not found"));
        stageEnd = System.currentTimeMillis();
        System.out.printf("[RELOAD AFTER SINGLE UPDATE] score=%d | time=%d ms%n",
            reloaded.getScore(), (stageEnd - stageStart));
        assertEquals(SINGLE_UPDATE_SCORE, reloaded.getScore(), "Score should be " + SINGLE_UPDATE_SCORE + " after single update");

        // =========================================================
        // PHASE 10: DELETE OPERATIONS
        // =========================================================

        p11Start = System.currentTimeMillis();
        System.out.println("\n--- PHASE 11: DELETE OPERATIONS ---");

        // delete non-existent - must return false
        stageStart = System.currentTimeMillis();
        boolean deletedGhost = repo.delete(UUID_GHOST).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[DELETE NON-EXISTENT] deleted=%b | time=%d ms%n", deletedGhost, (stageEnd - stageStart));
        assertFalse(deletedGhost, "delete() must return false for absent key");
        assertEquals(expectedCount, repo.count().join(), "count must not change after failed delete");

        // single delete - last player (index TOTAL_PLAYERS-1)
        int lastIdx = TOTAL_PLAYERS - 1;
        UUID lastUuid = testPlayers.get(lastIdx).getUuid();
        stageStart = System.currentTimeMillis();
        boolean deletedLast = repo.delete(lastUuid).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[DELETE SINGLE index %d] deleted=%b | time=%d ms%n",
            lastIdx, deletedLast, (stageEnd - stageStart));
        assertTrue(deletedLast, "delete() must return true for existing player");
        assertFalse(repo.find(lastUuid).join().isPresent(), "Deleted player must not be findable");

        long countAfterSingleDelete = repo.count().join();
        System.out.printf("[COUNT AFTER SINGLE DELETE] count=%,d%n", countAfterSingleDelete);
        assertEquals(expectedCount - 1, countAfterSingleDelete);

        // delete same key again - must return false
        stageStart = System.currentTimeMillis();
        boolean deletedAgain = repo.delete(lastUuid).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[DELETE ALREADY-DELETED] deleted=%b | time=%d ms%n", deletedAgain, (stageEnd - stageStart));
        assertFalse(deletedAgain, "Second delete of same key must return false");

        // bulk delete: indices 9000..9099 (100 players, none previously deleted)
        final int BULK_DEL_FROM = 9_000;
        final int BULK_DEL_TO   = 9_099;
        List<TestPlayer> toDelete = testPlayers.subList(BULK_DEL_FROM, BULK_DEL_TO + 1);

        stageStart = System.currentTimeMillis();
        int deletedBulkCount = 0;
        for (TestPlayer p : toDelete) {
            if (repo.delete(p.getUuid()).join()) {
                deletedBulkCount++;
            }
        }
        stageEnd = System.currentTimeMillis();
        System.out.printf("[BULK DELETE 100 idx %d..%d] deleted=%d | time=%d ms%n",
            BULK_DEL_FROM, BULK_DEL_TO, deletedBulkCount, (stageEnd - stageStart));
        assertEquals(100, deletedBulkCount, "All 100 players in bulk range must be deleted");

        long countAfterBulkDelete = repo.count().join();
        System.out.printf("[COUNT AFTER BULK DELETE] count=%,d%n", countAfterBulkDelete);
        assertEquals(expectedCount - 1 - 100, countAfterBulkDelete, "count must reflect 101 total deletions");

        // findMany on deleted UUIDs must return empty list
        List<UUID> deletedUuids = toDelete.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> shouldBeEmpty = repo.findMany(deletedUuids).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[FIND_MANY DELETED %d uuids] found=%d | time=%d ms%n",
            deletedUuids.size(), shouldBeEmpty.size(), (stageEnd - stageStart));
        assertTrue(shouldBeEmpty.isEmpty(), "findMany on deleted keys must return empty list");

        // query over the deleted time range must return 0
        Instant delFrom = baseDate.plus(BULK_DEL_FROM, ChronoUnit.DAYS);
        Instant delTo   = baseDate.plus(BULK_DEL_TO,   ChronoUnit.DAYS);
        stageStart = System.currentTimeMillis();
        List<TestPlayer> deletedRangeQuery = repo.query(Query.range("createdAt", delFrom, delTo)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[QUERY DELETED TIME RANGE] found=%d | time=%d ms%n",
            deletedRangeQuery.size(), (stageEnd - stageStart));
        assertEquals(0, deletedRangeQuery.size(), "Query over deleted time range must return 0");

        // query for score range of deleted players must return 0
        // (indices 9000..9099 had score 9000..9099, none were in the updated 0..999 batch)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> deletedScoreRange = repo.query(Query.range("score", BULK_DEL_FROM, BULK_DEL_TO)).join();
        stageEnd = System.currentTimeMillis();
        System.out.printf("[QUERY DELETED SCORE RANGE %d..%d] found=%d | time=%d ms%n",
            BULK_DEL_FROM, BULK_DEL_TO, deletedScoreRange.size(), (stageEnd - stageStart));
        assertEquals(0, deletedScoreRange.size(), "Score query for deleted range must return 0");

        // final count verification
        long finalCount = repo.count().join();
        System.out.printf("[FINAL COUNT] count=%,d%n", finalCount);
        assertEquals(expectedCount - 101, finalCount, "Final count: TOTAL + P9D inserts - 1 single - 100 bulk = " + (expectedCount - 101));

        // =========================================================
        // FINAL SUMMARY
        // =========================================================

        p11End = System.currentTimeMillis();
        long globalEnd = p11End;
        long totalMs   = globalEnd - globalStart;

        // Per-phase durations (ms)
        long phaseInsertMs    = insertDuration;
        long phaseReadsMs     = p4Start - p3Start;
        long phaseScoreMs     = p5Start - p4Start;
        long phaseBoolMs      = p6Start - p5Start;
        long phaseWorldMs     = p7Start - p6Start;
        long phaseAndMs       = p8Start - p7Start;
        long phaseTimestampMs = p9Start - p8Start;
        long phaseSaveAllMs   = p10Start - p9Start;
        long phaseUpdateMs    = p11Start - p10Start;
        long phaseDeleteMs    = p11End   - p11Start;

        // Timestamp scenario query stats
        java.util.LongSummaryStatistics tsStats = timestampQueryTimes.stream()
            .mapToLong(Long::longValue)
            .summaryStatistics();

        // Total query count across all query phases (score + bool + world + AND + timestamp)
        // Phase 4: 7 queries, Phase 5: 2, Phase 6: 4, Phase 7: 3, Phase 8: scenario count
        int totalQueryCount = 7 + 2 + 4 + 3 + (int) tsStats.getCount();
        long totalQueryMs   = phaseScoreMs + phaseBoolMs + phaseWorldMs + phaseAndMs + phaseTimestampMs;

        System.out.println("\n=================================================");
        System.out.println("FINAL SUMMARY");
        System.out.println("=================================================");

        System.out.println("\n[DATASET]");
        System.out.printf("  Total Players    : %,d%n", TOTAL_PLAYERS);
        System.out.printf("  Batch Size       : %,d%n", BATCH_SIZE);
        System.out.printf("  Score range      : 0 .. %,d%n", TOTAL_PLAYERS - 1);
        System.out.printf("  world_nether     : %,d  |  world : %,d%n", expectedWorldNether, expectedWorld);
        System.out.printf("  active=true      : %,d  |  active=false : %,d%n", expectedActive, expectedInactive);

        System.out.println("\n[RECORD LIFECYCLE]");
        System.out.printf("  Inserted         : %,d%n", TOTAL_PLAYERS);
        System.out.printf("  SaveAll updated  : %,d (9A world remap + 9B active toggle + 9C score shift + 9D existing)%n",
            saveAllTotalSaved - saveAllNewInserts);
        System.out.printf("  SaveAll inserted : %,d (9D new entities)%n", saveAllNewInserts);
        System.out.printf("  Updated bulk     : 1,000 (score 0..999 -> %d)%n", BULK_UPDATE_SCORE);
        System.out.printf("  Updated single   : 1 (idx %,d -> score %d)%n", singleTargetIdx, SINGLE_UPDATE_SCORE);
        System.out.printf("  Deleted single   : 1 (idx %,d)%n", lastIdx);
        System.out.printf("  Deleted bulk     : 100 (idx %,d..%,d)%n", BULK_DEL_FROM, BULK_DEL_TO);
        System.out.printf("  Remaining        : %,d%n", finalCount);

        System.out.println("\n[THROUGHPUT]");
        double insertOpsPerSec   = TOTAL_PLAYERS / Math.max(1.0, phaseInsertMs / 1000.0);
        double insertMsPerRec    = phaseInsertMs / (double) TOTAL_PLAYERS;
        System.out.printf("  Insert           : %,d records in %,d ms  ->  %,.0f ops/s  |  %.3f ms/record%n",
            TOTAL_PLAYERS, phaseInsertMs, insertOpsPerSec, insertMsPerRec);

        double saveAllOpsPerSec  = saveAllTotalSaved / Math.max(1.0, phaseSaveAllMs / 1000.0);
        System.out.printf("  SaveAll (P9)     : %,d records in %,d ms  ->  %,.0f ops/s%n",
            saveAllTotalSaved, phaseSaveAllMs, saveAllOpsPerSec);

        double updateOpsPerSec   = 1001.0 / Math.max(1.0, phaseUpdateMs / 1000.0);
        System.out.printf("  Update           : 1,001 records in %,d ms  ->  %,.0f ops/s%n",
            phaseUpdateMs, updateOpsPerSec);

        double deleteOpsPerSec = 101.0 / Math.max(1.0, phaseDeleteMs / 1000.0);
        System.out.printf("  Delete           : 101 records in %,d ms  ->  %,.0f ops/s%n",
            phaseDeleteMs, deleteOpsPerSec);

        System.out.println("\n[QUERY PERFORMANCE]");
        System.out.printf("  Total queries    : %d  |  total time : %,d ms  |  avg : %.1f ms/query%n",
            totalQueryCount, totalQueryMs, totalQueryMs / (double) totalQueryCount);
        System.out.printf("  Score queries    : %d queries in %,d ms  |  avg %.1f ms%n",
            7, phaseScoreMs, phaseScoreMs / 7.0);
        System.out.printf("  Boolean queries  : %d queries in %,d ms  |  avg %.1f ms%n",
            2, phaseBoolMs, phaseBoolMs / 2.0);
        System.out.printf("  World queries    : %d queries in %,d ms  |  avg %.1f ms%n",
            4, phaseWorldMs, phaseWorldMs / 4.0);
        System.out.printf("  AND queries      : %d queries in %,d ms  |  avg %.1f ms%n",
            3, phaseAndMs, phaseAndMs / 3.0);
        System.out.printf("  Timestamp scen.  : %d queries in %,d ms  |  min %d ms  |  max %d ms  |  avg %.1f ms%n",
            (int) tsStats.getCount(), phaseTimestampMs,
            (long) tsStats.getMin(), (long) tsStats.getMax(), tsStats.getAverage());

        System.out.println("\n[PHASE BREAKDOWN]");
        System.out.printf("  P2  Insert       : %,d ms  (%d%%)%n", phaseInsertMs,    phaseInsertMs    * 100 / Math.max(1, totalMs));
        System.out.printf("  P3  Basic reads  : %,d ms  (%d%%)%n", phaseReadsMs,     phaseReadsMs     * 100 / Math.max(1, totalMs));
        System.out.printf("  P4  Score Q      : %,d ms  (%d%%)%n", phaseScoreMs,     phaseScoreMs     * 100 / Math.max(1, totalMs));
        System.out.printf("  P5  Boolean Q    : %,d ms  (%d%%)%n", phaseBoolMs,      phaseBoolMs      * 100 / Math.max(1, totalMs));
        System.out.printf("  P6  World Q      : %,d ms  (%d%%)%n", phaseWorldMs,     phaseWorldMs     * 100 / Math.max(1, totalMs));
        System.out.printf("  P7  AND Q        : %,d ms  (%d%%)%n", phaseAndMs,       phaseAndMs       * 100 / Math.max(1, totalMs));
        System.out.printf("  P8  Timestamp Q  : %,d ms  (%d%%)%n", phaseTimestampMs, phaseTimestampMs * 100 / Math.max(1, totalMs));
        System.out.printf("  P9  saveAll Scen : %,d ms  (%d%%)%n", phaseSaveAllMs,   phaseSaveAllMs   * 100 / Math.max(1, totalMs));
        System.out.printf("  P10 Update       : %,d ms  (%d%%)%n", phaseUpdateMs,    phaseUpdateMs    * 100 / Math.max(1, totalMs));
        System.out.printf("  P11 Delete       : %,d ms  (%d%%)%n", phaseDeleteMs,    phaseDeleteMs    * 100 / Math.max(1, totalMs));
        System.out.printf("  TOTAL            : %,d ms%n", totalMs);
        System.out.println("=================================================\n");
    }

    private static class RangeScenario {

        private final String name;
        private final Instant from;
        private final Instant to;
        private final int expectedCount;

        private RangeScenario(
            String name,
            Instant from,
            Instant to,
            int expectedCount
        ) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.expectedCount = expectedCount;
        }
    }
}
