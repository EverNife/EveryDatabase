package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Multi-backend example target (LocalFile). Key type: a <b>composite record</b> {@link Key}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Home {

    private Key key;          // composite key
    private String world;

    /** Composite key: a named home owned by a player. */
    public record Key(UUID owner, String name) {
    }
}
