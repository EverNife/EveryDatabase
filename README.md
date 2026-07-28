<div align="center">

# EveryDatabase

### One async API. Every database. Zero lock-in.

A backend-agnostic persistence layer for the JVM. Write your data-access code **once** against a small, typed, `CompletableFuture`-based API — then run it on **MySQL/MariaDB, PostgreSQL, H2, MongoDB, local files, grouped files, or in-memory** without changing a line. Migrate data between any two of them with a single builder.

![Runtime](https://img.shields.io/badge/runtime-Java%208%2B-blue)
![Build](https://img.shields.io/badge/build-JDK%2025-orange)
![Backends](https://img.shields.io/badge/backends-SQL%20%7C%20Mongo%20%7C%20File%20%7C%20Memory-green)
![Version](https://img.shields.io/badge/version-1.1.1-informational)

</div>

---

## Table of contents

- [Why](#why)
- [Supported backends](#supported-backends)
- [Install](#install)
- [Distribution flavors](#distribution-flavors)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Instantiating each backend](#instantiating-each-backend)
- [CRUD operations](#crud-operations)
- [Indexing & queries (`@Indexed`)](#indexing--queries-indexed)
- [Optimistic locking](#optimistic-locking)
- [Transactions](#transactions)
- [Schema migrations](#schema-migrations)
- [Entity payload schema (`everydatabase-manager`)](#entity-payload-schema-everydatabase-manager)
- [Moving data between backends](#moving-data-between-backends)
- [Logging & diagnostics](#logging--diagnostics)
- [Caching & references (`everydatabase-manager`)](#caching--references-everydatabase-manager)
- [Building & running the tests](#building--running-the-tests)
- [Project layout](#project-layout)
- [Compatibility notes](#compatibility-notes)

---

## Why

Most persistence libraries marry you to one engine. EveryDatabase treats the engine as a **deployment choice**, not an architectural one: ship with file storage for small scenarios, flip to MariaDB or MongoDB for large ones, and move the live data across — all with no code changes.

- **🔌 One interface, many engines.** `Storage` + `Repository<K, V>` is the entire surface you code against.
- **⚡ Async-first.** Every I/O call returns a `CompletableFuture` — block with `.join()` or compose. Virtual threads on Java 21+.
- **🧩 Capabilities are interfaces, not flags.** Transactions, schema migrations and queries are *optional* interfaces a backend may implement, checked with `instanceof`. No backend pretends to support something it can't.
- **🗂️ Declarative indexes.** Annotate a field with `@Indexed` (or declare an `IndexHint`) and the backend builds a real secondary index — a SQL column + B-tree, a Mongo index, or an in-memory map.
- **🔁 Built-in data transfer.** `StorageTransfer.builder()` copies entities between *any* two backends, read-only on the source, with batching, progress and verification.
- **☕ Java 8 runtime.** Java 8 bytecode authored in modern Java, with a Java-8-clean default dependency set — **every backend runs on a Java 8 JVM** (see [Java version requirements](#java-version-requirements)).

---

## Supported backends

| Backend | Factory | Transactions | Schema migrations | Secondary indexes | Optimistic locking | Persistence |
|---|---|:---:|:---:|:---:|:---:|---|
| **MySQL / MariaDB** | `Storages.createSQL` | ✅ | ✅ | ✅ native column + B-tree | ✅ | Durable |
| **PostgreSQL** | `Storages.createPostgreSQL` | ✅ | ✅ | ✅ native column + B-tree | ✅ | Durable |
| **H2** (mem / file / tcp) | `Storages.createH2` | ✅ | ✅ | ✅ native column + B-tree | ❌ *(by design)* | Durable / ephemeral |
| **MongoDB** | `Storages.createMongo` | ✅ *(replica set)* | ✅ | ✅ native index | ✅ | Durable |
| **Local files** | `Storages.createLocalFile` | ❌ | ✅ | ⚠️ full scan (no real index) | ❌ | Crash-atomic (no torn files; last write may be lost on power loss) — one file per entity |
| **Grouped files** | `Storages.createGroupedFile` | ❌ | ✅ | ⚠️ full scan (no real index) | ❌ | Crash-atomic (no torn files; last write may be lost on power loss) — one file per key, all collections |
| **In-memory** | `Storages.createInMemory` | ✅ *(no isolation)* | ✅ *(ephemeral ledger)* | ✅ in-memory map | ❌ | Ephemeral |

> MySQL/MariaDB and PostgreSQL store the entity in a **native `JSON` column**, and MongoDB as a **native BSON sub-document** — not an escaped string — so the data stays queryable and readable in standard DB tools. (H2 stores it as plain `TEXT`.)

> **Ask the backend, never hard-code a list.** The *Optimistic locking* column is exactly what **`storage.enforcesOptimisticLock()`** returns at runtime — it's a plain method on `Storage` (default `false`; a backend claims enforcement by overriding), so code that must route a versioned entity can query the capability instead of pattern-matching on a backend name. See [Optimistic locking](#optimistic-locking).

> 📊 **Performance:** see the **[Benchmarks](https://github.com/EverNife/EveryDatabase/wiki/Benchmarks)** wiki page for per-backend throughput (insert / query / update / delete) at 10k records, plus a pick-a-backend cheat sheet and the caveats.

---

## Install

Published to a public Maven repository in **two flavors** — same code, same API, different packaging (see [Distribution flavors](#distribution-flavors)). Pick exactly one.

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    // RECOMMENDED — everything included by default (HikariCP, Jackson, Mongo driver, H2,
    // MySQL + PostgreSQL JDBC drivers); override any version via normal dependency management:
    implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.1.1'

    // OR runtime download — your jar stays tiny, the same set is downloaded at runtime via Libby:
    //implementation 'br.com.finalcraft.everydatabase:everydatabase-libby:1.1.1'
}
```

Nothing else to add — every backend works out of the box. To **change a version**, declare your own (Gradle picks the highest by default; append `!!` to force a downgrade — in Maven your nearest declaration wins). To **drop what you don't use**, exclude it:

```groovy
dependencies {
    implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.1.1'

    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'   // upgrade Jackson
    runtimeOnly    'com.mysql:mysql-connector-j:8.4.0!!'                  // force-downgrade the MySQL driver

    // Only target SQL? Drop the Mongo driver entirely:
    // implementation('br.com.finalcraft.everydatabase:everydatabase-core:1.1.1') {
    //     exclude group: 'org.mongodb'
    // }
}
```

**Maven**

```xml
<repositories>
  <repository>
    <id>petrus-public</id>
    <url>https://maven.petrus.dev/public</url>
  </repository>
</repositories>

<dependency>
  <groupId>br.com.finalcraft.everydatabase</groupId>
  <!-- or everydatabase-libby -->
  <artifactId>everydatabase-core</artifactId>
  <version>1.1.1</version>
</dependency>
```

---

## Distribution flavors

Both flavors expose the same API and carry the **same dependency set** — HikariCP, Jackson (`databind` + `yaml`), the MongoDB driver, H2, and the MySQL + PostgreSQL JDBC drivers. They differ only in **how that set reaches your classpath**.

### `everydatabase-core` — recommended

Everything declared as a **normal POM dependency**: it works out of the box, and you keep full control through dependency management — override any version, or exclude what you don't use (see [Install](#install)). Scopes are meaningful: `jackson-databind` and `mongodb-driver-sync` are `compile` (their types appear in the public API), everything else is `runtime`.

| Included by default | Version | POM scope |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.0 | compile |
| `org.mongodb:mongodb-driver-sync` | 5.8.0 | compile |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.22.0 | runtime |
| `com.zaxxer:HikariCP` (4.x = last Java 8 line; on 11+ feel free to override to 5.x) | 4.0.3 | runtime |
| `com.h2database:h2` (1.4.200 = last Java 8 release — see note below before overriding) | 1.4.200 | runtime |
| `com.mysql:mysql-connector-j` (protobuf excluded — only the removed X DevAPI needs it) | 9.7.0 | runtime |
| `org.postgresql:postgresql` | 42.7.12 | runtime |

> **H2 version note:** H2 1.x and 2.x use **incompatible database file formats** and slightly different SQL dialects. The default stays on 1.4.200 so Java 8 hosts work out of the box; on Java 11+ you can override to H2 2.x (`implementation 'com.h2database:h2:2.3.232'`) — but don't switch versions over an existing embedded-file database.

### `everydatabase-libby` — runtime download

`everydatabase-core` plus a small coordinator (package `br.com.finalcraft.everydatabase.libby`) that downloads the canonical, **non-relocated** libraries at runtime via [Libby](https://github.com/AlessioDP/libby): your jar stays tiny, and the POM excludes `core`'s transitive set so nothing heavy enters your build-time graph. Bootstrap it in your plugin's `onLoad` (or earliest bootstrap), **before touching any storage class**:

```java
import br.com.finalcraft.everydatabase.libby.DependencyManager;
import br.com.finalcraft.everydatabase.libby.EveryDatabaseDependencies;

@Override
public void onLoad() {
    DependencyManager manager = new DependencyManager("MyPlugin", getDataFolder(), "libs");
    EveryDatabaseDependencies.loadAll(manager);   // HikariCP, Jackson, Mongo driver, H2 + MySQL/PostgreSQL drivers

    // Slimmer setups can compose granular bundles instead of loadAll:
    // EveryDatabaseDependencies.loadSql(manager);            // HikariCP + slf4j-api
    // EveryDatabaseDependencies.loadMySqlDriver(manager);    // just the MySQL driver
    // EveryDatabaseDependencies.loadMongo(manager);          // just the Mongo stack
}
```

After `loadAll(...)` returns, use `Storages` normally. Note: `everydatabase-libby` depends on `net.byteflux:libby-core`, resolved from `https://repo.alessiodp.com/releases/` — add that repository to your build alongside the ones above.

---

## Quick start

```java
import br.com.finalcraft.everydatabase.*;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;

// 1. A plain entity — no-arg constructor + getters/setters so Jackson can (de)serialise it.
public class PlayerData {
    private UUID uuid;
    private String name;
    private int score;

    public PlayerData() {}

    public PlayerData(UUID uuid, String name, int score) {
        this.uuid = uuid;
        this.name = name;
        this.score = score;
    }

    public UUID getUuid()   { return uuid; }
    public String getName() { return name; }
    public int getScore()   { return score; }
    // setters omitted for brevity
}

// 2. Describe it once.
EntityDescriptor<UUID, PlayerData> PLAYERS = EntityDescriptor.builder(UUID.class, PlayerData.class)
        .collection("players")
        .keyExtractor(PlayerData::getUuid)
        .codec(new JacksonJsonCodec<>(PlayerData.class))
        .build();

// 3. Pick a backend and go.
Storage storage = Storages.createSQL(new SqlConfig("jdbc:mariadb://localhost:3306/mydb", "root", "root"));
storage.init().join();

Repository<UUID, PlayerData> repo = storage.repository(PLAYERS);

UUID aliceId = UUID.randomUUID();
repo.save(new PlayerData(aliceId, "Alice", 100)).join();

Optional<PlayerData> alice = repo.find(aliceId).join();
long total = repo.count().join();

storage.close().join();
```

Switching to MongoDB is a one-line change — everything below `storage.repository(...)` stays identical:

```java
Storage storage = Storages.createMongo(new MongoConfig("mongodb://localhost:27017", "mydb"));
```

---

## Core concepts

| Type | Role |
|---|---|
| **`Storage`** | Owns the connection/pool lifecycle (`init` / `close` / `health`) and is a factory for repositories. |
| **`Repository<K, V>`** | Typed CRUD for one collection. Every method returns a `CompletableFuture`. |
| **`EntityDescriptor<K, V>`** | Immutable metadata: collection name, key extractor, codec, indexes, optional versioning. Built with a fluent builder. |
| **`Codec<V>`** | Serialisation strategy. `JacksonJsonCodec` (everywhere) and `JacksonYamlCodec` (file backends only — local files and grouped files). |
| **`Storages`** | Static factory — typed builders per backend, plus a generic `create(StorageConfig)`. |

**Optional capability interfaces** — a `Storage` may also implement any of:

- `TransactionalStorage` — atomic `inTransaction(...)`
- `SchemaAwareStorage` — `register(...).migrate()`
- `ChangeFeedStorage` — a backend-native push feed of changes

Discover them with `instanceof` — the compiler stops you from using transactions on a backend that doesn't support them.

> **Not every capability is an interface.** `Storage.enforcesOptimisticLock()` is a plain **default method** returning `false`, not a marker interface — because `H2SqlStorage extends SqlStorage` has to answer `false` where its parent answers `true`, and Java has no way to *un*-implement an inherited interface. The rule of thumb: a capability that adds **methods** is an interface (`instanceof`); one that only answers a **question** is a method.

> **Codec tip:** `new JacksonJsonCodec<>(Type.class)` emits **compact** JSON (smallest payload — what a database wants). Use `JacksonJsonCodec.pretty(Type.class)` for indented, human-readable output — handy with `LocalFileStorage` when you want to read the files by eye.

> **Default mapper (`JacksonConfig`):** the built-in codecs are batteries-included — the `java.time` and `Optional` modules registered, dates as **ISO-8601** text (not epochs), `Map` entries written in **insertion order** (no profile reorders them by key), and unknown properties **tolerated** on read (a field removed in newer code won't break old data). Need a custom `ObjectMapper`? Pass it to `new JacksonJsonCodec<>(Type.class, mapper)`, or apply a profile to your own: `JacksonConfig.storageSafe(mapper)` (the default) or `JacksonConfig.compact(mapper)` (same, but **drops null/absent** via `NON_ABSENT`). Every profile shares one frozen *read* contract, so any profile reads what any other wrote.

> **Collection names** must match `^[a-zA-Z][a-zA-Z0-9_]*$` — the safe intersection of identifier rules across every backend (no quoting ever needed). Names starting with `_` are reserved for EveryDatabase-internal metadata, so your collections can never collide with the framework's. That rejection *is* the guarantee: because a regular descriptor can never be named `_anything`, the framework's own metadata needs no escaping and no opt-out. Reserved today: **`_schema_migrations`** (the [migration](#schema-migrations) ledger) and **`_entity_schema_sweeps`** (the [payload-sweep](#entity-payload-schema-everydatabase-manager) marker). The framework builds those through `EntityDescriptor.builder(...).reserved()`, which swaps the rule to `^_[a-zA-Z][a-zA-Z0-9_]*$` — **application code must not call it**: a reserved collection may be read, rewritten or dropped by the framework at any time.

> **Keys** are persisted by their `toString()` (SQL primary key, Mongo unique index, LocalFile filename) and matched by `equals`/`hashCode` (in-memory backend, manager cache). A key type must have a **stable, unique `toString()` of at most 255 characters** and value-based `equals`/`hashCode` — `UUID`, `String`, `Long`, `Integer` and `record`s qualify; the identity `Object.toString()` does **not**. `save`/`saveAll` reject an oversized key up front (the future completes with `IllegalArgumentException`), so a long key can never be silently truncated into a collision. (For `Ref` keys in the manager layer, the key must also be JSON-serializable.)

---

## Instantiating each backend

<details>
<summary><b>MySQL / MariaDB</b></summary>

```java
SqlStorage sql = Storages.createSQL(
        new SqlConfig("jdbc:mariadb://localhost:3306/mydb", "root", "root"));
sql.init().join();

// Full control over the HikariCP pool (min/max, connection timeout, idle timeout;
// a 5-arg PoolTuning constructor also exposes maxLifetime):
SqlStorage tuned = Storages.createSQL(new SqlConfig(
        "jdbc:mysql://db.internal:3306/app",
        "user", "pass",
        new PoolTuning(2, 10, Duration.ofSeconds(30), Duration.ofMinutes(10))));
```
</details>

<details>
<summary><b>PostgreSQL</b></summary>

```java
PostgreSqlStorage pg = Storages.createPostgreSQL(
        new SqlConfig("jdbc:postgresql://localhost:5432/mydb", "root", "root"));
pg.init().join();
```
> The generic `Storages.create(SqlConfig)` always picks the MySQL/MariaDB dialect. Use `createPostgreSQL` / `createH2` explicitly when you need those dialects.
</details>

<details>
<summary><b>H2 (in-memory, embedded file, or server)</b></summary>

```java
// In-memory (ephemeral)
H2SqlStorage mem  = Storages.createH2(new SqlConfig("jdbc:h2:mem:test", "", ""));
// Embedded file (persists on disk)
H2SqlStorage file = Storages.createH2(new SqlConfig("jdbc:h2:file:./data/storage", "", ""));
// Server / TCP (multi-JVM)
H2SqlStorage tcp  = Storages.createH2(new SqlConfig("jdbc:h2:tcp://localhost:9092/./data/storage", "", ""));
```
</details>

<details>
<summary><b>MongoDB</b></summary>

```java
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;

MongoStorage mongo = Storages.createMongo(new MongoConfig("mongodb://localhost:27017", "mydb"));
mongo.init().join();

// With auth and an explicit connect timeout:
MongoStorage authed = Storages.createMongo(new MongoConfig(
        "mongodb://user:pass@host:27017", "mydb", Optional.of(Duration.ofSeconds(10))));
```
> Transactions require a MongoDB **replica set** (4.0+). On a standalone server, `inTransaction(...)` throws at runtime.
</details>

<details>
<summary><b>Local files (one file per entity)</b></summary>

```java
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;

LocalFileStorage file = Storages.createLocalFile(new LocalFileConfig(Paths.get("data")));
file.init().join();
```
Like grouped files, this is a **file backend** that accepts a non-JSON codec — pair it with `JacksonYamlCodec` for human-friendly `.yml` files. (LocalFile treats the payload as opaque bytes, so any codec works; grouped files embed each value into a shared aggregate document, so they take JSON or YAML but not an arbitrary binary codec.)
</details>

<details>
<summary><b>Grouped files (one file per key, all collections)</b></summary>

```java
import br.com.finalcraft.everydatabase.modules.groupedfile.GroupedFileConfig;

GroupedFileStorage grouped = Storages.createGroupedFile(new GroupedFileConfig(Paths.get("playerdata")));
grouped.init().join();
```
Grouped files invert the local-file layout: one file **per key**, holding every collection that shares that key — ideal for "everything about one player in one file". The container format (JSON or YAML) follows the descriptor's codec, and all collections under one base directory must agree on it.
</details>

<details>
<summary><b>In-memory (tests / CI)</b></summary>

```java
InMemoryStorage mem = Storages.createInMemory();
mem.init().join();
```
</details>

<details>
<summary><b>Runtime-selected backend (from config)</b></summary>

```java
StorageConfig config = loadFromYaml();          // SqlConfig / MongoConfig / LocalFileConfig / GroupedFileConfig / InMemoryConfig
Storage storage = Storages.create(config);      // dispatches on the config type (SqlConfig always picks the MySQL/MariaDB dialect)
storage.init().join();
```
</details>

---

## CRUD operations

Every method is asynchronous. `.join()` blocks for the result; otherwise compose with `thenApply` / `thenCompose`.

```java
Repository<UUID, PlayerData> repo = storage.repository(PLAYERS);

// Create / update (upsert — same key replaces)
repo.save(new PlayerData(id, "Alice", 100)).join();
repo.saveAll(Arrays.asList(alice, bob, carol)).join();    // batched (JDBC batch / Mongo bulk)

// Read
Optional<PlayerData> one = repo.find(id).join();
List<PlayerData>     some = repo.findMany(Arrays.asList(id1, id2)).join();  // missing keys omitted
Stream<PlayerData>   all  = repo.all().join();               // skips rows whose payload cannot be decoded
boolean exists            = repo.exists(id).join();
long count                = repo.count().join();             // counts every stored row, decodable or not

// Delete
boolean removed = repo.delete(id).join();                  // true if it existed

// Non-blocking composition
repo.find(id)
    .thenApply(opt -> opt.map(PlayerData::getScore).orElse(0))
    .thenAccept(score -> System.out.println("score = " + score));
```

---

## Indexing & queries (`@Indexed`)

Declare indexes and the backend materialises a real secondary index. Two equivalent styles.

**Annotation-driven** — annotate fields, and `EntityDescriptor.build()` discovers them:

```java
public class PlayerData {
    private UUID uuid;

    @Indexed
    private String name;

    @Indexed(order = IndexHint.Order.DESCENDING)
    private int score;

    @Indexed(path = "location.world", type = String.class)   // nested dot-path
    private Location location;

    private List<Badge> badges;   // not indexed — stored as-is
}
```

**Manual** — declare `IndexHint`s on the builder (useful when you can't annotate the class):

```java
EntityDescriptor<UUID, PlayerData> PLAYERS = EntityDescriptor.builder(UUID.class, PlayerData.class)
        .collection("players")
        .keyExtractor(PlayerData::getUuid)
        .codec(new JacksonJsonCodec<>(PlayerData.class))
        .index(IndexHint.string("name"))
        .index(IndexHint.integer("score"))
        .index(IndexHint.timestamp("createdAt"))
        .build();
```

Then query — conditions are intersected with `AND`:

```java
// Shorthand equality
repo.findBy("name", "Alice").join();

// Composable query
repo.query(Query.eq("location.world", "world_nether")).join();
repo.query(Query.range("score", 100, 500)).join();          // inclusive; null = open end
repo.query(Query.in("name", "Alice", "Bob")).join();
repo.query(Query.range("createdAt",
        Instant.now().minus(7, ChronoUnit.DAYS), Instant.now())).join();

// AND of multiple conditions
repo.query(Query.eq("location.world", "world")
        .and(Query.range("score", 1000, null))).join();       // world == "world" AND score >= 1000
```

**Index type factories:** `IndexHint.string` · `integer` · `bigInt` · `decimal` · `bool` · `timestamp`.

> Querying an undeclared field throws `IllegalArgumentException` on **every** backend — including local files, which validate the declaration even though they answer with a full scan (`O(n)`, no real index). Indexes added or removed later are reconciled automatically (column/index created, backfilled, or dropped) the next time the repository is opened.

### Ordering & pagination (`QueryOptions`)

Pass a `QueryOptions` to `query(Query, QueryOptions)` to **order** and **page** at the storage layer, instead of loading the whole collection and sorting in memory. Use `Query.all()` to match every entity (a leaderboard or plain page):

```java
// Top 10 by score (highest first)
List<PlayerData> top10 = repo.query(
        Query.all(),
        QueryOptions.builder().descending("score").limit(10).build()).join();

// Page 2 of 20, ascending — filter first, then order + page
List<PlayerData> page2 = repo.query(
        Query.eq("world", "world_nether"),
        QueryOptions.builder().ascending("score").offset(20).limit(20).build()).join();
```

The ordering is **identical on every backend**, so a query behaves the same when you swap storage:

- **`orderBy` must be a declared index field** (same rule as query conditions) — an undeclared field throws `IllegalArgumentException`.
- **`NULL`/missing values sort as the smallest value** — first ascending, last descending.
- **Ties break by the entity key** (ascending), so paged results are stable — pages never overlap or drop a row because two entities shared a score. `limit`/`offset` with no `orderBy` is ordered by key for the same reason.
- `limit(0)` means **unbounded**; a negative `limit`/`offset` is rejected up front (`IllegalArgumentException`).

> H2 opts out of optimistic locking but still orders and pages like the others. SQL backends order on the materialized `_idx_<field>` column (a real B-tree); LocalFile/GroupedFile order during their full scan.

#### Page responses — `querySlice` (cheap) and `queryPage` (with total)

`query(...)` returns a plain `List`. For navigation metadata, two richer results take the same `QueryOptions` (use `.page(n, size)` for 0-based paging):

```java
// Slice — content + hasNext, fetched by reading one extra row. No COUNT runs.
Slice<PlayerData> slice = repo.querySlice(
        Query.all(), QueryOptions.builder().descending("score").page(0, 10).build()).join();
slice.hasNext();                       // is there a next page?
slice.nextPageRequest().ifPresent(next -> repo.querySlice(Query.all(), next));   // advance

// Page — adds totalElements / totalPages via an extra count(Query).
Page<PlayerData> page = repo.queryPage(
        Query.all(), QueryOptions.builder().descending("score").page(2, 10).build()).join();
page.totalElements();   // e.g. 237
page.totalPages();      // e.g. 24
page.number();          // 2 (0-based)
page.hasNext(); page.hasPrevious(); page.isLast();
Page<String> lines = page.map(p -> p.getName() + " — " + p.getScore());   // metadata preserved

// count alone (filtered), without fetching rows
long inNether = repo.count(Query.eq("world", "world_nether")).join();
```

> Prefer `querySlice`/`query` when you don't need the total — only `queryPage` pays for the `count`. `Page` is a `Slice` with `totalElements`/`totalPages`; the count and the page are read separately, so under concurrent writes the total may differ from the content by one (same as Spring Data).

#### Keyset (cursor) pagination — `queryAfter`

For deep paging or "load more"/infinite scroll, `queryAfter` seeks by the **position** of the last row instead of an offset, so it stays fast however far in you are (offset scans and discards the skipped rows). It returns a forward-only `Slice` with **no total**, stable under concurrent inserts/deletes. The cursor carries the order, so you set it only once:

```java
// First page (ordered by score, descending)
Slice<PlayerData> p1 = repo.queryAfter(
        Query.all(), Cursor.start("score", IndexHint.Order.DESCENDING), 10).join();

// Next page — feed back the cursor
if (p1.hasNext()) {
    Slice<PlayerData> p2 = repo.queryAfter(Query.all(), p1.nextCursor().get(), 10).join();
}

// Stateless transport (command argument, GUI button payload)
String token = p1.nextCursor().get().encode();
Slice<PlayerData> resumed = repo.queryAfter(Query.all(), Cursor.decode(token), 10).join();
```

> Keyset is forward-only and has no "jump to page N" or total — use `queryPage` for "page X of N" admin screens, `queryAfter` for feeds and large/deep lists. The order field must be a declared index; the same NULL=least, key-tie-break order applies, so cursor paging is consistent across every backend.

#### Full-collection maintenance sweep — `scanAll` + `WriteMode.UPDATE_ONLY`

For a pass over an **entire** collection — re-encoding every row through a codec transform, backfilling a field, auditing for corruption — `scanAll` pages by the **storage key** (no declared index needed) and, unlike `all()`/`query()`, **never silently drops a row that fails to decode**: each page element is a `ScanRow` that is either the decoded entity or the decode failure (`key()` + `error()`), so a poisoned row is counted and named instead of shrinking the scan.

```java
Cursor cursor = Cursor.scan();
List<PlayerData> repaired = new ArrayList<>();
while (true) {
    Slice<ScanRow<PlayerData>> page = repo.scanAll(cursor, 500).join();
    for (ScanRow<PlayerData> row : page.content()) {
        if (row.isFailed()) { log.warn("undecodable row {}: {}", row.key(), row.error()); continue; }
        repaired.add(transform(row.value()));
    }
    if (!page.hasNext()) break;
    cursor = page.nextCursor().get();
}

// Write the sweep back WITHOUT resurrecting rows deleted meanwhile:
repo.saveAll(repaired, WriteMode.UPDATE_ONLY).join();
```

`WriteMode.UPDATE_ONLY` updates only rows that still exist — an absent key is a silent no-op, never an insert — so a concurrent delete during the sweep is not undone; on a versioned descriptor it keeps the optimistic-lock guard (a version mismatch still throws `OptimisticLockException`). `WriteMode.UPSERT` is exactly `saveAll(entities)`.

> SQL/Mongo/InMemory page by key; the single-instance file backends return the whole collection in one page (`limit` is advisory there). `ScanRow.key()` is the real storage key on a successful row.

---

## Optimistic locking

Opt in per descriptor to guard against concurrent writers (e.g. two app instances editing the same entity). On a version mismatch the save fails with `OptimisticLockException` (surfacing as the cause of a `CompletionException` when you `.join()`).

**The easy way — annotate a `long`/`Long` field with `@OptimisticLock`.** `build()` finds it and wires the getter/setter via reflection. No interface, no builder call:

```java
import br.com.finalcraft.everydatabase.versioned.OptimisticLock;

public class Account {
    private UUID id;
    private long balance;

    @OptimisticLock
    private Long lockVersion;          // managed by the backend — never touch it manually
    // ...
}

EntityDescriptor<UUID, Account> ACCOUNTS = EntityDescriptor.builder(UUID.class, Account.class)
        .collection("accounts")
        .keyExtractor(Account::getId)
        .codec(new JacksonJsonCodec<>(Account.class))
        .build();                      // @OptimisticLock detected automatically
```

The field may be `long` or `Long` (a still-`null` `Long` reads as version `0`), and must not be `static` or `final`. Rules are **validated at `build()`** so mistakes fail fast: a wrong type throws `IllegalArgumentException`, two annotated fields throw `IllegalStateException`, and combining the annotation with the manual wiring below also throws — pick one mechanism.

<details>
<summary><b>Alternative: manual wiring (when you can't annotate the class)</b></summary>

```java
public class Account implements Versioned {
    private UUID id;
    private long balance;
    private long lockVersion;
    public long getLockVersion()            { return lockVersion; }
    public void setLockVersion(long v)      { this.lockVersion = v; }
    // ...
}

EntityDescriptor<UUID, Account> ACCOUNTS = EntityDescriptor.builder(UUID.class, Account.class)
        .collection("accounts")
        .keyExtractor(Account::getId)
        .codec(new JacksonJsonCodec<>(Account.class))
        .versioned()                   // wires getLockVersion / setLockVersion
        .build();

// ...or fully explicit, for any pair of accessors:
//      .version(Account::getLockVersion, Account::setLockVersion)
```
</details>

The version starts at `0` on insert and increments on every successful update. Descriptors **without** versioning keep plain upsert semantics — locking is entirely opt-in.

> **Backend support:** MySQL/MariaDB, PostgreSQL and MongoDB enforce the version check. **H2 does not** (by design — it's an embedded/dev engine): a versioned descriptor there silently degrades to plain upsert, never throwing `OptimisticLockException`. Local files and in-memory don't enforce it either. Use a server-grade backend when concurrent writers matter.

### Is my backend actually enforcing it? — `enforcesOptimisticLock()`

The degradation above is *silent*, and that's the danger: a versioned entity pointed at H2 or a file backend behaves perfectly in a single-process test and quietly drops one side of every concurrent write in production. Ask the backend directly rather than guessing from its class or config name:

```java
if (!storage.enforcesOptimisticLock()) {
    // last-write-wins here — fine for one writer, data loss for several
}
```

It's a capability question, not a per-descriptor one: it reports what the backend *would* do for **any** versioned descriptor.

**Let it fail at boot.** If you have the manager add-on, `SyncBindGuard` turns that check into a fail-fast at bind time — the only combination it rejects is the one that is *certainly* wrong (a versioned descriptor + a non-enforcing backend + writes intended from several instances):

```java
import br.com.finalcraft.everydatabase.manager.sync.SyncBindGuard;

// Once per entity, at bind time. Throws IllegalStateException on the fatal combination;
// a no-op otherwise — a single-writer deployment on H2 is still perfectly legal.
SyncBindGuard.check("accounts", ACCOUNTS, storage, /* multiInstanceIntent = */ true);
```

Declared multi-instance intent is what makes it decidable: without it, a versioned entity on H2 is merely unusual; with it, it is a misconfiguration that corrupts data hours later. The guard reads the capability off the `Storage` object, so it decides at bind time without opening a connection.

---

## Transactions

Backends implementing `TransactionalStorage` run a unit of work atomically: every SQL dialect (including H2), MongoDB (replica set required) and in-memory (atomic, no isolation) — local files don't. Repositories obtained from the scope share the transaction; it commits on success, rolls back on exception or an explicit `scope.rollback()`.

```java
if (storage instanceof TransactionalStorage) {
    TransactionalStorage tx = (TransactionalStorage) storage;

    tx.inTransaction(scope -> {
        Repository<UUID, Account> accounts = scope.repository(ACCOUNTS);
        return accounts.find(fromId).thenCompose(fromOpt -> {
            Account from = fromOpt.orElseThrow(IllegalStateException::new);
            from.setBalance(from.getBalance() - 100);
            return accounts.save(from);
        });
        // throw, or call scope.rollback(), to abort
    }).join();
}
```

---

## Schema migrations

Backends implementing `SchemaAwareStorage` — SQL (all dialects), MongoDB, local files, grouped files and in-memory — track applied migrations (a `_schema_migrations` table/collection/file) and apply pending ones in version order, exactly once. Migrations are **forward-only**. (In-memory's ledger is ephemeral: it dies with the instance and re-applies on the next startup — correct, since its data resets too; the point there is data-seeding/transform migrations, not DDL.)

```java
public final class V001_CreateAuditLog extends SqlMigration {
    public String version()     { return "001"; }
    public String description() { return "create audit_log table"; }
    public String upScript() {
        return "CREATE TABLE IF NOT EXISTS audit_log ("
             + "  id BIGINT PRIMARY KEY, msg VARCHAR(255))";
    }
}

SqlStorage sql = Storages.createSQL(config);
sql.init().join();
sql.register(new V001_CreateAuditLog()).migrate().join();
```

Each backend ships a convenience base class: `SqlMigration` (return `upScript()`), `MongoMigration` (override `executeOnDatabase(MongoDatabase)`), `LocalFileMigration` / `GroupedFileMigration` / `InMemoryMigration` (override `executeOnStorage(...)`). For full control, implement `Migration.execute(MigrationContext)` and pull the native client via `context.getNativeClient(...)`.

> Auto-create and migrations are complementary: entity tables/collections are created on first `repository(...)`; migrations cover everything else (backfills, auxiliary tables, your own indexes). Write SQL migrations to be **idempotent** — DDL implicitly commits on MySQL/MariaDB.

---

## Entity payload schema (`everydatabase-manager`)

Your entity **class** changes shape across releases — a field splits in two, a unit changes, a typo in a name is fixed — while rows written by last month's build sit unchanged on disk. That's a third, independent versioning axis. Keeping the three apart is deliberate; the verbose `EntitySchema*` prefix is what disambiguates them:

| Axis | Scope | Where the version lives | Entry point |
|---|---|---|---|
| **Schema migration** (DDL) | the whole storage | `_schema_migrations` ledger | `SchemaAwareStorage.register(...).migrate()` |
| **Optimistic lock** | one row, one write | `lock_version` column/field | [`@OptimisticLock`](#optimistic-locking) |
| **Entity payload schema** | one row, across releases | `schemaVersion` **inside** the entity | `EntitySchemaMigrations.register(...)` |

Stamp the entity, register the steps, and wrap the codec — old rows then upgrade **as they're read**:

```java
public class Account implements EntitySchema {
    private UUID id;
    private String currency;
    private int schemaVersion = EntitySchema.INITIAL_SCHEMA_VERSION;  // MUST be initialized
    @DirtyFlag private boolean dirty;      // lets a migrated row be re-persisted (see below)

    public int getSchemaVersion()       { return schemaVersion; }
    public void setSchemaVersion(int v) { this.schemaVersion = v; }
}

// A step is a PURE JSON-tree transform, v1 -> v2. No I/O, no shared state.
// The framework owns "schemaVersion" and re-stamps it for you after the step returns.
EntitySchemaMigrations.register(Account.class, 1, node -> node.put("currency", "USD"));

// ALWAYS wrap an EntitySchema type — even before the first step exists.
EntityDescriptor<UUID, Account> ACCOUNTS = EntityDescriptor.builder(UUID.class, Account.class)
        .collection("accounts")
        .keyExtractor(Account::getId)
        .codec(EntitySchemaMigratingCodec.wrap(
                Account.class, new JacksonJsonCodec<>(Account.class), "id"))  // "id" = protected
        .build();
```

Steps are declared **contiguously** from version 1 up (registering out of order throws), and each is `LAZY` by default — it runs on read, costing nothing until a stale row is touched. Register one as `EntitySchemaMigrationMode.EAGER` when you can't wait for organic reads, then have a `CachingManager` sweep the collection once at boot:

```java
SweepReport report = EntitySchemaSweeper.sweep(accounts, SweepOptions.defaults());
```

The sweep pages the collection, re-persists what it upgrades, and records how far it got in the reserved `_entity_schema_sweeps` marker so later boots skip the re-scan in O(1). It's a plain blocking utility: **you** own the thread, the schedule and the kill-switch (`SweepOptions.builder().abortCheck(...)`, polled at every batch boundary). Because it detects an upgraded row through its dirty flag, a type with **no dirty tracking** (neither `IDirtyable` nor `@DirtyFlag`) is rejected with `IllegalArgumentException` rather than silently sweeping nothing and marking the collection done.

> **The marker is a hint, never authority.** The lazy decode-time migration never consults it — the per-row `schemaVersion` plus the chain stay the single source of truth. The marker only buys a completed sweep the O(1) skip on the next boot, so losing or deleting it costs a re-scan, never correctness.

> **Wrap first, register later — both work.** An `EntitySchema` type is decorated even when no chain exists yet, precisely so that a chain registered *after* the descriptor was built still runs. The wrapped codec must expose its `ObjectMapper` (`ObjectMapperAware` — `JacksonJsonCodec` does); raw-tree migration needs one, so a codec that doesn't is rejected at `wrap(...)`. A stored version below `1`, or a step that throws, fails **that row's** decode with `EntitySchemaMigrationException` and leaves it untouched on disk to retry — it never writes a half-migrated row.

---

## Moving data between backends

`StorageTransfer` copies entities from one backend to another. The **source is never modified** — it only reads. Ideal for a maintenance-window cutover (file storage → MariaDB).

```java
TransferReport report = StorageTransfer.builder()
        .from(oldLocalFileStorage)
        .to(newSqlStorage)
        .descriptor(PLAYERS)
        .descriptor(ACCOUNTS)
        .applyTargetMigrations(true)             // run target migrations first
        .failIfTargetCollectionNotEmpty(true)    // refuse to overwrite
        .verifyCounts(true)                      // assert written == source count
        .errorPolicy(ErrorPolicy.FAIL_FAST)
        .progressListener(p -> System.out.printf("%s: %d/%d%n", p.collection(), p.done(), p.total()))
        .build()
        .execute()
        .join();

if (report.success()) {
    System.out.printf("Done: %d entities in %dms%n", report.totalEntities(), report.durationMs());
} else {
    report.errors().forEach(e -> System.err.printf("[%s] %s%n", e.collection(), e.cause().getMessage()));
}
```

Use `descriptor(sourceDesc, targetDesc)` to rename a collection or change codec mid-transfer (e.g. YAML on disk → JSON in SQL). The future never completes exceptionally for expected failures — they're collected in `report.errors()`.

---

## Logging & diagnostics

The library is **silent by default**: routine operations emit nothing, failures always do (an `ERROR` floor no configuration can switch off). Everything in between is opt-in, per **topic** (`INDEX`, `WRITE`, `DELETE`, `QUERY`, `MIGRATION`, `TRANSACTION`, `TRANSFER`, ...), editable live at runtime.

```java
// Create a storage that already watches index work and migrations, with writes muted.
// Every backend has a (config, logConfig) constructor for this:
StorageLogConfig logCfg = StorageLogConfig.defaults()        // WARN: routine silent, failures visible
        .level(StorageLogTopic.INDEX, StorageLogLevel.INFO)
        .level(StorageLogTopic.MIGRATION, StorageLogLevel.INFO)
        .mute(StorageLogTopic.WRITE);
SqlStorage sql = new SqlStorage(sqlConfig, logCfg);

// The config is LIVE — edit it at runtime and every repository reacts immediately:
sql.getStorageLogConfig()
   .level(StorageLogTopic.WRITE, StorageLogLevel.DEBUG)      // temporarily debug saves
   .includeKeys(true);                                       // opt-in: show entity keys
```

Other presets: `StorageLogConfig.silent()` (only the ERROR floor), `verbose()` (DEBUG), `trace()`.

**Where the lines go.** By default events route to **SLF4J** when it's on the runtime classpath (loggers named `everydatabase.<topic>`), and no-op silently otherwise — the library never requires a logging framework. A host application can install its own bridge once, globally:

```java
// e.g. a Bukkit plugin routing storage logs to its own logger:
StorageLogSinks.installDefault(event -> plugin.getLogger().info(event.format()));
```

**Privacy by default.** Log events carry counts, durations, collection names and index/migration metadata — never entity keys or content. `includeKeys(true)`, `includeValues(true)` (truncated `toString()`, single-entity saves only) and `includeQueryValues(true)` are explicit opt-ins for local debugging. (An *exception message* handed to the caller may name the offending key — but the caller supplied it.)

**Quick verbosity for tests/CI** — no code changes needed:

```bash
-Deverydatabase.log.level=info     # lifecycle, index, migration, batch summaries
-Deverydatabase.log.level=debug    # + saves, deletes, queries, progress ticks
```

---

## Caching & references (`everydatabase-manager`)

An **optional add-on module** that sits *in front of* the core: hold a **typed reference** to an entity in another collection (even another **database**), cache the hot ones with a policy you control, and resolve them lazily. It's a façade — the core stays untouched. A reference *is not* a cache: `Ref` is the pointer, the `CachingManager` is the cache.

```groovy
// the manager add-on does NOT pull core in transitively — declare both explicitly:
implementation 'br.com.finalcraft.everydatabase:everydatabase-manager:1.1.1'
implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.1.1'
```

```java
// An entity references another by a typed Ref — stored as just the key on disk ("guild":"<uuid>").
@Data @NoArgsConstructor @AllArgsConstructor
public class Player {
    private UUID uuid;
    private Ref<UUID, Guild> guild;
}

// A RefRegistry owns your refs; it vends the ref-aware codec and the manager (each backed by any backend).
RefRegistry refRegistry = new RefRegistry();
CachingManager<UUID, Guild> guilds = refRegistry.manager(GUILDS, storage, CachePolicy.always());

Player p = playerRepo.find(id).join().orElseThrow();   // playerRepo's codec = refRegistry.codec(Player.class)
Optional<Guild> g = p.getGuild().peek();          // synchronous, cache-only (the hot path)
p.getGuild().resolve().thenAccept(opt -> ...);    // async: cache hit, or load-and-cache
```

- **Typed refs that serialize as the key** — no embedded objects, no ORM; the target type is recovered from the field on read.
- **Caching with a policy you own** — `always()` / `ttl(...)` / `noCache()`, a per-field `@RefPolicy` override, and an LRU `maxSize`. `peek()` is a lock-free cache-only read; `resolve()` loads on a miss; `getAll(...)` batches (the N+1 antidote); `saveAndCache` / `deleteAndEvict` keep cache and backend consistent.
- **Write-back when you want it** — opt-in dirty-trackable entities (implement `IDirtyable` or annotate a `boolean` field with `@DirtyFlag`) mutate in memory and flush in a batch (`flushDirty()`); a dirty value is never reloaded over, and `seedIfAbsent(...)` caches a not-yet-persisted default.
- **Cross-backend by design** — a reference resolves through its type's manager, so a single root entity can fan out across MySQL, PostgreSQL, Mongo, H2, files and memory **at once**, each reference under its own key type.
- **Per-context registries, no global state** — each `RefRegistry` is its own isolated context; two can register a manager for the **same** type backed by different storages without colliding, so independent plugins never interfere. Registries can chain to a **parent** for a private-then-shared lookup (a plugin's own registry falling back to a shared one).

**→ Full guide: [Caching & References](https://github.com/EverNife/EveryDatabase/wiki/Caching-and-References) on the wiki** (and [Typed References](https://github.com/EverNife/EveryDatabase/wiki/Typed-References), [Caching Managers](https://github.com/EverNife/EveryDatabase/wiki/Caching-Managers), [Cache Policies & Freshness](https://github.com/EverNife/EveryDatabase/wiki/Cache-Policies-and-Freshness), [Cross-Process Cache Sync](https://github.com/EverNife/EveryDatabase/wiki/Cross-Process-Cache-Sync), [One Entity, Many Databases](https://github.com/EverNife/EveryDatabase/wiki/One-Entity-Many-Databases)).**

### Write-back conflict resolution (`manager.writeback`)

`flushDirty()` writes a whole batch of dirty entities at once. Behind an [enforcing backend](#is-my-backend-actually-enforcing-it--enforcesoptimisticlock), any entity in that batch can lose a version race — another instance moved the row on while you were editing your copy. `WriteBackFlusher` resolves each loser individually instead of failing the batch, and `ConflictHooks<K, V extends IDirtyable>` is the seam where *you* decide what winning means for your type:

```java
WriteBackFlusher flusher = new WriteBackFlusher(managerLog);   // null log = silent

flusher.persistBatch(accounts, dirtyBatch, FlushMode.FORCED, "accounts", HOOKS, null).join();
```

On a conflict the flusher re-reads the stored row, takes your entity's lock via `hooks.lock(live)`, and picks a branch — **in this order, first match wins**:

| Situation | What happens | Hook called |
|---|---|---|
| The re-read itself failed | local state kept, retried on the next flush | *(none — just re-marked dirty)* |
| The stored row **vanished** meanwhile | the lock is reset so the next flush re-creates the row | `resetLockForRecreate` |
| Your hooks **merge** (`mergesOnConflict()` → `true`) | the hook owns the whole resolution: combine both sides, re-mark dirty, adopt the lock version | `adoptStoredState` |
| The entity was **re-dirtied** while resolving | **your values are kept** — only the winner's lock version is adopted, so the next flush wins cleanly | `adoptStoredLockVersion` |
| Otherwise (still clean) | **ADOPT_WINNER** — the stored winner's state is copied into your live instance | `adoptStoredState`, then `afterAdopt` |

The live instance is always *updated in place*, never replaced: references your code already holds stay valid, which is what makes adoption safe. `PersistedState.copyInto(live, stored)` is the building block for `adoptStoredState` — a reflective field-by-field copy that skips `static`, `transient` and `@JsonIgnore` fields.

**The two modes differ only in reporting**, never in resolution: `FlushMode.BACKGROUND` logs and completes the future normally (a periodic autosave shouldn't explode), while `FlushMode.FORCED` completes it exceptionally — `StorageWriteException` for a real write failure, `OptimisticConflictException` for a pure race. A write failure is the *primary* exception with the race attached via `addSuppressed`, since a backend that won't write is the bigger news.

> **Hooks are typed `ConflictHooks<K, ? super V>`** at the call site on purpose: one hooks singleton written against a base type can serve every subtype in a heterogeneous batch.

> **Refuse writes from the future.** If you also use the [payload-schema axis](#entity-payload-schema-everydatabase-manager), call `flusher.refuseAheadWrite(entity, what, key)` before flushing and **skip** the entity when it returns `true`. A row written by a *newer* build already lost its unknown fields on decode (Jackson drops them), so persisting it would erase them for good while keeping the newer version stamp. The entity stays dirty and cached; this process is simply read-only for that row.

### Freezing writes

`tryFreezeWrites()` suspends persistence for one manager while its cache stays live and writable — the window you need to copy a collection elsewhere (see [`StorageTransfer`](#moving-data-between-backends)) without losing writes that land mid-copy:

```java
try (CachingManager.FreezeHandle freeze = accounts.tryFreezeWrites()
        .orElseThrow(() -> new IllegalStateException("a freeze is already held"))) {

    StorageTransfer.builder().from(oldStorage).to(newStorage)
            .descriptor(ACCOUNTS).build().execute().join();
}   // released here — deferred writes drain on the first flushDirty() afterwards
```

| While frozen | Behavior |
|---|---|
| `saveAndCache` / `saveAllAndCache` | **deferred** — the cache is updated **in place** and the value marked dirty |
| `flushDirty()` | **no-op that clears no flag** — the entire dirty set survives the window |
| `deleteAndEvict` | **fails** (`IllegalStateException`) — a delete has no dirty state to be retained in |

A write is only deferrable if something will eventually drain it, so the deferral is conditional: the type must be dirty-trackable **and** the manager must actually cache. On a `noCache()` manager or a type with no dirty tracking the frozen save **fails** instead — reporting a durable success that will never happen is the worse outcome. `tryFreezeWrites()` returns `Optional.empty()` when a freeze is already held (an atomic take — prefer it to checking `isFrozen()` first, which races); `freezeWrites()` is the same thing but throws. Closing a handle is idempotent.

> ⚠️ **Never leak the handle** — always `try`-with-resources. A leaked freeze is silent and unrecoverable: every later flush of that manager dies quietly and the dirty set grows in memory until the process restarts. Likewise, code that collects dirty entities and persists them *itself* must check `isFrozen()` **before** clearing their dirty flags — once cleared, the write is already gone and no later check can bring it back.

### Cross-process cache sync over pub/sub (`everydatabase-manager-jedis`)

When several instances share one backend, a write on one leaves the others' caches stale. The single `CacheSync` facade keeps them fresh — via a backend-native change feed where it exists (Mongo change streams, Postgres `LISTEN/NOTIFY`), or version **polling** anywhere. For backends with **no native feed** (MySQL/MariaDB, …) an **optional pub/sub transport** replaces polling with real push: lower latency, no per-key version-check load on the database.

```groovy
// optional add-on; pulls in Jedis. Declare it alongside manager + core:
implementation 'br.com.finalcraft.everydatabase:everydatabase-manager-jedis:1.1.1'
implementation 'br.com.finalcraft.everydatabase:everydatabase-manager:1.1.1'
implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.1.1'
```

```java
// One Jedis client speaks to both Redis AND Valkey (identical RESP wire protocol).
CacheSyncTransport transport = JedisCacheSyncTransport.connect(
        new JedisCacheSyncConfig("localhost", 6379));

CacheSync sync = CacheSync.attach(storage)
        .via(transport)        // push over pub/sub instead of polling — works on ANY backend
        .bind(guilds)
        .bind(players)
        .start();
// on shutdown: sync.close(); transport.close();   // the transport's lifecycle is yours
```

Each manager publishes a tiny signal (collection + key + op — **never** entity content) on every local write; the other instances invalidate/evict the matching key. The local in-memory cache stays the source of live objects — the transport only signals *"reload"*, so it never breaks the identity map. Delivery is fire-and-forget (at-least-once, unordered, lossy), so pair it with a `ttl(...)` policy as a self-healing safety net. Only manager-mediated writes (`saveAndCache` / `deleteAndEvict` / `saveAllAndCache`) propagate — a raw `repository().save()` does not. The transport is a generic SPI (`CacheSyncTransport`); Jedis (Redis/Valkey) is the first implementation.

Routing is scoped by **physical store**, not just collection name: every `Storage` has a stable `backendIdentity()`, so an event only invalidates caches that actually share the same store, and the Jedis channel is per-identity (`<channelBase>:<backendId>`). A `sharedIdentity(String)` override on every `*Config`, plus a `SyncParticipation` tri-state (`RECOMMENDED`/`ALWAYS`/`NEVER`), control this. **One gotcha when simulating "two instances" over the same local backend in dev/test:** under the default `RECOMMENDED`, a machine-local backend (H2 `mem:`, local files, SQL/Mongo on `localhost`) does **not** publish on the transport — declare the **same** `sharedIdentity` on both processes (or `syncParticipation=ALWAYS`, which then requires a `sharedIdentity`) so they count as the same store. Full details on the **[Cross-Process Cache Sync](https://github.com/EverNife/EveryDatabase/wiki/Cross-Process-Cache-Sync)** wiki page.

---

## Building & running the tests

### Prerequisites

- **JDK 25** — the only JDK you need. The wrapper is Gradle 9.5.1, which launches on JDK 25 directly, and all test code compiles and runs on the Java 25 toolchain.
  - The published artifacts still target **Java 8**: production sources compile on that same toolchain to Java 8 bytecode via the FinalCraft [Jabel](https://github.com/bsideup/jabel) fork (modern *syntax* → Java 8 *bytecode*, with `--release 8` keeping the API floor honest). No separate JDK 17 compiler needed.
- **Docker** (optional) — only for the SQL/Mongo integration suites against real servers; without it, run with `-PnoDocker`.

### Clone & build

```bash
git clone <repo-url> EveryDatabase
cd EveryDatabase

# Launch Gradle with JDK 25 — one JDK for everything
export JAVA_HOME=/path/to/jdk-25      # PowerShell: $env:JAVA_HOME = "C:\path\to\jdk-25"

./gradlew :core:build       # compile + run all tests
```

### Integration databases via Docker

The integration suites need real database servers. `docker-compose.yml` starts all of them on **non-default high ports** that match the test defaults — no configuration needed.

| Service | Host port | Credentials |
|---|---|---|
| MariaDB (MySQL-compatible) | `39306` | `root` / `root` |
| PostgreSQL | `39307` | `root` / `root` |
| MongoDB | `39308` | none (1-node replica set) |
| Valkey | `39309` | none (open) |
| Redis | `39310` | none (open) |

The last two back the `everydatabase-manager-jedis` cache-sync tests, which run the **same** contract against both servers and self-skip when a server is down.

```bash
docker compose up -d            # start all of them
docker compose up -d mariadb    # or just one
docker compose ps               # check health
docker compose down             # stop (keeps data)
docker compose down -v          # stop + wipe volumes
```

Running `./gradlew :core:test` brings the containers up automatically (the docker-compose plugin is wired to the `test` task). No Docker? Add `-PnoDocker` to skip the compose wiring entirely — the SQL/Mongo suites **self-skip when their server is unreachable**, and the embedded suites (H2, local files, in-memory) still run.

### Running specific tests

```bash
./gradlew :core:test                                   # everything
./gradlew :core:test -PskipStress                      # skip the 10k-record stress suites
./gradlew :core:test -PnoDocker                        # no Docker at all (SQL/Mongo suites self-skip)
./gradlew :core:test --tests "*MariaDbStorageTest"     # one class
./gradlew :core:test --tests "*MariaDbStorageTest.inTransaction_commit_savesAreVisible"
```

Override connection coordinates with env vars or `-Dkey=value` (e.g. `MARIADB_HOST`, `MONGO_USER`, `POSTGRES_URL`). Each SQL/Mongo test method runs against its own throwaway database (`enc_NNN_<backend>_<method>`), dropped afterwards — set `TEST_KEEP_DATABASES=true` to keep them for inspection.

> 📊 The `@Tag("stress")` suites double as a **benchmark**: each prints a per-backend throughput report (insert/query/update/delete + phase breakdown). Curated numbers, recommendations and caveats live on the **[Benchmarks](https://github.com/EverNife/EveryDatabase/wiki/Benchmarks)** wiki page.

---

## Project layout

```
EveryDatabase/
├── core/                              # the library core (everydatabase-core) — RECOMMENDED flavor, full POM deps
│   ├── src/main/java/br/com/finalcraft/everydatabase/
│   │   ├── (root)                       # Storage, Repository, EntityDescriptor, Storages, StorageExecutors
│   │   ├── codec/                       # JacksonJsonCodec (compact / pretty), JacksonYamlCodec
│   │   ├── versioned/                   # @OptimisticLock, Versioned, OptimisticLockException
│   │   ├── query/                       # IndexHint, @Indexed, Query
│   │   ├── tx/                          # TransactionalStorage, TransactionScope
│   │   ├── schema/                      # SchemaAwareStorage, Migration, MigrationContext
│   │   ├── log/                         # StorageLogConfig, topics/levels/sinks (see Logging & diagnostics)
│   │   ├── transfer/                    # StorageTransfer, TransferReport, ErrorPolicy
│   │   └── modules/                     # sql (+ postgresql, h2), mongo, localfile, groupedfile, memory
│   └── src/test/java/                   # backend-agnostic contract suites + per-backend + stress tests
├── libby/                               # runtime-download flavor (everydatabase-libby) — DependencyManager, EveryDatabaseDependencies
├── manager/                             # OPTIONAL add-on (everydatabase-manager) — typed refs + caching (see the wiki: Caching & References)
├── manager-jedis/                       # OPTIONAL add-on (everydatabase-manager-jedis) — Redis/Valkey pub/sub cache-sync transport
└── docker-compose.yml                   # MariaDB / PostgreSQL / MongoDB / Valkey / Redis for the integration suites
```

---

## Compatibility notes

### Java version requirements

**Everything runs on Java 8** — the library is compiled with `--release 8`, and the default dependency versions are the **last Java-8-compatible lines** of each library:

(Exact default versions are in the [Install](#install) `everydatabase-core` table — the single, catalog-stamped source; not repeated here to avoid drift.)

| Component | Minimum Java |
|---|:---:|
| EveryDatabase classes themselves | **8** (compiled with `--release 8`) |
| Jackson codecs (JSON/YAML) | 8 |
| MongoDB backend (`mongodb-driver-sync`) | 8 |
| SQL pooling (`HikariCP` — pinned to the last Java 8 line) | 8 |
| H2 backend (`com.h2database:h2` — pinned to the last Java 8 release) | 8 |
| MySQL / PostgreSQL JDBC drivers | 8 |
| Local files / In-memory backends (no external deps) | 8 |

Running on **Java 11+** and want the newer majors? With the `core` flavor just override them — the library's code paths work with both:

```groovy
implementation 'com.zaxxer:HikariCP:5.1.0'     // Java 11+ (5.x line)
implementation 'com.h2database:h2:2.3.232'     // Java 11+ (2.x line) — read the warning below!
```

> ⚠️ **H2 1.x ↔ 2.x are not interchangeable on disk:** the **file formats are incompatible** and the SQL dialects differ slightly. Pick one before production and never swap the major version over an existing embedded-file database (export/import instead). In-memory H2 (`jdbc:h2:mem:`) has no such concern.

### Other notes

- **Build:** authored in modern Java syntax, compiled to Java 8 bytecode via the FinalCraft [Jabel](https://github.com/bsideup/jabel) fork on the single **JDK 25** toolchain (Gradle 9.5 launches on JDK 25 directly).
- **Concurrency:** `StorageExecutors` uses virtual threads on Java 21+, falling back to a bounded daemon thread pool on older JVMs.
- **Dependencies & drivers:** both flavors ship the full backend set by default — HikariCP, Jackson, Mongo driver, H2, and the MySQL + PostgreSQL JDBC drivers. `core` lets you override versions via dependency management; `libby` downloads the set at runtime — see [Distribution flavors](#distribution-flavors).
- **Licensing:** third-party libraries are never bundled inside the artifacts — each flavor pulls them as normal dependencies (`core`) or downloads them at runtime (`libby`). In particular `mysql-connector-j` (GPLv2 + Universal FOSS Exception) is only POM metadata (`core`) or fetched from Maven Central on the end user's machine (`libby`), never bundled.
- **Logging:** SLF4J is **optional** — `slf4j-api` is compile-only, detected reflectively at runtime. Without it, logging quietly no-ops; no `NoClassDefFoundError`, no mandatory logging framework.
- **Serialisation:** entities must be Jackson-serialisable (a no-arg constructor plus accessors, or appropriate Jackson annotations).

<div align="center">

**Made by [Petrus Pradella](https://petrus.dev)**

</div>
