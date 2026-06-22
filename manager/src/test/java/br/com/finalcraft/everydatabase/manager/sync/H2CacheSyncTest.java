package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;

/**
 * Cache-sync contract on H2 (no Docker). Two storages share one named in-memory database
 * ({@code DB_CLOSE_DELAY=-1} keeps it alive across connections for the JVM lifetime). H2 opts out of
 * optimistic locking, so the version poll sees {@code 0} and catches only deletes - the update test
 * self-skips.
 */
@DisplayName("CacheSync contract - H2 (polling, delete-only)")
class H2CacheSyncTest extends AbstractCacheSyncTest {

    private final String url = "jdbc:h2:mem:cachesync_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @Override
    protected Storage openWriter() {
        return Storages.createH2(new SqlConfig(url, "", ""));
    }

    @Override
    protected Storage openReader() {
        return Storages.createH2(new SqlConfig(url, "", ""));
    }

    @Override
    protected boolean supportsUpdatePropagation() {
        return false;   // H2 does not enforce lock_version -> polling detects deletes, not updates
    }
}
