package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

import java.util.UUID;

/** {@link AbstractGenerationSwapTest} on embedded H2 (no external service). */
class H2GenerationSwapTest extends AbstractGenerationSwapTest {

    // DB_CLOSE_DELAY=-1 keeps the named in-memory database alive across generation storages,
    // so generation 2 opens a new pool over the SAME data - the SQL shape of a reload.
    private final String url = "jdbc:h2:mem:genswap_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @Override
    protected Storage openGeneration() {
        Storage storage = Storages.createH2(new SqlConfig(url, "", ""));
        storage.init().join();
        opened.add(storage);
        return storage;
    }
}
