package br.com.finalcraft.everydatabase.versioned;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.AnnotatedVersionedTestPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests (no storage backend) for the {@link OptimisticLock @OptimisticLock}
 * annotation: builder validation rules and the reflection-wired accessors.
 * End-to-end enforcement against real databases lives in
 * {@code AbstractVersionedStorageTest} (MariaDB / PostgreSQL / Mongo suites).
 */
@DisplayName("@OptimisticLock annotation - builder wiring & validation")
class OptimisticLockAnnotationTest {

    // ------------------------------------------------------------------
    //  Helper to build a descriptor for an arbitrary entity class
    // ------------------------------------------------------------------

    private static <V> EntityDescriptor<UUID, V> descriptorFor(Class<V> type) {
        return EntityDescriptor.builder(UUID.class, type)
            .collection("annotation_test")
            .keyExtractor(e -> UUID.randomUUID())
            .codec(new JacksonJsonCodec<>(type))
            .build();
    }

    // ------------------------------------------------------------------
    //  Happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@OptimisticLock field makes the descriptor versioned - no builder call needed")
    void annotatedEntity_descriptorIsVersioned() {
        EntityDescriptor<UUID, AnnotatedVersionedTestPlayer> descriptor =
            descriptorFor(AnnotatedVersionedTestPlayer.class);

        assertTrue(descriptor.isVersioned(),
            "@OptimisticLock alone must activate optimistic locking");
        assertNotNull(descriptor.versionGetter());
        assertNotNull(descriptor.versionSetter());
    }

    @Test
    @DisplayName("getter reads the field; a null Long is read as version 0")
    void getter_readsField_nullMeansZero() {
        EntityDescriptor<UUID, AnnotatedVersionedTestPlayer> descriptor =
            descriptorFor(AnnotatedVersionedTestPlayer.class);
        AnnotatedVersionedTestPlayer player =
            new AnnotatedVersionedTestPlayer(UUID.randomUUID(), "Alpha", 10);

        assertNull(player.getLockVersion(), "fixture: the wrapper field starts null");
        assertEquals(0L, descriptor.versionGetter().apply(player),
            "a never-persisted entity (null Long) must read as version 0");

        player.setLockVersion(7L);
        assertEquals(7L, descriptor.versionGetter().apply(player));
    }

    @Test
    @DisplayName("setter writes the field via reflection")
    void setter_writesField() {
        EntityDescriptor<UUID, AnnotatedVersionedTestPlayer> descriptor =
            descriptorFor(AnnotatedVersionedTestPlayer.class);
        AnnotatedVersionedTestPlayer player =
            new AnnotatedVersionedTestPlayer(UUID.randomUUID(), "Alpha", 10);

        descriptor.versionSetter().accept(player, 3L);

        assertEquals(3L, player.getLockVersion());
    }

    public static class PrimitiveLockEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private long version;   // primitive long is accepted too
    }

    @Test
    @DisplayName("a primitive long field works (boxed transparently)")
    void primitiveLongField_works() {
        EntityDescriptor<UUID, PrimitiveLockEntity> descriptor =
            descriptorFor(PrimitiveLockEntity.class);
        PrimitiveLockEntity entity = new PrimitiveLockEntity();

        assertTrue(descriptor.isVersioned());
        assertEquals(0L, descriptor.versionGetter().apply(entity));
        descriptor.versionSetter().accept(entity, 5L);
        assertEquals(5L, descriptor.versionGetter().apply(entity));
    }

    public static class LockBase {
        @OptimisticLock
        private Long lockVersion;
    }

    public static class LockSubclass extends LockBase {
        private UUID uuid = UUID.randomUUID();
        private String name;
    }

    @Test
    @DisplayName("an annotated field inherited from a superclass is found")
    void inheritedField_isFound() {
        EntityDescriptor<UUID, LockSubclass> descriptor = descriptorFor(LockSubclass.class);
        LockSubclass entity = new LockSubclass();

        assertTrue(descriptor.isVersioned());
        descriptor.versionSetter().accept(entity, 2L);
        assertEquals(2L, descriptor.versionGetter().apply(entity));
    }

    // ------------------------------------------------------------------
    //  Validation failures at build() time
    // ------------------------------------------------------------------

    public static class WrongTypeEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private String version;   // not long/Long
    }

    @Test
    @DisplayName("wrong field type (String) -> IllegalArgumentException at build()")
    void wrongType_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> descriptorFor(WrongTypeEntity.class));
        assertTrue(ex.getMessage().contains("must be of type"),
            "message must explain the type requirement, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("WrongTypeEntity.version"),
            "message must name the offending field, got: " + ex.getMessage());
    }

    public static class IntTypeEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private int version;   // int is NOT accepted - the contract is long/Long
    }

    @Test
    @DisplayName("int field -> IllegalArgumentException at build()")
    void intType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> descriptorFor(IntTypeEntity.class));
    }

    public static class TwoFieldsEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private Long lockVersion;
        @OptimisticLock
        private long otherVersion;
    }

    @Test
    @DisplayName("two annotated fields -> IllegalStateException at build()")
    void twoFields_throwsIllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> descriptorFor(TwoFieldsEntity.class));
        assertTrue(ex.getMessage().contains("only one field"),
            "message must explain the single-field rule, got: " + ex.getMessage());
    }

    public static class StaticFieldEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private static Long lockVersion;
    }

    @Test
    @DisplayName("static annotated field -> IllegalArgumentException at build()")
    void staticField_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> descriptorFor(StaticFieldEntity.class));
    }

    public static class FinalFieldEntity {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private final Long lockVersion = 0L;
    }

    @Test
    @DisplayName("final annotated field -> IllegalArgumentException at build()")
    void finalField_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> descriptorFor(FinalFieldEntity.class));
    }

    // ------------------------------------------------------------------
    //  Conflicts with the manual wiring
    // ------------------------------------------------------------------

    public static class AnnotatedAndVersionedEntity implements Versioned {
        private UUID uuid = UUID.randomUUID();
        @OptimisticLock
        private Long lockVersion;

        @Override public long getLockVersion()          { return lockVersion == null ? 0L : lockVersion; }
        @Override public void setLockVersion(long v)    { this.lockVersion = v; }
    }

    @Test
    @DisplayName("@OptimisticLock combined with .versioned() -> IllegalStateException at build()")
    void annotationPlusVersioned_throwsIllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            EntityDescriptor.builder(UUID.class, AnnotatedAndVersionedEntity.class)
                .collection("conflict_test")
                .keyExtractor(e -> UUID.randomUUID())
                .codec(new JacksonJsonCodec<>(AnnotatedAndVersionedEntity.class))
                .versioned()
                .build());
        assertTrue(ex.getMessage().contains("Use one mechanism, not both"),
            "message must explain the conflict, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("@OptimisticLock combined with .version(getter, setter) -> IllegalStateException at build()")
    void annotationPlusManualAccessors_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
            EntityDescriptor.builder(UUID.class, AnnotatedVersionedTestPlayer.class)
                .collection("conflict_test")
                .keyExtractor(AnnotatedVersionedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(AnnotatedVersionedTestPlayer.class))
                .version(
                    p -> p.getLockVersion() == null ? 0L : p.getLockVersion(),
                    AnnotatedVersionedTestPlayer::setLockVersion)
                .build());
    }

    @Test
    @DisplayName("build() stays idempotent with the annotation (second build() works)")
    void build_isIdempotentWithAnnotation() {
        EntityDescriptor.Builder<UUID, AnnotatedVersionedTestPlayer> builder =
            EntityDescriptor.builder(UUID.class, AnnotatedVersionedTestPlayer.class)
                .collection("idempotent_lock")
                .keyExtractor(AnnotatedVersionedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(AnnotatedVersionedTestPlayer.class));

        EntityDescriptor<UUID, AnnotatedVersionedTestPlayer> first  = builder.build();
        EntityDescriptor<UUID, AnnotatedVersionedTestPlayer> second = assertDoesNotThrow(builder::build);

        assertTrue(first.isVersioned());
        assertTrue(second.isVersioned());
    }
}
