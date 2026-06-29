package br.com.finalcraft.everydatabase.manager.sync.jedis;

import redis.clients.jedis.JedisPoolConfig;

/**
 * Connection settings for a {@link JedisCacheSyncTransport}. Effectively immutable - the only mutable
 * field is the caller-supplied {@link Builder#poolConfig(JedisPoolConfig) poolConfig}, which is held by
 * reference and must not be mutated after {@code build()}. The same settings work unchanged against Redis
 * and Valkey (identical RESP wire protocol).
 *
 * <p>The minimal form needs only a host and port - everything else has a sensible default:
 * <pre>{@code
 * JedisCacheSyncConfig cfg = new JedisCacheSyncConfig("localhost", 6379);
 * }</pre>
 * For production (TLS, ACL user, timeouts, a dedicated pool, a non-default channel) use the builder:
 * <pre>{@code
 * JedisCacheSyncConfig cfg = JedisCacheSyncConfig.builder("redis.internal", 6380)
 *         .ssl(true)
 *         .username("cache-sync").password(secret)   // Redis 6+ ACL
 *         .connectTimeoutMs(3000).socketTimeoutMs(3000)
 *         .channel("myapp:changes")                   // isolate from other apps on a shared server
 *         .build();
 * }</pre>
 *
 * <p>{@link #channel()} is the pub/sub channel every collection publishes to; the default is
 * {@value #DEFAULT_CHANNEL}. Pub/sub channels are <b>global per server</b> (not scoped by database
 * index), so when several independent applications share one server, give each its own channel via
 * {@link Builder#channel(String)} / {@link #withChannel(String)} so their signals do not cross.
 */
public final class JedisCacheSyncConfig {

    /** Default Redis/Valkey port. */
    public static final int DEFAULT_PORT = 6379;
    /** Default pub/sub channel for cache-sync signals. */
    public static final String DEFAULT_CHANNEL = "everydatabase:changes";
    /** Default connect/socket timeout (matches Jedis's own default). */
    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private final String host;
    private final int port;
    private final String username;   // nullable: no ACL user
    private final String password;   // nullable: no AUTH
    private final int database;
    private final String channel;
    private final boolean ssl;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;
    private final String clientName;        // nullable
    private final JedisPoolConfig poolConfig;   // never null

    private JedisCacheSyncConfig(Builder b) {
        this.host             = b.host;
        this.port             = b.port;
        this.username         = b.username;
        this.password         = b.password;
        this.database         = b.database;
        this.channel          = b.channel;
        this.ssl              = b.ssl;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.socketTimeoutMs  = b.socketTimeoutMs;
        this.clientName       = b.clientName;
        this.poolConfig       = b.poolConfig != null ? b.poolConfig : new JedisPoolConfig();
    }

    /** No auth, database 0, default channel. */
    public JedisCacheSyncConfig(String host, int port) {
        this(builder(host, port));
    }

    /** Authenticated (password only), database 0, default channel. */
    public JedisCacheSyncConfig(String host, int port, String password) {
        this(builder(host, port).password(password));
    }

    public JedisCacheSyncConfig(String host, int port, String password, int database, String channel) {
        this(builder(host, port).password(password).database(database).channel(channel));
    }

    /** Returns a copy of these settings with a different channel. */
    public JedisCacheSyncConfig withChannel(String newChannel) {
        return toBuilder().channel(newChannel).build();
    }

    public String host()         { return host; }
    public int port()            { return port; }
    /** The ACL username (Redis 6+), or {@code null} when none. */
    public String username()     { return username; }
    /** The AUTH password, or {@code null} when the server has no auth. */
    public String password()     { return password; }
    public int database()        { return database; }
    public String channel()      { return channel; }
    public boolean ssl()         { return ssl; }
    public int connectTimeoutMs(){ return connectTimeoutMs; }
    public int socketTimeoutMs() { return socketTimeoutMs; }
    /** A client name reported to the server (for {@code CLIENT LIST}), or {@code null}. */
    public String clientName()   { return clientName; }
    /** The pool configuration for the publish connection pool (never {@code null}). */
    public JedisPoolConfig poolConfig() { return poolConfig; }

    /** Starts a builder with the required host/port; everything else defaults. */
    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    private Builder toBuilder() {
        return builder(host, port)
                .username(username)
                .password(password)
                .database(database)
                .channel(channel)
                .ssl(ssl)
                .connectTimeoutMs(connectTimeoutMs)
                .socketTimeoutMs(socketTimeoutMs)
                .clientName(clientName)
                .poolConfig(poolConfig);
    }

    /** Fluent builder: only {@code host}/{@code port} are required, the rest carry sensible defaults. */
    public static final class Builder {
        private final String host;
        private final int port;
        private String username;
        private String password;
        private int database = 0;
        private String channel = DEFAULT_CHANNEL;
        private boolean ssl = false;
        private int connectTimeoutMs = DEFAULT_TIMEOUT_MS;
        private int socketTimeoutMs = DEFAULT_TIMEOUT_MS;
        private String clientName;
        private JedisPoolConfig poolConfig;

        private Builder(String host, int port) {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("host must not be null/empty");
            }
            this.host = host;
            this.port = port;
        }

        public Builder username(String username)      { this.username = username; return this; }
        public Builder password(String password)      { this.password = password; return this; }
        public Builder database(int database)         { this.database = database; return this; }

        public Builder channel(String channel) {
            if (channel == null || channel.isEmpty()) {
                throw new IllegalArgumentException("channel must not be null/empty");
            }
            this.channel = channel;
            return this;
        }

        public Builder ssl(boolean ssl)               { this.ssl = ssl; return this; }
        public Builder connectTimeoutMs(int timeout)  { this.connectTimeoutMs = timeout; return this; }
        public Builder socketTimeoutMs(int timeout)   { this.socketTimeoutMs = timeout; return this; }
        public Builder clientName(String clientName)  { this.clientName = clientName; return this; }
        /** Overrides the pool used for the publish connection (e.g. to tune {@code maxTotal}). */
        public Builder poolConfig(JedisPoolConfig poolConfig) { this.poolConfig = poolConfig; return this; }

        public JedisCacheSyncConfig build() {
            return new JedisCacheSyncConfig(this);
        }
    }
}
