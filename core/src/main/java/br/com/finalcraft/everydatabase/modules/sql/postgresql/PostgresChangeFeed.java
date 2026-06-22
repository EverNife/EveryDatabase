package br.com.finalcraft.everydatabase.modules.sql.postgresql;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * The listen side of {@code PostgreSqlStorage}'s change feed: a daemon thread that holds a dedicated
 * connection, runs {@code LISTEN everydatabase_changes}, and translates each {@code NOTIFY} payload
 * into a {@link ChangeEvent} on a {@link ChangeFeedSupport}.
 *
 * <p>The PostgreSQL JDBC driver is a {@code runtimeOnly} dependency of {@code :core}, so this class
 * never imports {@code org.postgresql.*} - it reaches {@code PGConnection.getNotifications(int)} and
 * {@code PGNotification} reflectively (the same approach {@code StorageExecutors} uses for virtual
 * threads). A separate, unpooled {@link DriverManager} connection is used so the long-lived LISTEN
 * never holds a HikariCP pool slot.
 *
 * <p>Delivery is fire-and-forget: a notification sent while this instance is disconnected is lost
 * (PostgreSQL does not queue them), so consumers pair the feed with a TTL safety net. On a dropped
 * connection the thread reconnects and re-LISTENs.
 */
final class PostgresChangeFeed {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final ChangeFeedSupport feed;
    private final StorageLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running = false;
    private volatile Thread thread;
    private volatile Connection connection;

    PostgresChangeFeed(String jdbcUrl, String user, String password,
                       ChangeFeedSupport feed, StorageLog log) {
        this.jdbcUrl  = jdbcUrl;
        this.user     = user;
        this.password = password;
        this.feed     = feed;
        this.log      = log;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::run, "everydatabase-pg-changefeed");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    synchronized void stop() {
        running = false;
        Connection c = connection;
        if (c != null) {
            try {
                c.close();   // unblocks getNotifications by tearing down the socket
            } catch (Exception ignored) {
                // ignore
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    // ------------------------------------------------------------------

    private void run() {
        while (running) {
            try {
                Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                connection = conn;
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + PgChangePayload.CHANNEL);
                }

                Class<?> pgConnIface = Class.forName("org.postgresql.PGConnection");
                Object pgConn = conn.unwrap(pgConnIface);
                Method getNotifications = pgConnIface.getMethod("getNotifications", int.class);
                Method getParameter = null;   // resolved lazily from the concrete notification class

                while (running) {
                    Object notifications = getNotifications.invoke(pgConn, 10_000);
                    if (notifications == null) {
                        continue;
                    }
                    int len = Array.getLength(notifications);
                    for (int i = 0; i < len; i++) {
                        Object notification = Array.get(notifications, i);
                        if (getParameter == null) {
                            getParameter = notification.getClass().getMethod("getParameter");
                        }
                        String payload = (String) getParameter.invoke(notification);
                        dispatch(payload);
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;   // expected: storage is closing
                }
                log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                        b -> b.detail("NOTIFY listener interrupted, reconnecting").error(e));
                sleepBeforeReconnect();
            } finally {
                closeConnectionQuietly();
            }
        }
    }

    private void dispatch(String payload) {
        ChangeEvent event = PgChangePayload.decode(mapper, payload);
        if (event != null) {
            feed.emit(event);
        }
    }

    private void closeConnectionQuietly() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
