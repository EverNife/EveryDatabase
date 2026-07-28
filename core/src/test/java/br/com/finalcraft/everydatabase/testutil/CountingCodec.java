package br.com.finalcraft.everydatabase.testutil;

import br.com.finalcraft.everydatabase.codec.Codec;
import br.com.finalcraft.everydatabase.codec.CodecException;
import br.com.finalcraft.everydatabase.codec.ObjectMapperAware;
import br.com.finalcraft.everydatabase.codec.TreeCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Codec decorator that counts how many entities an operation converts, in either direction, so a
 * test can assert how many stored rows it actually deserialised - the observable cost the scan
 * backends are supposed to avoid for rows they do not return.
 *
 * <p>The delegate must be Jackson-backed: the wrapper forwards {@link ObjectMapperAware} and
 * {@link TreeCodec} so the decorated codec keeps every fast path the plain codec would have taken.
 * Without that forwarding a backend would silently fall back to the byte form whenever a test
 * wrapped its codec, and the counts would describe a path production never takes.
 *
 * <p>Both forms feed the same counters: the question a test asks is how many rows became entities,
 * not which representation carried them.
 */
public final class CountingCodec<V> implements Codec<V>, ObjectMapperAware, TreeCodec<V> {

    private final Codec<V>      delegate;
    private final TreeCodec<V>  treeDelegate;
    private final AtomicInteger decodes = new AtomicInteger();
    private final AtomicInteger encodes = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public CountingCodec(Codec<V> delegate) {
        if (!(delegate instanceof ObjectMapperAware) || !(delegate instanceof TreeCodec)) {
            throw new IllegalArgumentException(
                "CountingCodec must wrap a Jackson codec so the backend keeps its tree fast paths; got "
                + delegate.getClass().getName());
        }
        this.delegate     = delegate;
        this.treeDelegate = (TreeCodec<V>) delegate;
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

    @Override
    public JsonNode encodeTree(V value) throws CodecException {
        encodes.incrementAndGet();
        return treeDelegate.encodeTree(value);
    }

    @Override
    public V decodeTree(JsonNode node) throws CodecException {
        decodes.incrementAndGet();
        return treeDelegate.decodeTree(node);
    }

    @Override public String      contentType()   { return delegate.contentType(); }
    @Override public boolean     isJsonCodec()   { return delegate.isJsonCodec(); }
    @Override public String      fileExtension() { return delegate.fileExtension(); }
    @Override public ObjectMapper objectMapper() { return ((ObjectMapperAware) delegate).objectMapper(); }
}
