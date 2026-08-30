# Changelog

All notable changes to this project. Follows [Semantic Versioning](https://semver.org/).

Versions are published as `everydatabase-core`, `everydatabase-libby`,
`everydatabase-manager` and `everydatabase-manager-jedis` under the group
`br.com.finalcraft.everydatabase`.

## [Unreleased]

## [1.5.0] - 2026-08-30

The types a plugin actually reaches for stop being second-class.

`java.math.BigDecimal` started it: accepted everywhere and returned intact almost nowhere. The number
came back rounded on MongoDB, stripped of its scale on InMemory and GroupedFile, and the only index
it could take was a `double` one that folded distinct amounts together. For a plugin holding
balances, that is the one Java type that must not drift.

Surveying the rest of the standard library the same way found the same shape of loss in the temporal
types - a `ZonedDateTime` came back as UTC, its zone gone - and a "cannot auto-detect" wall in front
of everything from an `enum` to a `LocalDate`.

### Added

- **`IndexHint.FieldType.DECIMAL`, with `IndexHint.bigDecimal(path)` and `@Indexed` auto-detection on
  any `java.math.BigDecimal` field.** The comparison is decimal rather than binary, so two amounts
  that differ past the 17th significant digit are two values instead of one, and `2.50`, `2.5` and
  `"2.5"` are three spellings the query accepts for the same row. Ordering and ranges compare
  numerically on all seven backends (a string index would sort `10.25` before `2.50`).

  Columns are `NUMERIC` on PostgreSQL and H2 (unbounded), `DECIMAL(65,30)` on MySQL/MariaDB - the
  widest their engine has, so the *index* there rounds past 30 decimal places and the server refuses
  more than 35 integer digits - and BSON `Decimal128` on MongoDB. InMemory and both file backends
  hold the `BigDecimal` itself, canonicalised so `2.50` and `2.5` land in one bucket the way a
  numeric column compares them.

  Before this, `@Indexed` on a `BigDecimal` field threw at `build()`, and the only option was
  `IndexHint.decimal` - a `double` index that rounds every value on the way in.

- **`IndexHint.FieldType.DATE`, with `IndexHint.date(path)` and `@Indexed` auto-detection on a
  `java.time.LocalDate` field.** A day is not a moment, and the difference is not pedantry: turning
  one into the other takes a time zone the value does not carry, and two processes in different
  zones would then disagree about which day a row is on. The column is a native SQL `DATE`; MongoDB
  and the map/scan backends hold the canonical ISO text, which sorts chronologically, so ordering
  agrees everywhere. A query takes a `LocalDate`, anything carrying one in a zone of its own
  (`LocalDateTime`, `ZonedDateTime`, `OffsetDateTime`), or an ISO string - and deliberately not an
  `Instant`, whose day is a guess without a zone.

- **`@Indexed` auto-detects the rest of the standard library.** `char`/`Character` and any `enum`
  index as text; `byte` and `short` as INT; `BigInteger` as the exact DECIMAL; `ZonedDateTime`,
  `OffsetDateTime` and `java.util.Date` as TIMESTAMP; `LocalDate` as DATE. Each of these threw
  "cannot auto-detect IndexHint type" before, and the message now names both ways out (an explicit
  `type =` for a text-shaped type like `URI`/`Currency`/`Duration`, and `path =` for a value inside
  a nested object) instead of only the first.

- **A `TIMESTAMP` query takes any spelling of the moment** - `Instant`, `LocalDateTime`,
  `ZonedDateTime`, `OffsetDateTime`, `java.util.Date`, epoch millis or ISO text. It accepted the
  first two.

### Fixed

- **`@Indexed(type = ...)` that names a temporal type the field cannot produce now fails at
  `build()`.** `@Indexed(type = Instant.class)` on a `LocalDate` compiled, saved, and indexed `null`
  on every row: the query it existed for returned nothing, forever, with no error anywhere to
  explain it. The check is deliberately narrow - only a date-ish field against a date-ish index it
  can never fill - so a `String` indexed as INT (it may hold digits) and a `BigDecimal` indexed as
  DOUBLE (a deliberate, documented downgrade) both still build.

- **A `ZonedDateTime` keeps its zone, and an `OffsetDateTime` its offset.** Jackson writes only the
  offset by default and rewrites every offset date to UTC on read, so `10:15+02:00[Europe/Paris]`
  came back as `08:15Z` - the same instant, and a different value: the local time and the zone are
  usually the reason the type was chosen over `Instant`. `storageSafe` now writes the zone id and
  `baseReadContract` stops adjusting to the context zone. Data written before this keeps its offset
  and loses only the zone id it never carried.

- **An undeclared number decodes as a `BigDecimal`, on every backend.** A fractional value in a
  field typed `Object` or a `Map<String, Object>` came back as a `Double` from LocalFile, the SQL
  dialects and MongoDB, but as a `BigDecimal` from InMemory and GroupedFile, which route the entity
  through a tree - the same collection, two different Java types depending on where it lived.
  `USE_BIG_DECIMAL_FOR_FLOATS` is now part of the read contract, which settles it as the lossless
  one. **Breaking for a caller that casts:** `(Double) map.get("amount")` on an untyped map now
  throws `ClassCastException` - read it as a `Number` (`((Number) v).doubleValue()`) instead. Fields
  with a declared type are untouched.

- **A `BigDecimal` keeps every digit and its scale on all seven backends.** Three separate ways of
  losing it, all of them silent:

  MongoDB stored the payload through `Document.parse`, whose JSON reader turns every fractional
  number into a `double`: `1234567890123456789012.123456789` came back as `1.2345678901234567E+21`,
  a *different amount*, and `2.50` as `2.5`. (An integer beyond `long` range did not even parse -
  the save died with a raw `NumberFormatException` from the driver's scanner.) The payload now goes
  through `BsonTrees`, which maps a decimal to BSON `Decimal128` - exact, scale-preserving, and the
  type MongoDB itself compares and indexes - and reads it back as a plain JSON number. BSON's 34
  significant digits are a real ceiling: a wider value now **fails its save with a message naming
  the field**, instead of being rounded into the collection. Rows already in a collection are read
  back as they were stored - a number rounded by the old path stays rounded, since the digits it
  lost were never written down; the next save of that entity stores it exactly.

  InMemory and GroupedFile route the entity through a Jackson tree, and Jackson strips a
  `BigDecimal`'s trailing zeros when it builds a node - so a price of `2.50` was persisted as `2.5`
  and `100` as `1E+2`. `JacksonConfig.baseReadContract` now pins the exact node factory, which every
  codec profile inherits.

  Parsing a stored document back into a tree - the aggregate file store, a scan backend filtering
  before it decodes - read numbers as `double` for the same reason. Those paths now use
  `JacksonConfig.exactTreeReader`, a per-call override that leaves the codec's own binding contract
  untouched (a field declared `Object` still deserialises a fractional number to `Double`).

- **PostgreSQL no longer skips the base dialect's JDBC conversions.** Its `toJdbcValue` returned the
  value unchanged for every type it did not handle itself instead of deferring to `super`, so a
  conversion added to the base dialect silently did not apply there. Surfaced by the new numeric
  column: a query value that does not spell a number reached the driver as a string and raised
  `operator does not exist: numeric = character varying` - where every other backend matches nothing.
  A `NULL` bound against a numeric column now carries its SQL type, which PostgreSQL needs to compare
  it at all.

- **A range bound of a type the stored value cannot be compared with matches nothing, instead of
  raising `ClassCastException` on the scan backends.** `normalizeQueryValue` documents that an
  uncoercible value "matches nothing, on every backend" - true of SQL and Mongo, but a scan compared
  it with `Comparable.compareTo` and threw.


## [1.4.0] - 2026-08-13

A UUID becomes something you can index, and query values stop meaning different things on different
backends. The two are the same story: the reason a `UUID` could not be indexed is the reason a
`UUID` handed to a query did not reach the value stored for it.

### Added

- **`IndexHint.FieldType.UUID`, with `IndexHint.uuid(path)` and `@Indexed` auto-detection on any
  `java.util.UUID` field.** Until now the only indexable UUID was the entity's own key, which is
  the primary key and needed no hint - so a *secondary* UUID (`ownerId`, `guildId`, and every
  manager `Ref`, which serializes as its key) had no way to be declared at all. `@Indexed(type =
  UUID.class)` is how a `Ref` field gets an index, the annotation naming the key's type rather than
  the field's.

  The value is carried as the canonical 36-character lowercase string. That is a deliberate choice
  and the whole reason ordering agrees across backends: `java.util.UUID.compareTo` compares the two
  longs *signed*, a different order from byte-wise, while lexicographic order over lowercase hex
  *is* byte-wise order - which is what PostgreSQL's native `uuid` compares. Columns are `CHAR(36)`
  on MySQL/MariaDB, the native `UUID` on PostgreSQL (16 bytes, bound as a real `java.util.UUID`),
  and `VARCHAR(36)` on H2 - not H2's native `UUID`, which would sort by the signed longs. Mongo,
  both file backends and InMemory hold the canonical string.

  Either spelling works at the call site: `Query.eq("guildId", uuid)` and
  `Query.eq("guildId", uuid.toString())` match the same rows, upper case included.

### Fixed

- **A query value is now coerced to the indexed field's type on every backend, not only the scan
  ones.** `IndexValueExtractor.normalizeQueryValue` was called by InMemory, LocalFile and
  GroupedFile alone, on the reasoning that JDBC and BSON coerce natively - true of numbers, false of
  everything else. The visible cost was on MongoDB, where a value whose BSON encoding differs from
  the stored form matched **nothing, silently**: a `java.util.UUID` encodes as binary subtype 4 and
  never meets a stored string, and `Query.eq("name", 42)` against a STRING index never met `"42"`.
  SQL and Mongo now normalize as well, so one `Query` means the same thing on all seven backends.

- **A range bound that cannot be converted no longer widens the range.** SQL decided which ends were
  open by looking at the *converted* bound, so a bound that converted to `null` - an unparseable
  timestamp, now also a value that does not spell a UUID - was read as "no bound" and quietly
  returned more rows than asked. The open ends are now read off the condition itself; a bound that
  fails conversion binds as `NULL` and matches nothing, which is what every other backend does.

Also in this release, two ways a scan and a write of the same directory could hurt each other.
Neither is a corner case: every write publishes through a sibling `.tmp` it then renames away, so a
scan running next to a write routinely walks entries that are disappearing.

- **A directory scan no longer escapes as a raw stream failure.** A directory stream reports a
  mid-iteration failure as `UncheckedIOException`, and `Files.walk` adds a second source of them by
  stat-ing every entry it hands out - so a `.tmp` vanishing between the directory read and the stat
  raised one naming that entry. `UncheckedIOException` is not an `IOException`, so it slipped past
  the `catch (IOException)` every scan is wrapped in and reached the caller instead of the backend's
  own error. Listing now goes through `DirectoryListing`, which translates it back into the checked
  failure those scans already handle. Affected every local-file scan (`count`, `all`, `scanAll`,
  `keys`, `query`), the grouped-file key listing, both layout inferences and the change feed's
  registration walk.

- **A write no longer loses its rename to a concurrent reader on Windows.** Renaming over a file
  another thread holds open fails there with `AccessDeniedException` - measured at roughly half of
  the writes to a directory being scanned at the same time, and at none of them when nothing read
  it. The blocking handle lives only as long as one read, so the rename is now retried for a few
  milliseconds instead of being reported. POSIX never saw this and still takes the first attempt.
  All six copies of the temp-file-plus-rename dance (both repositories, both migration ledgers,
  both layout files) now share one `AtomicFileWrite`.

## [1.3.0] - 2026-08-12

The manager registry learns to survive a live reload. A `RefRegistry` can now stay the same object
for the life of a process while a reload swaps only its *content*, manager by manager - so a `Ref`
alive from before the reload (in an entity field, a GUI, a static) resolves the new generation on
its next access, with no callback machinery and no window in which the type is unresolvable.

### Added

- **`RefRegistry.replace(type, resolver)`** - the atomic hot-swap primitive: installs a resolver
  over whatever is registered and returns the replaced one (`null` if none) so the caller can tear
  it down. At no instant is the type without a resolver - a concurrent `Ref.resolve()` sees the old
  manager or the new one, never the gap the unregister-then-register dance has. `register` keeps
  refusing an accidental duplicate; its message now teaches `replace` as the deliberate way out.

- **`RefRegistry.managerReplacing(descriptor, storage, options|policy, retired)`** - as
  `manager(...)`, but registering with replacement semantics. The replaced resolver is handed to
  the `retired` consumer, which owns its teardown in the order that makes live refs re-resolve:
  **flush pending writes → `clearCache()` → close the storage if that generation owned it**. The
  `clearCache()` is the step that cannot be skipped - it marks every cell evicted, which is what
  drops the memo of every live `Ref` pointing at the retired manager. A protected
  `CachingManager` constructor with the same semantics exists for domain-named subclasses.

### Notes

- A live `Ref` that crossed a swap keeps the *freshness policy* it memoized from the retired
  manager (the data comes from the new one). A per-reference `@RefPolicy`/`withPolicy` override is
  unaffected - it always wins; only the manager-default policy is memoized this way.

## [1.2.1] - 2026-08-03

Three ways the file backends could stay quiet about something they could not see.

### Fixed

- **A grouped-file store holding key files in a sub-directory now fails to open.** Only the base
  directory is read, so those files were invisible: the container format was inferred as if the
  directory were empty, the collections reported nothing, and the first save wrote a second copy
  beside data nobody could see - the same silent loss `_schema/layout.json` was added to prevent,
  reached through a different door. The failure names the sub-directory and says what to do with it
  (open a storage over that directory, or move the files up into the base).

- **A file event from below the base directory is no longer published as a key of the base.** The
  watch covers the tree, but only the base holds key files, so a file left in a sub-directory was
  announced under a key that resolves to nothing - to every collection of the storage.

- **A sub-directory the operating system refuses to watch no longer takes the whole change feed
  down.** Only `NotDirectoryException` was tolerated; any other `IOException` (a watch limit
  reached, a permission denied on one sub-tree) propagated out of `subscribe()` and left the storage
  with no feed at all. It is now reported and skipped - the rest of the tree keeps pushing - while
  the root still fails loudly, since losing it leaves nothing to watch.

## [1.2.0] - 2026-07-28

Performance and layout work on the file backends. Both of them stop paying full-decode prices for
questions that never needed the payload, both learn to describe their own on-disk format instead of
guessing it, and both gain a push change feed. Grouped files additionally gain a key-major read/write
API that matches what the backend already is.

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
  keys.

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
