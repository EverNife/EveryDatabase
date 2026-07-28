package br.com.finalcraft.everydatabase.testutil;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Codec decorator that counts {@code decode}/{@code encode} calls, so a test can assert how many
 * stored rows an operation actually deserialised - the observable cost the scan backends are
 * supposed to avoid for rows they do not return.
 *
 * <p>The delegate must be Jackson-backed: the wrapper forwards {@link ObjectMapperAware} so the
 * decorated codec keeps every fast path the plain codec would have taken, and a count therefore
 * measures the backend's behaviour rather than the decorator's.
 */
public final class CountingCodec<V> implements Codec<V>, ObjectMapperAware {

    private final Codec<V>      delegate;
    private final AtomicInteger decodes = new AtomicInteger();
    private final AtomicInteger encodes = new AtomicInteger();

    public CountingCodec(Codec<V> delegate) {
        if (!(delegate instanceof ObjectMapperAware)) {
            throw new IllegalArgumentException(
                "CountingCodec must wrap a Jackson codec so the backend keeps its tree fast paths; got "
                + delegate.getClass().getName());
        }
        this.delegate = delegate;
    }

    public int  decodeCount() { return decodes.get(); }
    public int  encodeCount() { return encodes.get(); }
    public void resetCounts()  { decodes.set(0); encodes.set(0); }

    @Override
    public byte[] encode(V value) throws CodecException {
        encodes.incrementAndGet();
        return delegate.encode(value);
    }

    @Override
    public V decode(byte[] data) throws CodecException {
        decodes.incrementAndGet();
        return delegate.decode(data);
    }

    @Override public String      contentType()   { return delegate.contentType(); }
    @Override public boolean     isJsonCodec()   { return delegate.isJsonCodec(); }
    @Override public String      fileExtension() { return delegate.fileExtension(); }
    @Override public ObjectMapper objectMapper() { return ((ObjectMapperAware) delegate).objectMapper(); }
}
