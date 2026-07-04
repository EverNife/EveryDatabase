package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Jackson module that teaches an {@code ObjectMapper} to read/write {@link Ref} as its key, and
 * <b>binds</b> every {@code Ref} it deserializes to a given {@link RefRegistry} so the ref resolves
 * against that registry's managers.
 *
 * <p>Register it on the mapper backing your codec (or use {@link RefRegistry#codec(Class)} /
 * {@link RefCodecs#json(Class, RefRegistry)}):
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().registerModule(new RefModule(registry));
 * Codec<Player> codec = new JacksonJsonCodec<>(Player.class, mapper);
 * }</pre>
 *
 * <p><b>A {@code Ref} is supported only as a value</b> - a field, or an element of a typed
 * {@code List}/{@code Set}/{@code Map} <em>value</em> ({@code Map<String, Ref<UUID, Guild>>}). It is
 * <b>not</b> supported as a {@code Map} <em>key</em> ({@code Map<Ref<UUID, Guild>, X>}): Jackson would
 * serialize it via the default key serializer (its {@code toString()}) and could not read it back.
 * Use the raw key type as the map key instead ({@code Map<UUID, X>}) and wrap in a {@code Ref} on read.
 */
public final class RefModule extends SimpleModule {

    public RefModule(RefRegistry registry) {
        super("EveryDatabaseRefModule");
        addSerializer(Ref.class, new RefSerializer());
        addDeserializer(Ref.class, new RefDeserializer(registry));
    }
}
