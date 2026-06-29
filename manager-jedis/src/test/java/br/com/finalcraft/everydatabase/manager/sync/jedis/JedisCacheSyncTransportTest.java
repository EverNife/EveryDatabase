package br.com.finalcraft.everydatabase.manager.sync.jedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Jedis transport that need <b>no</b> server: config defaults/builder and the
 * closed-transport guard. (The end-to-end pub/sub contract lives in {@link AbstractJedisCacheSyncTest}
 * and its Valkey/Redis subclasses, which self-skip without Docker.)
 */
@DisplayName("JedisCacheSyncTransport - unit (no server)")
class JedisCacheSyncTransportTest {

    @Test
    void config_minimal_form_applies_sensible_defaults() {
        JedisCacheSyncConfig cfg = new JedisCacheSyncConfig("localhost", 6379);
        assertEquals("localhost", cfg.host());
        assertEquals(6379, cfg.port());
        assertEquals(JedisCacheSyncConfig.DEFAULT_CHANNEL, cfg.channel());
        assertEquals(0, cfg.database());
        assertFalse(cfg.ssl());
        assertEquals(JedisCacheSyncConfig.DEFAULT_TIMEOUT_MS, cfg.connectTimeoutMs());
        assertEquals(JedisCacheSyncConfig.DEFAULT_TIMEOUT_MS, cfg.socketTimeoutMs());
        assertEquals("localhost", cfg.host());
    }

    @Test
    void builder_carries_advanced_settings_and_withChannel_preserves_them() {
        JedisCacheSyncConfig cfg = JedisCacheSyncConfig.builder("redis.internal", 6380)
                .ssl(true)
                .username("cache-sync")
                .password("secret")
                .database(3)
                .connectTimeoutMs(1500)
                .socketTimeoutMs(2500)
                .clientName("everydb")
                .channel("app:changes")
                .build();
        assertTrue(cfg.ssl());
        assertEquals("cache-sync", cfg.username());
        assertEquals(3, cfg.database());
        assertEquals(1500, cfg.connectTimeoutMs());
        assertEquals(2500, cfg.socketTimeoutMs());
        assertEquals("everydb", cfg.clientName());
        assertEquals("app:changes", cfg.channel());

        // withChannel keeps every other field, only swapping the channel.
        JedisCacheSyncConfig renamed = cfg.withChannel("app:other");
        assertEquals("app:other", renamed.channel());
        assertTrue(renamed.ssl());
        assertEquals("cache-sync", renamed.username());
        assertEquals(3, renamed.database());
        assertEquals(2500, renamed.socketTimeoutMs());
    }

    @Test
    void subscribe_after_close_fails_fast() {
        // No server needed: the pool is lazy and close() never opens a socket.
        JedisCacheSyncTransport transport =
                JedisCacheSyncTransport.connect(new JedisCacheSyncConfig("localhost", 39309));
        transport.close();
        assertThrows(IllegalStateException.class, () -> transport.subscribe(event -> { }));
    }
}
