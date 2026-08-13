package br.com.finalcraft.everydatabase.manager.refs;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;

/** {@link AbstractGenerationSwapTest} on the memory backend. */
class MemoryGenerationSwapTest extends AbstractGenerationSwapTest {

    private InMemoryStorage shared;

    @Override
    protected Storage openGeneration() {
        // In-memory data cannot outlive its storage, so every generation shares the ONE instance -
        // the reload shape where the storage survives and only the managers are rebuilt.
        if (shared == null) {
            shared = Storages.createInMemory();
            shared.init().join();
            opened.add(shared);
        }
        return shared;
    }
}
