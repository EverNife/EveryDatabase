package br.com.finalcraft.everydatabase.manager.testdata.tworegistries;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Clan;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Wallet;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Root of the <b>Survival</b> subsystem (one author / plugin). Its references resolve only through
 * the Survival {@code RefRegistry}: {@code champion} ({@link Player}) and {@code wallet} are types
 * the Lobby subsystem <i>also</i> uses, but here they resolve against Survival's own stores - no
 * collision with Lobby.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurvivalProfile {

    private UUID uuid;                          // key: UUID

    private Ref<UUID, Player>   champion;       // shared type -> Survival's player store
    private Ref<String, Clan>   clan;           // Survival-only -> Survival's clan store
    private Ref<Long, Wallet>   wallet;         // shared type -> Survival's wallet store
}
