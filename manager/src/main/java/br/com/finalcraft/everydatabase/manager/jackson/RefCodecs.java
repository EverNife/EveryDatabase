package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.codec.JacksonConfig;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Convenience factories for codecs that understand {@link br.com.finalcraft.everydatabase.manager.Ref}
 * fields, so callers don't have to wire the {@link RefModule} onto an {@code ObjectMapper} by hand.
 *
 * <p>Every codec is <b>bound to a {@link RefRegistry}</b>: a {@code Ref} it deserializes resolves
 * against that registry's managers. Prefer {@link RefRegistry#codec(Class)}, which delegates here.
 */
public final class RefCodecs {

    private RefCodecs() {
    }

    /** A fresh {@code ObjectMapper} with the EveryDatabase default profile
     *  ({@link JacksonConfig#storageSafe}) plus a {@link RefModule} bound to {@code registry}. */
    public static ObjectMapper newMapper(RefRegistry registry) {
        return JacksonConfig.storageSafe(new ObjectMapper()).registerModule(new RefModule(registry));
    }

    /**
     * A compact JSON codec for {@code type} that serializes {@code Ref} fields as their key and
     * binds every {@code Ref} it reads to {@code registry}.
     */
    public static <T> JacksonJsonCodec<T> json(Class<T> type, RefRegistry registry) {
        return new JacksonJsonCodec<>(type, newMapper(registry));
    }
}
