package br.com.finalcraft.everydatabase.modules.memory;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageStressTest;
import org.junit.jupiter.api.DisplayName;

/** 10k-record stress run against the in-memory backend. Skip with {@code -PskipStress}. */
@DisplayName("InMemoryStorage - stress")
class InMemoryStorageStressTest extends AbstractStorageStressTest {

    @Override
    protected Storage createStorage(String testMethodName) {
        return new InMemoryStorage();
    }
}
