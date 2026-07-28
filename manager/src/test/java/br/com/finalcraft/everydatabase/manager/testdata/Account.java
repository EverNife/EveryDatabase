package br.com.finalcraft.everydatabase.manager.testdata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A login name pointing at the {@link Player} it belongs to - the "account alias" shape, where
 * loading one account leads straight into loading another.
 *
 * <p>It is the one test entity keyed by a {@code String}, which is what makes it usable where a
 * test has to choose its keys' hash codes (two keys landing in the same {@code ConcurrentHashMap}
 * bin, say); a {@code UUID} key offers no such control.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    private String name;                // key

    private UUID player;
}
