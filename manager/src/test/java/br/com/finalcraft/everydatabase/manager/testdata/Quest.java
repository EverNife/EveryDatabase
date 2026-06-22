package br.com.finalcraft.everydatabase.manager.testdata;

import br.com.finalcraft.everydatabase.versioned.OptimisticLock;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A versioned test entity (optimistic locking), used by the version-polling tests: its
 * {@code version} is what {@code Repository.versions(...)} reports and the poller compares across
 * polls.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Quest {

    private UUID id;       // key
    private String title;

    @OptimisticLock
    private Long version;
}
