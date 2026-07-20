package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.redis;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractJedisChannelScopingTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The per-store channel scoping contract against <b>Redis</b> (port 39310). Self-skips when the Redis
 * container is down.
 */
@DisplayName("CacheSync channel scoping over Jedis - Redis")
class RedisChannelScopingTest extends AbstractJedisChannelScopingTest {

    @Override
    protected int port() {
        return 39310;
    }

    @Override
    protected String serverName() {
        return "Redis";
    }
}
