<div align="center">

# EveryDatabase

### One async API. Every database. Zero lock-in.

A backend-agnostic persistence layer for the JVM. Write your data-access code **once** against a small, typed, `CompletableFuture`-based API — then run it on **MySQL/MariaDB, PostgreSQL, H2, MongoDB, local files, or in-memory** without changing a line. Migrate data between any two of them with a single builder.

![Runtime](https://img.shields.io/badge/runtime-Java%208%2B-blue)
![Build](https://img.shields.io/badge/build-JDK%2025-orange)
![Backends](https://img.shields.io/badge/backends-SQL%20%7C%20Mongo%20%7C%20File%20%7C%20Memory-green)
![Version](https://img.shields.io/badge/version-1.0.1-informational)

</div>

---

## Table of contents

- [Why](#why)
- [Supported backends](#supported-backends)
- [Install](#install)
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
- **☕ Java 8 runtime.** Bytecode targets Java 8 while being authored in modern Java.

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

> SQL backends store the entity as a **native JSON column**; MongoDB stores it as a **native BSON sub-document** — not an escaped string — so the data is queryable and readable in standard DB tools.

---

## Install

The library is published to a public Maven repository.

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    implementation 'br.com.finalcraft:common-storage:1.0.1'

    // Bundled transitively: HikariCP, mongodb-driver-sync, H2, Jackson (databind + yaml).
    // You must add the JDBC driver for your SQL engine yourself:
    runtimeOnly 'com.mysql:mysql-connector-j:9.4.0'      // MySQL / MariaDB
    runtimeOnly 'org.postgresql:postgresql:42.7.7'       // PostgreSQL
    // (H2 is already bundled; MongoDB driver is already bundled)
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
  <groupId>br.com.finalcraft</groupId>
  <artifactId>common-storage</artifactId>
  <version>1.0.1</version>
</dependency>
```

---

## Quick start

```java
import br.com.finalcraft.evernifecore.storage.*;
import br.com.finalcraft.evernifecore.storage.codec.JacksonJsonCodec;
import br.com.finalcraft.evernifecore.storage.modules.sql.SqlConfig;

// 1. A plain entity (no-arg ctor + getters/setters so Jackson can (de)serialise it).
public class PlayerData {
    private UUID uuid;
    private String name;
    private int score;
    public PlayerData() {}
    public PlayerData(UUID uuid, String name, int score) { this.uuid = uuid; this.name = name; this.score = score; }
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

repo.save(new PlayerData(UUID.randomUUID(), "Alice", 100)).join();
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

---

## Instantiating each backend

<details>
<summary><b>MySQL / MariaDB</b></summary>

```java
import br.com.finalcraft.evernifecore.storage.modules.sql.*;

SqlStorage sql = Storages.createSQL(
        new SqlConfig("jdbc:mariadb://localhost:3306/mydb", "root", "root"));

// Full control over the HikariCP pool (min/max, connection timeout, idle timeout;
// a 5-arg PoolTuning constructor also exposes maxLifetime):
SqlStorage tuned = Storages.createSQL(new SqlConfig(
        "jdbc:mysql://db.internal:3306/app",
        "user", "pass",
        new PoolTuning(2, 10, Duration.ofSeconds(30), Duration.ofMinutes(10))));

sql.init().join();
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
import br.com.finalcraft.evernifecore.storage.modules.mongo.MongoConfig;

MongoStorage mongo = Storages.createMongo(new MongoConfig("mongodb://localhost:27017", "mydb"));

// With auth and an explicit connect timeout:
MongoStorage authed = Storages.createMongo(new MongoConfig(
        "mongodb://user:pass@host:27017", "mydb", Optional.of(Duration.ofSeconds(10))));
mongo.init().join();
```
> Transactions require a MongoDB **replica set** (4.0+). On a standalone server, `inTransaction(...)` throws at runtime.
</details>

<details>
<summary><b>Local files (one file per entity)</b></summary>

```java
import br.com.finalcraft.evernifecore.storage.modules.localfile.LocalFileConfig;

LocalFileStorage file = Storages.createLocalFile(new LocalFileConfig(Path.of("data")));
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
repo.saveAll(List.of(alice, bob, carol)).join();          // batched (JDBC batch / Mongo bulk)

// Read
Optional<PlayerData> one = repo.find(id).join();
List<PlayerData>     some = repo.findMany(List.of(id1, id2)).join();   // missing keys omitted
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

---

## Optimistic locking

Opt in per descriptor to guard against concurrent writers (e.g. two app instances editing the same entity). On a version mismatch, `save()` throws `OptimisticLockException`.

```java
public class Account implements Versioned {
    private UUID id;
    private long balance;
    private long lockVersion;          // managed by the backend
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
```

The version starts at `0` on insert and is incremented on every successful update. Descriptors **without** `.versioned()` (or `.version(getter, setter)`) keep plain upsert semantics — locking is entirely opt-in.

> **Backend support:** MySQL/MariaDB, PostgreSQL and MongoDB enforce the version check. **H2 does not** (by design — it's an embedded/dev engine): a versioned descriptor on H2 silently degrades to plain upsert, never throwing `OptimisticLockException`. Local files and in-memory don't enforce it either. Use a server-grade backend when concurrent writers matter.

---

## Transactions

Backends that implement `TransactionalStorage` run a unit of work atomically. Repositories obtained from the scope share the transaction; it commits on success, rolls back on exception or an explicit `scope.rollback()`.

```java
if (storage instanceof TransactionalStorage) {
    TransactionalStorage tx = (TransactionalStorage) storage;

    tx.inTransaction(scope -> {
        Repository<UUID, Account> accounts = scope.repository(ACCOUNTS);
        return accounts.find(fromId).thenCompose(fromOpt -> {
            Account from = fromOpt.orElseThrow();
            from.setBalance(from.getBalance() - 100);
            return accounts.save(from);
        });
        // throw, or call scope.rollback(), to abort
    }).join();
}
```

---

## Schema migrations

Backends implementing `SchemaAwareStorage` track applied migrations (a `_schema_migrations` table/collection/file) and apply pending ones in version order, exactly once. Migrations are **forward-only**.

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
import br.com.finalcraft.evernifecore.storage.log.*;

// Create a storage already watching index work and migrations, with writes muted:
StorageLogConfig logCfg = StorageLogConfig.defaults()        // WARN: routine silent, failures visible
        .level(StorageLogTopic.INDEX,     StorageLogLevel.INFO)
        .level(StorageLogTopic.MIGRATION, StorageLogLevel.INFO)
        .mute(StorageLogTopic.WRITE);
SqlStorage sql = Storages.createSQL(sqlConfig, logCfg);

// The config is LIVE - edit it at runtime and every repository reacts immediately:
sql.getStorageLogConfig()
   .level(StorageLogTopic.WRITE, StorageLogLevel.DEBUG)      // temporarily debug saves
   .includeKeys(true);                                       // opt-in: show entity keys
```

Other presets: `StorageLogConfig.silent()` (only the ERROR floor), `verbose()` (DEBUG), `trace()`.

**Where the lines go.** By default events route to **SLF4J** when it is on the runtime classpath (loggers named `evernifecore.storage.<topic>`), and become a silent no-op otherwise — the library never requires a logging framework. A host application can install its own bridge once, globally:

```java
// e.g. a Bukkit plugin routing storage logs to its own logger:
StorageLogSinks.installDefault(event -> plugin.getLogger().info(event.format()));
```

**Privacy by default.** Log lines carry counts, durations, collection names and index/migration metadata — never entity content. `includeKeys(true)`, `includeValues(true)` (truncated `toString()`, single-entity saves only) and `includeQueryValues(true)` are explicit opt-ins for local debugging.

**Quick verbosity for tests/CI** — no code changes needed:

```bash
-Devernifecore.storage.log.level=info     # lifecycle, index, migration, batch summaries
-Devernifecore.storage.log.level=debug    # + saves, deletes, queries, progress ticks
```

---

## Building & running the tests

### Prerequisites

- **JDK 21** to launch Gradle. The launcher JDK is *not* what compiles the code: the Gradle toolchain auto-provisions **JDK 25** for compiling and running tests, and the artifact still targets **Java 8** — see [Compatibility notes](#compatibility-notes). Don't point `JAVA_HOME` at JDK 25: Gradle 8.14 itself cannot run on it (`Unsupported class file major version 69`).
- **Docker** (optional) — only needed to run the SQL/Mongo integration suites against real servers.

### Clone & build

```bash
git clone <repo-url> EveryDatabase
cd EveryDatabase

# Launch Gradle with JDK 21 (the build toolchain provisions JDK 25 by itself)
export JAVA_HOME=/path/to/jdk-21      # PowerShell: $env:JAVA_HOME = "C:\path\to\jdk-21"

./gradlew :common-storage:build       # compile + run all tests
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

Running `./gradlew :common-storage:test` brings the containers up automatically (the Gradle docker-compose plugin is wired to the `test` task). **Suites self-skip when their server is unreachable**, so the build never fails just because a database is down.

### Running specific tests

```bash
./gradlew :common-storage:test                                   # everything
./gradlew :common-storage:test -PskipStress                      # skip the 10k-record stress suites
./gradlew :common-storage:test --tests "*MariaDbStorageTest"     # one class
./gradlew :common-storage:test --tests "*MariaDbStorageTest.inTransaction_commit_savesAreVisible"
```

Override connection coordinates with env vars or `-Dkey=value` (e.g. `MARIADB_HOST`, `MONGO_USER`, `POSTGRES_URL`). Each SQL/Mongo test method runs against its own throwaway database (`enc_NNN_<backend>_<method>`), dropped automatically afterwards — set `TEST_KEEP_DATABASES=true` to keep them for inspection.

---

## Project layout

```
EveryDatabase/
├── common-storage/                      # the published library (br.com.finalcraft:common-storage)
│   ├── src/main/java/br/com/finalcraft/evernifecore/storage/
│   │   ├── (root)                       # Storage, Repository, EntityDescriptor, Storages, StorageExecutors
│   │   ├── codec/                       # JacksonJsonCodec (compact / pretty), JacksonYamlCodec
│   │   ├── query/                       # IndexHint, @Indexed, Query
│   │   ├── versioned/                   # Versioned, OptimisticLockException
│   │   ├── tx/                          # TransactionalStorage, TransactionScope
│   │   ├── schema/                      # SchemaAwareStorage, Migration, MigrationContext
│   │   ├── log/                         # StorageLogConfig, topics/levels/sinks (see Logging & diagnostics)
│   │   ├── transfer/                    # StorageTransfer, TransferReport, ErrorPolicy
│   │   └── modules/                     # sql (+ postgresql, h2), mongo, localfile, memory
│   └── src/test/java/                   # backend-agnostic contract suites + per-backend + stress tests
├── docker-compose.yml                   # MariaDB / PostgreSQL / MongoDB for the integration suites
└── specs/                               # design specs (storage logging)
```

---

## Compatibility notes

- **Runtime:** the library compiles to **Java 8 bytecode** (no Java 9+ APIs at runtime).
- **Build:** authored in modern Java syntax and compiled to Java 8 via [Jabel](https://github.com/bsideup/jabel); the Gradle toolchain is **JDK 25**.
- **Concurrency:** `StorageExecutors` uses virtual threads on Java 21+, falling back to a bounded daemon thread pool on older JVMs.
- **Drivers:** H2 and the MongoDB driver are bundled. For MySQL/MariaDB or PostgreSQL, add the JDBC driver to your own runtime classpath.
- **Logging:** SLF4J is **optional** — `slf4j-api` is a compile-only dependency, detected reflectively at runtime. Without it (e.g. when the library is shaded into a plugin jar) logging quietly no-ops; no `NoClassDefFoundError`, no mandatory logging framework.
- **Serialisation:** entities must be Jackson-serialisable (a no-arg constructor plus accessors, or appropriate Jackson annotations).

<div align="center">

**Made by [Petrus Pradella](https://petrus.dev)**

</div>
