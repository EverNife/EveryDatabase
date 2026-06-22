package br.com.finalcraft.everydatabase.manager.sync;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Cache-sync contract on PostgreSQL <b>LISTEN/NOTIFY</b> (push). Two storages on the same DB.
 * Self-skips when Postgres is unreachable (standalone is fine - NOTIFY needs no replica set).
 */
@DisplayName("CacheSync contract - PostgreSQL (LISTEN/NOTIFY)")
class PostgresCacheSyncTest extends AbstractCacheSyncTest {

    private static final String SERVER = "jdbc:postgresql://localhost:39307";
    private static final String DB = "everydatabase_cachesync";

    @Override
    protected void assumeAvailable() {
        Assumptions.assumeTrue(ready(),
                "PostgreSQL not reachable - run 'docker compose up -d postgres'. Skipping NOTIFY contract.");
    }

    @Override
    protected Storage openWriter() {
        return Storages.createPostgreSQL(new SqlConfig(SERVER + "/" + DB, "root", "root"));
    }

    @Override
    protected Storage openReader() {
        return Storages.createPostgreSQL(new SqlConfig(SERVER + "/" + DB, "root", "root"));
    }

    private static boolean ready() {
        DriverManager.setLoginTimeout(3);
        try (Connection c = DriverManager.getConnection(SERVER + "/postgres", "root", "root");
             Statement st = c.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                st.execute("CREATE DATABASE \"" + DB + "\"");
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
