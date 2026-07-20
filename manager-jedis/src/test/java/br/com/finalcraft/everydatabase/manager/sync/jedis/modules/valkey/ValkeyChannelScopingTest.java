package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.valkey;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractJedisChannelScopingTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The per-store channel scoping contract against <b>Valkey</b> (port 39309). Self-skips when the
 * Valkey container is down.
 */
@DisplayName("CacheSync channel scoping over Jedis - Valkey")
class ValkeyChannelScopingTest extends AbstractJedisChannelScopingTest {

    @Override
    protected int port() {
        return 39309;
    }

    @Override
    protected String serverName() {
        return "Valkey";
    }
}
