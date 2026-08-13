package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.manager.cache.CacheOptions;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A <b>per-context registry of references</b>: it maps each entity type to its
 * {@link RefResolver} (its manager) and is the single object you wire your refs through.
 *
 * <p>There is <b>no global registry</b> on purpose. Each context (a plugin, a subsystem, a
 * test) creates its own {@code RefRegistry}, and a {@link Ref} resolves only against the
 * registry it was bound to. Two independent registries can therefore each register a manager for
 * the <b>same</b> entity type — backed by different storages, with different policies — and they
 * never collide. (A static, process-wide registry would silently re-introduce exactly that
 * collision: two managers for {@code Player}, last-writer-wins.)
 *
 * <h3>Wire a context through the registry</h3>
 * The registry <b>vends</b> the two things a ref-aware setup needs, so you cannot half-wire it:
 * <ul>
 *   <li>{@link #codec(Class)} — a ref-aware Jackson codec <b>bound to this registry</b>; every
 *       {@link Ref} it deserializes resolves here.</li>
 *   <li>{@link #manager(EntityDescriptor, Storage, CachePolicy)} — a {@link CachingManager}
 *       created and <b>registered in this registry</b> in one call.</li>
 * </ul>
 *
 * <pre>{@code
 * RefRegistry refRegistry = new RefRegistry();
 *
 * EntityDescriptor<UUID, Guild> GUILDS = EntityDescriptor.builder(UUID.class, Guild.class)
 *         .collection("guilds")
 *         .keyExtractor(Guild::getId)
 *         .codec(refRegistry.codec(Guild.class))     // bound to this registry
 *         .build();
 *
 * CachingManager<UUID, Guild> guilds = refRegistry.manager(GUILDS, guildStorage, CachePolicy.always());
 * }</pre>
 *
 * <h3>Parent chaining</h3>
 * A registry may be created with a {@link #RefRegistry(RefRegistry) parent}: {@link #resolver(Class)}
 * (and every {@link Ref} bound here) checks this registry first, then walks up the parent chain. This
 * composes a private-then-shared lookup - a subsystem registers its own entities locally and still
 * resolves shared entities published in a common parent - without a process-wide global map.
 * Registration ({@link #register}) is always local to the registry it is called on.
 *
 * <p>The map is a {@link ConcurrentHashMap}; registration and lookup are thread-safe.
 */
public final class RefRegistry {

    private final Map<Class<?>, RefResolver<?, ?>> resolvers = new ConcurrentHashMap<>();

    /** Fallback registry consulted when a type is not registered locally; {@code null} for a root. */
    private final RefRegistry parent;

    public RefRegistry() {
        this(null);
    }

    /**
     * Creates a registry that falls back to {@code parent} when a type is not registered locally.
     * Resolution checks this registry first, then walks up the parent chain - so a subsystem's own
     * registry resolves its private entities while still seeing shared entities published in a
     * common parent (e.g. a host framework's global registry). Registration is always local; a
     * {@code null} parent makes this a root registry (the previous default).
     */
    public RefRegistry(RefRegistry parent) {
        this.parent = parent;
    }

    /** The fallback registry consulted on a local miss, or {@code null} when this is a root. */
    public RefRegistry parent() {
        return parent;
    }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    /**
     * Registers the resolver for {@code type}. {@link CachingManager} calls this from its
     * constructor, so {@link #manager(EntityDescriptor, Storage, CachePolicy)} and a
     * {@code CachingManager} built with this registry both self-register here.
     *
     * <p>Rejects a <b>conflicting</b> duplicate: if a different resolver is already registered for
     * {@code type} in this registry, this throws instead of silently overwriting it - two managers
     * for the same type would collide last-writer-wins, a bug discoverable only at runtime, which is
     * exactly what the no-global-registry design exists to prevent. Re-registering the very same
     * resolver instance is a harmless no-op; a deliberate substitution goes through
     * {@link #replace(Class, RefResolver)}.
     *
     * @throws IllegalStateException if a different resolver is already registered for {@code type}
     */
    public void register(Class<?> type, RefResolver<?, ?> resolver) {
        RefResolver<?, ?> existing = resolvers.putIfAbsent(type, resolver);
        if (existing != null && existing != resolver) {
            throw new IllegalStateException(
                "a resolver is already registered for " + type.getName() + " in this RefRegistry - "
                + "two managers for the same type would collide (last-writer-wins). Use a separate "
                + "RefRegistry per context, or replace(" + type.getSimpleName()
                + ".class, resolver) for a deliberate hot-swap.");
        }
    }

    /**
     * Atomically installs {@code resolver} for {@code type}, <b>replacing</b> any resolver already
     * registered - the hot-swap primitive a live reload needs: at no instant is the type
     * unresolvable, so a concurrent {@link Ref#resolve()} sees the old resolver or the new one,
     * never a gap (the unregister-then-register dance has one, and {@code Ref.resolve()} fails in
     * it). Returns the resolver that was replaced ({@code null} if none) so the caller can tear it
     * down - flush pending writes, then {@code clearCache()} (which is what makes live {@code Ref}s
     * re-resolve against the replacement), then close its storage if this generation owns it.
     *
     * <p>Accidental duplicates should keep using {@link #register}, which refuses to overwrite;
     * replacing is the deliberate gesture of whoever orchestrates a reload. The cast is safe for
     * {@code V} ({@link #resolver(Class)} documents why); the key type is the caller's discipline,
     * as everywhere else in this registry.
     */
    @SuppressWarnings("unchecked")
    public <K, V> RefResolver<K, V> replace(Class<V> type, RefResolver<K, V> resolver) {
        return (RefResolver<K, V>) resolvers.put(type, resolver);
    }

    /** Removes the resolver for {@code type}, if any. */
    public void unregister(Class<?> type) {
        resolvers.remove(type);
    }

    /** Returns {@code true} when a resolver is registered for {@code type} in <b>this</b> registry. */
    public boolean isRegistered(Class<?> type) {
        return resolvers.containsKey(type);
    }

    /** Returns {@code true} when {@code type} is registered here or anywhere up the {@link #parent()} chain. */
    public boolean isRegisteredInChain(Class<?> type) {
        return resolvers.containsKey(type) || (parent != null && parent.isRegisteredInChain(type));
    }

    /**
     * Returns the resolver for {@code type}: the local registration if present, otherwise the one
     * resolved up the {@link #parent()} chain, or {@code null} when none is registered anywhere in
     * the chain. The cast is safe: registration always pairs a {@code Class<V>} with a matching
     * {@code RefResolver<?, V>}.
     */
    @SuppressWarnings("unchecked")
    public <K, V> RefResolver<K, V> resolver(Class<V> type) {
        RefResolver<?, ?> local = resolvers.get(type);
        if (local != null) {
            return (RefResolver<K, V>) local;
        }
        return parent != null ? parent.<K, V>resolver(type) : null;
    }

    /** Clears every registration. Handy in tests, or to tear a context down. */
    public void clear() {
        resolvers.clear();
    }

    // ------------------------------------------------------------------
    //  Factories (bound to this registry)
    // ------------------------------------------------------------------

    /**
     * A ref-aware compact JSON codec for {@code type}, <b>bound to this registry</b>: every
     * {@link Ref} it deserializes resolves through this registry's managers. Use it as the codec
     * on any descriptor whose entity contains {@code Ref} fields.
     */
    public <T> JacksonJsonCodec<T> codec(Class<T> type) {
        return RefCodecs.json(type, this);
    }

    /**
     * Creates a {@link CachingManager} for {@code descriptor} on {@code storage} and registers it
     * in this registry, in one call. Convenience for the non-subclass case; a domain-named
     * subclass passes this registry to {@code super(...)} instead.
     */
    public <K, V> CachingManager<K, V> manager(EntityDescriptor<K, V> descriptor, Storage storage, CacheOptions options) {
        return new CachingManager<>(descriptor, storage, options, this);
    }

    /** {@link #manager(EntityDescriptor, Storage, CacheOptions)} with an unbounded cache. */
    public <K, V> CachingManager<K, V> manager(EntityDescriptor<K, V> descriptor, Storage storage, CachePolicy policy) {
        return manager(descriptor, storage, CacheOptions.of(policy));
    }

    /**
     * As {@link #manager(EntityDescriptor, Storage, CacheOptions)}, but <b>replacing</b> any
     * resolver already registered for the entity type ({@link #replace(Class, RefResolver)}) - the
     * reload path. The replaced resolver is handed to {@code retired}, which owns its teardown:
     * typically flush pending writes, then {@code clearCache()} (so live {@link Ref}s re-resolve
     * against the new manager), then close its storage if that generation owned it. When nothing
     * was registered yet, {@code retired} is not invoked.
     */
    public <K, V> CachingManager<K, V> managerReplacing(EntityDescriptor<K, V> descriptor, Storage storage,
                                                        CacheOptions options,
                                                        Consumer<? super RefResolver<K, V>> retired) {
        return new CachingManager<>(descriptor, storage, options, this, retired);
    }

    /** {@link #managerReplacing(EntityDescriptor, Storage, CacheOptions, Consumer)} with an unbounded cache. */
    public <K, V> CachingManager<K, V> managerReplacing(EntityDescriptor<K, V> descriptor, Storage storage,
                                                        CachePolicy policy,
                                                        Consumer<? super RefResolver<K, V>> retired) {
        return managerReplacing(descriptor, storage, CacheOptions.of(policy), retired);
    }

    /**
     * A {@link Ref} bound to this registry — for resolving a key you hold programmatically (the
     * common case is a {@code Ref} deserialized from an entity, which the {@link #codec(Class)}
     * binds automatically). Resolving an <b>unbound</b> {@code Ref.of(key, type, null)} fails fast.
     */
    public <K, V> Ref<K, V> ref(K key, Class<V> type) {
        return Ref.of(key, type, this);
    }

    /** A bound {@link Ref} carrying a per-reference freshness override. */
    public <K, V> Ref<K, V> ref(K key, Class<V> type, CachePolicy policyOverride) {
        return Ref.of(key, type, this).withPolicy(policyOverride);
    }
}
