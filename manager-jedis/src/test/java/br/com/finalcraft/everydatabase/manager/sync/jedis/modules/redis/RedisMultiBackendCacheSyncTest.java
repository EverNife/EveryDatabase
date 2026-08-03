package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.redis;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractMultiBackendJedisCacheSyncTest;
import org.junit.jupiter.api.DisplayName;

/** Several backends synced through one <b>Redis</b> transport (port 39310). Self-skips when Redis is down. */
@DisplayName("CacheSync over Jedis multi-backend - Redis transport")
class RedisMultiBackendCacheSyncTest extends AbstractMultiBackendJedisCacheSyncTest {

    @Override
    protected int transportPort() {
        return 39310;
    }

    @Override
    protected String transportName() {
        return "Redis";
    }
}
