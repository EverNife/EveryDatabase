package br.com.finalcraft.everydatabase.transfer;

/**
 * Describes a single failure that occurred during a {@link StorageTransfer}.
 *
 * <p>Errors are accumulated in {@link TransferReport#errors()}. With
 * {@link ErrorPolicy#FAIL_FAST} the list will have at most one entry; with
 * {@link ErrorPolicy#CONTINUE} or {@link ErrorPolicy#SKIP_EXISTING} it may grow.
 */
public final class TransferError {

    private final String collection;
    private final Object key;
    private final Throwable cause;

    /**
     * @param collection the source collection name where the error occurred
     * @param key        the entity key involved, or {@code null} for global errors
     *                   (pre-flight failure, count mismatch, collection-level abort)
     * @param cause      the exception that caused the failure
     */
    public TransferError(String collection, Object key, Throwable cause) {
        this.collection = collection;
        this.key        = key;
        this.cause      = cause;
    }

    /** Source collection where the error occurred. */
    public String collection() { return collection; }

    /**
     * The entity key that triggered the error, or {@code null} if the error is
     * global/collection-level (e.g. pre-flight health check, count mismatch,
     * non-empty target abort).
     */
    public Object key() { return key; }

    /** The exception that caused this error. */
    public Throwable cause() { return cause; }

    @Override
    public String toString() {
        return "TransferError{collection='" + collection + "', key=" + key
            + ", cause=" + (cause != null ? cause.getMessage() : "null") + "}";
    }
}
