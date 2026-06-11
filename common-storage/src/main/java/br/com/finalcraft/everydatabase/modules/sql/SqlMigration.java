package br.com.finalcraft.everydatabase.modules.sql;

import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Convenience base class for SQL migrations.
 *
 * <p>Subclasses implement {@link #upScript()} and return a single DDL or DML statement.
 * The default {@link #execute(MigrationContext)} implementation obtains a {@link Connection}
 * from the context and executes that statement.
 *
 * <p>For multi-statement or procedural migrations, override {@link #execute(MigrationContext)}
 * directly and call {@code context.getNativeClient(Connection.class)} yourself.
 *
 * <pre>{@code
 * public final class V001_CreatePlayers extends SqlMigration {
 *
 *     public static final V001_CreatePlayers INSTANCE = new V001_CreatePlayers();
 *     private V001_CreatePlayers() {}
 *
 *     public String version()     { return "001"; }
 *     public String description() { return "Create players table"; }
 *     public String upScript() {
 *         return "CREATE TABLE IF NOT EXISTS players ("
 *              + "  uuid VARCHAR(36) NOT NULL,"
 *              + "  name VARCHAR(64) NOT NULL,"
 *              + "  PRIMARY KEY (uuid)"
 *              + ")";
 *     }
 * }
 * }</pre>
 */
public abstract class SqlMigration implements Migration {

    /**
     * Executes {@link #upScript()} on the connection obtained from the context.
     *
     * <p>Override this method when you need multiple statements or procedural logic.
     */
    @Override
    public void execute(MigrationContext context) throws Exception {
        Connection conn = context.getNativeClient(Connection.class);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(upScript());
        }
    }

    /**
     * The SQL statement to execute when applying this migration.
     *
     * <p>Must be a single, complete DDL or DML statement (no trailing semicolon).
     * For multiple statements, override {@link #execute(MigrationContext)} directly.
     */
    public abstract String upScript();
}
