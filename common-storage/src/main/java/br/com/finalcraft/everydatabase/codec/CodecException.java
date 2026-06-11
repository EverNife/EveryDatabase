package br.com.finalcraft.everydatabase.codec;

/**
 * Thrown when a {@link Codec} fails to encode or decode an entity.
 *
 * <p>This is an unchecked exception so that it can propagate through
 * {@link java.util.concurrent.CompletableFuture} chains without requiring
 * checked-exception wrapping at every step.</p>
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
