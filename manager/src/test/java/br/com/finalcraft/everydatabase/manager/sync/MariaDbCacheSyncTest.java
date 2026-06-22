package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Cache-sync contract on MariaDB (Docker, self-skips when down). MariaDB has no native push feed, so
 * the facade falls back to <b>version polling</b>; it enforces optimistic locking, so a versioned
 * update bumps {@code lock_version} and the poll detects it - update propagation works here.
 */
@DisplayName("CacheSync contract - MariaDB (version polling)")
class MariaDbCacheSyncTest extends AbstractCacheSyncTest {

    private static final String SERVER = "jdbc:mysql://localhost:39306";
    private static final String DB = "everydatabase_cachesync";

    @Override
    protected void assumeAvailable() {
        Assumptions.assumeTrue(ready(),
                "MariaDB not reachable - run 'docker compose up -d mariadb'. Skipping cache-sync contract.");
    }

    @Override
    protected Storage openWriter() {
        return Storages.createSQL(new SqlConfig(SERVER + "/" + DB, "root", "root"));
    }

    @Override
    protected Storage openReader() {
        return Storages.createSQL(new SqlConfig(SERVER + "/" + DB, "root", "root"));
    }

    private static boolean ready() {
        DriverManager.setLoginTimeout(3);
        try (Connection c = DriverManager.getConnection(SERVER + "/", "root", "root");
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + DB + "`");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
