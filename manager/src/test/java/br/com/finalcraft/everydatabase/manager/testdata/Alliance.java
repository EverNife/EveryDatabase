package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.manager.Ref;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * A pact between guilds - the root entity of the generation-swap tests: it holds a <b>list</b> of
 * {@link Guild} references, so a root re-read from a backend carries several deserialized
 * container-element {@link Ref}s at once, all of which must survive a hot-swap of the guild
 * manager without ever being rebound.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Alliance {

    private UUID id;                    // key
    private String name;
    private List<Ref<UUID, Guild>> guilds;
}
