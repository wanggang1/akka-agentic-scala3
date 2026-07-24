# Phase 1 Data Model: Human-in-the-loop approval gate

Derived from the spec's Key Entities and Requirements, and from research.md. Three layers, matching the
project's layering rule (FR-015): idiomatic Scala **domain** (no Akka deps — validation **and** the case
state machine + decision guard) and **API** DTOs; Java-shaped **application** task-result records
(two-mapper finding, R5). **No Entity and no persistent state of our own** — the durable record is the
task chain itself (R2/R3).

> **Reconciled with the as-built code** (post-merge, PR #12): the state machine and decision guard, first
> sketched here as endpoint logic, were extracted to the domain (`TaskOutcome` + `ApprovalCase`) on review
> and a `publish-failed` state was added. This document now describes what shipped. The endpoint-layer
> boundary decision (why the routing annotation stays) is deliberately *not* revisited here — see
> [`docs/http-endpoint-sdk-boundary.md`](../../docs/http-endpoint-sdk-boundary.md).

---

## 1. Domain layer — `com.gwgs.akkaagentic.approvals.domain`

### `ApprovalQuestion` (parse-don't-validate)

Pure Scala, mirrors cap-3's `HelpQuestion`. Proves the inbound question is present and non-blank so
downstream code never re-checks.

| Field | Type | Notes |
|---|---|---|
| `text` | `String` | guaranteed non-blank, trimmed |

```scala
final case class ApprovalQuestion(text: String)
object ApprovalQuestion:
  /** Right(question) when present & non-blank; Left(message) otherwise. Absent (None) and blank both fail. */
  def validate(raw: Option[String]): Either[String, ApprovalQuestion] =
    raw.map(_.trim).filterNot(_.isBlank).map(ApprovalQuestion.apply).toRight("question must not be blank")
```

**Rules**: `None` (absent field) and blank → `Left("question must not be blank")` → HTTP 400, no case
started (FR-010). Decision bodies carry only an optional free-text `note`, which needs no validation.

### `TaskOutcome[+A]` + `ApprovalCase` (the case state machine — pure domain)

> **Design note (as-built, deviates from the original Phase-1 sketch):** the case state machine and the
> decision guard were first written inline in `ApprovalEndpoint`, then extracted to the domain on review
> so the capability's core logic is unit-testable with no runtime (FR-015; Constitution II). The §3 tables
> below are the *specification* of these two functions; the endpoint now only adapts SDK snapshots into
> `TaskOutcome`s, calls `ApprovalCase`, and renders the result.

`TaskOutcome[+A]` collapses the SDK's seven `TaskStatus` values (and the snapshot's `Optional` result /
failure fields) into the five outcomes the FSM needs — no Akka type in the domain:

| Case | Meaning | Adapted from `TaskStatus` |
|---|---|---|
| `Missing` | task was never created (for the draft task ⇒ unknown case handle) | `get` throws `CommandException` |
| `Pending` | created, waiting (for dependencies, or — the gate — for a human) | `PENDING` |
| `Running` | claimed / under way | `ASSIGNED`, `IN_PROGRESS` |
| `Done[A](value)` | completed, carrying what the FSM needs from the typed result | `COMPLETED` (with a result) |
| `Failed(reason)` | terminally failed / cancelled / rejected, with any reason | `FAILED`, `CANCELLED`, `RESULT_REJECTED` |

> `Pending` and `Running` are kept apart on purpose: the gate is decidable **only** while strictly
> `Pending`, because `Running` (assigned) means a decision is already in flight.

`ApprovalCase` exposes two pure, total functions plus the ADTs they return — `CaseProgress` (the
observable state, one per §3 row, each carrying its wire `label`) and `Decision`
(`Open` / `NotFound` / `NotAwaiting` / `AlreadyDecided`):

```scala
object ApprovalCase:
  def progress(draft: TaskOutcome[String], gate: TaskOutcome[Unit], publish: TaskOutcome[String]): CaseProgress
  def decide(draft: TaskOutcome[String], gate: TaskOutcome[Unit]): Decision
```

---

## 2. Application layer — `com.gwgs.akkaagentic.approvals.application`

### Task-result records (Java-shaped — component payloads, R5)

Each is a Jackson-annotated Scala case class (`@JsonCreator`/`@JsonProperty`, `@Description` flowing into
the `complete_task` schema), like cap-3's `HelpAnswer`. Never exposed over HTTP directly.

**`Draft`** — result of the `DRAFT` task (what the reviewer sees):

| Field | Type | Description (into schema) |
|---|---|---|
| `body` | `String` | The candidate reply drafted for the customer. |

**`ApprovalDecision`** — result of the `APPROVAL` task, produced by the **human** on approval:

| Field | Type | Description |
|---|---|---|
| `approved` | `Boolean` | Always `true` on the completion path (rejection uses `fail`, not a result). |
| `note` | `String` | Optional reviewer comment on approval; `""` when none. |

> Rejection does **not** complete the approval task with an `ApprovalDecision`; it **fails** the task with
> the note as the failure reason (R3). `ApprovalDecision` therefore exists only for the approved path.

**`PublishedReply`** — result of the `PUBLISH` task (the terminal output):

| Field | Type | Description |
|---|---|---|
| `reply` | `String` | The final, published reply to the customer. |

### `ApprovalTasks` (task definitions — not components)

```scala
object ApprovalTasks:
  val DRAFT: Task[Draft] =
    Task.name("Draft").description("Draft a candidate customer reply for the given question.")
      .resultConformsTo(classOf[Draft])
  val APPROVAL: Task[ApprovalDecision] =
    Task.name("Approval").description("Human approval gate for the drafted reply.")
      .resultConformsTo(classOf[ApprovalDecision])
  val PUBLISH: Task[PublishedReply] =
    Task.name("Publish").description("Publish an approved reply.")
      .resultConformsTo(classOf[PublishedReply])
```

### Agents (autonomous, no domain tools — A-008)

- **`DraftAgent`** `@Component(id = "draft-agent", description = "Drafts a candidate customer reply.")`
  — `definition()` = `define().instructions(…).capability(TaskAcceptance.of(ApprovalTasks.DRAFT).maxIterationsPerTask(3))`.
- **`PublishAgent`** `@Component(id = "publish-agent", description = "Publishes an approved reply.")`
  — `definition()` = `define().instructions(…).capability(TaskAcceptance.of(ApprovalTasks.PUBLISH).maxIterationsPerTask(3))`.

Both are stateless; neither has a `@FunctionTool`. Descriptions are meaningful (they flow into the
system message).

---

## 3. The approval case (derived, not stored)

An **approval case** is not a record we persist — it is the triple of tasks reconstructed from one
handle. Given `caseId` (a UUID minted at submit):

| Derived id | Task | Assigned to | Depends on |
|---|---|---|---|
| `{caseId}-draft` | `DRAFT` → `Draft` | `DraftAgent` instance `{caseId}-draft-agent` | — |
| `{caseId}-approval` | `APPROVAL` → `ApprovalDecision` | **nobody (the gate)** | `{caseId}-draft` |
| `{caseId}-publish` | `PUBLISH` → `PublishedReply` | `PublishAgent` instance `{caseId}-publish-agent` | `{caseId}-approval` |

The case's **observable state** is computed by `ApprovalCase.progress` (§1) over the three task
snapshots, each first adapted into a `TaskOutcome` (`forTask(id).get(def)` → `status` / `result` /
`failureReason` → `Missing`/`Pending`/`Running`/`Done`/`Failed`).

### State machine (what `GET /approvals/{caseId}` reports)

Conditions below use `TaskOutcome` vocabulary; `Failed` folds `FAILED`/`CANCELLED`/`RESULT_REJECTED`.
Each state is a `CaseProgress` case carrying its wire `label`.

| State | Condition (draft `d`, gate `a`, publish `p`) | Payload |
|---|---|---|
| **(404 not found)** | `d == Missing` (case never existed) | — |
| `drafting` | `d ∈ {Pending, Running}` | — |
| `draft-failed` | `d == Failed` | `note = d.reason` |
| `awaiting-approval` | `d == Done` ∧ `a ∈ {Pending, Running}` | `draft = d.value.body` |
| `publishing` | `d == Done` ∧ `a == Done` ∧ `p ∈ {Pending, Running}` | `draft` (still available) |
| `published` | `d == Done` ∧ `a == Done` ∧ `p == Done` | `reply = p.value.reply` |
| `publish-failed` | `d == Done` ∧ `a == Done` ∧ `p == Failed` | `draft`, `note = p.reason` |
| `rejected` | `d == Done` ∧ `a == Failed` | `note = a.reason` (publish is `CANCELLED`) |

Terminal states: `published`, `rejected`, `draft-failed`, `publish-failed`. `publishing` is a brief
transient after approval; tests poll through it to `published`.

> **`publish-failed` is beyond the spec's minimum** (FR-002 lists "at least" the five core states).
> The code reports it — rather than polling `publishing` forever — when an approved case's publish task
> itself fails. A rejected gate (`a == Failed`) always wins over any publish outcome, so a rejection can
> never be misreported as published.

```text
          submit                    draft completes          approve
  (none) ──────▶ drafting ───────────────────────────▶ awaiting-approval ────────▶ publishing ──┬─▶ published
                    │                                          │                                 │   (terminal)
                    │ draft fails/abandons                     │ reject                          │ publish fails
                    ▼                                          ▼                                 ▼
                draft-failed (terminal)                 rejected (terminal;              publish-failed (terminal)
                                                         publish CANCELLED)
```

### Transition triggers

| Trigger | Actor | Effect |
|---|---|---|
| `POST /approvals` (valid question) | caller | mint `caseId`; create 3 tasks; assign draft & publish; **APPROVAL unassigned** |
| draft task completes | `DraftAgent` (model) | `awaiting-approval` (gate open) |
| draft task fails / hits iteration limit | runtime | `draft-failed`; gate never opens |
| `POST …/approve` (gate open) | human | `assign` + `complete(APPROVAL, ApprovalDecision(true, note))` → publish runnable |
| `POST …/reject` (gate open) | human | `assign` + `fail(note)` → publish auto-`CANCELLED` → `rejected` |
| publish task completes | `PublishAgent` (model) | `published` |

### Decision-guard truth table (`ApprovalCase.decide`, R4)

Applies to both `approve` and `reject`. Rows use `TaskOutcome` vocabulary and are evaluated top-down.

| draft `d` | gate `a` | `Decision` | HTTP |
|---|---|---|---|
| `Missing` | — | `NotFound` | **404** |
| any | `Missing` | `NotFound` | **404** |
| `Done` | `Pending` | `Open` → perform | **200** |
| `Done` | anything else (`Running`/`Done`/`Failed`) | `AlreadyDecided` (safe no-op) | **409** |
| not `Done` (`Pending`/`Running`/`Failed`) | any | `NotAwaiting` | **409** |

> A gate that is `Running` (assigned mid-decision) is treated as **already decided**, not open — this is
> what closes the window on a second decision slipping in while the first is in flight (FR-009). Guarding
> on the observed outcome — rather than trusting `complete`/`fail` idempotency — is what makes a premature
> decision and a repeated decision two *distinct*, deterministic refusals (FR-008/009).

---

## 4. API layer — `com.gwgs.akkaagentic.approvals.api` (idiomatic Scala DTOs)

Owned by `ApprovalEndpoint`; never exposes task results/snapshots directly (API isolation). All
`@JsonIgnoreProperties(ignoreUnknown = true)` on inbound types (tolerate unknown props, consistent with
caps 1–4).

| DTO | Shape | Direction |
|---|---|---|
| `SubmitRequest` | `question: Option[String]` | in (POST /approvals) |
| `CaseAccepted` | `caseId: String` | out (202) |
| `DecisionRequest` | `note: Option[String]` | in (approve / reject) |
| `CaseState` | `state: String`, `draft: Option[String]`, `reply: Option[String]`, `note: Option[String]` | out (GET 200) |

`toApi` maps the domain `CaseProgress` (returned by `ApprovalCase.progress`, §1) → `CaseState` — a total
match, one arm per state. Empty `Option`s are omitted from the JSON via `@JsonInclude(NON_ABSENT)`
(idiomatic Scala, feature-003), so `awaiting-approval` carries `draft` only, `published` carries `reply`
only, `rejected` carries `note` only — and the **absence** of a `reply` before approval is observable on
the wire, not a `null`.

---

## Serialization boundary (recap)

| Type(s) | Mapper | Shape |
|---|---|---|
| `Draft`, `ApprovalDecision`, `PublishedReply` (task results) | SDK **internal** | **Java-shaped** (Jackson annotations) |
| `SubmitRequest`, `CaseAccepted`, `DecisionRequest`, `CaseState` (HTTP bodies) | **public** `JsonSupport` (+ `DefaultScalaModule`) | idiomatic Scala (`Option`) |
| `ApprovalQuestion` | none (never serialized) | pure domain |
