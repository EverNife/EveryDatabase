package br.com.finalcraft.everydatabase;

import br.com.finalcraft.everydatabase.log.StorageLogConfig;
import br.com.finalcraft.everydatabase.schema.SchemaAwareStorage;
import br.com.finalcraft.everydatabase.tx.TransactionalStorage;
import br.com.finalcraft.everydatabase.util.BackendIdentities;

import java.util.concurrent.CompletableFuture;

/**
 * Base contract for all storage backends.
 *
 * <p>A {@code Storage} instance manages lifecycle (connection pool, file handles, etc.)
 * and acts as a factory for typed {@link Repository} instances.</p>
 *
 * <p>Optional capabilities are expressed as additional interfaces, not flags:
 * <ul>
 *   <li>{@link TransactionalStorage} - atomic transactions</li>
 *   <li>{@link SchemaAwareStorage} - schema migrations</li>
 * </ul>
 *
 * <p>{@link #enforcesOptimisticLock()} is the exception to that idiom, and deliberately so: it is a
 * queryable boolean rather than a marker interface because a subclass must be able to answer
 * {@code false} for a capability its superclass answers {@code true} for (H2 extends the SQL
 * backend and opts out), which un-implementing an inherited interface cannot express.
 *
 * <p>Backends are obtained via {@link Storages#create(StorageConfig)}.
 */
public interface Storage {

    /**
     * Initializes pool/connection. Idempotent.
     */
    CompletableFuture<Void> init();

    /**
     * Closes pool/connection. Idempotent.
     */
    CompletableFuture<Void> close();

    /**
     * Fast healthcheck: connected? ping?
     */
    CompletableFuture<HealthStatus> health();

    /**
     * Returns a typed repository for the entity described by the given descriptor.
     *
     * <p>Call this only after {@link #init()} and before {@link #close()}. The connection-backed
     * backends (SQL, MongoDB) throw {@link IllegalStateException} when called before init or after
     * close, so the misuse fails fast rather than surfacing a raw {@code NullPointerException} deep
     * in a later operation; the file and in-memory backends need no live connection and may return a
     * repository without an init, but that is not a guarantee to rely on.
     *
     * <p>Repositories are cached per collection name: calling this twice with descriptors that share
     * a collection name returns the <em>first</em> descriptor's repository (the collection name is the
     * identity), so register one descriptor per collection.
     *
     * @throws IllegalStateException if the backend requires a live connection and has not been
     *         initialised (or has been closed)
     * @throws IllegalArgumentException if the descriptor's codec is incompatible with the backend
     */
    <K, V> Repository<K, V> repository(EntityDescriptor<K, V> descriptor);

    /**
     * Returns the <b>live, mutable</b> {@link StorageLogConfig} for this storage.
     *
     * <p>The returned object is shared with all repositories belonging to this storage.
     * Editing it takes effect immediately for all repositories without any restart or
     * re-injection.
     *
     * <p>Example - enable write logging at runtime via a command:
     * <pre>{@code
     * storage.getStorageLogConfig()
     *        .level(StorageLogTopic.WRITE, StorageLogLevel.DEBUG)
     *        .includeKeys(true);
     * }</pre>
     */
    /**
     * Whether this backend actually ENFORCES the optimistic-lock version check on save for a
     * versioned descriptor.
     *
     * <p>Enforcing backends (MySQL/MariaDB, PostgreSQL, MongoDB) reject a stale write with
     * {@link br.com.finalcraft.everydatabase.versioned.OptimisticLockException}; non-enforcing ones
     * (H2 - a deliberate opt-out -, the file backends and the in-memory backend) silently degrade to
     * last-write-wins, which is only safe while a single process writes.
     *
     * <p>This is a capability question, not a per-descriptor one: it says what the backend
     * <em>would</em> do for a versioned descriptor, and answers the same for every descriptor.
     * Callers routing a versioned entity while writes from several instances are intended should
     * treat {@code false} as a misconfiguration - concurrent writes would silently drop one side.
     *
     * <p>Defaults to {@code false}: a backend only claims enforcement by overriding.
     */
    default boolean enforcesOptimisticLock() {
        return false;
    }

    /**
     * A stable identifier of the <b>physical store</b> this storage talks to.
     *
     * <p>Contract:
     * <ul>
     *   <li>Two {@code Storage} instances pointing at the SAME physical store report the SAME
     *       identity - even in different processes, on different machines.</li>
     *   <li>Two instances pointing at DIFFERENT stores never report the same identity, including the
     *       ambiguous case where their coordinates are textually equal but machine-local (two servers
     *       each running their own database on {@code localhost:3306/mc}, or each with their own data
     *       directory at {@code /home/mc/data}) - such coordinates carry a machine discriminator.</li>
     *   <li>The identity NEVER contains a credential. It is stamped on change events and may be
     *       logged, so a username or password must never reach it.</li>
     * </ul>
     *
     * <p>It answers a question rather than adding behaviour, so it is a method and not a marker
     * interface - the same reasoning as {@link #enforcesOptimisticLock()}, and for the same practical
     * reason: a subclass must be able to answer differently from its superclass (the H2 backend
     * reports a per-instance identity for an in-memory URL where the SQL base derives one from the
     * URL).
     *
     * <p>Consumers use it to scope cache-invalidation signals that travel over a shared pub/sub
     * channel: a signal is only applied to a manager whose backend reports the same identity.
     *
     * <p>Defaults to a <b>per-instance</b> identity, which never matches another process. A third
     * party {@code Storage} that does not override this therefore fails to the safe side: its
     * signals are never applied to a store that only looks alike.
     */
    default String backendIdentity() {
        return "storage-instance:" + BackendIdentities.jvmId() + ":" + System.identityHashCode(this);
    }

    /**
     * How this backend participates in the publish side of an explicit pub/sub cache-sync transport.
     *
     * <p>Read from the backend's own {@code config.syncParticipation()} by whoever overrides it, the
     * same way {@link #backendIdentity()} is read from {@code config.sharedIdentity()}. It governs
     * only whether a local write publishes a signal; it never affects receiving, the native
     * change-feed path, or any non-transport behaviour.
     *
     * <p>Defaults to {@link SyncParticipation#RECOMMENDED}.
     */
    default SyncParticipation syncParticipation() {
        return SyncParticipation.RECOMMENDED;
    }

    /**
     * Whether this backend's identity could NEVER be reached by another instance - so a signal it
     * would publish onto a per-store channel has no possible subscriber.
     *
     * <p>Answers {@code true} for a machine-local coordinate with no shared identity (a loopback
     * database, any file directory, an in-memory store) and for a per-instance identity like the
     * {@link #backendIdentity()} default; {@code false} for a routable coordinate, or whenever a
     * {@code sharedIdentity} is set - the operator declaring an explicit identity makes the store
     * shareable by decree, even if the underlying coordinate is loopback.
     *
     * <p>This must be answered from the SAME signal a backend uses to derive its identity (its
     * config's coordinate plus {@code sharedIdentity}), never by parsing the string returned from
     * {@link #backendIdentity()}: a per-instance identity carries no routable marker yet is entirely
     * machine-local, and a freely-chosen shared identity may look machine-local by accident.
     *
     * <p>Defaults to {@code false}: an unclassified backend (including a third-party one) keeps the
     * pre-existing behaviour of publishing, rather than silently going quiet and dropping a
     * cross-instance invalidation that used to work.
     */
    default boolean isMachineLocalIdentity() {
        return false;
    }

    StorageLogConfig getStorageLogConfig();

    /**
     * Replaces the entire {@link StorageLogConfig} with a new instance.
     *
     * <p>The new config is picked up immediately by all repositories (the dispatcher
     * re-reads it on every emit call). The previous config object is discarded.
     *
     * <p>For runtime tweaks prefer {@link #getStorageLogConfig()} and editing in-place;
     * use this method only when a clean slate is needed.
     *
     * @return {@code this} for chaining
     */
    Storage setStorageLogConfig(StorageLogConfig config);
}
