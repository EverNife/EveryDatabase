package br.com.finalcraft.evernifecore.storage.modules;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.versioned.OptimisticLockException;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.VersionedTestPlayer;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract tests for the optimistic-locking (versioned) capability.
 *
 * <p>Subclasses provide a concrete {@link Storage} via {@link #createStorage(String)}.
 * These tests exercise ALL backends (InMemory, LocalFile, H2) with zero Docker requirement.
 *
 * <p>The fixture entity ({@link VersionedTestPlayer}) is separate from {@link
 * br.com.finalcraft.evernifecore.storage.data.TestPlayer} so the 230+ non-versioned tests are
 * completely unaffected.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractVersionedStorageTest {

    // ------------------------------------------------------------------
    //  Fixed test UUIDs
    // ------------------------------------------------------------------

    public static final UUID UUID_ALPHA = UUID.fromString("aa000000-0000-0000-0000-000000000001");
    public static final UUID UUID_BETA  = UUID.fromString("bb000000-0000-0000-0000-000000000002");

    // ------------------------------------------------------------------
    //  Versioned descriptor - activates optimistic locking
    // ------------------------------------------------------------------

    public static final EntityDescriptor<UUID, VersionedTestPlayer> VERSIONED_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
            .collection("versioned_players")
            .keyExtractor(VersionedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
            .versioned()          // uses Versioned::getLockVersion / setLockVersion
            .build();

    // ------------------------------------------------------------------
    //  Non-versioned descriptor - must keep plain upsert behaviour
    // ------------------------------------------------------------------

    public static final EntityDescriptor<UUID, VersionedTestPlayer> PLAIN_DESCRIPTOR =
        EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
            .collection("plain_players")
            .keyExtractor(VersionedTestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
            .build();  // no .versioned() -> isVersioned()==false

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    protected Storage storage;
    protected Repository<UUID, VersionedTestPlayer> vRepo;
    protected Repository<UUID, VersionedTestPlayer> plainRepo;

    protected abstract Storage createStorage(String testMethodName);

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String methodName = testInfo.getTestMethod()
            .map(java.lang.reflect.Method::getName).orElse("unknown");
        storage   = createStorage(methodName);
        storage.init().join();
        vRepo     = storage.repository(VERSIONED_DESCRIPTOR);
        plainRepo = storage.repository(PLAIN_DESCRIPTOR);
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

    // ------------------------------------------------------------------
    //  A3 - isVersioned() on descriptor
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    //  A7 - First insert lands at version 0
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("[versioned] first save() -> entity lands at version 0")
    void firstSave_landsAtVersionZero() {
        VersionedTestPlayer p = alpha();
        assertEquals(0L, p.getLockVersion(), "Entity starts at version 0 before save");

        vRepo.save(p).join();

        // In-memory entity must reflect version 0 after insert.
        assertEquals(0L, p.getLockVersion(),
            "Entity version must still be 0 after first insert");

        // Reloaded entity must also have version 0.
        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(0L, loaded.get().getLockVersion(),
            "Persisted entity must have lock_version=0 after first insert");
    }

    // ------------------------------------------------------------------
    //  A7 - Second save increments to version 1
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("[versioned] second save() increments version to 1 and updates entity")
    void secondSave_incrementsVersionToOne() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();                    // insert -> version 0
        assertEquals(0L, p.getLockVersion());

        p.setScore(99);
        vRepo.save(p).join();                    // update -> version 1
        assertEquals(1L, p.getLockVersion(),
            "In-memory entity must be updated to version 1 after second save");

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(1L, loaded.get().getLockVersion(),
            "Persisted entity must have lock_version=1 after second save");
        assertEquals(99, loaded.get().getScore(),
            "Updated field must be persisted");
    }

    // ------------------------------------------------------------------
    //  A7 - Stale save throws OptimisticLockException
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("[versioned] stale save (wrong version) -> OptimisticLockException")
    void staleSave_throwsOptimisticLockException() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();   // insert, version=0

        // Simulate another writer updating the record (version -> 1).
        VersionedTestPlayer concurrent = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 10);
        concurrent.setLockVersion(0L);
        vRepo.save(concurrent).join();
        assertEquals(1L, concurrent.getLockVersion());

        // Now try to save `p` which still has version=0 (stale).
        // The backend should throw OptimisticLockException.
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
            // Some backends throw directly (InMemory) rather than wrapping in CompletionException.
            assertEquals(0L, ole.getExpectedVersion());
        }
    }

    // ------------------------------------------------------------------
    //  A7 - Multiple round-trips
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    //  A7 - Reload and save succeeds after conflict detection
    // ------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("[versioned] reload after conflict -> save succeeds with current version")
    void reloadAfterConflict_saveSucceeds() {
        VersionedTestPlayer p = alpha();
        vRepo.save(p).join();  // version 0

        // Another writer advances to version 1.
        VersionedTestPlayer other = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 10);
        other.setLockVersion(0L);
        vRepo.save(other).join();

        // Reload the fresh version from backend.
        VersionedTestPlayer fresh = vRepo.find(UUID_ALPHA).join().orElseThrow(AssertionError::new);
        assertEquals(1L, fresh.getLockVersion());

        // Save with the fresh version must succeed.
        fresh.setScore(77);
        vRepo.save(fresh).join();
        assertEquals(2L, fresh.getLockVersion());

        Optional<VersionedTestPlayer> loaded = vRepo.find(UUID_ALPHA).join();
        assertTrue(loaded.isPresent());
        assertEquals(77, loaded.get().getScore());
        assertEquals(2L, loaded.get().getLockVersion());
    }

    // ------------------------------------------------------------------
    //  A7 - Non-versioned descriptor keeps plain upsert (existing behaviour)
    // ------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("[versioned] non-versioned descriptor still upserts normally")
    void plainDescriptor_upsertsBehaviourUnchanged() {
        VersionedTestPlayer p1 = alpha();
        p1.setLockVersion(0L);
        plainRepo.save(p1).join();

        // Second save with same key -> upsert, no exception.
        VersionedTestPlayer p2 = new VersionedTestPlayer(UUID_ALPHA, "Alpha", 999);
        p2.setLockVersion(0L);  // version field is just a plain POJO field for non-versioned
        plainRepo.save(p2).join();

        VersionedTestPlayer found = plainRepo.find(UUID_ALPHA).join()
            .orElseThrow(AssertionError::new);
        assertEquals(999, found.getScore(), "Non-versioned must upsert without conflict checking");
    }

    // ------------------------------------------------------------------
    //  A7 - saveAll with versioned entities
    // ------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("[versioned] saveAll() inserts all at version 0 then increments on re-save")
    void saveAll_versionedEntities() {
        VersionedTestPlayer a = alpha();
        VersionedTestPlayer b = beta();

        java.util.List<VersionedTestPlayer> batch = java.util.Arrays.asList(a, b);
        vRepo.saveAll(batch).join();

        assertEquals(0L, a.getLockVersion());
        assertEquals(0L, b.getLockVersion());

        // Re-save both - both should increment.
        a.setScore(11);
        b.setScore(22);
        vRepo.saveAll(batch).join();

        assertEquals(1L, a.getLockVersion());
        assertEquals(1L, b.getLockVersion());

        assertEquals(11, vRepo.find(UUID_ALPHA).join().orElseThrow(AssertionError::new).getScore());
        assertEquals(22, vRepo.find(UUID_BETA).join().orElseThrow(AssertionError::new).getScore());
    }

    // ------------------------------------------------------------------
    //  Helper: accessor to the versioned descriptor for type checks
    // ------------------------------------------------------------------

    protected EntityDescriptor<UUID, VersionedTestPlayer> descriptor() {
        return VERSIONED_DESCRIPTOR;
    }
}
