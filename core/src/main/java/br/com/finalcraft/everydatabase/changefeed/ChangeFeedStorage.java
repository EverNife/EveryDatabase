package br.com.finalcraft.everydatabase.changefeed;

import br.com.finalcraft.everydatabase.Storage;

/**
 * Optional capability: a {@link Storage} that can push {@link ChangeEvent}s when entities change,
 * so other instances sharing the backend can invalidate their caches.
 *
 * <p>Backends that cannot observe changes simply do not implement this interface; the compiler
 * forces callers to {@code instanceof}-check, exactly like {@code TransactionalStorage} and
 * {@code SchemaAwareStorage}.
 *
 * <p>This is <b>cache invalidation</b>, complementary to the optimistic-lock {@code lock_version}
 * (which resolves write conflicts): a change feed lets a {@code CachingManager} in another instance
 * learn that its cached copy is stale. Wire it with the manager-side {@code CacheSync} consumer.
 *
 * <h3>Delivery semantics</h3>
 * At-least-once, unordered, and (for fire-and-forget transports like Postgres {@code NOTIFY})
 * lossy: an event may be duplicated, reordered, or dropped. This is safe for cache invalidation -
 * the cache cell's monotonic stamp makes duplicates/reorders harmless, and a missed event
 * self-heals under a TTL policy. Do not use this as a reliable event log.
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@code MongoStorage} - MongoDB Change Streams (resumable; requires a replica set).</li>
 *   <li>{@code PostgreSqlStorage} - {@code LISTEN/NOTIFY} (fire-and-forget; pair with a TTL).</li>
 *   <li>{@code InMemoryStorage} - reference implementation; emits on local writes (per-process).</li>
 * </ul>
 *
 * @see ChangeFeedSupport
 */
public interface ChangeFeedStorage extends Storage {

    /**
     * A stable identifier for <b>this storage instance</b>, unique per process/connection-set.
     * Carried on {@link ChangeEvent#originId()} for events this instance produces, so a consumer
     * can skip invalidating the cache that just wrote (it was already refreshed write-through).
     * Sources that cannot attribute origin (Mongo oplog, DB triggers) leave the event's origin
     * empty; the skip then simply never fires.
     */
    String originId();

    /**
     * Registers {@code listener} to receive change events. Returns a {@link ChangeSubscription};
     * close it to stop delivery. Multiple listeners may be registered.
     */
    ChangeSubscription subscribe(ChangeListener listener);
}
