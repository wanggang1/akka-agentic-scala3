# Phase 1 Data Model: Human-in-the-loop approval gate

Derived from the spec's Key Entities and Requirements, and from research.md. Three layers, matching the
project's layering rule (FR-015): idiomatic Scala **domain** (no Akka deps) and **API** DTOs; Java-shaped
**application** task-result records (two-mapper finding, R5). **No Entity and no persistent state of our
own** — the durable record is the task chain itself (R2/R3).

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

The case's **observable state** is computed from the three task snapshots (`forTask(id).get(def)` →
`status` / `result` / `failureReason`).

### State machine (what `GET /approvals/{caseId}` reports)

| State | Condition (draft `d`, approval `a`, publish `p`) | Payload |
|---|---|---|
| **(404 not found)** | `d` get throws (case never existed) | — |
| `drafting` | `d ∈ {PENDING, ASSIGNED, IN_PROGRESS}` | — |
| `draft-failed` | `d ∈ {FAILED, CANCELLED}` | reason (`d.failureReason`) |
| `awaiting-approval` | `d == COMPLETED` ∧ `a == PENDING` | `draft = d.result.body` |
| `publishing` | `a == COMPLETED` ∧ `p ∈ {PENDING, ASSIGNED, IN_PROGRESS}` | `draft` (still available) |
| `published` | `a == COMPLETED` ∧ `p == COMPLETED` | `reply = p.result.reply` |
| `rejected` | `a == FAILED` | `note = a.failureReason` (publish is `CANCELLED`) |

Terminal states: `published`, `rejected`, `draft-failed`. `publishing` is a brief transient after
approval; tests poll through it to `published`.

```text
          submit                       draft completes            approve
  (none) ───────▶ drafting ──────────────────────────▶ awaiting-approval ─────────▶ publishing ──▶ published
                     │                                          │                                   (terminal)
                     │ draft fails/abandons                     │ reject
                     ▼                                          ▼
                 draft-failed  (terminal)                    rejected  (terminal; publish CANCELLED)
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

### Decision-guard truth table (enforced by the endpoint, R4)

Applies to both `approve` and `reject`:

| draft status | approval status | decision result |
|---|---|---|
| get throws (unknown `caseId`) | — | **404** |
| not `COMPLETED` | any | **409** — not awaiting approval (still drafting / draft failed) |
| `COMPLETED` | `PENDING` | **perform** → 200 |
| `COMPLETED` | `COMPLETED` or `FAILED` | **409** — already decided (safe no-op) |

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

`toApi` maps the three snapshots → `CaseState` per the state-machine table. Empty `Option`s are omitted
from the JSON (idiomatic Scala, feature-003), so `awaiting-approval` carries `draft` only, `published`
carries `reply` only, `rejected` carries `note` only.

---

## Serialization boundary (recap)

| Type(s) | Mapper | Shape |
|---|---|---|
| `Draft`, `ApprovalDecision`, `PublishedReply` (task results) | SDK **internal** | **Java-shaped** (Jackson annotations) |
| `SubmitRequest`, `CaseAccepted`, `DecisionRequest`, `CaseState` (HTTP bodies) | **public** `JsonSupport` (+ `DefaultScalaModule`) | idiomatic Scala (`Option`) |
| `ApprovalQuestion` | none (never serialized) | pure domain |
