# Research: Reacting to activity (capability 16 — Consumer)

**Feature**: `018-event-consumer` · **Date**: 2026-09-19 · **SDK**: 3.6.3

Every answer below was **measured**. The probes are kept as evidence (FR-013):
`feed/probe/TodoProbeConsumer`, `TodoTopicProbeConsumer`, `TaskProbeConsumer` (all Scala), exercised by
`ConsumerProbeIntegrationTest` (Scala, the TestKit's **mocked** path) and
`ConsumerRealPathProbeIntegrationTest` (Java, the **real** projection path — Java only because writing
to capability 6's `TodoEntity` needs a method reference), plus two live runs of the service.

## Summary

| | Answer |
|---|---|
| **Q-A** authoring | **Scala-clean.** A plain top-level Scala class loads and receives; no bytecode-shape constraint |
| **Q-B** input | Capability 6's Java `TodoList`, the **whole list** — a consumer must derive *what changed* itself |
| **Q-C** publishing | **Scala-clean**, and the payload goes through the **Scala-aware** mapper. But one topic-producing consumer stops the **whole service** starting locally unless eventing support is configured |
| **Q-D** failure | Redelivery is **unbounded** (exponential, ~2×), and one failing message **blocks every other entity**. A handler that gives up and returns `done()` unblocks it within 1 ms |
| **Q-E** duplicates & restart | Duplicates are delivered; `ce-id` **changes per delivery**. After a restart the consumer **resumes** from its stored position — nothing is replayed |
| **Q-F** TestKit | The mocked channel does **not** model redelivery: a failing message is dropped and messages around it are lost |
| **Q-G** runtime-owned tasks | **Reachable** — a Scala consumer receives the SDK's own `TaskEntity` events. **But useless for fork B3**: capability 7's delegation to request-based specialists creates no tasks |

---

## Q-A — Can a Consumer be authored in Scala? **Yes, with no shape constraint.**

```text
Q-A >>> received: com.gwgs.akkaagentic.a2a.domain.TodoList: [1:buy milk:false] next=2
Q-A >>> delete: delete/deleted
```

`@Component` + `@Consume.FromKeyValueEntity(classOf[TodoEntity])` on a **plain top-level Scala class**;
the `@DeleteHandler` fires on `publishDelete`. Capability 11's View had to move its updater into a
companion object because the SDK constructs it reflectively with a zero-arg constructor; a Consumer is a
top-level component and needs nothing of the kind. The SDK surface confirms why nothing bites: `Consumer`
exposes `messageContext()`, `effects()` and `timers()`; the effect builder is `done/ignore/produce/…` —
**no method reference anywhere**. Descriptor key: `consumer`.

### Q-A addendum — how a handler is selected, and how the two ways of getting it wrong differ

Measured 2026-09-25, after a review question. A handler is chosen by its **parameter type**: `Consumer`
declares no abstract method, and `ReflectiveConsumerRouter` holds a `Map[payload type, MethodInvoker]`. The
method name is free (the SDK's own examples use `onChange` and `onEvent`), and a source with several payload
types may declare one handler per type. `@DeleteHandler` exists because a deletion carries no payload, so
there is nothing to match on.

| Mistake | Compiles? | Runtime |
|---|---|---|
| two handlers taking the same type | yes | **refuses to start**: `Duplicated update methods [onUpdate, onUpdateTwin] for state subscription are not allowed` / `Ambiguous handlers for …TodoList` |
| handler takes the **wrong** type | yes | **starts normally, and is never called** — nothing is logged or reported |

The second is the dangerous one, and it is why this capability's behaviour is asserted over HTTP rather than
by unit-testing the consumer class: with a wrong parameter type the integration tests time out, which is the
only signal there is.

## Q-B — What arrives, and through which mapper? **Capability 6's Java `TodoList`, whole.**

The handler receives `com.gwgs.akkaagentic.a2a.domain.TodoList` — the entire list, `(todos, nextId)`. A
key-value source delivers **state**, not change events, so the reaction must compute the change by
comparing against the last state it saw for that user. Metadata on every delivery:

```text
Q-B >>> metadata keys on a delivery: Content-Type, ce-datacontenttype, ce-id, ce-source, ce-specversion, ce-subject, ce-type, traceparent
```

`ce-subject` is the entity id (the username). There is no sequence number or version.

**The Scala-authored-event question is not reachable here**, as expected. Both sources this capability
reads carry Java-authored types (capability 6's `TodoList`, the SDK's `TaskEvent`). Whether a Scala
`enum`/sealed trait survives the internal mapper needs an event-sourced entity **authored in Scala** —
candidate A3. Recorded, not faked.

## Q-C — Publishing from Scala. **Clean, idiomatic — and a startup trap.**

```text
Q-C >>> produced payload: {"username":"bob","total":2,"firstDescription":"buy milk"}
Q-C >>> produced metadata: Content-Type=application/json, ce-datacontenttype=application/json, ce-id=…,
        ce-source=todo-topic-probe-consumer, ce-specversion=1.0, ce-subject=bob, ce-time=…, ce-type=com…
```

The produced type was **deliberately** an idiomatic case class with an `Option` field — the shape the
internal mapper rejects ("Cannot construct instance of `scala.Option`", README §3). It serialized cleanly,
with the `Option` unwrapped to its value. So **published messages go through the Scala-aware mapper**: an
outward payload can be idiomatic Scala, like an HTTP body and unlike a component payload. `ce-subject` was
set explicitly (the docs require it for per-entity ordering on a broker).

**The trap, found only outside the TestKit.** Starting the real service with the probe registered:

```text
ERROR akka.javasdk.ServiceLog - Error reported from Akka runtime: AK-00406 Component
[todo-topic-probe-consumer] has declared a message destination topic [todo-activity-probe],
but no topic support is configured.
kalix.runtime.InvalidServiceException
```

Local dev mode defaults to `eventing.support = "none"`, and one producing consumer then makes **the whole
service** refuse to start — every capability's `exec:java` breaks. The TestKit mocks topics, so the suite
never shows it. `akka.javasdk.dev-mode.eventing.support = "logging"` fixes it (measured: plain
`mvn compile exec:java` up in 8 s, no AK-00406) and logs each produced message. A deployed service needs a
real broker configured in the Akka project; that is untested here and claimed neither way.

## Q-D — The failure contract. **Unbounded, and it blocks everyone.** Measured on the real path.

**The mocked path could not answer this** (Q-F), so it was measured by writing to the real `TodoEntity`.
A handler throwing for one user, sampled every 5 s:

```text
Q-D real >>> 5s: poison attempts=9  bystanderSeen=false
Q-D real >>> 10s: poison attempts=11 bystanderSeen=false
Q-D real >>> 15s: poison attempts=13 bystanderSeen=false
Q-D real >>> 30s: poison attempts=15 bystanderSeen=false
Q-D real >>> redelivery schedule of the first failing state (ms): 0, 277, 789, 1719, 3419, 6985, 13976, 27611
```

- **Unbounded.** Exponential backoff, roughly doubling, still retrying at 27.6 s — the documented
  "redelivered until the application processes it without failures", now with its schedule.
- **Head-of-line across entities.** `bystanderSeen=false` throughout: a change for a **different user**,
  written after the failure began, was held back for the whole 30 s and arrived only once the failing
  message was cured. One poison message stalls the stream for everyone.
- **Replay from the last committed position.** Each round re-sent every uncommitted message, in order —
  here both states of the failing user.
- **No attempt number.** `MessageContext` carries `eventSubject`, `tracing` and `metadata` only — capability
  15's `TimedAction` gap again.

**Giving up unblocks it.** A handler that counts its own attempts and returns `done()` on the third:

```text
Q-D(c) real >>> giveUpAfter=3: poison deliveries: +0ms … | +291ms … | +759ms …
Q-D(c) real >>> bystander arrived 1 ms after the poison's final (give-up) delivery
```

A first run of this measurement was **inconclusive** and is not reported as a result: its bystander was
written in the same instant as the failing message and slipped through in the same batch, before any
redelivery. The run above first drives the stream into its failure loop, then writes the bystander.

## Q-E — Duplicates and restart.

**Duplicates are delivered, and nothing identifies them.** The same state published twice arrived twice
(`deliveries: 2`). On the real path, **`ce-id` differed on every redelivery** of the same message
(`4e9df810…`, `cadbe113…`, `d6e80086…`), so it cannot key idempotence or an attempt count. With no version
on a key-value state either, the only stable identity of "this delivery" is **(user, content of the
state)**.

**Restart: the consumer resumes; nothing is replayed.** Live, `persistence.enabled=true`, source = the
SDK's `TaskEntity` (writable without a model through capability 5's `POST /approvals`):

| Step | Time |
|---|---|
| `POST /approvals` → 3 × `TaskCreated`, 2 × `TaskAssigned`, `TaskStarted` delivered | 11:06:54 |
| 10 s persistence window, then kill (`db.mv.db` present) | 11:07:13 |
| restarted on the same store, healthy | 11:07:24 |
| 30 s later: `TaskCreated` for that case re-delivered | **0 times** |
| a *new* event for that case (`TaskFailed`, the draft agent out of iterations — no model) | delivered |

So the consumer's stored position survives a restart and it continues from there. **Consequence for any
in-process state a consumer keeps: a restart empties it, and the consumer will not resend what it already
delivered — that history is gone, silently, unless the design says so.** The same mechanism underlies a
key-value source; it was measured on the event-sourced task source because that one is writable without a
model.

## Q-F — What the TestKit's mocked channel proves. **Delivery and publishing — not failure.**

On the mocked path a failing message was **never redelivered** (`attempts=2`, and the two were two
*different* messages at 0 ms and 126 ms), a message for another user published during the failure **never
arrived**, and curing the failure brought nothing back. The real projection redelivers with backoff and
holds the bystander until it can deliver it. So:

| Behaviour | Mocked incoming | Real projection |
|---|---|---|
| delivery, input type, metadata, delete | ✅ faithful | ✅ |
| publishing (outgoing mock) | ✅ observable | needs `eventing.support` |
| redelivery of a failing message | ❌ none | exponential, unbounded |
| messages behind a failure | ❌ **lost** | held, then delivered |

**A test of failure handling on the mocked path would be a false green.** Failure behaviour is tested on
the real path. Suite cost of the probes: mocked 90 s, real 66 s — both dominated by deliberate 30 s
sampling loops, to be trimmed at implementation with the measurements cited here.

## Q-G — Can a Scala consumer read the runtime-owned task entity? **Yes.**

`akka.javasdk.agent.task.TaskEntity` is a public `final` class in the SDK jar, extending
`EventSourcedEntity[TaskState, TaskEvent]`, component id `akka-task`. Offline, creating a task with no agent
(capability 5's `APPROVAL` definition), assigning it and failing it:

```text
Q-G >>> task events reached the Scala consumer? true -> TaskCreated(Approval) |
        TaskAssigned(Approval, assignee=reviewer) | TaskFailed(Approval, probe: rejected on purpose)
```

Live, capability 5's autonomous agents showed `assignee` is the **agent instance id**
(`<caseId>-draft-agent`). This extends capability 13's finding: `dynamicCall` reaches runtime-owned
*agents*, and now a consumer reaches a runtime-owned *event stream*.

**Its value for fork B3, measured live (Ollama `qwen3:8b`, 2026-09-19): none, for capability 7 as built.**
One `POST /activities` ran to completion in ~55 s and the model reported
`consultedSpecialists: ["weather-specialist","activity-specialist"]`. Every task event the consumer saw in
that run:

```text
12:05:27.986 [d807e51f-…] TaskCreated(Suggest)
12:05:27.989 [d807e51f-…] TaskAssigned(Suggest, assignee=8a74c6ab-2c09-403b-82c6-347e318cc7ab)
12:05:27.990 [d807e51f-…] TaskStarted(Suggest)
12:06:16.505 [d807e51f-…] TaskCompleted(Suggest)
distinct task ids seen: 1
```

**Delegating to request-based specialists creates no task entities.** The only task is the coordinator's
own, and its `assignee` is the coordinator's instance id. So the task stream carries no record of *which*
specialists ran — the one thing B3 needed — and capability 7's `consultedSpecialists` remains the model's
self-report. (Autonomous-agent delegates, which run tasks of their own, would presumably appear; capability
7 does not use them, and that is untested here.)

---

## Consolidated decisions

| # | Decision | Rationale | Rejected |
|---|---|---|---|
| D1 | The consumer is a **plain Scala class**; **no Java in production** | Q-A/Q-C: nothing in the family takes a method reference | — |
| D2 | Source = capability 6's `TodoEntity`; the reaction **derives changes by comparing states** | Q-B: a key-value source delivers whole state | Consuming change events (none exist for this source) |
| D3 | Every delivery runs under a **self-counted attempt bound** (`feed.max-attempts`), then is **set aside and recorded**, returning `done()` | Q-D: unbounded redelivery that blocks all users; giving up unblocks in 1 ms | Relying on the platform (unbounded); throwing (stalls everyone) |
| D4 | Attempts and duplicates are keyed by **(user, fingerprint of the state)** | Q-E: no attempt number, `ce-id` changes per delivery, no version | `ce-id` (unstable) |
| D5 | Idempotence: a state identical to the **last applied** state for that user produces **no entry** | Q-E: duplicates are delivered; an identical state has an empty diff | Remembering every past state (then a genuine return to an earlier state would be swallowed) |
| D6 | Publish each change's entries as **one idiomatic message** to topic `todo-activity`, `ce-subject` = username; nothing for an empty diff or a set-aside | Q-C: Scala-aware mapper; `ce-subject` for per-user ordering | One message per entry (a `produce` is one payload per delivery) |
| D7 | `akka.javasdk.dev-mode.eventing.support = "logging"` in `application.conf` | Q-C: otherwise the whole service fails to start locally | Dropping publication (loses half the family's finding) |
| D8 | The feed is **in-process and says so**: every response carries `since` (when this process started) | Q-E: the consumer resumes, so an in-process feed loses pre-restart history; stating the window makes it honest rather than silent | A durable feed — needs an entity, whose client is method-reference-only (Java), and makes a sandbox feed a second capability |
| D9 | Bounded retention: newest **500** entries and **100** set-asides; per-user last-state for at most **1000** users, an evicted user's next change recorded as a `resync` entry | FR-009, capability 15's lesson | Unbounded (capability 15's shipped limit) |
| D10 | Failure behaviour is tested on the **real** path; the mocked path is used only for delivery shape and publishing | Q-F: the mock drops failing messages — a false green | Testing failure on the mock |

## What remains unverified

1. **Whether committed messages are ever replayed** after a failure elsewhere in a batch. If they are, D5
   may surface a reversal pair (an older state after a newer one). FR-004 still holds — the latest entry
   reflects the latest state — but exactly-once display is not achievable from a source with no version,
   and the docs say so.
2. **Head-of-line scope in production.** Measured in local dev mode; a deployed service partitions a
   projection into slices, which may confine blocking to a subset of entities. Claimed neither way.
3. **A real broker.** Publication is proven against the TestKit's channel and the `logging` sink.
