package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.manager.Ref;
import br.com.finalcraft.everydatabase.manager.RefPolicy;
import br.com.finalcraft.everydatabase.manager.RefRegistry;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;

/**
 * Deserializes a scalar key back into a typed {@link Ref}.
 *
 * <p>The referenced type {@code V} (and the key type {@code K}) are not in the JSON - they are
 * captured from the field's generic declaration via {@link ContextualDeserializer}. A
 * {@link RefPolicy} annotation on the same field, if present, is baked into the {@code Ref} as
 * a per-reference freshness override. Every {@code Ref} produced is bound to the {@link RefRegistry}
 * this deserializer was created with, so the ref resolves against that registry's managers.
 */
@SuppressWarnings("rawtypes")
public final class RefDeserializer extends JsonDeserializer<Ref> implements ContextualDeserializer {

    private final RefRegistry registry;
    private final JavaType keyType;
    private final JavaType valueType;
    private final CachePolicy override;

    public RefDeserializer(RefRegistry registry) {
        this(registry, null, null, null);
    }

    private RefDeserializer(RefRegistry registry, JavaType keyType, JavaType valueType, CachePolicy override) {
        this.registry = registry;
        this.keyType = keyType;
        this.valueType = valueType;
        this.override = override;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        // The Ref<K,V> type can arrive either as the contextual type (most specific, and the only
        // source for collection/map elements - whose property is the CONTAINER, not the element)
        // or as the field's declared type. Pick whichever actually is a parameterised Ref.
        JavaType refType = asRefType(ctxt.getContextualType());
        if (refType == null && property != null) {
            refType = asRefType(property.getType());
        }
        if (refType == null) {
            // Types not recoverable here - keep this instance; deserialize() fails with a clear message.
            return this;
        }
        CachePolicy refOverride = (property != null) ? policyOf(findRefPolicy(property)) : null;
        return new RefDeserializer(registry, refType.containedType(0), refType.containedType(1), refOverride);
    }

    /** Returns {@code candidate} iff it is a {@code Ref<K, V>} with both type parameters resolved. */
    private static JavaType asRefType(JavaType candidate) {
        return (candidate != null
                && Ref.class.isAssignableFrom(candidate.getRawClass())
                && candidate.containedTypeCount() >= 2) ? candidate : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Ref deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (keyType == null || valueType == null) {
            throw JsonMappingException.from(p,
                    "Ref can only be deserialized where its generic types are known - a typed field, "
                    + "or a typed collection/map element (e.g. List<Ref<UUID, Guild>>). "
                    + "A raw or wildcard Ref is unsupported.");
        }
        Object key = ctxt.readValue(p, keyType);
        Ref ref = Ref.of(key, valueType.getRawClass(), registry);
        return override != null ? ref.withPolicy(override) : ref;
    }

    @Override
    public Ref getNullValue(DeserializationContext ctxt) throws JsonMappingException {
        if (valueType == null) {
            // Same contract as deserialize(): a raw/wildcard Ref is unsupported. Returning a bare
            // null here would make the caller NPE on .isPresent()/.resolve() instead of failing clearly.
            throw JsonMappingException.from(ctxt,
                "Ref can only be deserialized where its generic types are known - a typed field, or a "
                + "typed collection/map element (e.g. List<Ref<UUID, Guild>>). "
                + "A raw or wildcard Ref is unsupported.");
        }
        // A JSON null round-trips to an empty Ref (never a bare null), so callers never NPE.
        return Ref.empty(valueType.getRawClass(), registry);
    }

    /**
     * Finds the {@link RefPolicy} for a property. With Lombok {@code @Data} the field is private and
     * accessed through a generated setter, so {@code BeanProperty.getAnnotation} (which inspects the
     * mutator) may miss a field-level annotation - fall back to the declared field by name.
     */
    private static RefPolicy findRefPolicy(BeanProperty property) {
        RefPolicy direct = property.getAnnotation(RefPolicy.class);
        if (direct != null) {
            return direct;
        }
        AnnotatedMember member = property.getMember();
        if (member == null) {
            return null;
        }
        for (Class<?> c = member.getDeclaringClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(property.getName());
                return field.getAnnotation(RefPolicy.class);   // present or null - the field is the source
            } catch (NoSuchFieldException ignored) {
                // not declared here - walk up the hierarchy
            }
        }
        return null;
    }

    /** Converts a {@link RefPolicy} annotation to a {@link CachePolicy}, or {@code null} to inherit. */
    private static CachePolicy policyOf(RefPolicy annotation) {
        if (annotation == null) {
            return null;
        }
        if (annotation.noCache()) {
            return CachePolicy.noCache();
        }
        long seconds = annotation.ttlSeconds();
        if (seconds < 0) {
            return null; // inherit the manager default
        }
        return seconds == 0 ? CachePolicy.always() : CachePolicy.ttl(Duration.ofSeconds(seconds));
    }
}
