package br.com.finalcraft.evernifecore.storage.modules.sql;

import br.com.finalcraft.evernifecore.storage.EntityDescriptor;
import br.com.finalcraft.evernifecore.storage.Repository;
import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.data.VersionedTestPlayer;
import br.com.finalcraft.evernifecore.storage.log.StorageLogEvent;
import br.com.finalcraft.evernifecore.storage.log.StorageLogLevel;
import br.com.finalcraft.evernifecore.storage.log.StorageOp;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.AbstractTransactionalStorageTest;
import br.com.finalcraft.evernifecore.storage.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
import br.com.finalcraft.evernifecore.storage.query.Query;
import br.com.finalcraft.evernifecore.testutil.CapturingSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete test suite for the H2 storage backend ({@link H2SqlStorage}:
 * ANSI double-quote identifiers, {@code TEXT}, {@code MERGE INTO ... KEY (...) VALUES (?)}).
 *
 * <p>H2 runs in-process as an embedded database - no external server or Docker container
 * is required. Each test method gets its own named in-memory database for full isolation.
 *
 * <p>Inherits the full contract suite from {@link AbstractStorageTest} plus the shared
 * transactional/lifecycle/schema-evolution suite from {@link AbstractTransactionalStorageTest},
 * and adds the H2-only schema <em>enforcement</em> test (removed IndexHint drops its column).
 *
 * <pre>
 * ./gradlew :common-storage:test --tests "*H2StorageTest"
 * </pre>
 */
@DisplayName("H2Storage (embedded, no external server required)")
class H2StorageTest extends AbstractTransactionalStorageTest {

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    /**
     * JDBC URL for the database created for the current test method.
     * Reused by the schema-drift subtests to open V1 and V2 on the same database.
     */
    private String currentTestDbUrl;

    @Override
    protected Storage createStorage(String testMethodName) {
        // Each test gets its own named in-memory database for full isolation.
        // DATABASE_TO_UPPER=FALSE preserves column/table names as declared.
        // DB_CLOSE_DELAY=-1 keeps the database alive for the duration of the pool.
        currentTestDbUrl = "jdbc:h2:mem:" + testMethodName
            + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        return new H2SqlStorage(new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL));
    }

    @Override
    protected Storage openExtraStorageOnSameDatabase() {
        return new H2SqlStorage(new SqlConfig(currentTestDbUrl, "sa", "", TEST_POOL));
    }

    // ------------------------------------------------------------------
    //  H2-specific: schema enforcement (removed IndexHint)
    // ------------------------------------------------------------------

    @Test
    @Order(1061)
    @DisplayName("enforcement: an IndexHint removed from the descriptor drops its _idx_ column and index")
    void schemaEnforcement_removedIndexHint_columnAndIndexDropped() throws Exception {
        // --- V1 descriptor: BOTH "name" and "score" indexed ---
        EntityDescriptor<UUID, TestPlayer> v1 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_enforcement")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .build();

        Storage storageV1 = openExtraStorageOnSameDatabase();
        storageV1.init().join();
        storageV1.repository(v1).save(alice()).join();
        storageV1.close().join();

        assertTrue(indexColumnPresent("schema_enforcement", "_idx_score"),
            "Precondition: _idx_score must exist after V1 created the table");

        // --- V2 descriptor: only "name" indexed ("score" removed) ---
        EntityDescriptor<UUID, TestPlayer> v2 = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("schema_enforcement")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        Storage storageV2 = openExtraStorageOnSameDatabase();
        storageV2.init().join();
        Repository<UUID, TestPlayer> repoV2 = storageV2.repository(v2);

        // Enforcement must have dropped the undeclared _idx_score column (its index goes with it).
        assertFalse(indexColumnPresent("schema_enforcement", "_idx_score"),
            "Enforcement must drop the _idx_score column that is no longer declared");

        // The still-declared name index keeps working and the data is intact.
        assertEquals(1L, repoV2.count().join());
        assertEquals(1, repoV2.query(Query.eq("name", "Alice")).join().size(),
            "The retained name index must still return Alice");

        storageV2.close().join();
    }

    // ------------------------------------------------------------------
    //  H2-specific: optimistic locking is intentionally NOT supported
    // ------------------------------------------------------------------

    /**
     * Documents the designed behavior: {@code H2SqlRepository.supportsVersioning()} returns
     * {@code false}, so a versioned descriptor on H2 silently degrades to plain upsert
     * semantics - no {@code lock_version} enforcement, no {@code OptimisticLockException}.
     * Use MariaDB/PostgreSQL/Mongo when optimistic locking matters.
     */
    @Test
    @Order(1065)
    @DisplayName("documental: a versioned descriptor on H2 falls back to plain upsert (no optimistic locking)")
    void versionedDescriptor_onH2_isPlainUpsert() {
        EntityDescriptor<UUID, VersionedTestPlayer> versioned =
            EntityDescriptor.builder(UUID.class, VersionedTestPlayer.class)
                .collection("versioned_on_h2")
                .keyExtractor(VersionedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(VersionedTestPlayer.class))
                .versioned()
                .build();

        Repository<UUID, VersionedTestPlayer> vRepo = storage.repository(versioned);

        // On a versioning backend this sequence would throw OptimisticLockException on the
        // second save (stale version 0). On H2 both saves are plain upserts - last one wins.
        vRepo.save(new VersionedTestPlayer(UUID_ALICE, "Alice", 100)).join();
        VersionedTestPlayer stale = new VersionedTestPlayer(UUID_ALICE, "Alice", 999);
        assertDoesNotThrow(() -> vRepo.save(stale).join(),
            "H2 must not enforce optimistic locking (supportsVersioning()=false by design)");

        assertEquals(999, vRepo.find(UUID_ALICE).join().orElseThrow(AssertionError::new).getScore(),
            "Plain upsert semantics: the last save wins");
    }

    // ------------------------------------------------------------------
    //  H2-specific: storage log events that need a real SQL dialect
    //  (specs/SPEC_storage_logging.md, secao 12 - "InMemory/H2, sem Docker")
    // ------------------------------------------------------------------

    @Test
    @Order(1070)
    @DisplayName("log: index evolution emits INDEX_CREATE/COLUMN_ADD/INDEX_DROP and INFO reconcile summaries")
    void log_indexEvolution_emitsCreateDropAndReconcile() {
        EntityDescriptor<UUID, TestPlayer> nameOnly = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("log_index_events")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .build();

        EntityDescriptor<UUID, TestPlayer> nameAndScore = EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection("log_index_events")
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class))
            .index(IndexHint.string("name"))
            .index(IndexHint.integer("score"))
            .build();

        // --- V1: fresh table. Index columns are created inline in CREATE TABLE, so this is
        //     one atomic act, not an evolution: no INDEX_CREATE, and the reconcile no-op
        //     stays at DEBUG (invisible at INFO).
        Storage storageV1 = openExtraStorageOnSameDatabase();
        storageV1.init().join();
        CapturingSink captureV1 = new CapturingSink();
        storageV1.getStorageLogConfig().defaultLevel(StorageLogLevel.INFO).sink(captureV1);

        storageV1.repository(nameOnly).save(alice()).join();

        assertTrue(captureV1.byOp(StorageOp.INDEX_CREATE).isEmpty(),
            "A fresh table is not an evolution - no INDEX_CREATE events");
        assertTrue(captureV1.byOp(StorageOp.INDEX_RECONCILE).isEmpty(),
            "A no-op reconcile must stay at DEBUG (invisible at INFO)");
        storageV1.close().join();

        // --- V2: "score" hint added to the existing table -> COLUMN_ADD + INDEX_CREATE +
        //     INFO reconcile listing the created field and the backfilled row.
        Storage storageV2 = openExtraStorageOnSameDatabase();
        storageV2.init().join();
        CapturingSink captureV2 = new CapturingSink();
        storageV2.getStorageLogConfig().defaultLevel(StorageLogLevel.INFO).sink(captureV2);

        storageV2.repository(nameAndScore);

        List<StorageLogEvent> creates = captureV2.byOp(StorageOp.INDEX_CREATE);
        assertEquals(1, creates.size(), "Exactly the newly added hint must emit INDEX_CREATE");
        assertTrue(creates.get(0).detail().contains("field=score"), creates.get(0).detail());
        assertEquals(1, captureV2.byOp(StorageOp.COLUMN_ADD).size(),
            "The new _idx_score column must emit COLUMN_ADD");

        List<StorageLogEvent> reconcilesV2 = captureV2.byOp(StorageOp.INDEX_RECONCILE);
        assertEquals(1, reconcilesV2.size(), "An evolution reconcile must emit one INFO summary");
        assertEquals(StorageLogLevel.INFO, reconcilesV2.get(0).level());
        String detailV2 = reconcilesV2.get(0).detail();
        assertTrue(detailV2.contains("created=") && detailV2.contains("score"),
            "Reconcile summary must list the created field: " + detailV2);
        assertTrue(detailV2.contains("backfilled=1"),
            "Alice's pre-existing row must be backfilled into the new column: " + detailV2);
        storageV2.close().join();

        // --- V3: back to name-only -> the score index/column is dropped (enforcement).
        Storage storageV3 = openExtraStorageOnSameDatabase();
        storageV3.init().join();
        CapturingSink captureV3 = new CapturingSink();
        storageV3.getStorageLogConfig().defaultLevel(StorageLogLevel.INFO).sink(captureV3);

        storageV3.repository(nameOnly);

        assertEquals(1, captureV3.byOp(StorageOp.INDEX_DROP).size(),
            "The undeclared score index must emit INDEX_DROP");
        List<StorageLogEvent> reconcilesV3 = captureV3.byOp(StorageOp.INDEX_RECONCILE);
        assertEquals(1, reconcilesV3.size());
        assertTrue(reconcilesV3.get(0).detail().contains("dropped="),
            "Reconcile summary must list the dropped column: " + reconcilesV3.get(0).detail());
        storageV3.close().join();
    }

    @Test
    @Order(1071)
    @DisplayName("log: a corrupted row is skipped with a WARN event and all() still returns the valid rows")
    void log_corruptedRow_skipsWithWarnEvent() throws Exception {
        repo.saveAll(Arrays.asList(alice(), bob())).join();

        // Corrupt Alice's JSON payload behind the repository's back (H2 stores it as TEXT).
        try (Connection conn = DriverManager.getConnection(currentTestDbUrl, "sa", "");
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE \"test_players\" SET \"storage_data\" = '{this is not json' WHERE \"storage_key\" = ?")) {
            ps.setString(1, UUID_ALICE.toString());
            assertEquals(1, ps.executeUpdate(), "Precondition: Alice's row must exist to be corrupted");
        }

        // No level tuning: WARN passes under both the WARN factory default and the INFO test preset.
        CapturingSink capture = new CapturingSink();
        storage.getStorageLogConfig().sink(capture);

        List<TestPlayer> survivors = repo.all().join().collect(Collectors.toList());
        assertEquals(1, survivors.size(), "all() must skip the corrupted row and keep the valid one");
        assertEquals(UUID_BOB, survivors.get(0).getUuid());

        List<StorageLogEvent> warns = capture.byLevel(StorageLogLevel.WARN);
        assertFalse(warns.isEmpty(), "Skipping a corrupted row must emit a WARN event (never a silent skip)");
        StorageLogEvent warn = warns.get(0);
        assertEquals(StorageOp.SCAN_ALL, warn.op());
        assertTrue(warn.detail().contains("skipped corrupted row"), warn.detail());
        assertNotNull(warn.error(), "The WARN event must carry the decode exception");
    }

    /** Opens a direct JDBC connection to the current test database and reports whether a column exists. */
    private boolean indexColumnPresent(String table, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection(currentTestDbUrl, "sa", "")) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String tbl : new String[]{table, table.toUpperCase()}) {
                try (ResultSet rs = meta.getColumns(null, null, tbl, null)) {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                    }
                }
            }
            return false;
        }
    }
}
