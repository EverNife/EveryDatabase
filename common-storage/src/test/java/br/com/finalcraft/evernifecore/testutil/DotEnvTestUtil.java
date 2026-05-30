package br.com.finalcraft.evernifecore.testutil;

/**
 * Lightweight config resolver for integration tests.
 *
 * <p>Resolution order for a given key:
 * <ol>
 *   <li>System property  ({@code -Dkey=value} on the JVM command line)</li>
 *   <li>Environment variable ({@code export key=value} / OS env)</li>
 *   <li>{@code defValue} passed to the call</li>
 * </ol>
 *
 * <p>Both system property and env var use the <em>same</em> key string, so
 * {@code MONGO_USER} resolves {@code -DMONGO_USER=x} or {@code $MONGO_USER}.
 *
 * <pre>{@code
 * String user = DotEnvTestUtil.getOrDefault("MONGO_USER", "root");
 * String pass = DotEnvTestUtil.getOrDefault("MONGO_PASS", "root");
 * }</pre>
 */
public final class DotEnvTestUtil {

    private DotEnvTestUtil() {}

    /**
     * Resolves {@code key} from system properties, then env vars, then falls back to
     * {@code defValue}.
     *
     * @param key      property / env-var name to look up
     * @param defValue value returned when neither source defines the key
     * @return the resolved value, never {@code null} if {@code defValue} is non-null
     */
    public static String getOrDefault(String key, String defValue) {
        String v = System.getProperty(key);
        if (v != null) return v;
        v = System.getenv(key);
        return v != null ? v : defValue;
    }
}
