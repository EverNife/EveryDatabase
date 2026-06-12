package br.com.finalcraft.everydatabase.data;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.query.Indexed;
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.UUID;

/**
 * The annotation-driven counterpart of {@link VersionedTestPlayer}: instead of
 * implementing {@code Versioned} and calling {@code .versioned()} on the builder,
 * the lock field carries {@link OptimisticLock @OptimisticLock} and
 * {@link EntityDescriptor.Builder#build()} wires the accessors via reflection.
 *
 * <p>The field is deliberately a {@code Long} (wrapper) left {@code null} until the
 * first save, proving that a never-persisted entity is treated as version 0.
 */
@Data
@NoArgsConstructor
public class AnnotatedVersionedTestPlayer {

    private UUID   uuid;

    @Indexed
    private String name;

    @Indexed
    private int    score;

    @OptimisticLock
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Long   lockVersion;   // null until the backend stores version 0

    public AnnotatedVersionedTestPlayer(UUID uuid, String name, int score) {
        this.uuid  = uuid;
        this.name  = name;
        this.score = score;
    }
}
