# Changelog

All notable changes to this project. Follows [Semantic Versioning](https://semver.org/).

Versions are published as `everydatabase-core`, `everydatabase-libby`,
`everydatabase-manager` and `everydatabase-manager-jedis` under the group
`br.com.finalcraft.everydatabase`.

## [1.1.1] — 2026-07-28

### Changed
- **`CachingManager` loads are now single-flight.** Concurrent misses on the same key share one
  `Repository.find` instead of each issuing its own: the second caller waits on the read already
  in flight, and both converge on the same instance. Identity was never at risk (`installColdMiss`
  is keep-first), but the duplicate read was — and the second caller used to wait on a read that
  started later than the one it could have joined.

  The freshness policy is deliberately not part of the in-flight identity: a load always fetches
  the authoritative row, and the policy only decides whether the *cached* cell could have been
  served, which is already answered before any load is issued. A `noCache()` read still bypasses
  everything, as its contract requires, and `getAll` is unchanged — a batch overlapping an
  in-flight single read still reads twice.

  Two consequences of sharing a read, both intended. A caller that joins sees the row as of when
  that read started, so an `invalidate(key)` landing mid-flight does not force a fresh read for
  whoever joins — `refresh(key)` never joins, and is the way to demand a read that starts *now*.
  And `missCount` is no longer a load count: concurrent misses on one key produce a single
  `loadSuccessCount` / `loadFailureCount` increment, so expect misses to exceed loads under
  contention (`loadFailureRate()` is per load, not per miss).

- **A `Repository.find` that *throws* instead of returning a failed future now fails that read**,
  rather than propagating out of `resolve`/`resolveCell` synchronously. Reads are uniformly
  async again, the failure is counted like any other load failure, and the key is left free for
  the next reader.

## [1.1.0] — 2026-07-20

Two independent correctness fixes, each verified against the running code before it
became a change: `Map` iteration order is no longer destroyed on write, and the
pub/sub cache-sync transport no longer cross-invalidates caches across unrelated
physical stores.

### Changed
- **Map insertion order is now preserved by default in every Jackson codec profile.**
  `JacksonConfig` no longer enables `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`
  on any profile (`baseReadContract`/`storageSafe`/`compact`), so a `Map` is written
  in the order it iterates instead of being reordered by key. The previous default
  silently reordered any `Comparable`-keyed map (and `@JsonAnyGetter` output) on the
  first write.

  > **⚠️ Data note:** data already written under the old default — with its `Map`
  > order reshuffled — is **irrecoverable**. The original iteration order was never
  > persisted anywhere, so this fix stops the loss from the next write onward; it does
  > not restore order lost in rows saved before the upgrade.

### Removed
- The `JacksonConfig.canonicalMapOrder` helper was removed entirely. EveryDatabase no
  longer offers canonical (key-sorted) `Map` serialization in any form — not as a
  default, not as an opt-in profile. A consumer who wants key-sorted bytes enables
  `ORDER_MAP_ENTRIES_BY_KEYS` on their own `ObjectMapper` and passes it to the codec
  (`new JacksonJsonCodec<>(Type.class, mapper)`), owning the consequences.

### Added
- **Backend identity — the pub/sub cache-sync transport now scopes by physical store.**
  Every `Storage` answers `backendIdentity()` (a `default` method, following the same
  idiom as `enforcesOptimisticLock()`), a stable identity of the physical store that
  never contains credentials. `CacheSync` routes each event by `(backendId, collection)`
  so a change only invalidates caches that share the same store, not merely the same
  collection name. On the Jedis transport the Redis/Valkey channel is now
  per-identity (`<channelBase>:<backendId>`, with the old `channel()` value becoming the
  prefix), so the broker itself routes only the traffic a store's subscribers care about.
  A `ChangeEvent` now carries a nullable `backendId`; a missing one applies to every
  manager of the collection (backward-compatible routing).
- **`SyncParticipation` (`RECOMMENDED`/`ALWAYS`/`NEVER`)** and a `sharedIdentity(String)`
  override on every `*Config` (`SqlConfig`/`MongoConfig`/`LocalFileConfig`/
  `GroupedFileConfig`/`InMemoryConfig`). `sharedIdentity`, when set, *is* the backend
  identity — the escape hatch for declaring that two textually different backends (two
  `localhost:3306`, two file directories) are the same physical store. `syncParticipation`
  gates only the transport's publish side; `SyncBindGuard.checkParticipation` fails fast
  at bind time when a machine-local backend declares `ALWAYS` without a `sharedIdentity`.

  > **⚠️ Behavior note (transport pub/sub only):** under the default `RECOMMENDED`,
  > a **machine-local** backend (SQL/Mongo on `localhost`, H2 `mem:`, any local file
  > directory) no longer publishes on the pub/sub transport — correct traffic savings
  > for the common single-process case. Two local processes that sync via Redis/Valkey
  > over such a backend must now declare the **same** `sharedIdentity` on both, or
  > `syncParticipation=ALWAYS` (which itself requires a `sharedIdentity` on a
  > machine-local backend, or the bind fails fast with `IllegalStateException`). The
  > native change-feed path (Mongo Change Streams, PostgreSQL `LISTEN/NOTIFY`) is
  > unchanged.

## [1.0.9] — 2026-07-15

### Added
- `manager.entityschema` — per-entity payload upcasting: the third schema axis,
  alongside DDL migrations and the optimistic lock. Includes `EntitySchemaMigrations`,
  `EntitySchemaMigratingCodec` and the `EntitySchemaSweeper` with a cross-instance lease.
- `manager.writeback` — batch write-back flush with conflict resolution
  (`WriteBackFlusher`, `ConflictHooks`, `FlushMode`).
- `CachingManager` write freeze (`tryFreezeWrites()`) — suppresses persistence while
  keeping the cache live.
- `Repository.scanAll(cursor, limit)` and `WriteMode.UPDATE_ONLY` for maintenance sweeps.
- `Storage.enforcesOptimisticLock()` plus `SyncBindGuard`, a bind-time guard against
  binding a versioned descriptor to a backend that cannot enforce the lock.

### Changed
- The leading-underscore collection namespace is reserved for framework metadata.

### Removed
- **Breaking:** `Repository.queryAfter` no longer ships a throwing default implementation.

## [1.0.8] — 2026-07-10

### Fixed
- Dirty write-back cells are pinned against LRU eviction, so a pending write can no
  longer be evicted before it is flushed.

## [1.0.7] — 2026-07-05

A hardening release: 49 commits, almost entirely correctness fixes across every backend.

### Fixed
- **Lifecycle** — `init()`/`close()` transitions are guarded by a lock and idempotent;
  pre-init `repository()` and post-lambda transaction scopes fail cleanly; PostgreSQL
  `close()` is mutually exclusive with the change-feed start.
- **Transactions & locking** — nested transactions are rejected; Mongo sets its
  in-transaction `ThreadLocal` only after `startSession()` succeeds, serialises versioned
  `saveAll`, closes the session on a failed `startTransaction` and retries unknown commit
  results; PostgreSQL emits change `NOTIFY` on the transaction connection so commit and
  rollback gate delivery.
- **Cache & sync** — TTL freshness is based on a monotonic clock; dirty write-back cells
  are exempt from `purgeExpired()`; tombstoned cells are dropped from `getAll()` instead
  of yielding null; duplicate type registration is rejected; a stale change-stream token
  is recovered; a malformed pub-sub payload is surfaced rather than dropped.
- **Query** — RANGE is type-tolerant in the scan backends (no `ClassCastException`);
  `Query.eq`/`in` reject null; query values are coerced to the hint type; SQL chunks
  IN-clause reads and absorbs concurrent-startup `ALTER` races; SQL stores TIMESTAMP
  index columns in UTC rather than the JVM timezone; InMemory index reads take the same
  lock as writes.
- **Keys & files** — null keys are rejected everywhere; over-long keys are hash-truncated
  so a valid key never overflows the filename limit; case-differing and reserved-device-name
  keys get hash-suffixed stems; `count()` and `all()` agree on corrupted files; the
  migration ledger is written atomically on file backends.
- **Schema** — `currentVersion()` reports the greatest applied version; duplicate migration
  versions are rejected at `register()`; the InMemory ledger resets on close.
- **Transfer** — retained errors are bounded and the source stream is closed; `FAIL_FAST`
  keeps its "exactly one error" contract with `verifyCounts` enabled; `SKIP_EXISTING`
  reports per-entity errors.
- **Log** — SQL CRUD failures reach the ERROR floor; keys stay opt-in in log events;
  `StorageLog.emit` rethrows fatal `Error`s instead of swallowing them.
- `Storages.create(null)` throws the documented `IllegalArgumentException` instead of NPE.
- `Codec.encode()` no longer fails on a Map with a non-Comparable key type.

### Changed
- README dependency versions are stamped from the version catalog and verified by `check`.
- **Breaking:** the never-applied `prettyPrint`/`fsyncEvery` LocalFile config fields were
  removed.

## [1.0.6] — 2026-07-03

### Added
- Observability — cache metrics plus cache-sync mode, health and counters
  (`manager` and `manager-jedis`).
- `CacheSync.via(transport)` works in `auto()` mode; the transport falls back to polling
  while disconnected (on by default).
- Production connection config for `manager-jedis` (TLS, ACL, timeouts, pool).
- Cache-aware quality-of-life methods on `CachingManager`.
- Public coordinate accessors in `libby` for relocating consumers.

### Changed
- The build adopts the FinalCraft Jabel fork, so a single JDK 25 compiles the whole
  project down to Java 8 bytecode.
- All dependencies updated to their latest Java-8-safe versions.

## [1.0.5] — 2026-06-29

### Added
- `everydatabase-manager-jedis` — a Redis/Valkey pub-sub cache-sync transport, replacing
  version-polling with real push on backends without a native change feed.
- A pluggable `CacheSyncTransport` axis in `manager`.
- A configurable benchmark suite, and CI via GitHub Actions.

### Changed
- The NOTIFY payload was promoted to a backend-neutral `ChangePayload`.

## [1.0.4] — 2026-06-26

### Added
- Cross-process cache synchronization across all backends.
- Ordering and pagination — `query(Query, QueryOptions)` became the single query
  primitive, with `Slice`/`Page` responses, `count(Query)`, `querySlice`/`queryPage`,
  `page(n, size)` sugar, and keyset (cursor) pagination via `queryAfter`.
- `JacksonConfig` profiles to enrich the default mapper; the secondary index is built
  with the codec's own mapper.
- `GroupedFileStorage` — key-major aggregate files.

### Fixed
- `QueryOptions` rejects a negative limit/offset.
- Data survives repeated close/init cycles on the SQL backends.

## [1.0.3] and earlier — up to 2026-06-20

Initial development, extracted from EverNifeCore's `br.com.finalcraft.evernifecore.storage`
package into a standalone project.

- The backend-agnostic `Storage`/`Repository` contract over SQL (MySQL/MariaDB/PostgreSQL/H2),
  MongoDB, local files and in-memory storage.
- `IndexHint` and `@Indexed` — declared secondary indexes with auto-creation and
  auto-deletion on SQL and Mongo.
- Optimistic locking via the `@OptimisticLock` annotation or the `Versioned` interface,
  enabling safe co-editing across applications.
- `StorageTransfer` — migration between any two backends.
- Schema migrations, including on InMemory.
- A log system, silent by default, with a customizable sink.
- `StorageKeys` — the 255-character cross-backend key contract.
- `everydatabase-manager` — the caching/reference add-on: `Ref`, the per-context
  `RefRegistry` with parent chaining, `CachingManager`, dirty tracking and write-back.
- `everydatabase-libby` — the runtime-download distribution flavor.
- Backported to Java 8 (HikariCP 4.0.3, H2 1.4.200); versions centralized in
  `gradle/libs.versions.toml`.

[1.0.9]: https://github.com/EverNife/EveryDatabase/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/EverNife/EveryDatabase/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/EverNife/EveryDatabase/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/EverNife/EveryDatabase/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/EverNife/EveryDatabase/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/EverNife/EveryDatabase/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/EverNife/EveryDatabase/releases/tag/v1.0.3
