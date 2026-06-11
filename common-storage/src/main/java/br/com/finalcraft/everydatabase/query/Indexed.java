package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field on an entity class as a storage index.
 *
 * <p>Annotated fields are automatically picked up by
 * {@link EntityDescriptor.Builder#build()}, which scans
 * the entity class and creates the appropriate {@link IndexHint} for each one.
 *
 * <h3>Type auto-detection</h3>
 * The {@link IndexHint.FieldType} is derived from the declared Java type of the annotated field:
 * <ul>
 *   <li>{@code String}                              → {@link IndexHint.FieldType#STRING}</li>
 *   <li>{@code int} / {@code Integer}               → {@link IndexHint.FieldType#INT}</li>
 *   <li>{@code long} / {@code Long}                 → {@link IndexHint.FieldType#LONG}</li>
 *   <li>{@code float/Float} / {@code double/Double} → {@link IndexHint.FieldType#DOUBLE}</li>
 *   <li>{@code boolean} / {@code Boolean}           → {@link IndexHint.FieldType#BOOLEAN}</li>
 *   <li>{@code Instant} / {@code LocalDateTime}     → {@link IndexHint.FieldType#TIMESTAMP}</li>
 * </ul>
 * Any other type throws {@link IllegalArgumentException} at
 * {@link EntityDescriptor.Builder#build()} time
 * unless {@link #type()} is set explicitly.
 *
 * <h3>Nested paths</h3>
 * Use {@link #path()} with dot-notation to index a nested field. Because the annotated
 * field's Java type may not match the nested field's type, also set {@link #type()} explicitly:
 * <pre>{@code
 * @Indexed(path = "guild.id", type = String.class)
 * private Guild guild;
 * }</pre>
 *
 * <h3>Usage examples</h3>
 * <pre>{@code
 * public class Player {
 *
 *     private UUID uuid;
 *
 *     @Indexed
 *     private String name;
 *
 *     @Indexed(order = IndexHint.Order.DESCENDING)
 *     private int score;
 * }
 *
 * // No .index() calls needed on the builder:
 * EntityDescriptor<UUID, Player> descriptor = EntityDescriptor
 *     .builder(UUID.class, Player.class)
 *     .collection("players")
 *     .keyExtractor(Player::getUuid)
 *     .codec(new JacksonJsonCodec<>(Player.class))
 *     .build();
 * }</pre>
 *
 * <h3>Coexistence with manual {@code .index()} declarations</h3>
 * Manual {@code .index(IndexHint)} calls and {@code @Indexed} annotations can be combined.
 * If the same field path appears in both, {@code build()} throws
 * {@link IllegalStateException} (duplicate index hint).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Indexed {

    /**
     * Index field path. Defaults to the annotated field's name when empty.
     *
     * <p>Use dot-notation for nested structures (e.g. {@code "location.world"}).
     * When pointing to a nested field whose Java type differs from the annotated
     * field's type, also set {@link #type()} explicitly so the scanner can
     * determine the correct {@link IndexHint.FieldType}.
     */
    String path() default "";

    /**
     * Override the Java type used to resolve the {@link IndexHint.FieldType}.
     *
     * <p>Defaults to {@code void.class}, which means auto-detect from the annotated
     * field's declared Java type. Set this explicitly when:
     * <ul>
     *   <li>{@link #path()} points to a nested field of a different type.</li>
     *   <li>The field's declared type is a custom object and only one of its primitive
     *       sub-fields should be indexed.</li>
     * </ul>
     *
     * <p>Accepted values: {@code String.class}, {@code Integer.class}/{@code int.class},
     * {@code Long.class}/{@code long.class}, {@code Double.class}/{@code double.class},
     * {@code Boolean.class}/{@code boolean.class},
     * {@code java.time.Instant.class}, {@code java.time.LocalDateTime.class}.
     */
    Class<?> type() default void.class;

    /** Sort order for the backing index. Default: ascending. */
    IndexHint.Order order() default IndexHint.Order.ASCENDING;
}
