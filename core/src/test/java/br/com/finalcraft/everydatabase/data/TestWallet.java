package br.com.finalcraft.everydatabase.data;

import br.com.finalcraft.everydatabase.query.Indexed;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Test entity for the {@link BigDecimal} contract: a decimal is stored with every digit and every
 * trailing zero it was saved with, and it can be indexed, compared and ordered as a number.
 *
 * <p>The fields split the contract into its parts:
 * <ul>
 *   <li>{@link #balance} - {@link Indexed} with no {@code type} override, so it also proves a
 *       {@code BigDecimal} field auto-detects to the DECIMAL index type</li>
 *   <li>{@link #exact} - never indexed: the payload's fidelity does not depend on the index</li>
 *   <li>{@link #history} - a decimal inside a container, which reaches the backends through the
 *       same tree the entity does</li>
 *   <li>{@link #ledger} - a nested object indexed by the dot-path {@code ledger.total}</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class TestWallet {

    private UUID uuid;

    @Indexed
    private BigDecimal balance;

    private BigDecimal exact;

    private List<BigDecimal> history;

    /** Explicit {@code type}: the field's own Java type is {@link Ledger}, which indexes nothing. */
    @Indexed(path = "ledger.total", type = BigDecimal.class)
    private Ledger ledger;

    public TestWallet(UUID uuid, BigDecimal amount) {
        this.uuid    = uuid;
        this.balance = amount;
        this.exact   = amount;
        this.ledger  = new Ledger(amount);
    }

    @Data
    @NoArgsConstructor
    public static class Ledger {
        private BigDecimal total;

        public Ledger(BigDecimal total) {
            this.total = total;
        }
    }
}
