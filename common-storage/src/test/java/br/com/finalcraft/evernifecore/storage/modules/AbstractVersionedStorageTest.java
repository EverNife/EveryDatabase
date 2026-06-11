package br.com.finalcraft.evernifecore.storage.modules;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.AnnotatedTestPlayer;
import br.com.finalcraft.evernifecore.storage.data.VersionedTestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Indexed;
import br.com.finalcraft.evernifecore.storage.versioned.OptimisticLockException;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract tests for:
 * <ol>
 *   <li><b>Optimistic locking (versioned)</b> - {@link VersionedTestPlayer} with
 *       {@code lock_version} enforcement (Orders 1-60).</li>
 *   <li><b>{@link Indexed} annotation auto-detection</b> - simple fields, nested dot-path
 *       indexes, un-indexed list fields, and error cases (Orders 100-161).</li>
 * </ol>
 *
 * <p>Subclasses provide a concrete {@link Storage} via {@link #createStorage(String)}.
 * Running the suite against multiple backends (MariaDB, PostgreSQL, MongoDB) ensures
 * both features work correctly across the full range of supported databases.
 *
 * <p>{@link VersionedTestPlayer} and {@link AnnotatedTestPlayer} are separate fixtures from
 * {@link br.com.finalcraft.evernifecore.storage.data.TestPlayer}, each exercising one feature
 * area in isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractVersionedStorageTest {

    // ------------------------------------------------------------------
    //  Fixed test UUIDs
    // ------------------------------------------------------------------

    public static final UUID UUID_ALPHA = UUID.fromString("aa000000-0000-0000-0000-000000000001");
    public static final UUID UUID_BETA  = UUID.fromString("bb000000-0000-0000-0000-000000000002");
    public static final UUID UUID_CAROL = UUID.fromString("cc000000-0000-0000-0000-000000000003");

    // ------------------------------------------------------------------
    //  Versioned descriptor - activates optimistic locking
    //  @Indexed on VersionedTestPlayer.name and .score are picked up automatically
    // ------------------------------------------------------------------

    public static final EntityDescriptor<UUID, VersionedTestPlayer> VERSIONED_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
            .collection("versioned_players")
            .keyExtractor(VersionedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
            .versioned()
            .build();

    // ------------------------------------------------------------------
    //  Non-versioned descriptor - must keep plain upsert behaviour
    // ------------------------------------------------------------------

    public static final EntityDescriptor<UUID, VersionedTestPlayer> PLAIN_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
            .collection("plain_players")
            .keyExtractor(VersionedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
            .build();

    // ------------------------------------------------------------------
    //  Annotated descriptor - @Indexed auto-detection tests
    //  Picks up: name (STRING), score (INT), rank.title (STRING), location.world (STRING)
    // ------------------------------------------------------------------

    public static final EntityDescriptor<UUID, AnnotatedTestPlayer> ANNOTATED_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, AnnotatedTestPlayer.class)
            .collection("annotated_players")
            .keyExtractor(AnnotatedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(AnnotatedTestPlayer.class))
            .build();

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    protected Storage storage;
    protected Repository<UUID, VersionedTestPlayer> vRepo;
    protected Repository<UUID, VersionedTestPlayer> plainRepo;
    protected Repository<UUID, AnnotatedTestPlayer> aRepo;

    protected abstract Storage createStorage(String testMethodName);

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String methodName = testInfo.getTestMethod()
            .map(java.lang.reflect.Method::getName).orElse("unknown");
        storage   = createStorage(methodName);
        storage.init().join();
        vRepo     = storage.repository(VERSIONED_DESCRIPTOR);
        plainRepo = storage.repository(PLAIN_DESCRIPTOR);
        aRepo     = storage.repository(ANNOTATED_DESCRIPTOR);
    }

    @AfterEach
    void tearDown() {
        storage.close().join();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    VersionedTestPlayer alpha() { return new VersionedTestPlayer(UUID_ALPHA, "Alpha", 10); }
    VersionedTestPlayer beta()  { return new VersionedTestPlayer(UUID_BETA,  "Beta",  20); }

    AnnotatedTestPlayer aAlice() {
        return new AnnotatedTestPlayer(
            UUID_ALPHA, "Alice", 100,
            new AnnotatedTestPlayer.Rank("Diamond", 3),
            new AnnotatedTestPlayer.Location("world", 10.0, 64.0, -5.0),
            Arrays.asList(
                new AnnotatedTestPlayer.Badge("FirstBlood", 1_000_000L),
                new AnnotatedTestPlayer.Badge("Veteran",    2_000_000L)
            )
        );
    }

    AnnotatedTestPlayer aBob() {
        return new AnnotatedTestPlayer(
            UUID_BETA, "Bob", 50,
            new AnnotatedTestPlayer.Rank("Gold", 1),
            new AnnotatedTestPlayer.Location("world_nether", 0.0, 64.0, 0.0),
            Arrays.asList(new AnnotatedTestPlayer.Badge("Newcomer", 500_000L))
        );
    }

    AnnotatedTestPlayer aCarol() {
        return new AnnotatedTestPlayer(
            UUID_CAROL, "Carol", 200,
            new AnnotatedTestPlayer.Rank("Diamond", 5),
            new AnnotatedTestPlayer.Location("world", 99.0, 70.0, 99.0),
            Arrays.asList()
        );
    }

    // ==================================================================
    //  SECTION 1: Optimistic locking (Orders 1-60)
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("[versioned] isVersioned() == true for versioned descriptor")
    void descriptor_isVersioned_trueForVersioned() {
        assertTrue(VERSIONED_DESCRIPTOR.isVersioned(),
            "A descriptor with .versioned() must report isVersioned()==true");
    }

    @Test
    @Order(2)
    @DisplayName("[versioned] isVersioned() == false for plain descriptor")
    void descriptor_isVersioned_falseForPlain() {
        assertFalse(PLAIN_DESCRIPTOR.isVersioned(),
            "A descriptor without .versioned() must report isVersioned()==false");
    }

    @Test
    @Order(10)
    @DisplayName("[versioned] first save() -> entity lands at version 0")
    void firstSave_landsAtVersionZero() {
        VersionedTestPlayer p = alpha();
        assertEquals(0L, p.getLockVersion(), "Entity starts at version 0 before save");

        vRepo.save(p).join();

        assertEquals(0L, p.getLockVersion(),
            "Entity version must still be 0 after first insert");

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(0L, loaded.get().getLockVersion(),
            "Persisted entity must have lock_version=0 after first insert");
    }

    @Test
    @Order(11)
    @DisplayName("[versioned] second save() increments version to 1 and updates entity")
    void secondSave_incrementsVersionToOne() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();
        assertEquals(0L, p.getLockVersion());

        p.setScore(99);
        vRepo.save(p).join();
        assertEquals(1L, p.getLockVersion(),
            "In-memory entity must be updated to version 1 after second save");

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(1L, loaded.get().getLockVersion(),
            "Persisted entity must have lock_version=1 after second save");
        assertEquals(99, loaded.get().getScore(),
            "Updated field must be persisted");
    }

    @Test
    @Order(20)
    @DisplayName("[versioned] stale save (wrong version) -> OptimisticLockException")
    void staleSave_throwsOptimisticLockException() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();

        VersionedTestPlayer concurrent = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 10);
        concurrent.setLockVersion(0L);
        vRepo.save(concurrent).join();
        assertEquals(1L, concurrent.getLockVersion());

        try {
            vRepo.save(p).join();
            fail("Expected OptimisticLockException for stale save");
        } catch (CompletionException ce) {
            assertInstanceOf(OptimisticLockException.class, ce.getCause(),
                "Cause must be OptimisticLockException");
            OptimisticLockException ole = (OptimisticLockException) ce.getCause();
            assertEquals(descriptor().type(), ole.getEntityType());
            assertEquals(0L, ole.getExpectedVersion(), "Expected version should be 0");
        } catch (OptimisticLockException ole) {
            assertEquals(0L, ole.getExpectedVersion());
        }
    }

    @Test
    @Order(30)
    @DisplayName("[versioned] three consecutive saves increment version correctly")
    void threeConsecutiveSaves_versionIncrements() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join(); assertEquals(0L, p.getLockVersion());
        vRepo.save(p).join(); assertEquals(1L, p.getLockVersion());
        vRepo.save(p).join(); assertEquals(2L, p.getLockVersion());

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(2L, loaded.get().getLockVersion());
        assertEquals(p, loaded.get());
    }

    @Test
    @Order(40)
    @DisplayName("[versioned] reload after conflict -> save succeeds with current version")
    void reloadAfterConflict_saveSucceeds() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();

        VersionedTestPlayer other = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 10);
        other.setLockVersion(0L);
        vRepo.save(other).join();

        VersionedTestPlayer fresh = vRepo.find(UUID_ALPHA).join().orElseThrow(AssertionError::new);
        assertEquals(1L, fresh.getLockVersion());

        fresh.setScore(77);
        vRepo.save(fresh).join();
        assertEquals(2L, fresh.getLockVersion());

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(77, loaded.get().getScore());
        assertEquals(2L, loaded.get().getLockVersion());
    }

    @Test
    @Order(50)
    @DisplayName("[versioned] non-versioned descriptor still upserts normally")
    void plainDescriptor_upsertsBehaviourUnchanged() {
        VersionedTestPlayer p1 = alpha();
        p1.setLockVersion(0L);
        plainRepo.save(p1).join();

        VersionedTestPlayer p2 = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 999);
        p2.setLockVersion(0L);
        plainRepo.save(p2).join();

        VersionedTestPlayer found = plainRepo.find(UUID_ALPHA).join()
            .orElseThrow(AssertionError::new);
        assertEquals(999, found.getScore(), "Non-versioned must upsert without conflict checking");
    }

    @Test
    @Order(60)
    @DisplayName("[versioned] saveAll() inserts all at version 0 then increments on re-save")
    void saveAll_versionedEntities() {
        VersionedTestPlayer a = alpha();
        VersionedTestPlayer b = beta();

        List<VersionedTestPlayer> batch = Arrays.asList(a, b);
        vRepo.saveAll(batch).join();

        assertEquals(0L, a.getLockVersion());
        assertEquals(0L, b.getLockVersion());

        a.setScore(11);
        b.setScore(22);
        vRepo.saveAll(batch).join();

        assertEquals(1L, a.getLockVersion());
        assertEquals(1L, b.getLockVersion());

        assertEquals(11, vRepo.find(UUID_ALPHA).join().orElseThrow(AssertionError::new).getScore());
        assertEquals(22, vRepo.find(UUID_BETA).join().orElseThrow(AssertionError::new).getScore());
    }

    // ==================================================================
    //  SECTION 2: @Indexed annotation auto-detection (Orders 100-161)
    // ==================================================================

    @Test
    @Order(100)
    @DisplayName("[@Indexed] VersionedTestPlayer: auto-detects @Indexed(name) and @Indexed(score)")
    void versionedTestPlayer_indexesAutoDetected() {
        List<IndexHint> indexes = VERSIONED_DESCRIPTOR.indexes();
        assertEquals(2, indexes.size(),
            "Expected 2 @Indexed fields: name and score");
        assertTrue(indexes.stream().anyMatch(h -> h.fieldPath().equals("name")),
            "Index on 'name' must be present");
        assertTrue(indexes.stream().anyMatch(h -> h.fieldPath().equals("score")),
            "Index on 'score' must be present");
    }

    @Test
    @Order(101)
    @DisplayName("[@Indexed] VersionedTestPlayer: 'name' index has FieldType STRING")
    void versionedTestPlayer_nameIndex_isString() {
        IndexHint nameHint = VERSIONED_DESCRIPTOR.indexes().stream()
            .filter(h -> h.fieldPath().equals("name"))
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals(IndexHint.FieldType.STRING, nameHint.fieldType());
    }

    @Test
    @Order(102)
    @DisplayName("[@Indexed] VersionedTestPlayer: 'score' index has FieldType INT")
    void versionedTestPlayer_scoreIndex_isInt() {
        IndexHint scoreHint = VERSIONED_DESCRIPTOR.indexes().stream()
            .filter(h -> h.fieldPath().equals("score"))
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals(IndexHint.FieldType.INT, scoreHint.fieldType());
    }

    @Test
    @Order(110)
    @DisplayName("[@Indexed] AnnotatedTestPlayer: auto-detects all 4 @Indexed fields")
    void annotatedTestPlayer_allIndexesAutoDetected() {
        List<IndexHint> indexes = ANNOTATED_DESCRIPTOR.indexes();
        assertEquals(4, indexes.size(),
            "Expected 4 indexes: name, score, rank.title, location.world");

        List<String> paths = new java.util.ArrayList<>();
        for (IndexHint h : indexes) paths.add(h.fieldPath());

        assertTrue(paths.contains("name"),           "Index on 'name' missing");
        assertTrue(paths.contains("score"),          "Index on 'score' missing");
        assertTrue(paths.contains("rank.title"),     "Index on 'rank.title' missing");
        assertTrue(paths.contains("location.world"), "Index on 'location.world' missing");
    }

    @Test
    @Order(111)
    @DisplayName("[@Indexed] AnnotatedTestPlayer: 'rank.title' resolved to STRING via type override")
    void annotatedTestPlayer_rankTitleIndex_isString() {
        IndexHint hint = ANNOTATED_DESCRIPTOR.indexes().stream()
            .filter(h -> h.fieldPath().equals("rank.title"))
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals(IndexHint.FieldType.STRING, hint.fieldType());
    }

    @Test
    @Order(120)
    @DisplayName("[@Indexed] findBy 'name' returns matching entities (VersionedTestPlayer)")
    void versionedRepo_findByName_returnsMatch() {
        vRepo.save(alpha()).join();
        vRepo.save(beta()).join();

        List<VersionedTestPlayer> found = vRepo.findBy("name", "Alpha").join();

        assertEquals(1, found.size());
        assertEquals(UUID_ALPHA, found.get(0).getUuid());
    }

    @Test
    @Order(121)
    @DisplayName("[@Indexed] findBy 'score' returns matching entities (VersionedTestPlayer)")
    void versionedRepo_findByScore_returnsMatch() {
        vRepo.save(alpha()).join();
        vRepo.save(beta()).join();

        List<VersionedTestPlayer> found = vRepo.findBy("score", 20).join();

        assertEquals(1, found.size());
        assertEquals(UUID_BETA, found.get(0).getUuid());
    }

    @Test
    @Order(130)
    @DisplayName("[@Indexed] findBy 'name' returns matching entities (AnnotatedTestPlayer)")
    void annotatedRepo_findByName_returnsMatch() {
        aRepo.save(aAlice()).join();
        aRepo.save(aBob()).join();
        aRepo.save(aCarol()).join();

        List<AnnotatedTestPlayer> found = aRepo.findBy("name", "Bob").join();

        assertEquals(1, found.size());
        assertEquals(UUID_BETA, found.get(0).getUuid());
    }

    @Test
    @Order(140)
    @DisplayName("[@Indexed] findBy 'rank.title' queries through nested Rank object")
    void annotatedRepo_findByNestedRankTitle_returnsCorrectEntities() {
        aRepo.save(aAlice()).join(); // Diamond
        aRepo.save(aBob()).join();   // Gold
        aRepo.save(aCarol()).join(); // Diamond

        List<AnnotatedTestPlayer> diamonds = aRepo.findBy("rank.title", "Diamond").join();

        assertEquals(2, diamonds.size(),
            "Alice and Carol are both Diamond - both must be returned");
        assertTrue(diamonds.stream().anyMatch(p -> p.getUuid().equals(UUID_ALPHA)));
        assertTrue(diamonds.stream().anyMatch(p -> p.getUuid().equals(UUID_CAROL)));
    }

    @Test
    @Order(141)
    @DisplayName("[@Indexed] findBy 'location.world' filters by nested world name")
    void annotatedRepo_findByNestedLocationWorld_returnsCorrectEntities() {
        aRepo.save(aAlice()).join(); // world
        aRepo.save(aBob()).join();   // world_nether
        aRepo.save(aCarol()).join(); // world

        List<AnnotatedTestPlayer> inNether = aRepo.findBy("location.world", "world_nether").join();

        assertEquals(1, inNether.size());
        assertEquals(UUID_BETA, inNether.get(0).getUuid());
    }

    @Test
    @Order(150)
    @DisplayName("[@Indexed] List<Badge> field is stored and retrieved correctly (no index)")
    void annotatedRepo_listField_roundTripsCorrectly() {
        aRepo.save(aAlice()).join(); // 2 badges

        Optional<AnnotatedTestPlayer> loaded = aRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());

        List<AnnotatedTestPlayer.Badge> badges = loaded.get().getBadges();
        assertEquals(2, badges.size(), "Both badges must survive the round-trip");
        assertEquals("FirstBlood", badges.get(0).getName());
        assertEquals("Veteran",    badges.get(1).getName());
        assertEquals(1_000_000L,   badges.get(0).getEarnedAt());
        assertEquals(2_000_000L,   badges.get(1).getEarnedAt());
    }

    @Test
    @Order(151)
    @DisplayName("[@Indexed] Empty List<Badge> field is stored and retrieved as empty list")
    void annotatedRepo_emptyList_roundTripsAsEmpty() {
        aRepo.save(aCarol()).join(); // empty badges

        Optional<AnnotatedTestPlayer> loaded = aRepo.find(UUID_CAROL).join();
        assertTrue(loaded.isPresent());

        List<AnnotatedTestPlayer.Badge> badges = loaded.get().getBadges();
        assertNotNull(badges);
        assertTrue(badges.isEmpty(), "Empty badge list must survive the round-trip as empty");
    }

    @Test
    @Order(160)
    @DisplayName("[@Indexed] unsupported field type (UUID) without type= throws at build()")
    void build_unsupportedFieldType_throwsIllegalArgument() {
        class BadEntity {
            @Indexed
            private UUID id = UUID.randomUUID();
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            EntityDescriptor.builder(UUID.class, BadEntity.class)
                .collection("bad_entity")
                .keyExtractor(e -> UUID.randomUUID())
                .codec(new JacksonJsonCodec<>(BadEntity.class))
                .build()
        );
        assertTrue(ex.getMessage().contains("cannot auto-detect IndexHint type"),
            "Error must mention the auto-detection failure");
        assertTrue(ex.getMessage().contains("UUID"),
            "Error must mention the unsupported type");
    }

    @Test
    @Order(161)
    @DisplayName("[@Indexed] duplicate index (manual .index() + @Indexed same path) throws at build()")
    void build_duplicateIndex_manualAndAnnotation_throwsIllegalState() {
        // 'name' is declared via @Indexed on AnnotatedTestPlayer; adding it again manually triggers the duplicate check.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            EntityDescriptor.builder(UUID.class, AnnotatedTestPlayer.class)
                .collection("dup_players")
                .keyExtractor(AnnotatedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(AnnotatedTestPlayer.class))
                .index(IndexHint.string("name"))
                .build()
        );
        assertTrue(ex.getMessage().contains("duplicate index hint on field 'name'"),
            "Error must identify the duplicated field path");
    }

    // ------------------------------------------------------------------
    //  Helper: accessor to the versioned descriptor for type checks
    // ------------------------------------------------------------------

    protected EntityDescriptor<UUID, VersionedTestPlayer> descriptor() {
        return VERSIONED_DESCRIPTOR;
    }
}
