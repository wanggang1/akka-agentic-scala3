# Concepts: AutonomousAgent, Task, and the three-task sequence (capability 5)

This document explains the two primitives capability 5 is built on — the **AutonomousAgent** and the
**Task** — and then walks the **exact lifecycle of all three tasks** in the approval gate: what triggers
each initial state, when an AutonomousAgent is involved, and when the *runtime* changes a task's state.
`quickstart.md` shows how to build, run and exercise the capability; `research.md` records the
Scala-interop findings. This one answers the "what is it, and how does it actually move" that neither of
those sets out directly.

It is grounded in this module's real code:
`DraftAgent` / `PublishAgent` / `ApprovalTasks` (`application/`), `ApprovalEndpoint` (`api/`), and the
pure state machine `ApprovalCase` / `TaskOutcome` / `CaseProgress` (`domain/`).

---

## What is an AutonomousAgent?

An `AutonomousAgent` is a **model-driven component that runs as a durable process** — a different base
class from the request-based `Agent` of capability 1.

- A **request-based `Agent`** has one command handler. You *call* it, it runs one model turn (maybe with
  tools), it replies, and the caller consumes that reply synchronously.
- An **`AutonomousAgent`** has **no command handler at all** — only a `definition()` method. It is a
  *process*: the runtime hands it work, it loops (up to `maxIterationsPerTask`) until the model decides
  it is finished, then it stops. **You never call it and you never get a return value back from it.**

In capability 5 there are two, both deliberately minimal — the point of the capability is the *gate*,
not the drafting:

```scala
class DraftAgent extends AutonomousAgent:
  override def definition(): AgentDefinition =
    define()
      .instructions(DraftAgent.Instructions)                                  // tone / rules for the model
      .capability(TaskAcceptance.of(ApprovalTasks.DRAFT).maxIterationsPerTask(3))  // what it accepts + loop bound
```

That declaration is the agent's whole behavior. It declares:

1. **Purpose** — `@Component(description = "...")`, injected into the model's system message.
2. **Instructions** — procedural guidance (tone, how to complete). *Not* orchestration; the loop belongs
   to the runtime.
3. **Accepted task type(s)** — `TaskAcceptance.of(ApprovalTasks.DRAFT)`, with an iteration bound so a
   task always terminates.

The agent class is **stateless** and holds nothing. Its output lands on the *task*, not on the agent.

## What is a Task?

A `Task` is the **unit of work and its durable, typed record** — a separate persistent entity with its
own identity, result schema and lifecycle, independent of any agent. It is a plain constant, **not** an
Akka component (so it is not listed in the component descriptor):

```scala
object ApprovalTasks:
  val DRAFT:    Task[Draft]            = Task.name("Draft")...resultConformsTo(classOf[Draft])
  val APPROVAL: Task[ApprovalDecision] = Task.name("Approval")...resultConformsTo(classOf[ApprovalDecision])
  val PUBLISH:  Task[PublishedReply]   = Task.name("Publish")...resultConformsTo(classOf[PublishedReply])
```

`resultConformsTo(...)` binds the typed result the model must produce. Per-case context is added at call
time with `.instructions(...)`; ordering is wired with `.dependsOn(...)`.

## How they work together

**The agent is the capability that produces a result; the task is where the result lands and lives.** The
two are decoupled on purpose, which is what makes the gate possible:

- You **create** tasks, **assign** them to agents, and later **poll** them by id. That is the entire
  surface your code touches.
- When a task is assigned to an agent **and its dependencies are met**, the *runtime* starts the agent in
  the background, feeds it the task (description + instructions + result schema), runs the model loop,
  and — when the model emits a conforming result — writes that result onto the task and flips it to
  `COMPLETED`. **Your code makes no call for any of this.**
- A task assigned to **no agent** is never auto-run. It sits at `PENDING` until something explicitly
  completes or fails it. In capability 5 that "something" is a **human**, and that unassigned task *is*
  the approval gate.

Dependencies gate **ordering, not data**: `dependsOn` controls when a task may start, but the draft text
is not automatically handed to the publish agent (a deliberate non-goal — `research.md` R5).

> **The symmetry that makes the gate work.** For an agent-backed task, the *runtime* calls `complete`
> implicitly from the model's output. For the unassigned gate task, the *endpoint* calls `complete`/`fail`
> by hand. Same transition, different trigger — and that is the whole answer to "where is the agent
> used in the gate": it isn't, which is the point.

---

## The three tasks, step by step

Actors, since the confusion is always about who does what:

- **Client** — whoever calls the HTTP API.
- **Endpoint** — `ApprovalEndpoint` (our Scala code): creates, assigns, polls; completes/fails only the gate.
- **Runtime** — the Akka runtime: starts agents, enforces dependencies, writes task state.
- **DraftAgent / PublishAgent** — `AutonomousAgent`s, *run by the runtime*, never called by us.
- **Reviewer** — a human, via `POST …/approve|reject`.

All three task ids are derived from one `caseId`: `{caseId}-draft`, `{caseId}-approval`,
`{caseId}-publish`. Nothing about the case is stored by us — the tasks are the durable record.

### t0 — one trigger creates all three (`POST /approvals`)

`submit(...)` validates the question (an invalid one is `400` with no task created and no model call),
then issues six `componentClient` calls in order:

```
create(draft)                        → DRAFT    : PENDING   (no deps)
create(approval).dependsOn(draft)    → APPROVAL : PENDING   (dep: draft)
create(publish).dependsOn(approval)  → PUBLISH  : PENDING   (dep: approval)
assignTasks(draft)   → DraftAgent      DRAFT    : ASSIGNED
assignTasks(publish) → PublishAgent    PUBLISH  : ASSIGNED  (but dep unmet → not started)
→ 202 Accepted + Location: /approvals/{caseId}
```

The asymmetry that *is* the design: DRAFT and PUBLISH are assigned to an agent; **APPROVAL is created and
never assigned**, which is what makes it a human gate.

### Task 1 — DRAFT (always agent-driven)

| # | Actor | Event | State |
|---|-------|-------|-------|
| 1 | Endpoint | `create(DRAFT.instructions(question))` | `PENDING` |
| 2 | Endpoint | `assignTasks(draft)` → DraftAgent | `ASSIGNED` |
| 3 | **Runtime** | deps met (none) → starts DraftAgent process | `IN_PROGRESS` |
| 4 | **DraftAgent** | model loop runs (≤3 iterations), emits a `Draft` | — |
| 5 | **Runtime** | validates output vs `resultConformsTo(Draft)`, stores result on the task | `COMPLETED` |
| 6 | **Runtime** | DraftAgent's queue drains → agent auto-stops | — |

Steps 3–6 happen in the background with **no call from our code**. `GET` reports `drafting` during 1–4,
`awaiting-approval` once step 5 lands (see Task 2).

### Task 2 — APPROVAL (the gate: human-driven, no agent)

| # | Actor | Event | State |
|---|-------|-------|-------|
| 1 | Endpoint | `create(APPROVAL).dependsOn(draft)` | `PENDING` |
| 2 | **Runtime** | draft completes → the gate's dependency is now met… | `PENDING` *(stays!)* |

Step 2 is the crux: the dependency is satisfied, **but no agent is assigned, so the runtime has nobody to
start — the task just sits at `PENDING`.** `ApprovalCase.progress` reads that `PENDING`-with-draft-`Done`
as `AwaitingApproval`: the gate is open. Then one of two branches.

**Approve branch** (`POST …/approve`):

| # | Actor | Event | State |
|---|-------|-------|-------|
| 3a | Reviewer→Endpoint | `ApprovalCase.decide(...)` returns `Decision.Open`; `forTask(gate).assign("reviewer")` | `ASSIGNED` |
| 4a | Endpoint | `forTask(gate).complete(APPROVAL, ApprovalDecision(true, note))` | `COMPLETED` |

**Reject branch** (`POST …/reject`):

| # | Actor | Event | State |
|---|-------|-------|-------|
| 3b | Reviewer→Endpoint | `forTask(gate).assign("reviewer")` | `ASSIGNED` |
| 4b | Endpoint | `forTask(gate).fail(note)` | `FAILED` |

Compare step 4 here with Task 1 step 5: **the endpoint calls `complete`/`fail` by hand** on the gate,
because there is no agent to do it. For DRAFT the runtime did the equivalent automatically.

The decision is guarded by `ApprovalCase.decide`: only `(draft Done, gate Pending)` → `Open`. A premature
decision (`404`/`409 not-awaiting`) or a repeat after a decision (`409 already-decided`) never reaches
`complete`/`fail`, so a second publish can never be triggered.

### Task 3 — PUBLISH (agent-driven, but gated by the dependency)

| # | Actor | Event | State |
|---|-------|-------|-------|
| 1 | Endpoint | `create(PUBLISH).dependsOn(approval)` | `PENDING` |
| 2 | Endpoint | `assignTasks(publish)` → PublishAgent | `ASSIGNED` |
| 3 | **Runtime** | dependency (approval) not `COMPLETED` → **does not start the agent** | `ASSIGNED` *(parked)* |

Then the branch is decided by what happened to the gate:

**If approved** (gate → `COMPLETED`):

| # | Actor | Event | State |
|---|-------|-------|-------|
| 4a | **Runtime** | approval now `COMPLETED` → dependency met → starts PublishAgent | `IN_PROGRESS` |
| 5a | **PublishAgent** | model emits a `PublishedReply` | — |
| 6a | **Runtime** | validates & stores result | `COMPLETED` |

**If rejected** (gate → `FAILED`):

| # | Actor | Event | State |
|---|-------|-------|-------|
| 4b | **Runtime** | a dependency `FAILED` → cancels the dependent before it ever starts | `CANCELLED` |

Step 4b is why a `PublishedReply` can **never** exist for a rejected case — enforced by the runtime's
dependency rule, not by any ordering in our code. PublishAgent's model is never even invoked on the
reject path.

### Two wall-clock sequences (the whole case)

**Approve path:**

```
Client ─POST /approvals─▶ Endpoint: create×3, assign draft+publish ─▶ 202
                           DRAFT: PENDING→ASSIGNED
Runtime ▶ DraftAgent runs ─▶ DRAFT: IN_PROGRESS→COMPLETED
                           APPROVAL: PENDING  (draft done, no agent → parks here)
                           PUBLISH: ASSIGNED  (parked: dep unmet)
   [GET ⇒ "awaiting-approval", draft text shown]
Reviewer ─POST /approve─▶ Endpoint: assign+complete ─▶ APPROVAL: COMPLETED
Runtime ▶ PublishAgent runs ─▶ PUBLISH: IN_PROGRESS→COMPLETED
   [GET ⇒ "publishing" → "published", reply shown]
```

**Reject path:**

```
… (same up to awaiting-approval) …
Reviewer ─POST /reject─▶ Endpoint: assign+fail(note) ─▶ APPROVAL: FAILED
Runtime ▶ dependency failed ─▶ PUBLISH: CANCELLED   (PublishAgent never runs)
   [GET ⇒ "rejected", note shown]
```

---

## Where the response is consumed

Nobody "receives" a reply from an agent. Because results live on the task, the response is consumed by
**polling the task by id**. `GET /approvals/{caseId}` derives the three ids, fetches each task snapshot,
reads the typed result off the `COMPLETED` ones, and folds all three into one `CaseProgress`
(`drafting → awaiting-approval → publishing → published`, or `rejected` / `*-failed`). The endpoint's
`outcomeOf` helper collapses the SDK's seven-value `TaskStatus` into the domain's five-way `TaskOutcome`,
and the pure `ApprovalCase` state machine decides what that means — unit-testable with no runtime.

## The one sentence

The `AutonomousAgent` is "used" at exactly two moments, both the **runtime's** doing and neither a call
you can see in the endpoint: when the runtime *starts* the agent (its task is assigned **and**
dependencies are met), and when the runtime *writes the agent's model output onto the task* as a typed
result and flips it to `COMPLETED`. Our code only ever **creates, assigns and polls** tasks — plus, for
the one task with no agent (the gate), **completes/fails it by hand**. The agent is the capability the
runtime drives; the task is the durable record where its result lands.
