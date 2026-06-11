package br.com.finalcraft.everydatabase.data;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.query.Indexed;
import br.com.finalcraft.everydatabase.versioned.Versioned;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.UUID;

/**
 * A versioned variant of {@link TestPlayer} used exclusively by optimistic-locking tests.
 *
 * <p>Implements {@link Versioned} so that the convenience
 * {@link EntityDescriptor.Builder#versioned()} can wire
 * the accessors automatically.
 *
 * <p>Kept separate from {@link TestPlayer} so the optimistic-locking fixtures do not
 * interfere with the plain (non-versioned) CRUD tests that use {@code TestPlayer}.
 */
@Data
@NoArgsConstructor
public class VersionedTestPlayer implements Versioned {

    private UUID   uuid;

    @Indexed
    private String name;

    @Indexed
    private int    score;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private long   lockVersion = 0L;

    public VersionedTestPlayer(UUID uuid, String name, int score) {
        this.uuid  = uuid;
        this.name  = name;
        this.score = score;
    }

    @Override
    public long getLockVersion() {
        return lockVersion;
    }

    @Override
    public void setLockVersion(long version) {
        this.lockVersion = version;
    }
}
