# Implementation Plan: Human-in-the-loop approval gate

**Branch**: `007-human-approval-gate` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-human-approval-gate/spec.md`

## Summary

Capability 5 puts a **human in the loop** using the Akka Autonomous Agent **"external input"** pattern:
a three-task chain — **draft → human gate → publish** — where the middle task has **no agent assigned**.
A `DraftAgent` produces a candidate reply; the unassigned `APPROVAL` task (a dependency of the publish
task) makes the chain **wait for a person**; a reviewer **completes** that gate to approve or **fails** it
to reject; on approval the `PublishAgent` runs and reaches a terminal *published* state, on rejection the
publish task is auto-cancelled and the chain ends *rejected*. Because the draft is produced
asynchronously and the gate then waits for a human, the HTTP surface is **start → poll**, plus a
**decision** endpoint (`approve`/`reject`).

The load-bearing planning finding is the **interop proof** (the point of this exploratory capability):
the whole flow — including the human decision — is authored in **idiomatic Scala with no Java detour**.
This is possible because the `TaskClient` surface (`create` / `get` / `result` / `assign` / `complete` /
`fail`) is keyed on `Task`/`TaskDefinition` value objects, `String` handles, and result values `R` —
**no `Function`/`SerializedLambda` parameter anywhere** (verified against SDK 3.6.0 bytecode). Contrast:
a Workflow `pause`/`resume` gate would force the capability into Java, because Workflow step wiring and
`resume(...)` are method-reference-only (the project's recurring "method-ref wall"). Capability 5 is the
counter-example — a durable, human-gated, multi-step flow that stays entirely in Scala, **tests
included** (unlike cap-4, which needed one Java test to read `SessionMemoryEntity`).

The **second load-bearing decision** follows directly from that goal: **introduce no Entity.** The
case's three task ids are **derived deterministically** from a single `caseId` handle
(`{caseId}-draft`, `{caseId}-approval`, `{caseId}-publish`), so the endpoint reconstructs the whole
chain from the path alone and reads/decides everything through `TaskClient`. A `KeyValueEntity` to store
the case→task-id mapping would have **reintroduced the wall** — its client is `.method(Entity::cmd)`-only
(no `dynamicCall`), un-callable from Scala (feature-006 R6). Statelessness here is not just tidy; it is
what keeps the capability pure Scala.

Two constraints carry over unchanged from earlier capabilities: (a) the three **task results** (`Draft`,
`ApprovalDecision`, `PublishedReply`) are component payloads serialized by the SDK's *internal* mapper
(feature-003 two-mapper finding), so they stay **Java-shaped** (Jackson-annotated Scala case classes,
like cap-3's `HelpAnswer`), while the HTTP DTOs remain idiomatic `Option`-typed; (b) the hand-maintained
descriptor gains the two new agents under the **`autonomous-agent`** key and the endpoint under
`http-endpoint`. Capabilities 1–4 are untouched and stay green.

## Technical Context

**Language/Version**: **Scala 3.3.8** (entirely Scala, tests included). Mixed build already in place;
**no `pom.xml` change** (cap-3/cap-4 established the AutonomousAgent + mixed-build settings).
**Primary Dependencies**: Akka Java SDK **3.6.0** — `AutonomousAgent`/`AgentDefinition`/`TaskAcceptance`
(`akka.javasdk.agent.autonomous.*`), `Task`/`TaskStatus`/`TaskSnapshot` (`akka.javasdk.agent.task.*`),
`ComponentClient` (`forAutonomousAgent`, **`forTask`** → `TaskClient`: `create`/`assign`/`complete`/
`fail`/`get`), HTTP endpoints. No new external dependency.
**Storage**: Durable task + agent-process state (Akka runtime) — no external store, **no Entity of our
own**. The pending gate and case state survive restarts because the *tasks themselves* are durable.
**Testing**: JUnit 5, AssertJ, Akka `TestKitSupport` + one `TestModelProvider` **per agent class**
(`DraftAgent`, `PublishAgent`) with `AutonomousAgentTools.completeTask`/`failTask`; the human decision is
driven from the test via the endpoint (`httpClient`), and polling uses Awaitility. **All tests Scala.**
Plus one manual live smoke test.
**Target Platform**: Akka runtime (local `exec:java` and Akka platform), JDK 21.
**Project Type**: Single Akka service; mixed Scala + Java — cap-5 is Scala.
**Performance Goals**: N/A — two bounded model-driven tasks (small `maxIterationsPerTask`) plus an
indefinite human wait (no gate timeout, A-006).
**Constraints**: caps 1–4 behavior byte-for-byte unchanged (their suites pass unmodified); offline suite
green with no API key/network; the hand-maintained descriptor stays the single source of truth
(`-proc:none`); publish is **genuinely gated** — never runs before approval (FR-007).
**Scale/Scope**: 2 autonomous agents, 3 task types, 0 domain tools, 1 endpoint (4 routes), ~1 domain
type, ~3 result records, ~2–3 test files.

### Resolved unknowns (see research.md)

- **R1 — `TaskClient` has NO method-reference wall (THE CRUX)**: every human-decision entry point takes
  a `Task`/`TaskDefinition` value object, a `String` id, or a result value `R` — **no `Function`
  parameter** (SDK 3.6.0 bytecode). ⇒ the human gate, and the whole capability, are authored in Scala,
  including the tests. A Workflow `resume(...)` gate would force Java; this is the deliberate contrast.
- **R2 — Stateless deterministic-id design (keeps it pure Scala)**: derive `{caseId}-draft/-approval/
  -publish` from one `caseId`; no Entity, no mapping store. An Entity would reintroduce the method-ref
  wall (feature-006 R6). Consequence: `GET`/decide reconstruct the chain from the path and read via
  `TaskClient` only. Residual verify: `forTask(unknownId).get(def)` throws for a never-created task (so
  unknown `caseId` → 404), matching cap-3's `Try(...).get`-then-`notFound` precedent.
- **R3 — External-input three-task chain (the mechanism)**: create all three tasks at submit time with
  `forTask(id).create(...)`; `APPROVAL` is created **unassigned** and `dependsOn(draftId)`; `PUBLISH`
  `dependsOn(approvalId)` and is assigned to `PublishAgent`. The runtime does not start a task whose
  deps are incomplete, so publish waits on the gate. Approve = `assign(reviewer)` + `complete(APPROVAL,
  decision)` → publish becomes runnable. Reject = `assign(reviewer)` + `fail(reason)` → publish
  auto-**`CANCELLED`** (dependency failure). This is FR-007 realized by the dependency graph, not by our
  code ordering.
- **R4 — Decision integrity via a status guard (FR-008/FR-009)**: before deciding, read the draft and
  approval snapshots. Act only when **draft `COMPLETED` AND approval `PENDING`** (the gate is genuinely
  open). Otherwise refuse distinctly: unknown `caseId` → 404; still-drafting or already-decided
  (approval `COMPLETED`/`FAILED`) → 409. The guard — not the idempotency of `complete`/`fail` — is what
  makes a double decision a safe no-op and a premature decision a distinct refusal.
- **R5 — Task results stay Java-shaped (two-mapper, carried from 003)**: `resultConformsTo(Class)` builds
  the `complete_task` schema and deserializes the tool arguments through the SDK's internal mapper, so
  `Draft`, `ApprovalDecision`, `PublishedReply` are Jackson-annotated Scala case classes (cap-3 pattern).
  HTTP DTOs stay idiomatic. Draft→publish content flow is **not** automatic (deps gate ordering, not
  context); minimal generation per A-008, so this is out of scope and SC-002 "corresponds" is shown by
  the mocked test's construction and the live smoke test's plausible output.
- **R6 — Offline testing with two mocked agents**: register a `TestModelProvider` per agent class; mock
  `DraftAgent`→`completeTask(Draft)` (and a `failTask` case for the abandon edge), `PublishAgent`→
  `completeTask(PublishedReply)`. Drive submit/poll/approve/reject via `httpClient`, poll snapshots via
  the endpoint's `GET`. All Scala — the clean contrast with cap-4's forced Java entity test.
- **R7 — Gemini tools-vs-JSON does NOT bite (contrast 002/004; same as 003)**: each agent delivers its
  typed result via the built-in `complete_task` **tool** (function calling), not a JSON
  `responseMimeType`. No domain `@FunctionTool` is even used (A-008). Verify on the live smoke test.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — PASS. Built entirely on SDK primitives: two `AutonomousAgent`s,
  the `Task`/`TaskClient` external-input pattern, an HTTP endpoint, `ComponentClient`. **No new external
  dependency**; **no Entity** (durability is intrinsic to tasks — the SDK's explicit guidance). No
  Workflow (the whole point: the task chain is already a durable, gated pipeline).
- **II. Design Principles** — PASS. *Domain independence*: question validation is pure Scala, no Akka
  deps. *API isolation*: the endpoint owns its request/response DTOs, never exposing task results or
  snapshots. *Single responsibility*: `DraftAgent` drafts, `PublishAgent` publishes, the gate is a
  no-agent task — three focused roles rather than one. *Descriptive naming*: `DraftAgent`, `PublishAgent`,
  `ApprovalTasks.{DRAFT,APPROVAL,PUBLISH}`, `Draft`, `ApprovalDecision`, `PublishedReply`, `ApprovalCase`
  state.
- **III. Test Coverage** — PASS. Adds a domain unit test (question validation) and integration tests
  covering approve→published, reject→(never published), the gated-lifecycle states, and
  decision-integrity/validation rules — all offline with mocked models. Caps 1–4 suites untouched and
  must stay green (SC-007). Coverage increases.
- **IV. Simplicity** — PASS. The minimum that demonstrates a human gate: three tasks, two trivial agents,
  no domain tool, no Entity, no Workflow. YAGNI honored — **out of scope**: reviewer authn/authz/audit,
  gate timeout/SLA/escalation, multi-reviewer/quorum, approve-with-edits, push notifications, real
  generation/KB (A-006…A-009).

**Result**: All gates pass. No Complexity-Tracking concessions required — cap-5 is idiomatic Scala with
no Java module, no duplication, and no Entity/Workflow.

## Project Structure

### Documentation (this feature)

```text
specs/007-human-approval-gate/
├── plan.md              # This file
├── research.md          # Phase 0 output — findings R1–R7
├── data-model.md        # Phase 1 output — entities, task chain, state machine, wire types
├── quickstart.md        # Phase 1 output — build/run/curl the gated flow + live smoke test
├── contracts/
│   └── approval-api.md   # Phase 1 output — POST /approvals, GET /approvals/{id}, approve, reject
├── checklists/
│   └── requirements.md  # from /akka.specify
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
# ── Capabilities 1 (Scala), 2 (Java), 3 (Scala), 4 (Scala) — ALL UNCHANGED ──
src/main/scala/com/gwgs/akkaagentic/{api,application,domain}/…      # cap-1
src/main/java/com/gwgs/akkaagentic/team/…                          # cap-2
src/main/scala/com/gwgs/akkaagentic/assistant/…                    # cap-3
src/main/scala/com/gwgs/akkaagentic/chat/…                         # cap-4

# ── Capability 5 (Scala) — NEW, self-contained approvals module ──
src/main/scala/com/gwgs/akkaagentic/approvals/
├── api/
│   └── ApprovalEndpoint.scala        # POST /approvals; GET /approvals/{caseId}; POST …/approve; …/reject
│                                     #   idiomatic Scala DTOs; derives the 3 task ids from caseId
├── application/
│   ├── DraftAgent.scala              # @Component(id="draft-agent", description=…) extends AutonomousAgent; accepts DRAFT
│   ├── PublishAgent.scala            # @Component(id="publish-agent", description=…) extends AutonomousAgent; accepts PUBLISH
│   ├── ApprovalTasks.scala           # DRAFT: Task[Draft], APPROVAL: Task[ApprovalDecision], PUBLISH: Task[PublishedReply]
│   ├── Draft.scala                   # DRAFT result — Java-shaped (Jackson-annotated) Scala case class
│   ├── ApprovalDecision.scala        # APPROVAL result — Java-shaped
│   └── PublishedReply.scala          # PUBLISH result — Java-shaped
└── domain/
    └── ApprovalQuestion.scala        # parse-don't-validate: Option[String] => Either[String, ApprovalQuestion]

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
    #   ADD DraftAgent + PublishAgent to `autonomous-agent`; ADD ApprovalEndpoint to `http-endpoint`

# ── Capability 5 tests (Scala) ──
src/test/scala/com/gwgs/akkaagentic/approvals/
├── domain/ApprovalQuestionTest.scala                 # blank / absent / present validation
└── api/ApprovalEndpointIntegrationTest.scala         # two mocked agents + httpClient:
                                                      #   submit→poll(awaiting, shows draft, no reply yet)
                                                      #   →approve→poll(published, reply); reject→rejected(note), never published;
                                                      #   lifecycle states distinct; 404 unknown; 409 premature/double decision; 400 validation
```

**Structure Decision**: cap-5 is a new, self-contained **Scala** module under
`com.gwgs.akkaagentic.approvals.{api,application,domain}`, mirroring cap-3's layering. It shares no code
with caps 1–4 and touches none of them. Only one shared file changes, additively: the hand-maintained
descriptor gains two `autonomous-agent` entries and one `http-endpoint` entry. **No `pom.xml` change**
(the mixed build + AutonomousAgent settings are already in place). Deliberately **no Entity and no
Workflow** — the deterministic-id, task-only design is what keeps the whole capability, tests included,
in idiomatic Scala.

## Complexity Tracking

> No violations. Cap-5 introduces no Java module, no code duplication, no Entity, and no Workflow. The
> stateless deterministic-id design and the `TaskClient` external-input pattern let it demonstrate a
> durable human-in-the-loop gate as idiomatic Scala, so the Constitution Check passes without concessions.
