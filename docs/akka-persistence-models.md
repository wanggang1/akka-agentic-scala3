# Akka's two persistence models — and what actually backs them

*Platform architecture, not Scala interop.* Nothing here is language-specific; it applies
identically to the Java capabilities in this repo. Verified against **Akka SDK 3.6.3**
(`akka-runtime-core_2.13` 1.6.15).

## The two state models

Akka offers exactly **two** entity state models, and you pick one per entity by choosing a base
class. There is no third option and no per-entity storage configuration —
`concepts/state-model.html.md` is explicit: *"For each state model, Akka uses a specific back-end
data store, which cannot be configured."*

| | **Event Sourced Entity** | **Key Value Entity** |
|---|---|---|
| Base class | `EventSourcedEntity<State, Event>` | `KeyValueEntity<State>` |
| What you write | `effects().persist(event).thenReply(...)` **+** an `applyEvent` fold | `effects().updateState(newState).thenReply(...)` |
| State is | *derived* by replaying events | *stored* directly |
| History | full audit trail — replayable, and new projections can be built from the past | none at the API level |
| Views consume via | `@Consume.FromEventSourcedEntity` + `onEvent(Event)` | `@Consume.FromKeyValueEntity` + `onUpdate(State)` |
| Extra machinery | event types, sealed interface, `@TypeName`, schema evolution | none |
| Multi-region writes | replicated; CRDT semantics available for write-anywhere | replicated, read-anywhere / write-at-origin (LWW is *planned*, not shipped) |
| Descriptor key | `event-sourced-entity` | `key-value-entity` |

Both are sharded by Akka Cluster Sharding: exactly one live instance per id across the cluster,
commands handled **sequentially**, state cached in memory and recovered from durable storage after
passivation, rebalance, rolling upgrade, or crash. That part is identical between the two.

**Choosing:** take the Event Sourced model when the *sequence of changes* is itself valuable — audit,
debugging, temporal queries, or projections you have not written yet (you can replay history into a
new View). Take Key Value when only the latest value matters and you would rather not maintain an
event vocabulary. Key Value is the cheaper default; Event Sourced is the one you cannot retrofit,
because the history you did not record does not exist.

## What backs them (the finding)

**Both are backed by the same Akka Persistence event journal.** The Key Value Entity is *not*
implemented on Akka Persistence's durable-state store, as its name and the conceptual docs both
suggest. From the runtime's own configuration (`akka-runtime-core_2.13-1.6.15.jar`,
`reference.conf`, the `akka.runtime.value-entity.cleanup` block):

> Key Value Entity is implemented with event sourcing where each event contains the current state.
> Older events are cleaned up by a background process that implements the
> `akka.runtime.spi.KeyValueEntityCleanupStore` interface.

So the layering is:

| Layer | Key Value Entity | Event Sourced Entity |
|---|---|---|
| SDK API | state-based | event-based |
| Runtime encoding | **one event carrying the whole state** per update; older events GC'd (`cleanup.older-than = 24 hours`, one slice at a time) | events as authored, plus snapshots (`snapshot-every = 100`) |
| Persistence plugin | `akka.persistence.r2dbc.journal` | `akka.persistence.r2dbc.journal` |
| Database | H2 locally, PostgreSQL deployed | same |

Two checks that rule out the obvious alternative:

- `akka-runtime-core` contains **no** `DurableState*` classes. Akka Persistence R2DBC *ships* a
  durable-state store (`PostgresDurableStateDao`, `H2DurableStateDao`, `R2dbcDurableStateStore` are
  all in `akka-persistence-r2dbc_2.13`); the SDK runtime simply does not use it for entities.
- None of the runtime configs (`runtime.conf`, `h2-memory.conf`, `postgres-dev-mode.conf`) set a
  `durable-state-store` plugin — only `akka.persistence.journal.plugin` and
  `akka.persistence.snapshot-store.plugin`, both pointed at `akka.persistence.r2dbc.*`.

A corroborating detail: the `value-entity` config block has **no snapshot setting**, while
`eventsourced-entity` has `snapshot-every = 100`. It doesn't need one — every event *is* a complete
snapshot, so recovery reads the latest event with no fold to apply.

### The storage stack

```
KeyValueEntity / EventSourcedEntity / Workflow   <- SDK component API
        Akka Persistence (event journal)          <- akka.persistence.r2dbc.journal
        Akka Persistence R2DBC                    <- akka-persistence-r2dbc_2.13 1.3.x
        H2 (local)  |  PostgreSQL (deployed)      <- not configurable per entity
```

Locally the journal is **in-memory H2** by default; `-Dakka.javasdk.dev-mode.persistence.enabled=true`
switches it to on-disk H2 (`db.mv.db`) — that is the flag the cap-3 and cap-5 restart-durability notes
in the README use. The file location is overridable with
`-Dakka.persistence.r2dbc.connection-factory.database=./target/my-db`.

### A doc nuance worth knowing

`concepts/state-model.html.md` describes Key Value entities as storing "their entire state as a
single entry in a Key/Value store", and says each state model uses "a specific back-end data store".
That reads as two different stores. The runtime config says it is one journal with two encodings.
Both statements are defensible — the *effective semantics* are key/value — but if you are reasoning
about storage cost, retention, or replication, the journal is the thing that's actually there.

## Why this matters in practice

- **State-change subscription is not a bolted-on extra.** A View over a Key Value Entity
  (`@Consume.FromKeyValueEntity` + `onUpdate(State)`) is a projection over a real event stream where
  each event happens to be a full state. That is exactly how capability 11's `TodoSummaryView`
  projects capability 6's `TodoEntity`.
- **The history exists, but it is not yours.** Key Value events are an implementation detail with a
  24-hour retention and an active cleanup process. "No history" remains the correct mental model at
  the API level — do not plan to mine it, and do not choose Key Value expecting to recover an audit
  trail later.
- **Write amplification is by whole state.** Every Key Value update writes the entire state, so a
  large state mutated often costs more than a small event describing the same change. Both models cap
  a single stored record at `max-stored-size = 10M`.

## Everything else durable in this repo, and which model it uses

| Thing | Model | Ours or the runtime's? |
|---|---|---|
| `TodoEntity` (cap-6 to-do lists) | Key Value | ours — descriptor key `key-value-entity` |
| `SessionMemoryEntity` (cap-4 / cap-6 chat history) | **Event Sourced** | the runtime's — deliberately absent from our descriptor |
| `GreetingWorkflow` (cap-2) | Workflow — event sourced internally, each step recorded | ours |
| Autonomous-agent **tasks** (caps 3, 5, 7) | runtime-persisted task status + typed result, plus agent process state | the runtime's — no `persist(...)` anywhere in our code |
| `TodoSummaryView` (cap-11) | not an entity — a projection, rebuilt from the source entity's stream | ours — descriptor key `view` |

This repo has **never written an Event Sourced Entity of its own**. The one Event Sourced Entity we
interact with is the SDK's `SessionMemoryEntity`, and only in a Java test that reads its history —
see README "Scala interop notes" §6.

## Sources

- `akka-context/concepts/state-model.html.md` — the two state models, sharding, replication, origin
- `akka-context/sdk/key-value-entities.html.md`, `akka-context/sdk/event-sourced-entities.html.md`
- `akka-context/sdk/running-locally.html.md` §"Running a service with persistence enabled"
- `reference.conf` inside `akka-runtime-core_2.13-1.6.15.jar` — `akka.runtime.value-entity`,
  `akka.runtime.eventsourced-entity`, `akka.runtime.workflow-entity`; and `h2-memory.conf` /
  `runtime.conf` for the plugin wiring
