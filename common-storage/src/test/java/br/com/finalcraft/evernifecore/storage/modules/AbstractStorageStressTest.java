package br.com.finalcraft.evernifecore.storage.modules;

import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.Query;
import org.instancio.Instancio;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest.DESCRIPTOR;
import static br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest.UUID_GHOST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Backend-agnostic stress suite: 10k-record bulk insert, full CRUD, the complete
 * index/query matrix and a printed throughput report. Extracted from
 * {@link AbstractStorageTest} so the heavy run lives in its own class per backend.
 *
 * <p>Tagged {@code stress}: runs by default with {@code :common-storage:test}, but can be
 * skipped with {@code -PskipStress} (wired in {@code common-storage/build.gradle}).
 *
 * <p>The scenario is intentionally <b>one</b> cumulative {@code @Test}: each phase builds on
 * the state left by the previous one (inserts, then reads, then index migrations, then
 * deletes), asserting cross-phase invariants like total count. The phases are split into
 * private methods purely for readability; state crosses phases via instance fields.
 *
 * <p>The {@code System.out} output is an intentional benchmark report (insert/query/update
 * throughput per backend), funneled through {@link #report(String, Object...)}.
 */
@Tag("stress")
public abstract class AbstractStorageStressTest {

    static final int TOTAL_PLAYERS = 10_000;
    static final int BATCH_SIZE    =  1_000;

    protected Storage storage;
    protected Repository<UUID, TestPlayer> repo;

    /** Implement to provide the concrete {@link Storage} instance under test. */
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
    //  Cross-phase state
    // ------------------------------------------------------------------

    private List<TestPlayer> testPlayers;
    private Instant baseDate;

    // Expected sizes pre-computed from the generated dataset (phase 1)
    private long expectedWorldNether;
    private long expectedWorld;
    private long expectedActive;
    private long expectedInactive;
    private long expectedWorldAndActive;
    private long expectedWorldAndScore1000_1999;
    private long expectedTripleAnd;

    /** Running expected count - starts at TOTAL_PLAYERS, grows when phase 9D inserts new records. */
    private long expectedCount = TOTAL_PLAYERS;

    // Phase 8 query times, phase 9 saveAll tracking, phase 10/11 bookkeeping for the summary
    private final List<Long> timestampQueryTimes = new ArrayList<>();
    private long saveAllTotalSaved = 0;
    private int  saveAllNewInserts = 0;
    private int  singleTargetIdx;
    private int  lastIdx;
    private long finalCount;

    private static final int BULK_UPDATE_SCORE   = -1;
    private static final int SINGLE_UPDATE_SCORE = -2;
    private static final int BULK_DEL_FROM = 9_000;
    private static final int BULK_DEL_TO   = 9_099;

    // Per-phase wall-clock durations, filled by the orchestrator
    private long phaseInsertMs, phaseReadsMs, phaseScoreMs, phaseBoolMs, phaseWorldMs,
                 phaseAndMs, phaseTimestampMs, phaseSaveAllMs, phaseUpdateMs, phaseDeleteMs;

    // ------------------------------------------------------------------
    //  Orchestrator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[stress] Massive Inserts + CRUD + Advanced Range Queries (10k records)")
    void stressTestMassiveInsertsCRUDAndRangeQueries() {
        report("\n=================================================");
        report("STARTING MASSIVE RANGE QUERY TEST");
        report("=================================================");
        report("Total Records : " + TOTAL_PLAYERS);

        long globalStart = System.currentTimeMillis();

        phase1_generateDataset();
        phaseInsertMs    = timed(this::phase2_bulkInsert);
        phaseReadsMs     = timed(this::phase3_basicReads);
        phaseScoreMs     = timed(this::phase4_scoreQueries);
        phaseBoolMs      = timed(this::phase5_booleanQueries);
        phaseWorldMs     = timed(this::phase6_worldQueries);
        phaseAndMs       = timed(this::phase7_compoundAndQueries);
        phaseTimestampMs = timed(this::phase8_timestampScenarios);
        phaseSaveAllMs   = timed(this::phase9_saveAllScenarios);
        phaseUpdateMs    = timed(this::phase10_bulkUpdate);
        phaseDeleteMs    = timed(this::phase11_deletes);

        printFinalSummary(System.currentTimeMillis() - globalStart);
    }

    private static long timed(Runnable phase) {
        long start = System.currentTimeMillis();
        phase.run();
        return System.currentTimeMillis() - start;
    }

    // ------------------------------------------------------------------
    //  PHASE 1: dataset generation
    // ------------------------------------------------------------------

    private void phase1_generateDataset() {
        long stageStart = System.currentTimeMillis();

        testPlayers = Instancio.ofList(TestPlayer.class)
            .size(TOTAL_PLAYERS)
            .create();

        baseDate = Instant.now().minus(TOTAL_PLAYERS, ChronoUnit.DAYS);

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
        report("\n[DATASET GENERATION]");
        report("Generated    : " + testPlayers.size() + " players");
        report("Date range   : " + baseDate + "  ->  " + baseDate.plus(TOTAL_PLAYERS - 1, ChronoUnit.DAYS));
        report("Score range  : 0  ->  " + (TOTAL_PLAYERS - 1));
        report("Time         : " + (stageEnd - stageStart) + " ms");

        // Pre-compute expected sizes by streaming the local list (avoids hardcoding counts)
        expectedWorldNether    = testPlayers.stream().filter(p -> "world_nether".equals(p.getWorld())).count();
        expectedWorld          = TOTAL_PLAYERS - expectedWorldNether;
        expectedActive         = testPlayers.stream().filter(TestPlayer::isActive).count();
        expectedInactive       = TOTAL_PLAYERS - expectedActive;
        expectedWorldAndActive = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.isActive()).count();
        expectedWorldAndScore1000_1999 = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.getScore() >= 1000 && p.getScore() <= 1999).count();
        expectedTripleAnd = testPlayers.stream()
            .filter(p -> "world".equals(p.getWorld()) && p.isActive() && p.getScore() >= 2000 && p.getScore() <= 3999).count();

        report("world_nether : " + expectedWorldNether + " | world : " + expectedWorld);
        report("active=true  : " + expectedActive + " | active=false : " + expectedInactive);
    }

    // ------------------------------------------------------------------
    //  PHASE 2: bulk insert (saveAll in batches)
    // ------------------------------------------------------------------

    private void phase2_bulkInsert() {
        report("\n--- PHASE 2: BULK INSERT ---");
        long stageStart = System.currentTimeMillis();
        int inserted = 0;
        for (int batchStart = 0; batchStart < TOTAL_PLAYERS; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, TOTAL_PLAYERS);
            repo.saveAll(testPlayers.subList(batchStart, batchEnd)).join();
            inserted += (batchEnd - batchStart);
            long elapsed = System.currentTimeMillis() - stageStart;
            report("[INSERT] batch %,d-%,d | total=%,d | elapsed=%d ms",
                batchStart, batchEnd - 1, inserted, elapsed);
        }
        long insertDuration = System.currentTimeMillis() - stageStart;
        report("[INSERT SUMMARY] records=%,d | time=%d ms | ops/s=%.0f",
            inserted, insertDuration, inserted / Math.max(1.0, insertDuration / 1000.0));
    }

    // ------------------------------------------------------------------
    //  PHASE 3: basic read verification
    // ------------------------------------------------------------------

    private void phase3_basicReads() {
        report("\n--- PHASE 3: BASIC READS ---");

        long stageStart = System.currentTimeMillis();
        long count = repo.count().join();
        report("[COUNT] count=%,d | time=%d ms", count, System.currentTimeMillis() - stageStart);
        assertEquals(TOTAL_PLAYERS, count, "count() must equal total inserted");

        stageStart = System.currentTimeMillis();
        TestPlayer foundFirst = repo.find(testPlayers.get(0).getUuid()).join()
            .orElseThrow(() -> new AssertionError("First player not found"));
        report("[FIND FIRST] score=%d | time=%d ms", foundFirst.getScore(), System.currentTimeMillis() - stageStart);
        assertEquals(0, foundFirst.getScore());

        stageStart = System.currentTimeMillis();
        TestPlayer foundMiddle = repo.find(testPlayers.get(TOTAL_PLAYERS / 2).getUuid()).join()
            .orElseThrow(() -> new AssertionError("Middle player not found"));
        report("[FIND MIDDLE] score=%d | time=%d ms", foundMiddle.getScore(), System.currentTimeMillis() - stageStart);
        assertEquals(TOTAL_PLAYERS / 2, foundMiddle.getScore());

        stageStart = System.currentTimeMillis();
        TestPlayer foundLast = repo.find(testPlayers.get(TOTAL_PLAYERS - 1).getUuid()).join()
            .orElseThrow(() -> new AssertionError("Last player not found"));
        report("[FIND LAST] score=%d | time=%d ms", foundLast.getScore(), System.currentTimeMillis() - stageStart);
        assertEquals(TOTAL_PLAYERS - 1, foundLast.getScore());

        stageStart = System.currentTimeMillis();
        boolean existsAlice = repo.exists(testPlayers.get(42).getUuid()).join();
        report("[EXISTS present] exists=%b | time=%d ms", existsAlice, System.currentTimeMillis() - stageStart);
        assertTrue(existsAlice);

        stageStart = System.currentTimeMillis();
        boolean existsGhost = repo.exists(UUID_GHOST).join();
        report("[EXISTS absent] exists=%b | time=%d ms", existsGhost, System.currentTimeMillis() - stageStart);
        assertFalse(existsGhost);

        List<UUID> first10Keys = testPlayers.subList(0, 10).stream()
            .map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundFirst10 = repo.findMany(first10Keys).join();
        report("[FIND_MANY 10] found=%d | time=%d ms", foundFirst10.size(), System.currentTimeMillis() - stageStart);
        assertEquals(10, foundFirst10.size());

        List<UUID> mid100Keys = testPlayers.subList(TOTAL_PLAYERS / 2, TOTAL_PLAYERS / 2 + 100).stream()
            .map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundMid100 = repo.findMany(mid100Keys).join();
        report("[FIND_MANY 100] found=%d | time=%d ms", foundMid100.size(), System.currentTimeMillis() - stageStart);
        assertEquals(100, foundMid100.size());

        List<UUID> mixedKeys = Arrays.asList(
            testPlayers.get(0).getUuid(), testPlayers.get(1).getUuid(), UUID_GHOST);
        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundMixed = repo.findMany(mixedKeys).join();
        report("[FIND_MANY mixed 3 keys, 1 absent] found=%d | time=%d ms",
            foundMixed.size(), System.currentTimeMillis() - stageStart);
        assertEquals(2, foundMixed.size(), "Missing keys must be silently omitted");

        stageStart = System.currentTimeMillis();
        long allCount = repo.all().join().count();
        report("[ALL] count=%,d | time=%d ms", allCount, System.currentTimeMillis() - stageStart);
        assertEquals(TOTAL_PLAYERS, allCount);
    }

    // ------------------------------------------------------------------
    //  PHASE 4: score range queries
    // ------------------------------------------------------------------

    private void phase4_scoreQueries() {
        report("\n--- PHASE 4: SCORE QUERIES ---");

        // Dynamic indices derived from TOTAL_PLAYERS so queries remain valid for any size
        final int SCORE_MID  = TOTAL_PLAYERS / 2;
        final int SCORE_Q1   = TOTAL_PLAYERS / 4;
        final int SCORE_Q3   = TOTAL_PLAYERS * 3 / 4;
        final int SCORE_LAST = TOTAL_PLAYERS - 1;
        final int SCORE_UPPER_THRESHOLD = Math.max(1, TOTAL_PLAYERS - 1_000);

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreEqMid = repo.query(Query.eq("score", SCORE_MID)).join();
        report("[SCORE =%d] found=%d | time=%d ms", SCORE_MID, scoreEqMid.size(), System.currentTimeMillis() - stageStart);
        assertEquals(1, scoreEqMid.size(), "Exactly one player with score=" + SCORE_MID);
        assertEquals(SCORE_MID, scoreEqMid.get(0).getScore());

        stageStart = System.currentTimeMillis();
        List<TestPlayer> score0_999 = repo.query(Query.range("score", 0, 999)).join();
        report("[SCORE 0..999] found=%d | time=%d ms", score0_999.size(), System.currentTimeMillis() - stageStart);
        assertEquals(1000, score0_999.size(), "Score range 0..999 should return 1000 players");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> score2kRange = repo.query(Query.range("score", SCORE_Q1, SCORE_Q1 + 1999)).join();
        report("[SCORE %d..%d] found=%d | time=%d ms",
            SCORE_Q1, SCORE_Q1 + 1999, score2kRange.size(), System.currentTimeMillis() - stageStart);
        assertEquals(2000, score2kRange.size(), "Score range Q1..Q1+1999 should return 2000 players");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreGteThreshold = repo.query(Query.range("score", SCORE_UPPER_THRESHOLD, null)).join();
        int expectedGte = TOTAL_PLAYERS - SCORE_UPPER_THRESHOLD;
        report("[SCORE >=%d] found=%d | expected=%d | time=%d ms",
            SCORE_UPPER_THRESHOLD, scoreGteThreshold.size(), expectedGte, System.currentTimeMillis() - stageStart);
        assertEquals(expectedGte, scoreGteThreshold.size(),
            "Score >= " + SCORE_UPPER_THRESHOLD + " should return last " + expectedGte + " players");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreLte999 = repo.query(Query.range("score", null, 999)).join();
        report("[SCORE <=999] found=%d | time=%d ms", scoreLte999.size(), System.currentTimeMillis() - stageStart);
        assertEquals(1000, scoreLte999.size(), "Score <= 999 should return 1000 players");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreIn3 = repo.query(Query.in("score", 0, SCORE_MID, SCORE_LAST)).join();
        report("[SCORE IN [0,%d,%d]] found=%d | time=%d ms",
            SCORE_MID, SCORE_LAST, scoreIn3.size(), System.currentTimeMillis() - stageStart);
        assertEquals(3, scoreIn3.size(), "IN query for 3 distinct scores should return 3 players");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreIn5 = repo.query(
            Query.in("score", Arrays.asList(0, SCORE_Q1, SCORE_MID, SCORE_Q3, SCORE_LAST))).join();
        report("[SCORE IN [0,%d,%d,%d,%d]] found=%d | time=%d ms",
            SCORE_Q1, SCORE_MID, SCORE_Q3, SCORE_LAST, scoreIn5.size(), System.currentTimeMillis() - stageStart);
        assertEquals(5, scoreIn5.size(), "IN query for 5 distinct scores should return 5 players");
    }

    // ------------------------------------------------------------------
    //  PHASE 5: boolean queries
    // ------------------------------------------------------------------

    private void phase5_booleanQueries() {
        report("\n--- PHASE 5: BOOLEAN QUERIES ---");

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> activeTrue = repo.query(Query.eq("active", true)).join();
        report("[ACTIVE=true] found=%d | expected=%d | time=%d ms",
            activeTrue.size(), expectedActive, System.currentTimeMillis() - stageStart);
        assertEquals(expectedActive, activeTrue.size(), "active=true count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> activeFalse = repo.query(Query.eq("active", false)).join();
        report("[ACTIVE=false] found=%d | expected=%d | time=%d ms",
            activeFalse.size(), expectedInactive, System.currentTimeMillis() - stageStart);
        assertEquals(expectedInactive, activeFalse.size(), "active=false count mismatch");

        assertEquals(TOTAL_PLAYERS, activeTrue.size() + activeFalse.size(),
            "active=true + active=false must sum to TOTAL_PLAYERS");
    }

    // ------------------------------------------------------------------
    //  PHASE 6: world string queries
    // ------------------------------------------------------------------

    private void phase6_worldQueries() {
        report("\n--- PHASE 6: WORLD STRING QUERIES ---");

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> qWorldNether = repo.query(Query.eq("world", "world_nether")).join();
        report("[WORLD=world_nether] found=%d | expected=%d | time=%d ms",
            qWorldNether.size(), expectedWorldNether, System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorldNether, qWorldNether.size(), "world=world_nether count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> qWorld = repo.query(Query.eq("world", "world")).join();
        report("[WORLD=world] found=%d | expected=%d | time=%d ms",
            qWorld.size(), expectedWorld, System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorld, qWorld.size(), "world=world count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> findByWorldNether = repo.findBy("world", "world_nether").join();
        report("[FINDBY world=world_nether] found=%d | time=%d ms",
            findByWorldNether.size(), System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorldNether, findByWorldNether.size(), "findBy must agree with query.eq");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> worldInBoth = repo.query(
            Query.in("world", Arrays.asList("world", "world_nether"))).join();
        report("[WORLD IN [world, world_nether]] found=%d | time=%d ms",
            worldInBoth.size(), System.currentTimeMillis() - stageStart);
        assertEquals(TOTAL_PLAYERS, worldInBoth.size(), "IN for both world values must return all players");
    }

    // ------------------------------------------------------------------
    //  PHASE 7: compound AND queries
    // ------------------------------------------------------------------

    private void phase7_compoundAndQueries() {
        report("\n--- PHASE 7: COMPOUND AND QUERIES ---");

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> worldAndActive = repo.query(
            Query.eq("world", "world").and(Query.eq("active", true))).join();
        report("[world=world AND active=true] found=%d | expected=%d | time=%d ms",
            worldAndActive.size(), expectedWorldAndActive, System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorldAndActive, worldAndActive.size(), "world AND active count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> worldAndScoreRange = repo.query(
            Query.eq("world", "world").and(Query.range("score", 1000, 1999))).join();
        report("[world=world AND score 1000..1999] found=%d | expected=%d | time=%d ms",
            worldAndScoreRange.size(), expectedWorldAndScore1000_1999, System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorldAndScore1000_1999, worldAndScoreRange.size(), "world AND score range count mismatch");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> tripleAnd = repo.query(
            Query.eq("world", "world").and(Query.eq("active", true)).and(Query.range("score", 2000, 3999))).join();
        report("[world=world AND active=true AND score 2000..3999] found=%d | expected=%d | time=%d ms",
            tripleAnd.size(), expectedTripleAnd, System.currentTimeMillis() - stageStart);
        assertEquals(expectedTripleAnd, tripleAnd.size(), "triple AND count mismatch");
    }

    // ------------------------------------------------------------------
    //  PHASE 8: timestamp (createdAt) range scenarios
    // ------------------------------------------------------------------

    private void phase8_timestampScenarios() {
        // Players have indices 0..TOTAL_PLAYERS-1 with createdAt = baseDate + i days.
        // All fixed offsets below are within range for any TOTAL_PLAYERS >= 10_000.
        report("\n--- PHASE 8: TIMESTAMP RANGE SCENARIOS ---");

        Instant datasetStart = baseDate;
        Instant datasetEnd   = baseDate.plus(TOTAL_PLAYERS - 1L, ChronoUnit.DAYS);

        List<RangeScenario> scenarios = List.of(
            // All TOTAL_PLAYERS records (1 day padding on each side)
            new RangeScenario("FULL DATASET",
                datasetStart.minus(1, ChronoUnit.DAYS), datasetEnd.plus(1, ChronoUnit.DAYS), TOTAL_PLAYERS),
            // indices 0..99 inclusive -> 100 records
            new RangeScenario("FIRST 100 RECORDS",
                datasetStart, datasetStart.plus(99, ChronoUnit.DAYS), 100),
            // indices TOTAL-100..TOTAL-1 inclusive -> 100 records
            new RangeScenario("LAST 100 RECORDS",
                datasetEnd.minus(99, ChronoUnit.DAYS), datasetEnd, 100),
            // indices 4500..5500 inclusive -> 1001 records
            new RangeScenario("MIDDLE WINDOW 1001 RECORDS",
                datasetStart.plus(4_500, ChronoUnit.DAYS), datasetStart.plus(5_500, ChronoUnit.DAYS), 1_001),
            // single record at index 5000
            new RangeScenario("EXACT SINGLE RECORD",
                datasetStart.plus(5_000, ChronoUnit.DAYS), datasetStart.plus(5_000, ChronoUnit.DAYS), 1),
            // 100 days after dataset end - guaranteed empty
            new RangeScenario("FUTURE RANGE EMPTY",
                datasetEnd.plus(1, ChronoUnit.DAYS), datasetEnd.plus(100, ChronoUnit.DAYS), 0),
            // 100 days before dataset start - guaranteed empty
            new RangeScenario("PAST RANGE EMPTY",
                datasetStart.minus(100, ChronoUnit.DAYS), datasetStart.minus(1, ChronoUnit.DAYS), 0),
            // open upper: index 9000 to end -> TOTAL_PLAYERS - 9_000 records
            new RangeScenario("OPEN UPPER FROM INDEX 9000",
                datasetStart.plus(9_000, ChronoUnit.DAYS), null, TOTAL_PLAYERS - 9_000),
            // open lower: beginning to index 4999 -> 5000 records
            new RangeScenario("OPEN LOWER TO INDEX 4999",
                null, datasetStart.plus(4_999, ChronoUnit.DAYS), 5_000),
            // tiny range: indices 500..504 = 5 records
            new RangeScenario("TINY RANGE 5 RECORDS",
                datasetStart.plus(500, ChronoUnit.DAYS), datasetStart.plus(504, ChronoUnit.DAYS), 5),
            // large range: indices 2000..6999 = 5000 records
            new RangeScenario("LARGE RANGE 5K",
                datasetStart.plus(2_000, ChronoUnit.DAYS), datasetStart.plus(6_999, ChronoUnit.DAYS), 5_000)
        );

        int scenarioNumber = 1;
        for (RangeScenario scenario : scenarios) {
            report("\n-------------------------------------------------");
            report("SCENARIO #%d: %s", scenarioNumber++, scenario.name);
            report("From           : " + scenario.from);
            report("To             : " + scenario.to);
            report("Expected Count : " + scenario.expectedCount);

            long queryStart = System.currentTimeMillis();
            List<TestPlayer> found = repo.query(
                Query.range("createdAt", scenario.from, scenario.to)).join();
            long scenarioMs = System.currentTimeMillis() - queryStart;
            timestampQueryTimes.add(scenarioMs);

            report("Returned Count : " + found.size());
            report("Query Time     : " + scenarioMs + " ms");

            if (!found.isEmpty()) {
                long minTs = found.stream().mapToLong(TestPlayer::getCreatedAt).min().orElse(0);
                long maxTs = found.stream().mapToLong(TestPlayer::getCreatedAt).max().orElse(0);
                report("Min Timestamp  : " + Instant.ofEpochMilli(minTs));
                report("Max Timestamp  : " + Instant.ofEpochMilli(maxTs));
            }

            assertEquals(scenario.expectedCount, found.size(),
                "Timestamp scenario '" + scenario.name + "' count mismatch");
            report("[OK] Passed");
        }
    }

    // ------------------------------------------------------------------
    //  PHASE 9: saveAll scenarios (read -> mutate -> saveAll -> requery)
    // ------------------------------------------------------------------

    private void phase9_saveAllScenarios() {
        report("\n--- PHASE 9: saveAll SCENARIOS ---");

        // ---- SCENARIO 9A: world migration  (world_nether -> world_end) ----
        report("\n  [9A] World migration: world_nether -> world_end");

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> netherPlayers = repo.query(Query.eq("world", "world_nether")).join();
        report("  [9A] read world_nether=%,d | time=%d ms",
            netherPlayers.size(), System.currentTimeMillis() - stageStart);
        assertEquals(expectedWorldNether, netherPlayers.size(), "9A: pre-condition world_nether count");

        for (TestPlayer p : netherPlayers) p.setWorld("world_end");

        stageStart = System.currentTimeMillis();
        saveAllInBatches(netherPlayers);
        report("  [9A] saveAll world_end migration=%,d records | time=%d ms",
            netherPlayers.size(), System.currentTimeMillis() - stageStart);
        saveAllTotalSaved += netherPlayers.size();

        assertEquals(0, repo.query(Query.eq("world", "world_nether")).join().size(),
            "9A: world_nether index must be empty after migration");
        assertEquals(expectedWorldNether, repo.query(Query.eq("world", "world_end")).join().size(),
            "9A: world_end index must hold all migrated records");
        assertEquals(expectedCount, repo.count().join(),
            "9A: total count must be unchanged after world migration");
        report("  [9A] PASSED - world index correctly remapped");

        // ---- SCENARIO 9B: boolean batch toggle  (active=false -> active=true) ----
        report("\n  [9B] Mass activation: all inactive -> active");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> inactivePlayers = repo.query(Query.eq("active", false)).join();
        report("  [9B] read active=false=%,d | time=%d ms",
            inactivePlayers.size(), System.currentTimeMillis() - stageStart);
        assertEquals(expectedInactive, inactivePlayers.size(), "9B: pre-condition inactive count");

        for (TestPlayer p : inactivePlayers) p.setActive(true);

        stageStart = System.currentTimeMillis();
        saveAllInBatches(inactivePlayers);
        report("  [9B] saveAll activate=%,d records | time=%d ms",
            inactivePlayers.size(), System.currentTimeMillis() - stageStart);
        saveAllTotalSaved += inactivePlayers.size();

        assertEquals(0, repo.query(Query.eq("active", false)).join().size(),
            "9B: active=false must be empty after mass activation");
        assertEquals(expectedCount, repo.query(Query.eq("active", true)).join().size(),
            "9B: active=true must equal total player count");
        assertEquals(expectedCount, repo.count().join(),
            "9B: total count must be unchanged after active toggle");
        report("  [9B] PASSED - boolean index correctly updated for all records");

        // ---- SCENARIO 9C: integer score shift  (middle 10% -> +1_000_000) ----
        // Range is derived so it stays within [0, TOTAL_PLAYERS-1] for any dataset size.
        // Must not overlap the Phase 10 bulk-update range [0, 999].
        final int SHIFT_FROM   = TOTAL_PLAYERS / 2;
        final int SHIFT_TO     = SHIFT_FROM + Math.max(100, TOTAL_PLAYERS / 10) - 1;
        final int SCORE_OFFSET = 1_000_000;

        report("%n  [9C] Score shift: [%,d, %,d] -> [%,d, %,d]",
            SHIFT_FROM, SHIFT_TO, SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET);

        stageStart = System.currentTimeMillis();
        List<TestPlayer> toShift = repo.query(Query.range("score", SHIFT_FROM, SHIFT_TO)).join();
        report("  [9C] read score[%,d..%,d]=%,d | time=%d ms",
            SHIFT_FROM, SHIFT_TO, toShift.size(), System.currentTimeMillis() - stageStart);
        assertEquals(SHIFT_TO - SHIFT_FROM + 1, toShift.size(), "9C: pre-condition shift range count");

        for (TestPlayer p : toShift) p.setScore(p.getScore() + SCORE_OFFSET);

        stageStart = System.currentTimeMillis();
        saveAllInBatches(toShift);
        report("  [9C] saveAll score shift=%,d records | time=%d ms",
            toShift.size(), System.currentTimeMillis() - stageStart);
        saveAllTotalSaved += toShift.size();

        stageStart = System.currentTimeMillis();
        List<TestPlayer> oldRangeAfterShift = repo.query(Query.range("score", SHIFT_FROM, SHIFT_TO)).join();
        report("  [9C] old range [%,d..%,d] after shift=%d | time=%d ms",
            SHIFT_FROM, SHIFT_TO, oldRangeAfterShift.size(), System.currentTimeMillis() - stageStart);
        assertEquals(0, oldRangeAfterShift.size(), "9C: old score range must be empty after shift");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> newRangeAfterShift = repo.query(
            Query.range("score", SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET)).join();
        report("  [9C] new range [%,d..%,d] after shift=%,d | time=%d ms",
            SHIFT_FROM + SCORE_OFFSET, SHIFT_TO + SCORE_OFFSET, newRangeAfterShift.size(),
            System.currentTimeMillis() - stageStart);
        assertEquals(SHIFT_TO - SHIFT_FROM + 1, newRangeAfterShift.size(),
            "9C: new score range must hold all shifted records");
        assertTrue(newRangeAfterShift.stream().allMatch(
            p -> p.getScore() >= SHIFT_FROM + SCORE_OFFSET && p.getScore() <= SHIFT_TO + SCORE_OFFSET),
            "9C: all records in new range must have shifted score value");
        assertEquals(expectedCount, repo.count().join(),
            "9C: total count must be unchanged after score shift");
        report("  [9C] PASSED - integer index correctly remapped after score shift");

        // ---- SCENARIO 9D: mixed saveAll  (500 brand-new + 500 updated existing in one call) ----
        // This specifically tests that saveAll correctly handles a batch that contains both
        // entirely new keys (INSERT) and existing keys (UPDATE) in a single call.
        final int NEW_PLAYER_COUNT   = 500;
        final int NEW_PLAYER_SCORE   = 2_000_000;   // never collides with natural [0,TOTAL_PLAYERS-1]
        final int EXISTING_UPD_SCORE = 3_000_000;   // never collides with natural scores
        // 40% mark - always below SHIFT_FROM (50%) and above Phase 10 bulk-update range [0,999]
        final int MIXED_RANGE_FROM   = TOTAL_PLAYERS * 4 / 10;
        final int MIXED_RANGE_TO     = MIXED_RANGE_FROM + NEW_PLAYER_COUNT - 1;

        report("%n  [9D] Mixed saveAll: %d new (score=%,d) + %d updated existing (score %,d..%,d -> %,d)",
            NEW_PLAYER_COUNT, NEW_PLAYER_SCORE, NEW_PLAYER_COUNT,
            MIXED_RANGE_FROM, MIXED_RANGE_TO, EXISTING_UPD_SCORE);

        List<TestPlayer> newPlayers = Instancio.ofList(TestPlayer.class).size(NEW_PLAYER_COUNT).create();
        for (TestPlayer p : newPlayers) p.setScore(NEW_PLAYER_SCORE);

        stageStart = System.currentTimeMillis();
        List<TestPlayer> existingToMix = repo.query(
            Query.range("score", MIXED_RANGE_FROM, MIXED_RANGE_TO)).join();
        report("  [9D] read %,d existing players | time=%d ms",
            existingToMix.size(), System.currentTimeMillis() - stageStart);
        assertEquals(NEW_PLAYER_COUNT, existingToMix.size(),
            "9D: must read exactly " + NEW_PLAYER_COUNT + " existing players for the mix");
        for (TestPlayer p : existingToMix) p.setScore(EXISTING_UPD_SCORE);

        List<TestPlayer> mixedBatch = new ArrayList<>(NEW_PLAYER_COUNT * 2);
        mixedBatch.addAll(newPlayers);
        mixedBatch.addAll(existingToMix);

        long countBeforeMixed = repo.count().join();
        stageStart = System.currentTimeMillis();
        repo.saveAll(mixedBatch).join();
        report("  [9D] saveAll %,d mixed entities (%,d new + %,d updated) | time=%d ms",
            mixedBatch.size(), NEW_PLAYER_COUNT, existingToMix.size(), System.currentTimeMillis() - stageStart);
        saveAllTotalSaved += mixedBatch.size();
        saveAllNewInserts  = NEW_PLAYER_COUNT;
        expectedCount     += NEW_PLAYER_COUNT;

        long countAfterMixed = repo.count().join();
        report("  [9D] count: before=%,d | after=%,d | diff=%d",
            countBeforeMixed, countAfterMixed, countAfterMixed - countBeforeMixed);
        assertEquals(expectedCount, countAfterMixed,
            "9D: count must increase by " + NEW_PLAYER_COUNT + " (new inserts only)");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundNew = repo.query(Query.eq("score", NEW_PLAYER_SCORE)).join();
        report("  [9D] score=%,d -> found=%,d | time=%d ms",
            NEW_PLAYER_SCORE, foundNew.size(), System.currentTimeMillis() - stageStart);
        assertEquals(NEW_PLAYER_COUNT, foundNew.size(),
            "9D: new players must be queryable by score=" + NEW_PLAYER_SCORE);

        stageStart = System.currentTimeMillis();
        List<TestPlayer> foundUpdated = repo.query(Query.eq("score", EXISTING_UPD_SCORE)).join();
        report("  [9D] score=%,d -> found=%,d | time=%d ms",
            EXISTING_UPD_SCORE, foundUpdated.size(), System.currentTimeMillis() - stageStart);
        assertEquals(NEW_PLAYER_COUNT, foundUpdated.size(),
            "9D: updated existing players must be at score=" + EXISTING_UPD_SCORE);

        List<TestPlayer> oldMixRange = repo.query(
            Query.range("score", MIXED_RANGE_FROM, MIXED_RANGE_TO)).join();
        assertEquals(0, oldMixRange.size(),
            "9D: old score range [" + MIXED_RANGE_FROM + ".." + MIXED_RANGE_TO + "] must be empty after mixed saveAll");

        report("  [9D] PASSED - mixed saveAll correctly inserted %,d new and updated %,d existing records",
            NEW_PLAYER_COUNT, NEW_PLAYER_COUNT);
        report("%n[PHASE 9 SUMMARY] total saveAll writes=%,d | new inserts=%,d | repo size=%,d",
            saveAllTotalSaved, saveAllNewInserts, expectedCount);
    }

    private void saveAllInBatches(List<TestPlayer> players) {
        for (int batchStart = 0; batchStart < players.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, players.size());
            repo.saveAll(players.subList(batchStart, batchEnd)).join();
        }
    }

    // ------------------------------------------------------------------
    //  PHASE 10: bulk update
    // ------------------------------------------------------------------

    private void phase10_bulkUpdate() {
        report("\n--- PHASE 10: BULK UPDATE ---");

        long stageStart = System.currentTimeMillis();
        List<TestPlayer> toUpdate = repo.query(Query.range("score", 0, 999)).join();
        report("[PRE-UPDATE QUERY score 0..999] found=%d | time=%d ms",
            toUpdate.size(), System.currentTimeMillis() - stageStart);
        assertEquals(1000, toUpdate.size(), "Pre-update query must return 1000 players");

        for (TestPlayer p : toUpdate) {
            p.setScore(BULK_UPDATE_SCORE);
        }
        stageStart = System.currentTimeMillis();
        saveAllInBatches(toUpdate);
        report("[BULK UPDATE -> score=%d] updated=%d | time=%d ms",
            BULK_UPDATE_SCORE, toUpdate.size(), System.currentTimeMillis() - stageStart);

        stageStart = System.currentTimeMillis();
        long countAfterUpdate = repo.count().join();
        report("[COUNT AFTER UPDATE] count=%,d | time=%d ms", countAfterUpdate, System.currentTimeMillis() - stageStart);
        assertEquals(expectedCount, countAfterUpdate, "count() must not change after upsert");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> score0_999afterUpdate = repo.query(Query.range("score", 0, 999)).join();
        report("[SCORE 0..999 AFTER UPDATE] found=%d | time=%d ms",
            score0_999afterUpdate.size(), System.currentTimeMillis() - stageStart);
        assertEquals(0, score0_999afterUpdate.size(), "Score 0..999 must be empty after update");

        stageStart = System.currentTimeMillis();
        List<TestPlayer> scoreUpdated = repo.query(Query.eq("score", BULK_UPDATE_SCORE)).join();
        report("[SCORE=%d AFTER UPDATE] found=%d | time=%d ms",
            BULK_UPDATE_SCORE, scoreUpdated.size(), System.currentTimeMillis() - stageStart);
        assertEquals(1000, scoreUpdated.size(), "score=" + BULK_UPDATE_SCORE + " must return 1000 updated players");

        assertTrue(scoreUpdated.stream().allMatch(p -> p.getScore() == BULK_UPDATE_SCORE),
            "All returned players must have score=" + BULK_UPDATE_SCORE);

        // single record update + verify (use midpoint index - guaranteed not in 0..999 batch)
        singleTargetIdx = TOTAL_PLAYERS / 2;
        TestPlayer singleTarget = testPlayers.get(singleTargetIdx);
        singleTarget.setScore(SINGLE_UPDATE_SCORE);
        stageStart = System.currentTimeMillis();
        repo.save(singleTarget).join();
        report("[SINGLE UPDATE index %d -> score=%d] time=%d ms",
            singleTargetIdx, SINGLE_UPDATE_SCORE, System.currentTimeMillis() - stageStart);

        stageStart = System.currentTimeMillis();
        TestPlayer reloaded = repo.find(singleTarget.getUuid()).join()
            .orElseThrow(() -> new AssertionError("Updated player not found"));
        report("[RELOAD AFTER SINGLE UPDATE] score=%d | time=%d ms",
            reloaded.getScore(), System.currentTimeMillis() - stageStart);
        assertEquals(SINGLE_UPDATE_SCORE, reloaded.getScore(),
            "Score should be " + SINGLE_UPDATE_SCORE + " after single update");
    }

    // ------------------------------------------------------------------
    //  PHASE 11: delete operations
    // ------------------------------------------------------------------

    private void phase11_deletes() {
        report("\n--- PHASE 11: DELETE OPERATIONS ---");

        long stageStart = System.currentTimeMillis();
        boolean deletedGhost = repo.delete(UUID_GHOST).join();
        report("[DELETE NON-EXISTENT] deleted=%b | time=%d ms", deletedGhost, System.currentTimeMillis() - stageStart);
        assertFalse(deletedGhost, "delete() must return false for absent key");
        assertEquals(expectedCount, repo.count().join(), "count must not change after failed delete");

        // single delete - last player (index TOTAL_PLAYERS-1)
        lastIdx = TOTAL_PLAYERS - 1;
        UUID lastUuid = testPlayers.get(lastIdx).getUuid();
        stageStart = System.currentTimeMillis();
        boolean deletedLast = repo.delete(lastUuid).join();
        report("[DELETE SINGLE index %d] deleted=%b | time=%d ms",
            lastIdx, deletedLast, System.currentTimeMillis() - stageStart);
        assertTrue(deletedLast, "delete() must return true for existing player");
        assertFalse(repo.find(lastUuid).join().isPresent(), "Deleted player must not be findable");

        long countAfterSingleDelete = repo.count().join();
        report("[COUNT AFTER SINGLE DELETE] count=%,d", countAfterSingleDelete);
        assertEquals(expectedCount - 1, countAfterSingleDelete);

        stageStart = System.currentTimeMillis();
        boolean deletedAgain = repo.delete(lastUuid).join();
        report("[DELETE ALREADY-DELETED] deleted=%b | time=%d ms", deletedAgain, System.currentTimeMillis() - stageStart);
        assertFalse(deletedAgain, "Second delete of same key must return false");

        // bulk delete: indices 9000..9099 (100 players, none previously deleted)
        List<TestPlayer> toDelete = testPlayers.subList(BULK_DEL_FROM, BULK_DEL_TO + 1);

        stageStart = System.currentTimeMillis();
        int deletedBulkCount = 0;
        for (TestPlayer p : toDelete) {
            if (repo.delete(p.getUuid()).join()) {
                deletedBulkCount++;
            }
        }
        report("[BULK DELETE 100 idx %d..%d] deleted=%d | time=%d ms",
            BULK_DEL_FROM, BULK_DEL_TO, deletedBulkCount, System.currentTimeMillis() - stageStart);
        assertEquals(100, deletedBulkCount, "All 100 players in bulk range must be deleted");

        long countAfterBulkDelete = repo.count().join();
        report("[COUNT AFTER BULK DELETE] count=%,d", countAfterBulkDelete);
        assertEquals(expectedCount - 1 - 100, countAfterBulkDelete, "count must reflect 101 total deletions");

        List<UUID> deletedUuids = toDelete.stream().map(TestPlayer::getUuid).collect(Collectors.toList());
        stageStart = System.currentTimeMillis();
        List<TestPlayer> shouldBeEmpty = repo.findMany(deletedUuids).join();
        report("[FIND_MANY DELETED %d uuids] found=%d | time=%d ms",
            deletedUuids.size(), shouldBeEmpty.size(), System.currentTimeMillis() - stageStart);
        assertTrue(shouldBeEmpty.isEmpty(), "findMany on deleted keys must return empty list");

        Instant delFrom = baseDate.plus(BULK_DEL_FROM, ChronoUnit.DAYS);
        Instant delTo   = baseDate.plus(BULK_DEL_TO,   ChronoUnit.DAYS);
        stageStart = System.currentTimeMillis();
        List<TestPlayer> deletedRangeQuery = repo.query(Query.range("createdAt", delFrom, delTo)).join();
        report("[QUERY DELETED TIME RANGE] found=%d | time=%d ms",
            deletedRangeQuery.size(), System.currentTimeMillis() - stageStart);
        assertEquals(0, deletedRangeQuery.size(), "Query over deleted time range must return 0");

        // (indices 9000..9099 had score 9000..9099, none were in the updated 0..999 batch)
        stageStart = System.currentTimeMillis();
        List<TestPlayer> deletedScoreRange = repo.query(Query.range("score", BULK_DEL_FROM, BULK_DEL_TO)).join();
        report("[QUERY DELETED SCORE RANGE %d..%d] found=%d | time=%d ms",
            BULK_DEL_FROM, BULK_DEL_TO, deletedScoreRange.size(), System.currentTimeMillis() - stageStart);
        assertEquals(0, deletedScoreRange.size(), "Score query for deleted range must return 0");

        finalCount = repo.count().join();
        report("[FINAL COUNT] count=%,d", finalCount);
        assertEquals(expectedCount - 101, finalCount,
            "Final count: TOTAL + P9D inserts - 1 single - 100 bulk = " + (expectedCount - 101));
    }

    // ------------------------------------------------------------------
    //  Final summary
    // ------------------------------------------------------------------

    private void printFinalSummary(long totalMs) {
        LongSummaryStatistics tsStats = timestampQueryTimes.stream()
            .mapToLong(Long::longValue)
            .summaryStatistics();

        // Total query count across all query phases (score + bool + world + AND + timestamp)
        // Phase 4: 7 queries, Phase 5: 2, Phase 6: 4, Phase 7: 3, Phase 8: scenario count
        int totalQueryCount = 7 + 2 + 4 + 3 + (int) tsStats.getCount();
        long totalQueryMs   = phaseScoreMs + phaseBoolMs + phaseWorldMs + phaseAndMs + phaseTimestampMs;

        report("\n=================================================");
        report("FINAL SUMMARY");
        report("=================================================");

        report("\n[DATASET]");
        report("  Total Players    : %,d", TOTAL_PLAYERS);
        report("  Batch Size       : %,d", BATCH_SIZE);
        report("  Score range      : 0 .. %,d", TOTAL_PLAYERS - 1);
        report("  world_nether     : %,d  |  world : %,d", expectedWorldNether, expectedWorld);
        report("  active=true      : %,d  |  active=false : %,d", expectedActive, expectedInactive);

        report("\n[RECORD LIFECYCLE]");
        report("  Inserted         : %,d", TOTAL_PLAYERS);
        report("  SaveAll updated  : %,d (9A world remap + 9B active toggle + 9C score shift + 9D existing)",
            saveAllTotalSaved - saveAllNewInserts);
        report("  SaveAll inserted : %,d (9D new entities)", saveAllNewInserts);
        report("  Updated bulk     : 1,000 (score 0..999 -> %d)", BULK_UPDATE_SCORE);
        report("  Updated single   : 1 (idx %,d -> score %d)", singleTargetIdx, SINGLE_UPDATE_SCORE);
        report("  Deleted single   : 1 (idx %,d)", lastIdx);
        report("  Deleted bulk     : 100 (idx %,d..%,d)", BULK_DEL_FROM, BULK_DEL_TO);
        report("  Remaining        : %,d", finalCount);

        report("\n[THROUGHPUT]");
        double insertOpsPerSec = TOTAL_PLAYERS / Math.max(1.0, phaseInsertMs / 1000.0);
        double insertMsPerRec  = phaseInsertMs / (double) TOTAL_PLAYERS;
        report("  Insert           : %,d records in %,d ms  ->  %,.0f ops/s  |  %.3f ms/record",
            TOTAL_PLAYERS, phaseInsertMs, insertOpsPerSec, insertMsPerRec);

        double saveAllOpsPerSec = saveAllTotalSaved / Math.max(1.0, phaseSaveAllMs / 1000.0);
        report("  SaveAll (P9)     : %,d records in %,d ms  ->  %,.0f ops/s",
            saveAllTotalSaved, phaseSaveAllMs, saveAllOpsPerSec);

        double updateOpsPerSec = 1001.0 / Math.max(1.0, phaseUpdateMs / 1000.0);
        report("  Update           : 1,001 records in %,d ms  ->  %,.0f ops/s",
            phaseUpdateMs, updateOpsPerSec);

        double deleteOpsPerSec = 101.0 / Math.max(1.0, phaseDeleteMs / 1000.0);
        report("  Delete           : 101 records in %,d ms  ->  %,.0f ops/s",
            phaseDeleteMs, deleteOpsPerSec);

        report("\n[QUERY PERFORMANCE]");
        report("  Total queries    : %d  |  total time : %,d ms  |  avg : %.1f ms/query",
            totalQueryCount, totalQueryMs, totalQueryMs / (double) totalQueryCount);
        report("  Score queries    : %d queries in %,d ms  |  avg %.1f ms",
            7, phaseScoreMs, phaseScoreMs / 7.0);
        report("  Boolean queries  : %d queries in %,d ms  |  avg %.1f ms",
            2, phaseBoolMs, phaseBoolMs / 2.0);
        report("  World queries    : %d queries in %,d ms  |  avg %.1f ms",
            4, phaseWorldMs, phaseWorldMs / 4.0);
        report("  AND queries      : %d queries in %,d ms  |  avg %.1f ms",
            3, phaseAndMs, phaseAndMs / 3.0);
        report("  Timestamp scen.  : %d queries in %,d ms  |  min %d ms  |  max %d ms  |  avg %.1f ms",
            (int) tsStats.getCount(), phaseTimestampMs,
            tsStats.getMin(), tsStats.getMax(), tsStats.getAverage());

        report("\n[PHASE BREAKDOWN]");
        report("  P2  Insert       : %,d ms  (%d%%)", phaseInsertMs,    phaseInsertMs    * 100 / Math.max(1, totalMs));
        report("  P3  Basic reads  : %,d ms  (%d%%)", phaseReadsMs,     phaseReadsMs     * 100 / Math.max(1, totalMs));
        report("  P4  Score Q      : %,d ms  (%d%%)", phaseScoreMs,     phaseScoreMs     * 100 / Math.max(1, totalMs));
        report("  P5  Boolean Q    : %,d ms  (%d%%)", phaseBoolMs,      phaseBoolMs      * 100 / Math.max(1, totalMs));
        report("  P6  World Q      : %,d ms  (%d%%)", phaseWorldMs,     phaseWorldMs     * 100 / Math.max(1, totalMs));
        report("  P7  AND Q        : %,d ms  (%d%%)", phaseAndMs,       phaseAndMs       * 100 / Math.max(1, totalMs));
        report("  P8  Timestamp Q  : %,d ms  (%d%%)", phaseTimestampMs, phaseTimestampMs * 100 / Math.max(1, totalMs));
        report("  P9  saveAll Scen : %,d ms  (%d%%)", phaseSaveAllMs,   phaseSaveAllMs   * 100 / Math.max(1, totalMs));
        report("  P10 Update       : %,d ms  (%d%%)", phaseUpdateMs,    phaseUpdateMs    * 100 / Math.max(1, totalMs));
        report("  P11 Delete       : %,d ms  (%d%%)", phaseDeleteMs,    phaseDeleteMs    * 100 / Math.max(1, totalMs));
        report("  TOTAL            : %,d ms", totalMs);
        report("=================================================\n");
    }

    /**
     * Single funnel for the intentional stdout benchmark report.
     * With no args the line is printed verbatim; with args it is {@code printf}-formatted.
     */
    private static void report(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else                  System.out.printf(fmt + "%n", args);
    }

    private static final class RangeScenario {
        private final String name;
        private final Instant from;
        private final Instant to;
        private final int expectedCount;

        private RangeScenario(String name, Instant from, Instant to, int expectedCount) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.expectedCount = expectedCount;
        }
    }
}
