package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageStressTest;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/** 10k-record stress run against embedded H2. Skip with {@code -PskipStress}. */
@DisplayName("H2Storage - stress")
class H2StorageStressTest extends AbstractStorageStressTest {

    private static final PoolTuning TEST_POOL = new PoolTuning(
        1, 5, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Override
    protected Storage createStorage(String testMethodName) {
        String url = "jdbc:h2:mem:stress_" + testMethodName
            + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        return new H2SqlStorage(new SqlConfig(url, "sa", "", TEST_POOL));
    }
}
