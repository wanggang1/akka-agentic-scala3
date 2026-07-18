# Phase 0 Research: Human-in-the-loop approval gate

All unknowns for capability 5 resolved before design. Sources: the Akka docs under
`akka-context/sdk/autonomous-agents/` (`capabilities.html.md` §External input / §Human approval gate,
`tasks.html.md`, `client.html.md`, `testing.html.md`), the SDK 3.6.0 bytecode (`TaskClient`), and the
carried-over findings from features 003 (two-mapper), 005 (AutonomousAgent no wall), and 006 (the
method-ref wall recurs on entity/workflow clients). The `publishing` sample in
`autonomous-agent-playground` is the reference implementation of the pattern.

---

## R1 — `TaskClient` has no method-reference wall (THE CRUX)

**Decision**: Author the entire capability — including the human approve/reject decision — in
**idiomatic Scala**, tests included.

**Rationale**: The human decision is made through `componentClient.forTask(id)` → `TaskClient`. Its
surface is keyed on value objects and strings, **never a Java method reference**:

| Method | Signature (SDK 3.6.0) | Method-ref? |
|---|---|---|
| `create` | `<R> String create(Task<R>)` | no — `Task` value object |
| `get` | `<R> TaskSnapshot<R> get(TaskDefinition<R>)` | no |
| `result` | `<R> R result(TaskDefinition<R>)` | no |
| `assign` | `void assign(String assignee)` | no — string label |
| `complete` | `<R> void complete(TaskDefinition<R>, R result)` | no — definition + value |
| `fail` | `void fail(String reason)` | no — string |

A Scala lambda compiles to a synthetic `$anonfun$N` that the SDK's `impl.client.MethodRefResolver` can
never resolve; but no `TaskClient` method takes a lambda, so there is nothing to resolve. Likewise
`forAutonomousAgent(Class, String)` and `assignTasks(String...)` take a `Class` and strings (cap-3 R1).

**Contrast (why this capability exists)**: a Workflow `pause`/`resume` gate would force Java — Workflow
step wiring and `WorkflowClient.resume(...)` are method-reference-only (feature-004 wall). Cap-5 is the
counter-example: a durable, human-gated, multi-step flow that stays in Scala.

**Alternatives considered**: (a) Workflow pause/resume — rejected: forces Java (the wall). (b) A
request-based Agent + a manual "wait" — rejected: no durable gate primitive; we would hand-roll
persistence, violating "Akka SDK First".

---

## R2 — Stateless deterministic-id design (what keeps it pure Scala)

**Decision**: A case is identified by one `caseId` (a UUID). The three task ids are **derived**:
`draftId = s"$caseId-draft"`, `approvalId = s"$caseId-approval"`, `publishId = s"$caseId-publish"`. The
endpoint reconstructs the whole chain from the path and reads/decides via `TaskClient` only. **No Entity,
no mapping store.**

**Rationale**: The obvious alternative — a `KeyValueEntity` keyed by `caseId` storing the three task ids
— would **reintroduce the method-ref wall**: an entity client is `.method(Entity::cmd)`-only with no
`dynamicCall` (feature-006 R6), so a Scala endpoint cannot call it and a Scala test cannot read it. That
is exactly the wall cap-5 is meant to avoid. Deriving ids deterministically makes the case fully
reconstructable from the handle, so **no state of our own is needed** — and durability is already
intrinsic to the tasks (they survive restarts; FR-011). Statelessness here is a correctness requirement
for the interop goal, not merely tidy.

**Residual verify (foundational step)**: `forTask(unknownId).get(def)` on a **never-created** task must
surface as "not found" (an exception we map to 404), so an unknown `caseId` is distinguishable from a
valid one whose draft task simply has not completed yet. Cap-3 already relies on this (`Try(...).get`
→ `Failure` → `notFound`); confirm it holds and, if the SDK instead returns a benign empty snapshot,
branch on that shape instead. Either way the *behavior* (unknown → 404) is asserted in a test.

**Alternatives considered**: random unrelated task ids returned as a bundle to the caller — rejected:
forces the caller to carry three handles and still needs a lookup to map a single decision handle to the
chain. One derived handle is simpler and REST-friendly (`/approvals/{caseId}` + `…/approve`).

---

## R3 — The external-input three-task chain (the mechanism)

**Decision**: At submit, create all three tasks up front and wire the dependency graph:

```scala
// draftId, approvalId, publishId derived from caseId (R2)
componentClient.forTask(draftId).create(ApprovalTasks.DRAFT.instructions(question))
componentClient.forTask(approvalId).create(
  ApprovalTasks.APPROVAL.instructions("Review the draft and approve or reject.").dependsOn(draftId))
componentClient.forTask(publishId).create(
  ApprovalTasks.PUBLISH.instructions("Publish the approved reply.").dependsOn(approvalId))
// assign the two agent tasks; APPROVAL stays UNASSIGNED (the gate)
componentClient.forAutonomousAgent(classOf[DraftAgent], s"$caseId-draft-agent").assignTasks(draftId)
componentClient.forAutonomousAgent(classOf[PublishAgent], s"$caseId-publish-agent").assignTasks(publishId)
```

**Rationale** (from `capabilities.html.md` §Human approval gate + `tasks.html.md` §Dependencies):

- A task with dependencies is **not started** until every dependency `COMPLETED`. So `PUBLISH` waits on
  `APPROVAL`, and `APPROVAL` — having no agent — sits at `PENDING` after the draft completes. This *is*
  the gate (FR-007), realized by the dependency graph, not by our code ordering.
- **Approve** = `forTask(approvalId).assign(reviewer)` then `.complete(ApprovalTasks.APPROVAL, decision)`.
  Its dependency now satisfied, `PUBLISH` becomes runnable and `PublishAgent` runs it → *published*.
- **Reject** = `forTask(approvalId).assign(reviewer)` then `.fail(reason)`. Failing a task with dependents
  **auto-cancels** them: `PUBLISH` → `CANCELLED`, so no reply is ever published (FR-006). The reviewer's
  note is retained as the approval task's `failureReason`.

Using `forTask(id).create(...)` + `assignTasks(...)` (rather than `runSingleTask`) is what lets us choose
the **deterministic** ids (R2); `runSingleTask` auto-generates and returns an id we could not derive.

**Alternatives considered**: create the publish task lazily inside the approve endpoint (carrying the
draft text forward) — rejected: it moves the gating into our code and abandons the dependency-graph
demonstration that is the spec's stated mechanism (A-001, FR-007). Content flow is handled per R5/A-008.

---

## R4 — Decision integrity via a status guard (FR-008 / FR-009)

**Decision**: The approve and reject endpoints **read the draft and approval snapshots first** and act
only when the gate is genuinely open — **`draft.status == COMPLETED` AND `approval.status == PENDING`**.
Otherwise refuse distinctly:

| Situation | draft | approval | Response |
|---|---|---|---|
| unknown `caseId` | (get throws) | — | **404** not found |
| still drafting / draft failed | not `COMPLETED` | `PENDING`/`CANCELLED` | **409** not awaiting approval |
| gate open | `COMPLETED` | `PENDING` | perform → **200** |
| already approved | `COMPLETED` | `COMPLETED` | **409** already decided (no-op) |
| already rejected | `COMPLETED` | `FAILED` | **409** already decided (no-op) |

**Rationale**: A second `complete`/`fail` on an already-terminal task is undefined/likely an error, and a
premature `complete` on an unopened gate would wrongly satisfy the publish dependency. Guarding on the
observed statuses — rather than relying on `complete`/`fail` idempotency — makes a repeated decision a
**safe no-op** (terminal outcome unchanged, no second publish; FR-009) and a premature decision a
**distinct refusal** (FR-008), deterministically and offline-testably.

**Alternatives considered**: rely on the runtime rejecting a double `complete` — rejected: behavior
unverified and it would surface as a 500, not a clean 409; the explicit guard is clearer and testable.

---

## R5 — Task results stay Java-shaped; draft→publish content flow is out of scope

**Decision**: `Draft`, `ApprovalDecision`, and `PublishedReply` are **Java-shaped** Jackson-annotated
Scala case classes (explicit `@JsonCreator`/`@JsonProperty`, `java.util.List` if any list field); the
HTTP request/response DTOs stay idiomatic `Option`-typed.

**Rationale**: `resultConformsTo(Class)` both generates the built-in `complete_task` tool's JSON schema
and deserializes the model's tool arguments through the SDK's **internal** mapper, which the
feature-003 `Bootstrap`/`DefaultScalaModule` hook does not reach (the two-mapper finding). This mirrors
cap-1's `Result` and cap-3's `HelpAnswer` exactly. The public mapper still governs the endpoint DTOs, so
those remain idiomatic.

**Content-flow note**: Task dependencies gate **ordering**, not context — the publish agent does not
automatically receive the draft text. Per A-008 (minimal generation; publish may be a light
finalization) this is **out of scope**. SC-002's "the published reply corresponds to the approved draft"
is demonstrated by the mocked test's construction (the publish mock echoes the known draft) and by the
live smoke test's plausible output; wiring the draft body into the publish task is a deliberate
non-goal.

---

## R6 — Offline testing with two mocked agents; everything stays Scala

**Decision**: One `TestModelProvider` per agent class, registered in `testKitSettings()`; drive the flow
through the endpoint with `httpClient`; poll with Awaitility. **No Java test.**

```scala
private val draftModel = new TestModelProvider()
private val publishModel = new TestModelProvider()
override protected def testKitSettings(): TestKit.Settings =
  TestKit.Settings.DEFAULT
    .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
    .withModelProvider(classOf[DraftAgent], draftModel)
    .withModelProvider(classOf[PublishAgent], publishModel)
```

- `draftModel.fixedResponse(completeTask(Draft(...)))` → draft completes → state `awaiting-approval`.
- For the abandon edge: `draftModel.fixedResponse(failTask("…"))` → draft `FAILED` → state
  `draft-failed`, gate never opens.
- `publishModel.fixedResponse(completeTask(PublishedReply(...)))` → after approval, publish completes →
  state `published`.
- The human decision is issued from the test via `httpClient.POST("/approvals/{id}/approve" | "/reject")`
  — exercising the real `TaskClient` calls in the endpoint.

**Rationale / the clean contrast**: unlike cap-4 — which needed a **Java** test to read
`SessionMemoryEntity` (entity client method-ref-only, feature-006 R6) — cap-5 reads *everything* through
`TaskClient` (Scala-callable, R1), so the tests are Scala too. This is the capability's headline result:
the external-input pattern is Scala end-to-end, including verification.

**Live proof (FR-014 / SC-008)**: automated tests cannot exercise a real human waiting on a real model,
so a manual live smoke test against Gemini proves the end-to-end gate (approve → published; reject → not
published). Documented in quickstart.md; not part of `mvn verify`.

---

## R7 — Gemini tools-vs-JSON conflict does not arise

**Decision**: Neither agent requests a JSON `responseMimeType`; each delivers its typed result via the
built-in `complete_task` **tool** (function calling). No domain `@FunctionTool` is used at all (A-008).

**Rationale**: The Gemini "function calling + JSON response mime type" conflict (features 002/004) is
triggered only by the native-schema mode. As in cap-3, the typed result comes from a tool call, so there
is no JSON mime type and no conflict. Confirm on the live smoke test.

---

## Summary of decisions

| # | Decision | Consequence |
|---|---|---|
| R1 | `TaskClient` has no method-ref wall | Whole capability + tests in Scala |
| R2 | Deterministic task ids from one `caseId`; **no Entity** | Stateless; avoids reintroducing the wall |
| R3 | Three-task external-input chain (draft → unassigned gate → publish) | Gate realized by the dependency graph (FR-007) |
| R4 | Status-guard before deciding | Safe no-op + distinct refusals (FR-008/009), offline-testable |
| R5 | Results Java-shaped; content flow out of scope | Two-mapper compliance; A-008 |
| R6 | Two mocked agents, decision via `httpClient` | All-Scala tests — contrast with cap-4's Java test |
| R7 | Typed result via `complete_task` tool | No Gemini tools-vs-JSON conflict |
