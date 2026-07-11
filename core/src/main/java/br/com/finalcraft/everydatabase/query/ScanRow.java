package br.com.finalcraft.everydatabase.query;

import java.util.Objects;

/**
 * One row produced by {@link br.com.finalcraft.everydatabase.Repository#scanAll(Cursor, int)}: either a
 * successfully decoded entity or a decode failure, always carrying the storage key.
 *
 * <p>Unlike {@code all()}/{@code query()}, which silently drop a row whose stored payload cannot be
 * decoded, {@code scanAll} surfaces the failure here (key + cause) so a maintenance pass can count it,
 * name it in a log, and refuse to declare the scan complete. A successful row carries {@link #value()}
 * and a {@code null} {@link #error()}; a failed row carries a {@code null} value and the {@link #error()}.
 *
 * <p>On a successful row {@link #key()} is the storage key, consistent across every backend. On a
 * <em>failed</em> row the key can no longer be recovered from the undecodable payload, so it is a
 * best-effort identifier: backends that store the key separately (SQL, Mongo, InMemory) still report the
 * real key, while the file backends fall back to the file name (which equals the key when it needed no
 * sanitization).
 *
 * @param <V> the entity type
 */
public final class ScanRow<V> {

    private final String key;
    private final V value;
    private final Throwable error;

    private ScanRow(String key, V value, Throwable error) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = value;
        this.error = error;
    }

    /** A row whose stored payload decoded to {@code value}. */
    public static <V> ScanRow<V> ok(String key, V value) {
        return new ScanRow<>(key, value, null);
    }

    /** A row whose stored payload could not be decoded; {@code error} is the cause. */
    public static <V> ScanRow<V> failed(String key, Throwable error) {
        return new ScanRow<>(key, null, Objects.requireNonNull(error, "error"));
    }

    /** The storage key on a successful row; a best-effort identifier on a failed one (see class doc). */
    public String key() {
        return key;
    }

    /** The decoded entity, or {@code null} when {@link #isFailed()}. */
    public V value() {
        return value;
    }

    /** The decode failure, or {@code null} when the row decoded successfully. */
    public Throwable error() {
        return error;
    }

    /** {@code true} when the stored payload could not be decoded. */
    public boolean isFailed() {
        return error != null;
    }
}
