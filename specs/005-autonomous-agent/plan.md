# Implementation Plan: Autonomous help-desk Agent

**Branch**: `005-autonomous-agent` | **Date**: 2026-07-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/005-autonomous-agent/spec.md`

## Summary

Capability 3 is a single **Autonomous Agent** (`HelpDeskAgent`) that answers a user's question through
a **model-driven iteration loop**: the model decides for itself whether to consult a canned
knowledge-base `@FunctionTool` (zero or more times) before completing a **typed task** carrying
`HelpAnswer{answer, category, citedTopics, confidence}`. It is exposed asynchronously (start → poll):
`POST /help` starts one task and returns `202` + a handle; `GET /help/{taskId}` returns the typed
result once the task is `COMPLETED`.

The load-bearing planning finding is the **inverse of capability 2's**: by inspecting the SDK 3.6.0
bytecode we established that the **entire Autonomous Agent authoring + client surface is keyed on
`Class` references, `Task` constants, and annotations — with *no* `Function`/`SerializedLambda`
parameter anywhere** (`forAutonomousAgent(Class,String)`, `runSingleTask(Task)`, `forTask(String)`,
`Task.name(...).resultConformsTo(Class)`, `AgentDefinition.capability(...)`). There is therefore **no
method-reference wall**, so — unlike the Workflow — **an Autonomous Agent can be authored in Scala.**
Capability 3 returns to **Scala** (package `com.gwgs.akkaagentic.assistant.*`), living alongside the
untouched Scala cap-1 and Java cap-2.

Two known constraints carry over and are handled exactly as in cap-1: (a) the **task result** is a
component payload serialized by the SDK's *internal* mapper (the feature-003 two-mapper finding), so
`HelpAnswer` stays **Java-shaped** (a Jackson-annotated Scala case class, like `GreetingAgent.Result`),
while the HTTP DTOs remain idiomatic; (b) the hand-maintained descriptor gains an **`autonomous-agent`**
key (confirmed from the annotation-processor constant pool).

## Technical Context

**Language/Version**: **Scala 3.3.8** (returns to Scala; cap-1 is Scala, cap-2 stays Java, both
untouched). Mixed build already in place (`scala-maven-plugin` + `maven-compiler-plugin -proc:none`).
**Primary Dependencies**: Akka Java SDK **3.6.0** — `AutonomousAgent`, `AgentDefinition`, `Task`/
`TaskAcceptance` (`akka.javasdk.agent.autonomous.*`, `akka.javasdk.agent.task.*`), HTTP endpoints,
`ComponentClient` (`forAutonomousAgent`/`forTask`). No new external dependency.
**Storage**: Durable task + agent state (Akka runtime) — no external store.
**Testing**: JUnit 5, AssertJ, Akka `TestKitSupport` + `TestModelProvider` with
`AutonomousAgentTools.completeTask`/`failTask` (mocked model, offline), Awaitility for the async poll;
plus a manual live smoke test.
**Target Platform**: Akka runtime (local `exec:java` and Akka platform), JDK 21.
**Project Type**: Single Akka service; mixed Scala + Java (cap-3 is Scala).
**Performance Goals**: N/A — a bounded model-driven loop; small `maxIterationsPerTask` for cost control.
**Constraints**: cap-1 & cap-2 behavior byte-for-byte unchanged; offline suite green with no API key;
the hand-maintained component descriptor stays the single source of truth (`-proc:none`).
**Scale/Scope**: 1 autonomous agent, 1 task type, 1 tool, 1 endpoint, ~2 domain types, ~5 tests.

### Resolved unknowns (see research.md)

- **R1 — The Autonomous Agent API has NO method-reference wall (THE CRUX, inverse of cap-2)**: proven
  via `javap` on SDK 3.6.0 — every authoring/client entry point takes `Class`, `Task`/`TaskDefinition`,
  `String`, or an annotation; **no `Function`/`SerializedLambda` parameter exists**. ⇒ cap-3 in Scala.
- **R2 — Descriptor key is `autonomous-agent`**: confirmed as a distinct constant in
  `ComponentAnnotationProcessor` (alongside `agent`, `workflow`, `view`, …). The agent is registered
  under `autonomous-agent`; the endpoint under `http-endpoint`. The `Tasks` holder and `HelpAnswer` are
  **not** components (no descriptor entry).
- **R3 — Task result stays Java-shaped (two-mapper, carried from 003)**: `resultConformsTo(Class)` both
  builds the `complete_task` JSON schema and deserializes the tool arguments through the SDK's internal
  mapper (no Scala module). `HelpAnswer` is therefore a Jackson-annotated Scala case class (cap-1's
  `Result` pattern). *Residual verify at the foundational step*: confirm schema-gen + round-trip for a
  Scala case class; fall back to a Java record for that single type only if it fails.
- **R4 — Gemini tools-vs-JSON does NOT bite here (contrast with 002/004)**: the typed result is
  delivered via the built-in `complete_task` **tool** (function calling), not a JSON `responseMimeType`.
  The domain `lookupPolicy` tool is also function calling. No JSON response mime type is requested, so
  the Gemini "function calling + JSON mime" conflict does not arise. Verify on the live smoke test.
- **R5 — Async exposure + offline testing**: `runSingleTask` returns a task id immediately; the task is
  the durable, queryable record (`forTask(id).get(ANSWER)` → `TaskSnapshot`). Endpoint maps
  `COMPLETED`→`200`, `FAILED`→`422`, not-ready/unknown→`404`. Tests mock the model with
  `TestModelProvider` + `AutonomousAgentTools` and poll with Awaitility.
- **R6 — The Scala-interop through-line narrows (meta)**: cap-3 is the first multi-step/orchestration-
  flavored capability that does **not** hit an interop wall — because the AutonomousAgent API is
  declarative (Class/Task/annotation), not method-reference-based. The wall was *Workflow-specific*, not
  intrinsic to orchestration on this SDK.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — PASS. Uses the SDK's `AutonomousAgent` primitive, its `Task`
  API, a `@FunctionTool`, an HTTP endpoint, and `ComponentClient`. **No new external dependency.**
- **II. Design Principles** — PASS. *Domain independence*: `KnowledgeBase` and question validation are
  pure Scala with no Akka deps. *API isolation*: the endpoint owns its request/response DTOs, never
  exposing `HelpAnswer` internals or the task snapshot. *Single responsibility*: one agent, one task
  type, one tool. *Descriptive naming*: `HelpDeskAgent`, `HelpDeskTasks.ANSWER`, `HelpAnswer`,
  `KnowledgeBase`.
- **III. Test Coverage** — PASS. Adds unit tests (`KnowledgeBase`, question validation) and integration
  tests (agent completes via mocked model incl. a tool-consulting iteration and a failure path; endpoint
  async lifecycle). cap-1 and cap-2 suites untouched and must stay green (SC-006). Coverage increases.
- **IV. Simplicity** — PASS. One agent, one task type, one tool is the minimum that demonstrates a
  model-driven durable task. YAGNI honored: **no** delegation/handoff/teams/moderation (deferred). No
  wrapping Workflow (the task is already durable and queryable — the SDK's explicit guidance).

**Result**: All gates pass. No Complexity-Tracking concessions required (unlike cap-2, no Java module
and no code duplication — cap-3 is idiomatic Scala reusing nothing it shouldn't).

## Project Structure

### Documentation (this feature)

```text
specs/005-autonomous-agent/
├── plan.md              # This file
├── research.md          # Phase 0 output — findings R1–R6
├── data-model.md        # Phase 1 output — entities, task lifecycle, wire types
├── quickstart.md        # Phase 1 output — build/run/curl the async flow
├── contracts/
│   └── help-api.md       # Phase 1 output — POST /help, GET /help/{taskId}
├── checklists/
│   └── requirements.md  # from /akka.specify
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
# ── Capability 1 (Scala) — UNCHANGED ──
src/main/scala/com/gwgs/akkaagentic/{api,application,domain}/…

# ── Capability 2 (Java) — UNCHANGED ──
src/main/java/com/gwgs/akkaagentic/team/{api,application,domain}/…

# ── Capability 3 (Scala) — NEW, self-contained assistant module ──
src/main/scala/com/gwgs/akkaagentic/assistant/
├── api/
│   └── HelpDeskEndpoint.scala       # POST /help, GET /help/{taskId}; idiomatic Scala DTOs
├── application/
│   ├── HelpDeskAgent.scala          # @Component(id="help-desk-agent", description=…) extends AutonomousAgent; definition(); @FunctionTool lookupPolicy(topic)
│   ├── HelpDeskTasks.scala          # val ANSWER: Task[HelpAnswer] = Task.name("Answer")…resultConformsTo(classOf[HelpAnswer])
│   └── HelpAnswer.scala             # task result — Java-shaped (Jackson-annotated) Scala case class
└── domain/
    └── KnowledgeBase.scala          # canned topic→entry map + lookup (pure), and question validation

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # ADD `autonomous-agent = [HelpDeskAgent]`; add endpoint to `http-endpoint`

# ── Capability 3 tests (Scala) ──
src/test/scala/com/gwgs/akkaagentic/assistant/
├── domain/KnowledgeBaseTest.scala
├── application/HelpDeskAgentIntegrationTest.scala   # mocked model: completeTask + a tool-consulting iteration + failTask
└── api/HelpDeskEndpointIntegrationTest.scala        # httpClient: start→poll→200, 404 not-ready/unknown, 422 failed, 400 validation
```

**Structure Decision**: cap-3 is a new, self-contained **Scala** module under
`com.gwgs.akkaagentic.assistant.{api,application,domain}`, mirroring cap-1's layering. It shares no code
with cap-1 or cap-2 and touches neither. Only two shared files change, both additively: the `pom.xml`
needs **no** change (the mixed build is already configured), and the hand-maintained descriptor gains
the `autonomous-agent` key plus the new endpoint.

## Complexity Tracking

> No violations. Cap-3 introduces no Java module, no code duplication, and no wrapping Workflow. It is
> idiomatic Scala on the AutonomousAgent primitive, so the Constitution Check passes without concessions.
