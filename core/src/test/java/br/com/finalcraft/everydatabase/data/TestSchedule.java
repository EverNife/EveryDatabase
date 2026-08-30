package br.com.finalcraft.everydatabase.data;

import br.com.finalcraft.everydatabase.query.Indexed;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * Test entity for the temporal and auto-detection contract: the types a plugin reaches for after
 * {@code String}/{@code int} - a release day, a start time in a real zone, a rarity, a supply too
 * large for a {@code long} - all indexed by {@link Indexed} with no {@code type} override.
 *
 * <p>Its round-trip half is the other point: a {@link ZonedDateTime} must come back in the zone it
 * was saved in, not rewritten to UTC.
 */
@Data
@NoArgsConstructor
public class TestSchedule {

    public enum Rarity { COMMON, RARE, EPIC }

    private UUID uuid;

    /** A calendar day - auto-detects to the DATE index. */
    @Indexed
    private LocalDate releasedOn;

    /** A moment in a named zone - auto-detects to TIMESTAMP. */
    @Indexed
    private ZonedDateTime startsAt;

    /** A moment with an offset and no zone id - auto-detects to TIMESTAMP. */
    @Indexed
    private OffsetDateTime endsAt;

    /** The legacy date type, still everywhere in older code - auto-detects to TIMESTAMP. */
    @Indexed
    private Date archivedAt;

    /** An enum indexes as its name. */
    @Indexed
    private Rarity rarity;

    /** An integer too large for a {@code long} - auto-detects to the exact DECIMAL index. */
    @Indexed
    private BigInteger supply;

    /** The narrow integer types index as INT. */
    @Indexed
    private short slot;

    @Indexed
    private byte tier;

    @Indexed
    private Character grade;

    public TestSchedule(UUID uuid, LocalDate releasedOn, ZonedDateTime startsAt) {
        this.uuid       = uuid;
        this.releasedOn = releasedOn;
        this.startsAt   = startsAt;
        this.endsAt     = startsAt.toOffsetDateTime();
        this.archivedAt = Date.from(startsAt.toInstant());
        this.rarity     = Rarity.EPIC;
        this.supply     = new BigInteger("123456789012345678901234567890");
        this.slot       = 7;
        this.tier       = 3;
        this.grade      = 'A';
    }
}
