package br.com.finalcraft.evernifecore.storage.data;

import br.com.finalcraft.evernifecore.storage.versioned.Versioned;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.UUID;

/**
 * A versioned variant of {@link TestPlayer} used exclusively by optimistic-locking tests.
 *
 * <p>Implements {@link Versioned} so that the convenience
 * {@link br.com.finalcraft.evernifecore.storage.EntityDescriptor.Builder#versioned()} can wire
 * the accessors automatically.
 *
 * <p>Kept separate from {@link TestPlayer} so that the 230+ existing tests that use
 * {@code TestPlayer} are completely unaffected.
 */
@Data
@NoArgsConstructor
public class VersionedTestPlayer implements Versioned {

    private UUID   uuid;
    private String name;
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
