package br.com.finalcraft.evernifecore.storage.modules.memory;

import br.com.finalcraft.evernifecore.storage.Storage;
import br.com.finalcraft.evernifecore.storage.modules.AbstractStorageStressTest;
import org.junit.jupiter.api.DisplayName;

/** 10k-record stress run against the in-memory backend. Skip with {@code -PskipStress}. */
@DisplayName("InMemoryStorage - stress")
class InMemoryStorageStressTest extends AbstractStorageStressTest {

    @Override
    protected Storage createStorage(String testMethodName) {
        return new InMemoryStorage();
    }
}
