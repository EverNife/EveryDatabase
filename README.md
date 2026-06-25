<div align="center">

# EveryDatabase

### One async API. Every database. Zero lock-in.

A backend-agnostic persistence layer for the JVM. Write your data-access code **once** against a small, typed, `CompletableFuture`-based API — then run it on **MySQL/MariaDB, PostgreSQL, H2, MongoDB, local files, or in-memory** without changing a line. Migrate data between any two of them with a single builder.

![Runtime](https://img.shields.io/badge/runtime-Java%208%2B-blue)
![Build](https://img.shields.io/badge/build-JDK%2025-orange)
![Backends](https://img.shields.io/badge/backends-SQL%20%7C%20Mongo%20%7C%20File%20%7C%20Memory-green)
![Version](https://img.shields.io/badge/version-1.0.3-informational)

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
- [Moving data between backends](#moving-data-between-backends)
- [Logging & diagnostics](#logging--diagnostics)
- [Caching & references (`everydatabase-manager`)](#caching--references-everydatabase-manager)
- [Building & running the tests](#building--running-the-tests)
- [Project layout](#project-layout)
- [Compatibility notes](#compatibility-notes)

---

## Why

Most persistence libraries marry you to one engine. EveryDatabase treats the engine as a **deployment choice**, not an architectural one. An application can ship with file storage for small scenarios, let operators flip to MariaDB or MongoDB for large ones, and move the live data across with no code changes.

- **🔌 One interface, many engines.** `Storage` + `Repository<K, V>` is the entire surface you code against.
- **⚡ Async-first.** Every I/O call returns a `CompletableFuture`. Block with `.join()` when you must; compose when you can. Uses virtual threads on Java 21+.
- **🧩 Capabilities are interfaces, not flags.** Transactions, schema migrations and rich queries are *optional* interfaces a backend may implement — checked with `instanceof`, enforced by the compiler. No backend pretends to support something it can't.
- **🗂️ Declarative indexes.** Annotate a field with `@Indexed` (or declare an `IndexHint`) and the backend creates a real secondary index — a SQL column + B-tree, a Mongo index, or an in-memory map.
- **🔁 Built-in data transfer.** `StorageTransfer.builder()` copies entities between *any* two backends, read-only on the source, with batching, progress and verification.
- **☕ Java 8 runtime.** Bytecode targets Java 8 while being authored in modern Java — and the default dependency set is Java-8-clean too, so **every backend runs on a Java 8 JVM** (see [Java version requirements](#java-version-requirements)).

---

## Supported backends

| Backend | Factory | Transactions | Schema migrations | Secondary indexes | Optimistic locking | Persistence |
|---|---|:---:|:---:|:---:|:---:|---|
| **MySQL / MariaDB** | `Storages.createSQL` | ✅ | ✅ | ✅ native column + B-tree | ✅ | Durable |
| **PostgreSQL** | `Storages.createPostgreSQL` | ✅ | ✅ | ✅ native column + B-tree | ✅ | Durable |
| **H2** (mem / file / tcp) | `Storages.createH2` | ✅ | ✅ | ✅ native column + B-tree | ❌ *(by design)* | Durable / ephemeral |
| **MongoDB** | `Storages.createMongo` | ✅ *(replica set)* | ✅ | ✅ native index | ✅ | Durable |
| **Local files** | `Storages.createLocalFile` | ❌ | ✅ | ⚠️ full scan (no real index) | ❌ | Durable (one file per entity) |
| **In-memory** | `Storages.createInMemory` | ✅ *(no isolation)* | ❌ | ✅ in-memory map | ❌ | Ephemeral |

> MySQL/MariaDB and PostgreSQL store the entity in a **native `JSON` column**, and MongoDB as a **native BSON sub-document** — not an escaped string — so the data stays queryable and readable in standard DB tools. (H2 stores it as plain `TEXT`.)

---

## Install

The library is published to a public Maven repository in **two flavors** — same code, same API, different packaging (see [Distribution flavors](#distribution-flavors)). Pick exactly one.

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    // RECOMMENDED — everything included by default (HikariCP, Jackson, Mongo driver, H2,
    // MySQL + PostgreSQL JDBC drivers); override any version via normal dependency management:
    implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.0.3'

    // OR runtime download — your jar stays tiny, the same set is downloaded at runtime via Libby:
    //implementation 'br.com.finalcraft.everydatabase:everydatabase-libby:1.0.3'
}
```

Nothing else to add — every backend works out of the box. To **change a version**, just declare your own (Gradle picks the highest by default; append `!!` to force a downgrade — in Maven your nearest declaration always wins). To **drop what you don't use**, exclude it:

```groovy
dependencies {
    implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.0.3'

    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.2'   // upgrade Jackson
    runtimeOnly    'com.mysql:mysql-connector-j:8.4.0!!'                  // force-downgrade the MySQL driver

    // Only target SQL? Drop the Mongo driver entirely:
    // implementation('br.com.finalcraft.everydatabase:everydatabase-core:1.0.3') {
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
  <version>1.0.3</version>
</dependency>
```

---

## Distribution flavors

Both flavors expose the exact same API and carry the **same dependency set** — HikariCP, Jackson (`databind` + `yaml`), the MongoDB driver, H2, and the MySQL + PostgreSQL JDBC drivers. They only differ in **how that set reaches your classpath**.

### `everydatabase-core` — recommended

The library with everything declared as a **normal POM dependency**: it works out of the box, and you keep full control through standard dependency management — upgrade or downgrade any of the libraries by declaring your own version, or exclude what you don't use (see [Install](#install)). Scopes are meaningful: `jackson-databind` and `mongodb-driver-sync` are `compile` (their types appear in the public API), everything else is `runtime`.

| Included by default | Version | POM scope |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.15.4 | compile |
| `org.mongodb:mongodb-driver-sync` | 4.11.2 | compile |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.15.4 | runtime |
| `com.zaxxer:HikariCP` (4.x = last Java 8 line; on 11+ feel free to override to 5.x) | 4.0.3 | runtime |
| `com.h2database:h2` (1.4.200 = last Java 8 release — see note below before overriding) | 1.4.200 | runtime |
| `com.mysql:mysql-connector-j` (protobuf excluded — only the removed X DevAPI needs it) | 9.4.0 | runtime |
| `org.postgresql:postgresql` | 42.7.7 | runtime |

> **H2 version note:** H2 1.x and 2.x use **incompatible database file formats** and slightly different SQL dialects. The default stays on 1.4.200 so Java 8 hosts work out of the box; if you run on Java 11+ and want H2 2.x, override it (`implementation 'com.h2database:h2:2.3.232'`) — but don't switch versions over an existing embedded-file database.

### `everydatabase-libby` — runtime download

`everydatabase-core` plus a small coordinator (package `br.com.finalcraft.everydatabase.libby`) that downloads the canonical, **non-relocated** libraries at runtime via [Libby](https://github.com/AlessioDP/libby) — your jar stays tiny, and the POM excludes `core`'s transitive set so nothing heavy enters your build-time graph either. Bootstrap it in your plugin's `onLoad` (or earliest bootstrap), **before touching any storage class**:

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

After `loadAll(...)` returns, use `Storages` normally. Note: `everydatabase-libby` itself depends on `net.byteflux:libby-core`, resolved from `https://repo.alessiodp.com/releases/` — add that repository to your build alongside the ones above.

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
| **`Codec<V>`** | Serialisation strategy. `JacksonJsonCodec` (everywhere) and `JacksonYamlCodec` (local files only). |
| **`Storages`** | Static factory — typed builders per backend, plus a generic `create(StorageConfig)`. |

**Optional capability interfaces** — a `Storage` may also implement any of:

- `TransactionalStorage` — atomic `inTransaction(...)`
- `SchemaAwareStorage` — `register(...).migrate()`

You discover them with `instanceof`, so the compiler stops you from using transactions on a backend that doesn't support them.

> **Codec tip:** `new JacksonJsonCodec<>(Type.class)` emits **compact** JSON (smallest payload — what you want in a database). Use `JacksonJsonCodec.pretty(Type.class)` for indented, human-readable output — pairs nicely with `LocalFileStorage` when you want to read the files by eye.

> **Collection names** must match `^[a-zA-Z][a-zA-Z0-9_]*$` — the safe intersection of identifier rules across every supported backend (no quoting or escaping ever needed).

> **Keys** are persisted by their `toString()` (the SQL primary key, the Mongo unique index, the LocalFile filename) and matched by `equals`/`hashCode` (the in-memory backend and the manager cache). A key type must therefore have a **stable, unique `toString()` of at most 255 characters** and value-based `equals`/`hashCode` — `UUID`, `String`, `Long`, `Integer` and `record`s all qualify (the default identity `Object.toString()` does **not**). `save`/`saveAll` reject an oversized key up front: the returned future completes exceptionally with a clear `IllegalArgumentException`, so a long key can never be **silently truncated** into a collision. (For `Ref` keys in the manager layer, the key must also be JSON-serializable.)

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
This is the **only** backend that accepts a non-JSON codec — pair it with `JacksonYamlCodec` to get human-friendly `.yml` files.
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
StorageConfig config = loadFromYaml();          // returns SqlConfig / MongoConfig / LocalFileConfig / InMemoryConfig
Storage storage = Storages.create(config);      // dispatches on the config type
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
Stream<PlayerData>   all  = repo.all().join();
boolean exists            = repo.exists(id).join();
long count                = repo.count().join();

// Delete
boolean removed = repo.delete(id).join();                  // true if it existed

// Non-blocking composition
repo.find(id)
    .thenApply(opt -> opt.map(PlayerData::getScore).orElse(0))
    .thenAccept(score -> System.out.println("score = " + score));
```

---

## Indexing & queries (`@Indexed`)

Declare indexes and the backend materialises a real secondary index. Two equivalent styles:

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

> Querying a field that was **not** declared as an index throws `IllegalArgumentException` on **every** backend — including local files, which validate the declaration even though they answer queries with a full scan (`O(n)`, no real index). Indexes added or removed later are reconciled automatically (column/index created, backfilled, or dropped) the next time the repository is opened.

### Ordering & pagination (`QueryOptions`)

Pass a `QueryOptions` to `query(Query, QueryOptions)` to **order** and **page** results at the storage layer, instead of loading the whole collection and sorting in memory. Use `Query.all()` to match every entity (a leaderboard or a plain page):

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

The ordering is **identical on every backend**, so a query keeps behaving the same when you swap the storage:

- **`orderBy` must be a declared index field** (same rule as query conditions) — an undeclared field throws `IllegalArgumentException`.
- **`NULL`/missing values sort as the smallest value** — first when ascending, last when descending.
- **Ties are broken by the entity key** (ascending), so a paged result is stable; pages never overlap or drop a row because two entities shared a score. Pagination with only `limit`/`offset` (no `orderBy`) is ordered by key for the same reason.
- `limit(0)` means **unbounded**; a negative `limit`/`offset` is rejected up front (`IllegalArgumentException`).

> H2 opts out of optimistic locking but still orders and pages like the others. SQL backends order on the materialized `_idx_<field>` column (a real B-tree); LocalFile/GroupedFile order during their full scan.

#### Page responses — `querySlice` (cheap) and `queryPage` (with total)

`query(...)` returns a plain `List`. When you want navigation metadata, two richer results are available — both take the same `QueryOptions` (use `.page(n, size)` for 0-based paging):

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

For deep paging or "load more"/infinite scroll, `queryAfter` seeks by the **position** of the last row instead of an offset, so it stays fast no matter how far in you are (offset scans and discards the skipped rows). It returns a forward-only `Slice` with **no total**, and is stable under concurrent inserts/deletes. The cursor carries the order, so you set it only once:

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

---

## Optimistic locking

Opt in per descriptor to guard against concurrent writers (e.g. two app instances editing the same entity). On a version mismatch the save fails with `OptimisticLockException` (when you `.join()` the future, it surfaces as the cause of a `CompletionException`).

**The easy way — annotate a `long`/`Long` field with `@OptimisticLock`** and you're done: `build()` finds it and wires the getter/setter via reflection. No interface, no builder call:

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

The field may be `long` or `Long` (a still-`null` `Long` reads as version `0`), and must not be `static` or `final`. The rules are **validated at `build()` time** so mistakes fail fast: a wrong type throws `IllegalArgumentException`, two annotated fields throw `IllegalStateException`, and combining the annotation with the manual wiring below also throws — pick one mechanism.

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

The version starts at `0` on insert and is incremented on every successful update. Descriptors **without** versioning (no annotation, no `.versioned()` / `.version(getter, setter)`) keep plain upsert semantics — locking is entirely opt-in.

> **Backend support:** MySQL/MariaDB, PostgreSQL and MongoDB enforce the version check. **H2 does not** (by design — it's an embedded/dev engine): a versioned descriptor on H2 silently degrades to plain upsert, never throwing `OptimisticLockException` — creating the storage never fails because of versioning. Local files and in-memory don't enforce it either. Use a server-grade backend when concurrent writers matter.

---

## Transactions

Backends that implement `TransactionalStorage` run a unit of work atomically: every SQL dialect (including H2), MongoDB (replica set required) and in-memory (atomic, but no isolation) — local files don't. Repositories obtained from the scope share the transaction; it commits on success, rolls back on exception or an explicit `scope.rollback()`.

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

Backends implementing `SchemaAwareStorage` — SQL (all dialects), MongoDB and local files — track applied migrations (a `_schema_migrations` table/collection/file) and apply pending ones in version order, exactly once. Migrations are **forward-only**.

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

Each backend ships a convenience base class: `SqlMigration` (return `upScript()`), `MongoMigration` (override `executeOnDatabase(MongoDatabase)`), `LocalFileMigration` (override `executeOnStorage(LocalFileStorage)`). For full control, implement `Migration.execute(MigrationContext)` and pull the native client via `context.getNativeClient(...)`.

> Auto-create and migrations are complementary: entity tables/collections are created automatically on first `repository(...)`; migrations cover everything else (backfills, auxiliary tables, indexes you manage yourself). Write SQL migrations to be **idempotent** — DDL implicitly commits on MySQL/MariaDB.

---

## Moving data between backends

`StorageTransfer` copies entities from one backend to another. The **source is never modified** — it only reads. Ideal for a maintenance-window cutover (e.g. file storage → MariaDB).

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

Use `descriptor(sourceDesc, targetDesc)` to rename a collection or change codec mid-transfer (e.g. YAML on disk → JSON in SQL). The returned future never completes exceptionally for expected failures — they're collected in `report.errors()`.

---

## Logging & diagnostics

The library is **silent by default**: routine operations emit nothing, while failures always do (an `ERROR` floor that no configuration can switch off). Everything in between is opt-in, per **topic** (`INDEX`, `WRITE`, `DELETE`, `QUERY`, `MIGRATION`, `TRANSACTION`, `TRANSFER`, ...), with live runtime editing.

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

**Where the lines go.** By default events route to **SLF4J** when it is on the runtime classpath (loggers named `everydatabase.<topic>`), and become a silent no-op otherwise — the library never requires a logging framework. A host application can install its own bridge once, globally:

```java
// e.g. a Bukkit plugin routing storage logs to its own logger:
StorageLogSinks.installDefault(event -> plugin.getLogger().info(event.format()));
```

**Privacy by default.** Log lines carry counts, durations, collection names and index/migration metadata — never entity content. `includeKeys(true)`, `includeValues(true)` (truncated `toString()`, single-entity saves only) and `includeQueryValues(true)` are explicit opt-ins for local debugging.

**Quick verbosity for tests/CI** — no code changes needed:

```bash
-Deverydatabase.log.level=info     # lifecycle, index, migration, batch summaries
-Deverydatabase.log.level=debug    # + saves, deletes, queries, progress ticks
```

---

## Caching & references (`everydatabase-manager`)

An **optional add-on module** that sits *in front of* the core: hold a **typed reference** to an entity in another collection (and even another **database**), cache the hot ones with a policy you control, and resolve them lazily. It's a façade — the core stays untouched. A reference *is not* a cache: `Ref` is the pointer, the `CachingManager` is the cache.

```groovy
// the manager add-on does NOT pull core in transitively — declare both explicitly:
implementation 'br.com.finalcraft.everydatabase:everydatabase-manager:1.0.3'
implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.0.3'
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
- **Caching with a policy you own** — `always()` / `ttl(...)` / `noCache()`, a per-field `@RefPolicy` override, and an LRU `maxSize`. `peek()` is a lock-free, cache-only read; `resolve()` loads on a miss; `getAll(...)` batches (the N+1 antidote); `saveAndCache` / `deleteAndEvict` keep cache and backend consistent.
- **Write-back when you want it** — opt-in dirty-trackable entities (implement `IDirtyable` or annotate a `boolean` field with `@DirtyFlag`) mutate in memory and flush in a batch (`flushDirty()`); a dirty value is never reloaded over, and `seedIfAbsent(...)` caches a not-yet-persisted default.
- **Cross-backend by design** — because a reference resolves through its type's manager, a single root entity can fan out across MySQL, PostgreSQL, Mongo, H2, files and memory **at once**, each reference under its own key type.
- **Per-context registries, no global state** — each `RefRegistry` is its own isolated context; two of them can register a manager for the **same** type backed by different storages without colliding, so independent plugins never interfere. Registries can also chain to a **parent**, composing a private-then-shared lookup (a plugin's own registry falling back to a shared one).

**→ Full guide: [`manager/README.md`](manager/README.md).**

---

## Building & running the tests

### Prerequisites

- **JDK 25** — the only JDK you need to set up. The wrapper is Gradle 9.5.1, which launches on JDK 25 directly, and all test code compiles and runs on the Java 25 toolchain.
  - The published artifacts still target **Java 8**: production sources are compiled by an auto-detected **JDK 17** ([Jabel](https://github.com/bsideup/jabel) lets Java 17 *syntax* emit Java 8 *bytecode*, with `--release 8` keeping the API floor honest). Gradle finds a JDK 17 in the usual locations (e.g. `~/.jdks`) or provisions one — no manual setup.
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

The integration suites need real database servers. `docker-compose.yml` starts all three on **non-default high ports** that match the test defaults — no configuration needed.

| Service | Host port | Credentials |
|---|---|---|
| MariaDB (MySQL-compatible) | `39306` | `root` / `root` |
| PostgreSQL | `39307` | `root` / `root` |
| MongoDB | `39308` | `root` / `root` |

```bash
docker compose up -d            # start all three
docker compose up -d mariadb    # or just one
docker compose ps               # check health
docker compose down             # stop (keeps data)
docker compose down -v          # stop + wipe volumes
```

Running `./gradlew :core:test` brings the containers up automatically (the Gradle docker-compose plugin is wired to the `test` task). No Docker on the machine? Add `-PnoDocker` to skip the compose wiring entirely — the SQL/Mongo suites **self-skip when their server is unreachable**, and the embedded suites (H2, local files, in-memory) still run.

### Running specific tests

```bash
./gradlew :core:test                                   # everything
./gradlew :core:test -PskipStress                      # skip the 10k-record stress suites
./gradlew :core:test -PnoDocker                        # no Docker at all (SQL/Mongo suites self-skip)
./gradlew :core:test --tests "*MariaDbStorageTest"     # one class
./gradlew :core:test --tests "*MariaDbStorageTest.inTransaction_commit_savesAreVisible"
```

Override connection coordinates with env vars or `-Dkey=value` (e.g. `MARIADB_HOST`, `MONGO_USER`, `POSTGRES_URL`). Each SQL/Mongo test method runs against its own throwaway database (`enc_NNN_<backend>_<method>`), dropped automatically afterwards — set `TEST_KEEP_DATABASES=true` to keep them for inspection.

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
│   │   └── modules/                     # sql (+ postgresql, h2), mongo, localfile, memory
│   └── src/test/java/                   # backend-agnostic contract suites + per-backend + stress tests
├── libby/                               # runtime-download flavor (everydatabase-libby) — DependencyManager, EveryDatabaseDependencies
├── manager/                             # OPTIONAL add-on (everydatabase-manager) — typed refs + caching (see manager/README.md)
└── docker-compose.yml                   # MariaDB / PostgreSQL / MongoDB for the integration suites
```

---

## Compatibility notes

### Java version requirements

**Everything runs on Java 8** — the library is compiled with `--release 8`, and the default dependency versions were deliberately chosen as the **last Java-8-compatible lines** of each library:

| Component | Default version | Minimum Java |
|---|---|:---:|
| EveryDatabase classes themselves | — | **8** (compiled with `--release 8`) |
| Jackson codecs (JSON/YAML) | 2.15.4 | 8 |
| MongoDB backend (`mongodb-driver-sync`) | 4.11.2 | 8 |
| SQL pooling (`HikariCP`) | 4.0.3 — last Java 8 line | 8 |
| H2 backend (`com.h2database:h2`) | 1.4.200 — last Java 8 release | 8 |
| MySQL / PostgreSQL JDBC drivers | 9.4.0 / 42.7.7 | 8 |
| Local files / In-memory backends | (no external deps) | 8 |

Running on **Java 11+** and want the newer majors? With the `core` flavor just override them — the library's code paths work with both lines:

```groovy
implementation 'com.zaxxer:HikariCP:5.1.0'     // Java 11+ (5.x line)
implementation 'com.h2database:h2:2.3.232'     // Java 11+ (2.x line) — read the warning below!
```

> ⚠️ **H2 1.x ↔ 2.x are not interchangeable on disk:** the database **file formats are incompatible** and the SQL dialects differ slightly. Pick one before going to production and never swap the major version over an existing embedded-file database (export/import instead). In-memory H2 (`jdbc:h2:mem:`) has no such concern.

### Other notes

- **Build:** authored in Java 17 syntax and compiled to Java 8 via [Jabel](https://github.com/bsideup/jabel); the Gradle toolchain is **JDK 25** (Gradle 9.5 launches on JDK 25 directly).
- **Concurrency:** `StorageExecutors` uses virtual threads on Java 21+, falling back to a bounded daemon thread pool on older JVMs.
- **Dependencies & drivers:** both flavors ship the full backend set by default — HikariCP, Jackson, Mongo driver, H2, and the MySQL + PostgreSQL JDBC drivers. With `core` you override versions via normal dependency management; `libby` downloads the full set at runtime — see [Distribution flavors](#distribution-flavors).
- **Licensing:** this project never redistributes third-party libraries inside its own artifacts — each flavor pulls them as normal dependencies (`core`) or downloads them at runtime (`libby`). In particular `mysql-connector-j` (GPLv2 + Universal FOSS Exception) is only referenced as POM metadata (`core`) or fetched from Maven Central on the end user's machine (`libby`), never bundled.
- **Logging:** SLF4J is **optional** — `slf4j-api` is a compile-only dependency, detected reflectively at runtime. Without it on the classpath logging quietly no-ops; no `NoClassDefFoundError`, no mandatory logging framework.
- **Serialisation:** entities must be Jackson-serialisable (a no-arg constructor plus accessors, or appropriate Jackson annotations).

<div align="center">

**Made by [Petrus Pradella](https://petrus.dev)**

</div>
