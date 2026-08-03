package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.valkey;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractMultiBackendJedisCacheSyncTest;
import org.junit.jupiter.api.DisplayName;

/** Several backends synced through one <b>Valkey</b> transport (port 39309). Self-skips when Valkey is down. */
@DisplayName("CacheSync over Jedis multi-backend - Valkey transport")
class ValkeyMultiBackendCacheSyncTest extends AbstractMultiBackendJedisCacheSyncTest {

    @Override
    protected int transportPort() {
        return 39309;
    }

    @Override
    protected String transportName() {
        return "Valkey";
    }
}
