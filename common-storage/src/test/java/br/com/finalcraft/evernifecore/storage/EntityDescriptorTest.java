package br.com.finalcraft.evernifecore.storage;

import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.data.AnnotatedTestPlayer;
import br.com.finalcraft.evernifecore.storage.data.TestPlayer;
import br.com.finalcraft.evernifecore.storage.query.IndexHint;
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
}
