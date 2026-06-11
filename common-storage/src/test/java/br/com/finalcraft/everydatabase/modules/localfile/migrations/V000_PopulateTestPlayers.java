package br.com.finalcraft.everydatabase.modules.localfile.migrations;

import br.com.finalcraft.everydatabase.Repository;
import br.com.finalcraft.everydatabase.data.TestPlayer;
import br.com.finalcraft.everydatabase.modules.AbstractStorageTest;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileMigration;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileStorage;

import java.util.*;

/**
 * Test-only migration that seeds the {@code test_players} collection with
 * 20 deterministic {@link TestPlayer} entries.
 *
 * <h3>Dataset</h3>
 * <ul>
 *   <li>20 players, indexed {@code 0..19}</li>
 *   <li>UUID: {@code new UUID(0, index + 1)} - fully deterministic</li>
 *   <li>Name: {@code "Player_<index>"}</li>
 *   <li>Score: {@code new Random(index).nextInt(11)} - seeded random in [0, 10]</li>
 * </ul>
 *
 * <p>The full dataset is available as {@link #SEEDED_PLAYERS} so tests can assert
 * exact values without repeating the generation logic.
 *
 * <h3>Idempotency</h3>
 * <p>The migration runner tracks applied versions; re-registering and calling
 * {@code migrate()} a second time skips this migration silently.
 */
public final class V000_PopulateTestPlayers extends LocalFileMigration {

    public static final V000_PopulateTestPlayers INSTANCE = new V000_PopulateTestPlayers();

    /** The exact 20 players written by this migration. Use for assertions in tests. */
    public static final List<TestPlayer> SEEDED_PLAYERS = buildPlayers();

    private static List<TestPlayer> buildPlayers() {
        List<TestPlayer> players = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            UUID   uuid  = new UUID(0, i + 1);
            String name  = "Player_" + i;
            int    score = new Random(i).nextInt(11); // seeded: deterministic score in [0, 10]
            players.add(new TestPlayer(uuid, name, score));
        }
        return Collections.unmodifiableList(players);
    }

    private V000_PopulateTestPlayers() {}

    @Override
    public String version() {
        return "000";
    }

    @Override
    public String description() {
        return "Seed test_players collection with 20 deterministic players (scores 0-10)";
    }

    @Override
    protected void executeOnStorage(LocalFileStorage storage) {
        Repository<UUID, TestPlayer> repo = storage.repository(AbstractStorageTest.DESCRIPTOR);
        repo.saveAll(SEEDED_PLAYERS).join();
    }
}
