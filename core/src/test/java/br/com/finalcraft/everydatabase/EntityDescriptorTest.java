package br.com.finalcraft.everydatabase;

import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.data.AnnotatedTestPlayer;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.query.IndexHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityDescriptorTest {

    @Test
    @DisplayName("build() is idempotent - a second build() on the same builder does not duplicate @Indexed hints")
    void build_calledTwice_isIdempotent() {
        EntityDescriptor.Builder<UUID, AnnotatedTestPlayer> builder =
            EntityDescriptor.builder(UUID.class, AnnotatedTestPlayer.class)
                .collection("idempotent_build")
                .keyExtractor(AnnotatedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(AnnotatedTestPlayer.class));

        EntityDescriptor<UUID, AnnotatedTestPlayer> first = builder.build();
        // Historically this threw IllegalStateException: build() mutated the builder's
        // index list with the @Indexed scan results, so the second scan saw duplicates.
        EntityDescriptor<UUID, AnnotatedTestPlayer> second = assertDoesNotThrow(builder::build);

        assertEquals(first.indexes().size(), second.indexes().size(),
            "both builds must produce the same index hints");
        assertEquals(4, first.indexes().size(),
            "AnnotatedTestPlayer declares 4 @Indexed fields");
    }

    @Test
    @DisplayName("manual .index() on a field also annotated with @Indexed still fails as duplicate")
    void build_duplicateManualAndAnnotated_throws() {
        EntityDescriptor.Builder<UUID, AnnotatedTestPlayer> builder =
            EntityDescriptor.builder(UUID.class, AnnotatedTestPlayer.class)
                .collection("duplicate_hints")
                .keyExtractor(AnnotatedTestPlayer::getUuid)
                .codec(new JacksonJsonCodec<>(AnnotatedTestPlayer.class))
                .index(IndexHint.string("name")); // 'name' is also @Indexed on the class

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    @DisplayName("invalid collection names are rejected")
    void build_invalidCollection_throws() {
        for (String bad : new String[]{"1abc", "with space", "with-dash", "with.dot", ""}) {
            EntityDescriptor.Builder<UUID, TestPlayer> builder =
                EntityDescriptor.builder(UUID.class, TestPlayer.class)
                    .collection(bad)
                    .keyExtractor(TestPlayer::getUuid)
                    .codec(new JacksonJsonCodec<>(TestPlayer.class));
            assertThrows(IllegalStateException.class, builder::build,
                "collection '" + bad + "' should be rejected");
        }
    }

    // ------------------------------------------------------------------
    //  Reserved underscore namespace
    // ------------------------------------------------------------------

    private static EntityDescriptor.Builder<UUID, TestPlayer> playerBuilder(String collection) {
        return EntityDescriptor.builder(UUID.class, TestPlayer.class)
            .collection(collection)
            .keyExtractor(TestPlayer::getUuid)
            .codec(new JacksonJsonCodec<>(TestPlayer.class));
    }

    @Test
    @DisplayName("a regular descriptor rejects the reserved '_' prefix, and says so")
    void build_underscorePrefixWithoutReserved_throws() {
        for (String reservedName : new String[]{"_players", "_schema_migrations", "_entity_schema_sweeps"}) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> playerBuilder(reservedName).build(),
                "collection '" + reservedName + "' is framework-reserved and must be rejected");
            assertTrue(thrown.getMessage().contains("reserved"),
                "the error must point at the reserved namespace: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("reserved() accepts a single leading underscore followed by an identifier")
    void build_reserved_acceptsUnderscorePrefixedName() {
        EntityDescriptor<UUID, TestPlayer> descriptor =
            assertDoesNotThrow(() -> playerBuilder("_entity_schema_sweeps").reserved().build());

        assertEquals("_entity_schema_sweeps", descriptor.collection());
    }

    @Test
    @DisplayName("reserved() rejects a name outside the reserved namespace")
    void build_reserved_rejectsNamesOutsideTheNamespace() {
        // "players": no underscore at all - a reserved descriptor must not claim a user name.
        // "_": nothing after the underscore. "__x": double underscore. "_1x": digit after the underscore.
        for (String bad : new String[]{"players", "_", "__x", "_1x"}) {
            assertThrows(IllegalStateException.class,
                () -> playerBuilder(bad).reserved().build(),
                "reserved collection '" + bad + "' should be rejected");
        }
    }

    @Test
    @DisplayName("build() stays idempotent on a reserved() builder")
    void build_reserved_calledTwice_isIdempotent() {
        EntityDescriptor.Builder<UUID, TestPlayer> builder = playerBuilder("_reserved_idempotent").reserved();

        EntityDescriptor<UUID, TestPlayer> first  = builder.build();
        EntityDescriptor<UUID, TestPlayer> second = assertDoesNotThrow(builder::build);

        assertEquals(first.collection(), second.collection());
    }
}
