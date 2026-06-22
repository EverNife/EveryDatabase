<div align="center">

# everydatabase-manager

### Typed references + caching, in front of EveryDatabase.

A thin façade over [`everydatabase-core`](../README.md): hold a **typed reference** to an entity
that lives in another collection (and even another **database**), cache the hot ones with a policy
you control, and resolve them lazily — without turning the library into an ORM.

</div>

---

## Table of contents

- [Why](#why)
- [Install](#install)
- [The 30-second version](#the-30-second-version)
- [Core concepts](#core-concepts)
- [`Ref<K, V>` — a reference that serializes as its key](#refk-v--a-reference-that-serializes-as-its-key)
- [`CachingManager` — the cache-backed façade](#cachingmanager--the-cache-backed-façade)
- [Freshness vs capacity](#freshness-vs-capacity)
- [Per-reference policy (`@RefPolicy`)](#per-reference-policy-refpolicy)
- [Writing & deleting through the cache](#writing--deleting-through-the-cache)
- [Write-back](#write-back)
- [Nested references](#nested-references)
- [⭐ One entity, many databases, many key types](#-one-entity-many-databases-many-key-types)
- [Multiple registries — the same type, different resolvers](#multiple-registries--the-same-type-different-resolvers)
- [Parent registries](#parent-registries)
- [Keeping caches fresh across instances (`CacheSync`)](#keeping-caches-fresh-across-instances-cachesync)
- [What it guarantees (and what it doesn't)](#what-it-guarantees-and-what-it-doesnt)
- [Non-goals](#non-goals)

---

## Why

Core stores **self-contained** entities (`Key → ComplexEntity`) and is deliberately thin and async:
every read hits the backend, there is no value cache, and `find` returns a **fresh** instance each
time. That's correct for the core — but real applications want two more things:

1. **Typed references between entities.** Hold a `Guild`, not a raw `UUID` — while still storing only
   the key on disk.
2. **Caching of hot data.** The same guilds/definitions are read thousands of times; you shouldn't hit
   the backend every time.

Both are *façade* concerns, so they live in this optional module. The key idea: **a reference is not a
cache.** `Ref` is a pointer; the cache lives in the `CachingManager`. Keeping them separate is what
makes the design composable — and what lets a reference cross backends transparently.

---

## Install

`everydatabase-manager` is an **add-on that sits in front of** `everydatabase-core` (the recommended
flavor). It does **not** pull core in transitively — declare **both** explicitly, so it stays obvious
which artifact owns what:

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    implementation 'br.com.finalcraft.everydatabase:everydatabase-manager:1.0.3'
    implementation 'br.com.finalcraft.everydatabase:everydatabase-core:1.0.3'
}
```

**Maven**

```xml
<dependency>
  <groupId>br.com.finalcraft.everydatabase</groupId>
  <artifactId>everydatabase-manager</artifactId>
  <version>1.0.3</version>
</dependency>
<dependency>
  <groupId>br.com.finalcraft.everydatabase</groupId>
  <artifactId>everydatabase-core</artifactId>
  <version>1.0.3</version>
</dependency>
```

---

## The 30-second version

```java
// 1. An entity that references another by a typed Ref (serialized as just the key on disk).
@Data @NoArgsConstructor @AllArgsConstructor
public class Player {
    private UUID uuid;
    private Ref<UUID, Guild> guild;   // -> "guild":"<uuid>" in the stored JSON
}

// 2. A RefRegistry owns your refs. It vends the ref-aware codec and the manager, so a
//    Ref<?, Guild> read through this registry resolves through this registry's Guild manager.
RefRegistry refRegistry = new RefRegistry();

EntityDescriptor<UUID, Guild> GUILDS = EntityDescriptor.builder(UUID.class, Guild.class)
        .collection("guilds")
        .keyExtractor(Guild::getId)
        .codec(refRegistry.codec(Guild.class))            // ref-aware codec bound to this registry
        .build();

CachingManager<UUID, Guild> guilds = refRegistry.manager(GUILDS, storage, CachePolicy.always());

// 3. Resolve a reference — it goes through the Guild manager (its cache, its backend).
Player p = playerRepo.find(playerId).join().orElseThrow();   // playerRepo built with refRegistry.codec(Player.class)
Optional<Guild> g = p.getGuild().peek();          // synchronous, cache-only (the hot path)
p.getGuild().resolve().thenAccept(opt -> ...);    // async: cache hit, or load-and-cache
```

`peek()` never does I/O; `resolve()` loads on a miss. The `Player` neither knows nor cares where its
`Guild` lives or how it's cached — only that its reference was read through `refRegistry`.

---

## Core concepts

| Type | Role |
|---|---|
| **`Ref<K, V>`** | A typed, lazily-resolved reference. Serializes as its **key**; the target type `V` is recovered from the field declaration on read. |
| **`CachingManager<K, V>`** | A cache-backed façade in front of one `Repository`. Owns an in-memory identity map and resolves `Ref`s to that type. Registered in a `RefRegistry`. |
| **`RefRegistry`** | A per-context registry: entity type → its manager. **No global default** — each context makes its own. It *vends* the ref-aware `codec(type)` and the `manager(...)`, and a `Ref` is bound to one and resolves there. |
| **`CachePolicy`** | Freshness strategy: `always()`, `ttl(Duration)`, `noCache()`. Per-reference overridable. |
| **`CacheOptions`** | Store-level config: default `CachePolicy` + `maxSize` (LRU bound). |
| **`@RefPolicy`** | Per-field freshness override declared right on a `Ref` field. |
| **`IDirtyable` / `@DirtyFlag`** | Two opt-in forms for mutate-in-memory / flush-later entities (implement the interface **or** annotate a `boolean` field): a dirty cell is never reloaded over, and `flushDirty()` persists the dirty set in one batch. |
| **`RefCodecs`** | Builds a ref-aware Jackson codec bound to a registry (`registry.codec(Type.class)`, delegating to `RefCodecs.json(Type.class, registry)`). |

The naming reflects the split: the **`Ref*`** family is *the reference*; the **`Cache*`** family is
*the cache*.

---

## `Ref<K, V>` — a reference that serializes as its key

On disk a `Ref<UUID, Guild> guild` is just `"guild":"<uuid>"` — byte-for-byte what storing the raw
`UUID` would produce. No embedded entity, no `@JsonIgnore` tricks. The target type `V` is recovered
from the field's generic declaration at deserialization (via a Jackson `ContextualDeserializer`), so
the JSON stays clean and the Java side stays typed.

Resolution goes through the entity type's manager (looked up in the `Ref`'s bound `RefRegistry`):

| Call | Blocking? | I/O? | Returns |
|---|:---:|:---:|---|
| `ref.peek()` | sync | never | `Optional<V>` — cache-only; empty if absent/stale |
| `ref.resolve()` | async | on miss | `CompletableFuture<Optional<V>>` |
| `ref.join()` | blocks | on cold miss | `V` or `null` (convenience) |

After the first resolution a `Ref` **memoizes the live cache cell**, so later `peek()`/`resolve()`
read it directly — **lock-free, no map lookup** — and, because the cell updates in place, the handle
always observes the latest value. A long-held `Ref` (e.g. on an online player) behaves like a
self-refreshing live handle.

Build a bound `Ref` to resolve programmatically with `registry.ref(key, Type.class)`. The registry
is always an explicit argument to `Ref.of`: pass `null` **deliberately** —
`Ref.of(key, Type.class, null)` — for an **unbound** ref, fine to build and store (only the key is
serialized) but resolving it fails fast. There is no two-argument `Ref.of`, so the choice is always
visible at the call site. An empty reference is `Ref.empty(Type.class)` (a JSON `null` round-trips to
an empty `Ref`, never a bare `null`).

> **Codec.** Wrap entities that contain `Ref` fields with `registry.codec(Type.class)` — it builds a
> codec with the `RefModule` bound to that registry, so every `Ref` it reads resolves there.
> (Equivalent to `RefCodecs.json(Type.class, registry)`.) Targets without `Ref` fields can use a
> plain `JacksonJsonCodec`, but reading them through `registry.codec(...)` is harmless too.

---

## `CachingManager` — the cache-backed façade

Create one manager per entity type at startup, passing the `RefRegistry` it belongs to; it
self-registers there (keyed by the entity type), so every `Ref<?, ThatType>` bound to that registry
resolves through it. Use `registry.manager(...)` directly, or subclass it for a domain name and pass
the registry to `super(...)`:

```java
public final class GuildManager extends CachingManager<UUID, Guild> {
    public GuildManager(Storage storage, RefRegistry registry) {
        super(GUILDS, storage, CacheOptions.builder()
                .policy(CachePolicy.always())   // small hot set: keep resident
                .maxSize(1000)                  // ...but bounded
                .build(), registry);
    }
}
```

Surface:

| Method | What it does |
|---|---|
| `resolve(key)` / `peek(key)` (+ `(key, policy)`) | the value-level read API (used by `Ref`) |
| `getAll(keys)` | **batched** multi-get with partial cache hits — the in-loop antidote to N+1 |
| `preloadAll()` | mirror the whole collection into the cache (a small hot set) |
| `saveAndCache(value)` | write-through: persist **and** update the cell |
| `deleteAndEvict(key)` | delete from the backend **and** evict the cell |
| `seedIfAbsent(key, value)` | cache a not-yet-persisted instance (write-back lazy default), no I/O |
| `saveAllAndCache(values)` / `flushDirty()` | batch write-through / flush dirty (write-back) cells (see [Write-back](#write-back)) |
| `invalidate(key)` / `evict(key)` / `invalidateAll()` / `clearCache()` | invalidation knobs |
| `purgeExpired()` | drop entries the policy no longer considers fresh (release memory) |
| `cachedValues()` / `cachedSize()` | snapshot the live cached values / count them (for bulk iteration) |
| `repository()` | the underlying uncached `Repository` (queries, cache-bypassing reads) |

---

## Freshness vs capacity

Two different concerns, two different types — on purpose:

- **Freshness** is a `CachePolicy` (`always()`, `ttl(Duration)`, `noCache()`), evaluated per read. It
  is **per-reference overridable** (see below).
- **Capacity** is `CacheOptions.maxSize` (LRU eviction), a property of the **store** — shared by every
  entry, so it is *not* per-reference overridable.

You can't give one reference its own `maxSize` (the store is shared), but you *can* give it its own
freshness. So `maxSize` is a manager knob; TTL is a per-read knob.

> **Memory & GC.** Cached entries are held by **strong** references, so TTL governs *freshness*, not
> memory. Bound memory with `maxSize` (evicted entries become GC-eligible) and/or call `purgeExpired()`
> periodically. (A `SoftReference` mode is intentionally not used — real caches like Guava/Caffeine
> default to size+time eviction over soft refs for the same reasons.)

---

## Per-reference policy (`@RefPolicy`)

The manager owns a default policy per type, but a single reference can override freshness right where
it's declared:

```java
@Data @NoArgsConstructor
public class Guild {
    private UUID id;
    private String name;

    @RefPolicy(ttlSeconds = 180)                       // this ref: 3-minute TTL
    private Ref<UUID, GuildBattleData> battleData;

    @RefPolicy(noCache = true)                         // this ref: always hit the backend
    private Ref<UUID, LiveScoreboard> scoreboard;
}
```

`ttlSeconds > 0` → TTL; `0` → `always()`; `< 0` → inherit the manager default; `noCache = true` → a
true bypass. The override only changes *this* reference's freshness verdict — the cached value stays
shared (one instance for everyone). A stricter reference may trigger a reload that refreshes the entry
for all consumers — never staler, possibly fresher.

> **Works with Lombok `@Data`.** `@RefPolicy` (like core's `@Indexed`) is read from the **field** —
> the deserializer falls back to reading the declared field by reflection when a Lombok-generated setter
> would otherwise hide it. So private fields + `@Data` are fully supported.

---

## Writing & deleting through the cache

The cache-aware write/delete pair keeps the cache and backend consistent — prefer them over
`repository().save/delete`, which touch the backend but leave a stale cache entry:

```java
guilds.saveAndCache(guild).join();     // persist + update the cell (memoized handles see it)
guilds.deleteAndEvict(id).join();      // delete + evict (a tombstone blocks racy in-flight reloads)
```

Both are stamp-ordered: a slower in-flight reload can neither clobber a newer `saveAndCache` nor
resurrect a newer `deleteAndEvict`. On an `OptimisticLockException`, `saveAndCache` auto-evicts the
stale entry so the next read reloads.

---

## Write-back

By default the cache is read-through + write-through (`saveAndCache` persists immediately). Some
workloads want the opposite: mutate the cached instance many times in memory, then flush the dirty
set in one batch (a periodic tick, a logout, a shutdown). Opt in **one of two ways** (pick one, never
both) - the same alternative split as `Versioned` vs `@OptimisticLock` for locking:

**1. Implement `IDirtyable`** - explicit three-method contract:

```java
public class Account implements IDirtyable {
    private UUID id;
    private long balance;

    @JsonIgnore private transient boolean dirty;   // the flag is never persisted

    public void deposit(long n) { balance += n; markDirty(); }

    public boolean isDirty() { return dirty; }
    public void markClean()  { dirty = false; }
    public void markDirty()  { dirty = true; }
}
```

**2. Annotate a `boolean` field with `@DirtyFlag`** - no interface; the manager reads/clears/re-sets
the field by reflection. Your own mutations set it `true`:

```java
public class Account {
    private UUID id;
    private long balance;

    @DirtyFlag @JsonIgnore
    private transient boolean dirty;               // the flag is never persisted

    public void deposit(long n) { balance += n; this.dirty = true; }
}
```

The field must be `boolean`/`Boolean`, non-static, non-final, at most one per entity (a `Boolean`
that is still `null` reads as not-dirty). Combining `@DirtyFlag` with `implements IDirtyable` throws.

A dirty-trackable value changes three things in its manager:

- **Dirty wins.** While `isDirty()` is `true` the cell is always served and **never reloaded over** -
  not by a TTL, not by `invalidate(...)`. An unflushed local change can't be lost to a backend reload,
  so a TTL policy is safe even on a mutable entity (once flushed and clean, the policy applies again).
- **`seedIfAbsent(key, value)`** caches a freshly created instance **without writing it** - a lazy
  default that becomes the canonical cached object (repeated reads return the same instance and
  accumulate mutations), persisted only when it is flushed.
- **`flushDirty()`** collects every dirty cell, persists them in one `saveAll`, and clears each flag.

```java
Account acc = accounts.seedIfAbsent(id, new Account(id, 0));  // cached, no I/O
acc.deposit(100);                                             // dirty; never reloaded over
// ...later, on a flush tick:
BatchSaveReport<UUID> report = accounts.flushDirty().join();  // one batch write, flags cleared
```

`flushDirty()` (and the lower-level `saveAllAndCache(values)`) return a **`BatchSaveReport`** of the
failures only - empty on a clean flush. A failed batch is retried entity by entity, so one bad record
never loses the rest:

- an **optimistic-lock conflict** evicts the stale cell (`report.conflictedKeys()`), so the next read
  reloads the winning version;
- any **other error** keeps the cell and re-marks it dirty (`report.erroredKeys()`), so the next flush
  retries it.

A flush is **at-least-once**: a value re-dirtied by another thread *during* the flush re-sets its own
flag and is picked up next time, never lost. Entities that are neither `IDirtyable` nor
`@DirtyFlag`-annotated keep the plain read-through / write-through behavior.

---

## Nested references

Nesting falls out for free: **each entity type has its own manager**, with its own policy/TTL. A
`Guild` held in memory does **not** drag its `GuildBattleData` in — it only holds the key.

```java
new GuildManager(storage, refRegistry).preloadAll().join();       // guilds resident (always())
refRegistry.manager(BATTLE, storage, CachePolicy.ttl(Duration.ofMinutes(3)));  // battle data lazy, 3-min TTL

Guild g = player.getGuild().peek().orElseThrow();                 // memory
g.getBattleData().resolve().thenAccept(opt -> render(opt));       // backend on a cold miss, then TTL'd
```

`preloadAll()` of guilds does not cascade into battle data; cycles are safe (resolution is lazy);
N+1 is opt-in per level (batch with `getAll(...)`).

---

## ⭐ One entity, many databases, many key types

This is the payoff. Because a `Ref` resolves through its type's manager — and a manager can be backed
by **any** `Storage` — a single root entity can fan out across **heterogeneous databases**, each
reference under its **own key type**, and the root neither knows nor cares.

```java
// A root profile in MariaDB, referencing five entities each in a DIFFERENT database & key type.
@Data @NoArgsConstructor @AllArgsConstructor
public class PlayerProfile {
    private UUID uuid;
    private Ref<String, Clan>           clan;      // String key  -> PostgreSQL
    private Ref<Long, Wallet>           wallet;    // Long key    -> MongoDB
    private Ref<Integer, Stats>         stats;     // Integer key -> H2
    private Ref<Settings.Key, Settings> settings;  // record key  -> LocalFile
    private Ref<Session.Id, Session>    session;   // record key  -> InMemory
}

// One registry for this context; one manager per type, each on its own backend:
RefRegistry refRegistry = new RefRegistry();
refRegistry.manager(PROFILES, mariadb,  CachePolicy.always());
refRegistry.manager(CLANS,    postgres, CachePolicy.always());
refRegistry.manager(WALLETS,  mongo,    CachePolicy.always());
refRegistry.manager(STATS,    h2,       CachePolicy.always());
refRegistry.manager(SETTINGS, localFile,CachePolicy.always());
refRegistry.manager(SESSIONS, inMemory, CachePolicy.always());
// (each descriptor's codec is refRegistry.codec(Type.class), so the root's refs bind to this registry)

// Load the root, then resolve each reference — every one hits a different database:
PlayerProfile p = profiles.resolve(profileId).join().orElseThrow();
String clanName  = p.getClan().resolve().join().get().getName();       // PostgreSQL
long   balance   = p.getWallet().resolve().join().get().getBalance();  // MongoDB
int    kills     = p.getStats().resolve().join().get().getKills();     // H2
```

The same `Ref`/`CachingManager` machinery handles `UUID`, `String`, `Long`, `Integer`, and even
composite/wrapper `record` keys — the key serializes inside the root's JSON and is recovered on read.
A working, end-to-end version of this (against all six real backends, plus an embedded-only variant
that runs without Docker) lives in
[`MultiBackendRefExampleTest`](src/test/java/br/com/finalcraft/everydatabase/manager/MultiBackendRefExampleTest.java).

> **Why this is nice:** your domain model expresses relationships naturally (`profile.getWallet()`),
> while *where* each piece is stored stays a deployment decision — wallets in Mongo, stats in H2,
> sessions in memory — changeable without touching a line of the entity or the call sites.

---

## Multiple registries — the same type, different resolvers

Because there is **no global registry**, two independent contexts (two plugins, two authors) can each
register a manager for the **same** entity type, backed by **different** storages, and never collide.
A `Ref` resolves only against the registry it was bound to (by the codec that read it), so the same
key resolves to different data in different registries:

```java
// Two registries, each with its own stores. Both register a Hero manager.
RefRegistry survival = new RefRegistry();
RefRegistry lobby    = new RefRegistry();
survival.manager(heroDesc(survival), survivalStore, CachePolicy.always());   // Hero -> survivalStore
lobby.manager(heroDesc(lobby),       lobbyStore,    CachePolicy.always());    // Hero -> lobbyStore

// SurvivalProfile read via survival.codec(...) -> its hero ref resolves in 'survival';
// LobbyProfile read via lobby.codec(...)       -> its hero ref resolves in 'lobby'.
survivalProfile.getHero().resolve();   // -> survivalStore's Hero
lobbyProfile.getHero().resolve();      // -> lobbyStore's Hero (same id, different entity)
```

This is impossible with a single global `Class → resolver` map (the second `Hero` manager would
overwrite the first). A full two-subsystem example — `SurvivalProfile`/`LobbyProfile` sharing the
`Hero` and `Wallet` types across separate registries and backends — lives in
[`MultiBackendRefExampleTest`](src/test/java/br/com/finalcraft/everydatabase/manager/MultiBackendRefExampleTest.java).

> **Tip:** for a single-plugin app, one `RefRegistry` is all you need — make it at startup and pass
> it to your codecs and managers. The point of dropping the global default is that you *can't* be
> surprised by another plugin sharing it.

---

## Parent registries

A registry can be created with a **parent**: `new RefRegistry(parent)`. Resolution then checks the
registry itself first and falls back up the parent chain - so a subsystem keeps a **private** registry
for its own entities while still resolving **shared** entities published in a common parent, with no
process-wide global map:

```java
RefRegistry global = new RefRegistry();             // shared, framework-owned
RefRegistry plugin = new RefRegistry(global);       // a plugin's own registry, child of global

global.manager(PLAYERS, storage, CachePolicy.always());   // shared: published in the parent
plugin.manager(JOBS,    storage, CachePolicy.always());   // private: local to the plugin

plugin.resolver(Jobs.class);     // found locally
plugin.resolver(Player.class);   // not local -> resolved via the parent (fallback)
```

A `Ref` bound to the child (by the child's codec) resolves through the same chain, so a plugin's
entity can reference a shared one published in the parent. To expose an entity to other subsystems,
register it in the shared parent; to keep it private, register it in your own registry. Registration is
always local - `register(...)`/`manager(...)` never touch the parent. `isRegistered` is local;
`isRegisteredInChain` walks the chain.

> Keep the chain shallow (one parent: yours -> shared). A single shared parent is the cross-subsystem
> bus; reach into another subsystem's registry directly only for the rare case it didn't publish what
> you need.

---

## Keeping caches fresh across instances (`CacheSync`)

A `CachingManager` only sees its **own** writes. When 5–20 application instances share one backend
and instance A updates an entity, instances B…N keep serving their stale cached copy until TTL. Wire
`CacheSync` to close that gap. It's the **single entry point** and hides *which* mechanism a backend
uses: it picks the backend-native **push** feed when available (Mongo/Postgres/InMemory) and falls
back to **polling** otherwise (MySQL/MariaDB). The same wiring works on any backend:

```java
RefRegistry registry = new RefRegistry();
CachingManager<UUID, Guild>  guilds  = registry.manager(GUILDS,  storage, CachePolicy.always());
CachingManager<UUID, Player> players = registry.manager(PLAYERS, storage, CachePolicy.ttl(Duration.ofSeconds(30)));

CacheSync sync = CacheSync.attach(storage)
        .pollEvery(Duration.ofSeconds(10))   // only used if `storage` can't push (MySQL/MariaDB)
        .bind(guilds)
        .bind(players)
        .start();
// ... on shutdown:
sync.close();
```

If `storage` can push, it subscribes and `pollEvery` is ignored; if it can't, it polls every
interval. With neither a feed nor a `pollEvery`, `start()` throws a clear error telling you to add
one. So you **don't need to know your backend** — switching MySQL → Mongo needs no code change, it
just gets faster.

A write through any instance now invalidates the same entity's cache in every other instance: the
cell is marked stale and the next `resolve` reloads authoritative state. The cell's monotonic stamp
makes this safe — a late invalidation can never clobber a newer local write nor resurrect a newer
delete.

**How each backend is served:**

| Backend | Mechanism | Notes |
|---|---|---|
| **MongoDB** | Change Streams (push) | resumable (survives a brief disconnect); needs a **replica set**. SAVE and DELETE both propagate (the entity key is the document `_id`). |
| **PostgreSQL** | `LISTEN/NOTIFY` (push) | zero extra infra; fire-and-forget → pair with a TTL policy |
| **InMemory** | local writes (push) | per-process reference implementation |
| **MySQL/MariaDB** | version polling (pull) | needs `.pollEvery(...)` |
| H2 · LocalFile | version polling (pull) | embedded/single-node; H2 polling detects deletes only |

### Managers on different backends — `CacheSync.auto()`

When your managers live on **different** backends, `auto()` routes each one by its own storage —
push where the backend supports it, poll where it doesn't:

```java
CacheSync sync = CacheSync.auto()
        .pollEvery(Duration.ofSeconds(10))   // fallback for the non-push managers
        .bind(guildsOnMongo)     // -> push (change stream)
        .bind(walletsOnMySql)    // -> poll (version polling)
        .start();
```

Notes (apply to both `attach` and `auto`):
- **Keys** are parsed back from their persisted `toString()` form — `UUID`/`Long`/`Integer`/`String`
  work out of the box; for a composite/`record` key pass a parser: `.bind(manager, str -> myKey(str))`.
- **Own writes** are skipped by default (this instance's cache was already updated write-through);
  call `.includeOwnOrigin()` for the rare several-caches-over-one-storage-in-one-process setup.
- Delivery is **at-least-once and best-effort** (Postgres may drop a notification, polling has a
  one-interval latency) — always keep a TTL or version-check as the safety net.
- **Polling detail:** detecting in-place *updates* needs a versioned entity (`@OptimisticLock`); a
  non-versioned descriptor (and H2) reports version `0`, so polling then catches only deletes.

The two underlying primitives stay available if you want explicit control: a raw
`ChangeFeedStorage.subscribe(...)` (push) and `PollingCacheSync.every(Duration).bind(...).start()`
(pull) — `CacheSync` just wires them for you. Spec: `specs/SPEC_cache_sync.md`.

---

## What it guarantees (and what it doesn't)

The cache hands out the **same instance** per key for its lifetime — an identity map. Publication is
atomic and stamp-ordered, so concurrent cold misses converge on one instance and an authoritative
`saveAndCache`/`deleteAndEvict` is never clobbered by a slower reload. But the value is shared mutable
state, and freshness is bounded:

| Hazard | Solved by optimistic lock? | What solves it |
|---|:---:|---|
| **Read staleness** (you don't see another process's update) | ❌ | TTL / `invalidate` |
| **Write staleness** (you save a stale copy over a newer one) | ✅ (versioned backends) | `OptimisticLockException` → auto-evict + reload |
| **Mutating a swapped-out value** | ❌ | mutate *through* `saveAndCache`, or single-writer discipline |

Cross-process writes are invisible to a local cache unless you wire **[`CacheSync`](#keeping-caches-fresh-across-instances-cachesync)**
(backend change feed → `invalidate`/`evict`); otherwise bound staleness with a TTL policy.

---

## Non-goals

No eager fetch, no lazy proxies, no hidden I/O, no implicit joins, no entity-graph mapping, and no
*automatic* dirty tracking - write-back is **opt-in** via `IDirtyable` or `@DirtyFlag`, off by
default. Resolution is
always explicit (`peek`/`resolve`) and batchable (`getAll`). The cache is opt-in; freshness is a knob
the caller owns.

<div align="center">

Part of [**EveryDatabase**](../README.md) · Made by [Petrus Pradella](https://petrus.dev)

</div>
