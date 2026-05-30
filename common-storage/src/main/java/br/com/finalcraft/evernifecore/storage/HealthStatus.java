package br.com.finalcraft.evernifecore.storage;

/**
 * Result of a {@link Storage#health()} call.
 *
 * <p>Create via the factory methods {@link #ok(long)} and {@link #down(String)}.
 */
public final class HealthStatus {

    private final boolean connected;
    private final long pingMs;
    private final String details;

    private HealthStatus(boolean connected, long pingMs, String details) {
        this.connected = connected;
        this.pingMs    = pingMs;
        this.details   = details;
    }

    /** Storage is reachable and responding. */
    public static HealthStatus ok(long pingMs) {
        return new HealthStatus(true, pingMs, "OK");
    }

    /** Storage is unreachable or unhealthy. */
    public static HealthStatus down(String reason) {
        return new HealthStatus(false, -1L, reason);
    }

    public boolean isConnected() { return connected; }
    public long pingMs()         { return pingMs; }
    public String details()      { return details; }

    @Override
    public String toString() {
        return connected
            ? "HealthStatus{CONNECTED, ping=" + pingMs + "ms}"
            : "HealthStatus{DOWN, reason='" + details + "'}";
    }
}
