package br.com.finalcraft.everydatabase.versioned;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the optimistic-lock version field of an entity class.
 *
 * <p>Annotated entities get optimistic locking wired automatically:
 * {@link EntityDescriptor.Builder#build()} scans the entity class (including
 * superclasses), finds the annotated field, and creates the version getter and setter
 * via reflection - no {@code .version(getter, setter)} / {@code .versioned()} call and
 * no {@link Versioned} interface needed:
 *
 * <pre>{@code
 * public class Account {
 *     private UUID id;
 *     private long balance;
 *
 *     @OptimisticLock
 *     private Long lockVersion;   // managed by the backend - never touch it manually
 * }
 *
 * // Nothing else to declare - build() detects the annotation:
 * EntityDescriptor<UUID, Account> ACCOUNTS = EntityDescriptor
 *     .builder(UUID.class, Account.class)
 *     .collection("accounts")
 *     .keyExtractor(Account::getId)
 *     .codec(new JacksonJsonCodec<>(Account.class))
 *     .build();
 * }</pre>
 *
 * <h3>Field requirements (validated at {@code build()} time)</h3>
 * <ul>
 *   <li>Type must be {@code long} or {@code Long}; anything else throws
 *       {@link IllegalArgumentException}. A {@code Long} field that is still {@code null}
 *       (entity never persisted) is read as version {@code 0}.</li>
 *   <li>Must not be {@code static} or {@code final} (the backend writes the new version
 *       back after every successful save) - {@link IllegalArgumentException} otherwise.</li>
 *   <li>At most one field per entity (including inherited fields) may carry the
 *       annotation; two or more throw {@link IllegalStateException}.</li>
 *   <li>Mutually exclusive with the manual wiring: combining the annotation with
 *       {@code .version(...)} or {@code .versioned()} on the builder throws
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h3>Semantics</h3>
 * Identical to the manual wiring: the version starts at {@code 0} on the first insert and
 * is incremented by the backend on every successful update; a stale in-memory version makes
 * {@code save()} fail with {@link OptimisticLockException}. Backends that do not enforce
 * optimistic locking (H2, local files, in-memory) silently degrade to plain upsert - the
 * descriptor stays valid, no error is raised at storage-creation time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OptimisticLock {
}
