# Changelog

All notable changes to this project. Follows [Semantic Versioning](https://semver.org/).

Versions are published as `everydatabase-core`, `everydatabase-libby`,
`everydatabase-manager` and `everydatabase-manager-jedis` under the group
`br.com.finalcraft.everydatabase`.

## [Unreleased]

## [1.2.0] - 2026-07-28

Performance and layout work on the file backends. Both of them stop paying full-decode prices for
questions that never needed the payload, both learn to describe their own on-disk format instead of
guessing it, and both gain a push change feed. Grouped files additionally gain key spaces, directory
fan-out, and a key-major read/write API that matches what the backend already is.

### Added

- **`Repository.keys(Cursor, int)`** - key-ordered pagination over the stored keys, decoding no
  entity at all: an index-only scan on SQL, a covered query on Mongo, a plain directory listing on
  LocalFile. There was no way to ask "which keys exist" without decoding entities, so selective
  preloading, reconciling two backends and sweeping all paid a full decode through `all()` or
  `scanAll()`.

  GroupedFile is the exception, and it is not free: a key file aggregates every collection sharing
  the key, so a key whose file exists without *this* collection is not a key of this collection and
  the directory alone cannot say which is which. It opens every key file and runs a streaming field
  probe - no tree built, no entity decoded, but every file read. On both file backends the page is
  also cut from a freshly built, fully sorted name list, so each page costs a whole sweep.

  ```java
  Slice<String> page = repo.keys(Cursor.scan(), 500).join();
  List<Player> loaded = repo.findMany(parse(page.content())).join();
  ```

  The keys are storage keys, in `ScanRow.key()`'s form. On the file backends a key that had to be
  sanitised into a file name is reported sanitised and does not round-trip to `find` - recovering
  the original would mean reading the payload this exists to avoid. Use `scanAll` when the exact
  original matters for keys that are not plain UUID/numeric/lower-case strings. A row whose payload
  is unreadable still appears here, following the same rule as `count()`.

- **Key spaces for grouped files** - `GroupedFileConfig.builder(base).keySpace(name, collections...)`
  puts a group of collections in its own sub-directory, with its own listing and its own locks. One
  base directory tends to accumulate collections keyed by unrelated things (player UUIDs, account
  UUIDs, free-form cooldown ids); they share the directory but never share a meaningful key, so
  every scan reads files that cannot hold what it is looking for, and an accidental key collision
  puts two unrelated collections in one file behind one lock.

  Collections are declared grouped by key space because the group is the point: co-location is what
  a key space *means*, and listing the members together makes a typo look wrong instead of quietly
  splitting one entity's file in two. Declaring none leaves the tree exactly as it was.

- **Directory fan-out** - `GroupedFilePartitioner.hashFanout(levels)` / `prefix(chars)` / `flat()`,
  passed to `keySpace(...)`. A key space shrinks the small directories and leaves the big one as big;
  ten thousand files in one directory already slows listing down on NTFS. Point reads still resolve
  the path rather than searching for it, so nothing on the read path gets worse.

  The hash is SHA-1 over the key's UTF-8 bytes, which rules out `String.hashCode`: a file's location
  is permanent, so the function that decided it has to be identical on every JVM, version and OS.

- **`GroupedFileRelayout.relayout(config)`** - moves files to where a new configuration places them,
  after declaring a key space or changing a partitioner. It moves *entries*, not files: a key file
  holds collections that are staying put, so it is split rather than moved. Idempotent; prunes the
  bucket directories it empties.

- **`KeyMajorStorage`** (new package `keymajor`, with `KeyBundle` and `KeyBatch`) - reads or writes
  every collection of one key in a single operation. Implemented only by grouped files, where the
  collections already share one file behind one lock; every other backend stores them apart, so
  callers check with `instanceof` and fall back to N calls.

  ```java
  KeyBundle bundle = kms.loadKey(uuid, PLAYER_DATA, ECONOMY, HOMES).join();   // one parse
  kms.batchKey(uuid, b -> b.put(PLAYER_DATA, data).put(ECONOMY, eco)).join(); // one atomic move
  ```

  The write side is not merely cheaper: a crash between two of N saves leaves the key with half its
  collections updated, which one publication cannot do. That is **atomicity per key and nothing
  else** - grouped files still do not implement `TransactionalStorage`, and nothing here spans two
  keys. Descriptors from different key spaces are refused rather than served with N reads.

- **A change feed for local and grouped files** - both now implement `ChangeFeedStorage` over the
  operating system's file-watch notification, so `CacheSync.attach(storage)` takes the push path
  with no poll interval. This is the only feed that sees a change made *outside* the application: an
  administrator editing a file by hand invalidates caches exactly like a write through the API.

  Two consequences worth knowing. A file event carries **no origin** - a file system has nowhere to
  record who wrote a file, and claiming otherwise would make every other instance in the process
  discard the event as its own - so a local write echoes back and re-marks the cell it just
  refreshed. And on grouped files the event names the *file*, so every collection sharing that key is
  woken: a false wake-up, never a missed one. On macOS the JDK falls back to an internal polling
  watcher with second-scale latency; use `PollingCacheSync` explicitly there if the cadence must be
  yours.

- **`GroupedFileConfig.rootCacheSize(int)`** - grouped files memoize aggregate documents, bounded
  (default 256 documents; `0` disables). A key file holds every collection sharing its key, so
  loading one entity-root used to read and parse the same file once per collection - 20 collections
  meant 20 parses of one document. Point reads and writes now share one parsed document; directory
  scans deliberately do not participate, since they touch each file once.

  Validity is a `(lastModifiedTime, size)` stamp checked per access, which costs one extra syscall
  on a read that misses the memo - a workload that only ever touches one collection per key is
  marginally better off with `rootCacheSize(0)`. Writes made through the storage refresh the memo
  directly; an external process rewriting a file to exactly the same length within one filesystem
  timestamp tick is the case the stamp cannot see.

- **`TreeCodec`** - an optional codec capability (same idiom as `ObjectMapperAware`) for converting
  directly to and from a Jackson tree. `JacksonJsonCodec` and `JacksonYamlCodec` implement it;
  anything else keeps working through `encode`/`decode`.

  It exists for the backends that already hold a tree: GroupedFile embeds each entity as a sub-node
  of a shared document, and InMemory round-trips entities to isolate the caller's instance from the
  stored one. Both used to serialise that tree to bytes purely so the codec could parse it straight
  back - bytes that were never stored and never transmitted. SQL and Mongo are deliberately
  untouched: they persist the encoded bytes, so for them there is no round-trip to remove.

  `IndexValueExtractor.toTree` now asks the codec for its tree form before falling back to a mapper.
  That one is a correctness fix, not a speed one: a codec that builds trees without exposing an
  `ObjectMapper` was previously indexed through the fallback mapper, so its indexed values could
  disagree with what it persisted.

### Fixed

- **A file store opened with the wrong codec format now fails instead of reporting an empty
  collection.** This was silent data loss in the worst shape a persistence library has: both file
  backends resolve every path through the codec's extension, and nothing on disk recorded which
  extension the data had actually been written with. Opening a YAML store with a JSON codec matched
  no files, reported the collection as empty, and the first save wrote a parallel `.json` file
  beside the `.yml` one still holding the data. No error, anywhere.

  Grouped files record the container format of the whole directory in `_schema/layout.json`; local
  files record the extension **per collection** in `_schema_layout.json`, because each collection
  owns a sub-directory and may legitimately differ from its neighbours. A store that predates these
  files has its format inferred from what is on disk and written down. One that holds both formats
  refuses to open at all, listing how many files of each it found - that is the fingerprint of a
  mismatch that already happened, and only the operator can say which set to keep. An unreadable
  layout fails the open rather than falling back to the codec's guess.

  Grouped files record collection placement and fan-out there too, so a configuration that
  disagrees with the record fails to open: where a file lives is a file operation, not a config
  change. `GroupedFileRelayout` is how you make the move.

- **Local and grouped files report a version that moves**, so `PollingCacheSync` detects remote
  *updates* over them and not only deletes. They used to report `0` for every existing key, which
  meant two processes over one directory - a network mount, or one process holding two storages -
  served stale data indefinitely. The poller never needed a lock version: it asks whether the number
  grew, and a file answers that with its modification time (with the size folded into the low bits,
  so two writes inside one clock tick still differ).

  Grouped files still check that the key file holds the collection before stamping it - a file that
  lost this collection is a delete of it, however recent the file is. The flip side is that the
  stamp is per file, so writing one collection makes the others reload once for nothing.

### Changed

- **`Repository.count()` counts stored rows, not decodable ones.** A row whose payload fails to
  decode is now included: it occupies storage and a write to its key overwrites it, so it exists.
  `all()` and `query()` still skip it, which makes `count() != all().count()` the signal that a
  collection holds a poisoned row - and `scanAll()` is what names it. This aligns the file backends
  with SQL (`SELECT COUNT(*)`) and Mongo (`countDocuments`), which always counted such rows.

- **`count()` and `keys()` on grouped files fail on a key file they cannot parse**, instead of
  quietly leaving it out. A key file holds every collection sharing its key, so one that will not
  parse belongs to *no* collection: skipping it under-reports the collection being counted, and
  counting it inflates every other collection in the directory. Neither number is true, and the old
  behaviour picked the first one and logged a WARN - so a collection missing rows reported a clean,
  confident count, and even the `count() != all().count()` tell-tale read as clean, because `all()`
  omitted the same file.

  The failure names the file and points at the tool that lists them all:

  ```
  GroupedFile: cannot count 'ec_accounts': key file 'a3f1c2.json' is not a readable document,
  so it belongs to no collection and any answer would be a guess. Use scanAll() to list every
  unreadable file, then repair or remove it.
  ```

  Deliberately unchanged: a **poisoned row** (a file that parses and declares this collection, whose
  payload does not decode) is still counted - it is unambiguously a row of this collection. `all()`
  and `query()` still skip what they cannot decode, which is their contract on every backend, and
  `scanAll()` still reports each offending file as a failed `ScanRow`. No other backend can reach
  this state: elsewhere a row is attributable by construction, so nothing about them changed.

  The old behaviour cost a full read of the collection to answer "how many": LocalFile decoded
  every file and GroupedFile decoded every matching sub-node, only to discard the result. Counting
  is now a directory listing on LocalFile and a presence probe per key file on GroupedFile.

- **`Repository.versions(...)` documents its value as opaque.** It is comparable only with an earlier
  reading of the same key on the same backend; all a caller may conclude is that a bigger number
  means the row changed. It is a `lock_version` on the enforcing backends, a file stamp on the file
  backends, `0` on H2 and on non-versioned descriptors elsewhere - never a counter, never a
  timestamp to format, never comparable across backends.

- **`CollectionStats` gained `entitiesRead()`**, and `StorageTransfer`'s count verification now
  compares what was written against what the source *handed over* rather than against
  `sourceCount()`. A source row that cannot be decoded is reported as its own explicit transfer
  error (naming how many were left behind) instead of surfacing as a confusing count mismatch -
  previously such a row was invisible in the report on both sides. The `CollectionStats`
  constructor takes the new value after `sourceCount`.

- **Grouped-file reads stream instead of materialising the whole document.** A key file aggregates
  every collection sharing its key, but a repository owns one of them; scans now walk the top-level
  field names with a streaming parser and materialise at most the one that matches.

### Breaking

- **`Repository` gained `keys(Cursor, int)`.** Any implementation of the interface outside this
  project must add it. Test doubles inside a consumer's own suite are the likely place this lands.
- **`CollectionStats`'s constructor takes `entitiesRead` after `sourceCount`.** Direct construction
  is unusual - the type is normally produced by `StorageTransfer` - but the signature changed.
- **A grouped-file or local-file directory whose format disagrees with the codec now throws on
  open** where it previously returned an empty collection. That is the fix, not a regression, but a
  consumer whose configuration was quietly wrong will see the failure at startup.
- **`versions()` on the file backends no longer returns `0`.** Code that compared the value against
  a literal zero, rather than against an earlier reading, needs to stop.
- **`count()` and `keys()` on grouped files can now throw** (`IllegalStateException`) where they
  previously returned a number or a page that quietly omitted an unparseable key file. A caller that
  treats a count as infallible - a boot guard, a health check - needs to decide what an unreadable
  store means to it.

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
