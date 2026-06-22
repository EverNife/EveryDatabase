package br.com.finalcraft.everydatabase.manager.testdata.tworegistries;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.testdata.Player;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Wallet;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Root of the <b>Lobby</b> subsystem (a different author / plugin). It references the same
 * {@link Player} and {@code Wallet} types as {@link SurvivalProfile}, but resolves them through the
 * Lobby {@code RefRegistry} - its own stores, its own cached instances. The two roots can even share
 * a profile id and a champion id without ever interfering.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LobbyProfile {

    private UUID uuid;                          // key: UUID

    private Ref<UUID, Player>      champion;    // shared type -> Lobby's player store
    private Ref<Integer, Cosmetics> cosmetics; // Lobby-only -> Lobby's cosmetics store
    private Ref<Long, Wallet>      wallet;      // shared type -> Lobby's wallet store
}
