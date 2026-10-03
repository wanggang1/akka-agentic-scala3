# Research: Event-sourced wallet (capability 18 / A3)

**Feature**: `020-event-sourced-wallet` · **Date**: 2026-10-03 · **SDK**: 3.6.3 · **Scala**: 3.3.8

Every answer below was **measured**, not read. The Phase 0 probes lived under
`com.gwgs.akkaagentic.wallet.probe` and are preserved as FR-014 evidence in the **Phase-0 commit**
(`ed6e2f2`). They were **retired from the working tree when the production entity landed** — not as
cleanup but because they could not coexist with it: `@TypeName` values are **service-global**, so the
probe's `WalletEvent.Opened` and the production one both claiming `opened` collided at startup
(`IllegalStateException: Collision with existing mapping ... -> opened. The same type name can't be used
for other class`). That collision is itself a finding (see Q-F). The probes were:

- `WalletEntity.scala` — the probe entity (Scala), with `Wallet` state (Scala case class).
- `WalletEvent.java` — the event hierarchy's final working form (Java sealed interface).
- `WalletSerializationProbeTest.scala` — drove the SDK's internal mapper directly (Q-B, Q-C).
- `WalletProbeTest.scala` — pinned the unit-testkit wall from Scala (Q-E).
- `WalletJavaUnitProbeTest.java` — the Java control: a method reference drove the same Scala entity.
- `WalletProbeIntegrationTest.java` — booted the whole service over the real journal (startup, Q-D).

---

## Q-A — Can the event hierarchy be a Scala 3 `enum`? **NO — and the failure is specific.**

A Scala 3 `enum WalletEvent` with `@TypeName` on each case **compiles** as the `E` of
`EventSourcedEntity[Wallet, WalletEvent]`, and the **write path works**: the internal mapper serialized
`Opened` to content type `json.akka.io/opened`, so `@TypeName` is honoured on an enum case. The failure is
on **read**:

```text
JSON with contentType [json.akka.io/opened] could not be decoded into a [WalletEvent$Opened]:
Cannot construct instance of `WalletEvent$Opened`, problem: Cannot reflectively create enum objects
```

Jackson sees an enum case as an enum constant and refuses to instantiate it from a constructor. So an
`enum` is a one-way type here — it can be persisted and never read back. That makes it unusable as an event
hierarchy, which only has value if it round-trips.

## Q-B — Does a Scala 3 `sealed trait` of case classes work? **It round-trips, but FAILS STARTUP VALIDATION.**

Two separate gates, and the sealed trait passes the first and fails the second:

| Gate | Result |
|---|---|
| Internal mapper round-trip (`JsonSerializer.toBytes`/`fromBytes`) | **PASSES** — all four case classes serialize and deserialize, `@TypeName` resolves the concrete case polymorphically, and **no `@JsonCreator`/`@JsonProperty` was needed** for flat primitive fields. |
| SDK startup validation | **FAILS**: `On 'WalletEntity': The event type of an EventSourcedEntity is required to be a sealed interface.` |

The reason is in the bytecode. A Scala 3 `sealed trait` compiles to a JVM `interface`, but **not a
JVM-`sealed` one**:

```text
classOf[WalletEvent].isInterface         = true
classOf[WalletEvent].isSealed            = false
classOf[WalletEvent].getPermittedSubclasses = null
```

Scala 3.3.8 does not emit the JVM `PermittedSubclasses` attribute for a `sealed trait`, so the SDK's
validation — which requires a true sealed interface — rejects it at boot. **The serializer and the
validator disagree about what "sealed" means, and the validator is the one that gates startup.**

This is the correction that matters most: the project's prior shorthand ("Scala sum types can't cross the
internal mapper") was **too coarse**. The sealed trait *does* cross the mapper. It is a *second*,
independent SDK gate — startup validation — that forces the events to Java.

## Decision — events are a **Java `sealed interface` of records**; state and entity stay Scala

A Java `sealed interface` is a genuine JVM-sealed interface, so it passes validation; its records
(de)serialize exactly as the Scala case classes did. Measured working end to end:

- the service **boots** with the Scala `WalletEntity` registered under the new `event-sourced-entity`
  descriptor key, referencing the Java `WalletEvent`;
- commands persist and the balance folds correctly over the real journal (125 = 100 + 50 − 30 + 5);
- `snapshot-every = 2` forces a snapshot write during the run with no error.

The **entity** is Scala and the **`Wallet` state** is an idiomatic Scala case class (Q-C). So the Java
footprint of this capability is exactly one file: the event hierarchy.

## Q-C — Does Scala-authored snapshot STATE round-trip? **YES.**

Measured passivation-free through the same internal `JsonSerializer` the testkit uses: `Wallet(120, true)`
→ `toBytes` → `fromBytes(classOf[Wallet])` returns an equal value. A plain Scala 3 case class round-trips in
**both** directions with no Jackson annotations. The state type has **no** sealed requirement (that gate is
only on the event type), so nothing forces the state to Java. Snapshots of Scala state are safe.

## Q-D — Is the entity command client method-reference-only? **YES — the caller is Java.**

Confirmed as capability 6 found: `componentClient.forEventSourcedEntity(id)` exposes only
`.method(EntityClass::cmd)` with no `dynamicCall`. The integration test drives the Scala entity with Java
method references (`WalletEntity::open`, `::deposit`, …) and works; a Scala lambda cannot resolve the
client. So the production endpoint is Java — the same shape as capability 11's View caller and
capability 14's stream caller.

A smaller, related measurement: `getBalance(): ReadOnlyEffect[Long]` returns a Scala `Long`, and a Java
caller's method reference infers the reply type as **`Object`**, not `long` — the reply must be read as
`((Number) reply).longValue()`. Scala `Long` at the `Effect[R]` type position erases to `Object` for the
Java client.

## Q-E — Can the entity be unit-tested from Scala? **The unit testkit is on the wall; drive it from Java.**

`EventSourcedTestKit.of(Function[Context, ES])` is a plain SAM, so **constructing** the testkit from a Scala
lambda works. But `.method(...)` resolves the command's return type via
`akka.javasdk.impl.reflection.Reflect.getReturnType`, which casts `method.getGenericReturnType` to
`ParameterizedType` to extract `R` from `Effect[R]`:

- a **Java method reference** resolves to the real command method → return type is the parameterized
  `Effect[Done]` → works;
- a **Scala lambda** resolves to a synthetic method whose return type is erased to a raw `Class` →
  `ClassCastException: class java.lang.Class cannot be cast to ... ParameterizedType`.

So the method-reference wall reaches the *unit* testkit, not only the component client. Consequence for the
real capability: the **domain rules are unit-tested in pure Scala** (no SDK types — FR-009), and the
**entity is exercised by the Java integration test**. A green Scala test (`WalletProbeTest`) pins the wall
so a future reader does not rediscover it.

## Q-F — `@TypeName` is service-global (measured when the probe met production)

Registering the production `wallet.application.WalletEntity` **alongside** the still-present probe entity
failed to boot:

```text
IllegalStateException: Collision with existing mapping class wallet.domain.WalletEvent$Opened -> opened.
The same type name can't be used for other class class wallet.probe.WalletEvent$Opened
```

So a `@TypeName` is unique across the **whole service**, not per entity — the SDK builds one global
type-name → class registry (the ESE doc's recommendation "use logical names unique per Akka service" is in
fact enforced). Two event hierarchies cannot share a logical name even if they belong to different
entities. Consequence here: the probe had to be **retired the moment the production entity was registered**,
which pulled task T018 forward into US1. A design consequence for anyone adding a second entity later:
choose type names that are unique service-wide (e.g. prefix by aggregate) rather than per-entity.

## Bonus — `snapshot-every` is service-global, like `@TypeName`

`akka.javasdk.event-sourced-entity.snapshot-every` has **no per-entity form** — it is one service-wide
knob. So a capability cannot set its own entity's snapshot cadence without also changing every other
entity's, including the runtime-owned `SessionMemoryEntity` that capabilities 4/6/17 depend on. The wallet
therefore keeps the SDK default (100) in production and exercises snapshotting with a per-test override
(`WalletSnapshotIntegrationTest` sets it to 2). Two service-global registries in one capability
(`@TypeName`, Q-F; and this), both of which constrain how a *second* entity would be added later.

## Bonus — build ordering did not break

A3's stated secondary risk was the scalac-then-javac arrangement (§13 R3, broken twice before). Here the
Scala `WalletEntity` references a **same-package** Java `WalletEvent`, and `mvn clean verify` compiled both
with no ordering error. One data point, in this direction: a Scala→Java reference within one package is
fine. (gRPC's generated sources — candidate A4 — remain the untested, higher-risk case.)

## Consolidated decisions

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | **Events are a Java `sealed interface` of records**, one file | Q-A (enum unreadable), Q-B (sealed trait fails startup validation: `isSealed == false`) | Scala `enum` (write-only); Scala `sealed trait` (boots-fails); hand-emitting JVM sealed from Scala (no 3.3.8 flag does it) |
| D2 | **State is a Scala `case class`** | Q-C: round-trips both ways through the internal mapper, no annotations, no sealed gate | Java-shaped state (unnecessary — nothing forces it) |
| D3 | **Entity is Scala** | Compiles and runs; the only Java it needs is the event type it references | Java entity (concedes more than measured) |
| D4 | **Endpoint / caller is Java** | Q-D: `forEventSourcedEntity` client is method-reference-only, no `dynamicCall` | Scala endpoint (cannot resolve the client) |
| D5 | **Domain rules in pure Scala, unit-tested there; entity tested by Java integration** | Q-E: the unit testkit needs a Java method reference; FR-009 wants rules SDK-free anyway | Entity unit tests in Scala (hit the wall) |
| D6 | **Reply types avoid bare Scala `Long` where a Java caller reads them** — or the caller casts via `Number` | Q-D bonus: Scala `Long` erases to `Object` for the Java method-ref client | Ignoring it (NPE/cast surprises at the boundary) |

## What remains unverified

1. **Snapshot read-back on a real passivation/reload.** Q-C proves the state (de)serializes through the
   mapper directly, and the integration test proves the snapshot *writes*; the testkit exposes no
   passivation hook, so a genuine reload-from-snapshot is not exercised here. The serializer round-trip is
   the evidence that it would succeed; the reload itself is noted as not directly observed.
2. **Whether a Java `sealed interface` could permit *Scala* case classes** (shrinking the Java footprint
   further). Not pursued — the all-Java event file is the repo's existing pattern and removes the question.
