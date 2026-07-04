# Implementation Plan: Multi-agent greeting Workflow

**Branch**: `004-multi-agent-workflow` | **Date**: 2026-07-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-multi-agent-workflow/spec.md`

## Summary

Orchestrate two request-based agents through an Akka `Workflow` in a fixed two-step sequence
(**tone detection → greeting composition**), exposed asynchronously (start → poll). During planning
we established — by inspecting the SDK bytecode and running compile spikes — that **the entire
Workflow API is keyed on Java method references** resolved via `SerializedLambda` (step wiring:
`transitionTo`/`thenTransitionTo`/`stepTimeout`/`stepRecovery`/`failoverTo`, *and*
`WorkflowClient.method`), with **no `dynamicCall` escape hatch** like the agent client has. Scala
lambdas compile to mangled `$anonfun` names and do not resolve, so **a pure-Scala Akka Workflow
cannot wire its own steps and a Scala caller cannot invoke a workflow.** (See research R1.)

Therefore capability 2 is implemented **entirely in Java**, as a self-contained `team` module with
**zero cross-language dependencies**: `GreetingTeamEndpoint`, `ToneAgent`, `GreetingComposerAgent`,
`GreetingWorkflow`, Java-record wire types, and its own small `TimeOfDay`/`Tone` domain. Capability 1
(Scala) is left exactly where it is and untouched. Java records make every component payload
Java-shaped by construction, so the feature-003 two-mapper problem simply does not arise and no
`Bootstrap`/Scala-module is needed for cap-2. This is the capability-2 finding — the workflow
analogue of feature 003's two-mapper result: **workflow-centric features are impractical in
idiomatic Scala on this SDK; write them in Java.**

## Technical Context

**Language/Version**: **Java 21** for cap-2 (records, `switch`); Scala 3.3.8 for the untouched cap-1.
Mixed build via `scala-maven-plugin` (Scala) + `maven-compiler-plugin` (Java, `-proc:none`).
**Primary Dependencies**: Akka Java SDK 3.6.0 (`Workflow`, `Agent`, HTTP endpoints, `ComponentClient`);
no new external dependency (Java records are stdlib; `maven-compiler-plugin` is already managed by the
parent).
**Storage**: Durable workflow state (Akka runtime) — no external store.
**Testing**: JUnit 5, AssertJ, Akka `TestKitSupport` + `TestModelProvider` (mocked model, offline),
Awaitility for the async poll; plus a manual live smoke test.
**Target Platform**: Akka runtime (local `exec:java` and Akka platform), JDK 21.
**Project Type**: Single Akka service; now **mixed Scala + Java** sources.
**Performance Goals**: N/A — two sequential LLM round-trips; per-step timeout 60s (LLM latency).
**Constraints**: cap-1 behavior byte-for-byte unchanged; offline suite green with no API key; the
hand-maintained component descriptor stays the single source of truth (annotation processor disabled).
**Scale/Scope**: 1 workflow, 2 agents, 1 endpoint, ~4 Java records, 2 small domain types, 5 tests.

### Resolved unknowns (see research.md)

- **R1 — Workflow step wiring is Java-method-reference-only (THE CRUX)**: proven via bytecode
  (`MethodRefResolver` reads `SerializedLambda.implMethodName`) and compile spikes (Scala lambdas →
  `$anonfun`, Java `::` refs → correct). `WorkflowClient` has no `dynamicCall`. ⇒ cap-2 in Java.
- **R2 — Annotation-processor / descriptor collision**: the parent pom binds
  `ComponentAnnotationProcessor` to `maven-compiler-plugin`; adding Java sources would regenerate and
  **overwrite** the hand-maintained `akka-javasdk-components_*.conf` (losing cap-1 + `service-setup`).
  ⇒ compile Java with `-proc:none`; hand-maintain all entries (Scala **and** Java).
- **R3 — All-Java cap-2 is fully decoupled from Scala**: no cross-language references either way, so
  compile order is irrelevant and the build stays single-pass per language. Java records are
  Java-shaped wire types, so the two-mapper issue never arises and `Bootstrap` is not needed by cap-2.
- **R4 — Gemini tools-vs-JSON** (carried from feature 002): the composer has a `@FunctionTool` **and**
  a structured result, so it uses `responseAs` + `onFailure` (not `responseConformsTo`). `ToneAgent`
  has no tool and returns a plain label.
- **R6 — No native Scala scaffolding exists** (context finding): `akka code init` only clones Java
  samples; `akka specify init` scaffolds an empty Java project + SDD templates. Scala-on-the-SDK is
  AI-reconstructed, so interop gaps (this one included) surface one capability at a time.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — PASS. Orchestration uses the SDK's `Workflow` primitive;
  agents use `Agent`; the endpoint uses the HTTP endpoint + `ComponentClient`. **No new external
  dependency.** Choosing Java over Scala for this module is *more* SDK-aligned, not less — it uses the
  Workflow API exactly as designed (method-reference step wiring) instead of fighting it.
- **II. Design Principles** — PASS. *Domain independence*: cap-2's `TimeOfDay`/`Tone` are pure Java
  with no Akka deps. *API isolation*: the endpoint owns `StartRequest`/`GreetReply`, never exposing
  `GreetingWorkflow.State`. *Single responsibility*: `ToneAgent` classifies, `GreetingComposerAgent`
  composes, `GreetingWorkflow` orchestrates — three focused components instead of cap-1's monolith.
  *Descriptive naming*: domain-aligned names throughout.
- **III. Test Coverage** — PASS. Adds unit tests (`TimeOfDay`, both agents) and integration tests
  (workflow end-to-end, endpoint async lifecycle). Cap-1's suite is untouched and must stay green
  (SC-006). Coverage increases.
- **IV. Simplicity** — PASS. The Java choice is the **least-friction** path (the alternatives —
  pure-Scala workflow, or a Scala/Java hybrid with method-ref shims and mixed-compilation ordering —
  are strictly more complex). YAGNI honored: two agents + one workflow is the minimum that
  demonstrates orchestration; no speculative abstraction. Two deliberate concessions are logged in
  Complexity Tracking.

**Result**: All gates pass. Two justified concessions recorded below.

## Project Structure

### Documentation (this feature)

```text
specs/004-multi-agent-workflow/
├── plan.md              # This file
├── research.md          # Phase 0 output — the findings (R1–R6)
├── data-model.md        # Phase 1 output — entities, state machine, wire types
├── quickstart.md        # Phase 1 output — build/run/curl the async flow
├── contracts/
│   └── greeting-team-api.md   # Phase 1 output — POST /greetings, GET /greetings/{id}
├── checklists/
│   └── requirements.md  # from /akka.specify
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
# ── Capability 1 (Scala) — UNCHANGED, left in place (no greeting/ move) ──
src/main/scala/com/gwgs/akkaagentic/
├── api/{GreetingEndpoint,HealthEndpoint}.scala
├── application/{GreetingAgent,Bootstrap}.scala
└── domain/Greeting.scala

# ── Capability 2 (Java) — NEW, self-contained team module ──
src/main/java/com/gwgs/akkaagentic/team/
├── api/
│   └── GreetingTeamEndpoint.java        # POST /greetings, GET /greetings/{id}; StartRequest/GreetReply records
├── application/
│   ├── ToneAgent.java                   # @Component("tone-agent") — detect tone label (no tool)
│   ├── GreetingComposerAgent.java       # @Component("greeting-composer-agent") — compose given tone; @FunctionTool time; responseAs + onFailure
│   └── GreetingWorkflow.java            # @Component("greeting-workflow") — start/getResult + toneStep/composeStep/toneFallbackStep/failedStep; wire records (StartGreeting, ComposeRequest, GreetingResult)
└── domain/
    ├── TimeOfDay.java                   # pure time-of-day labels (Java copy of the Scala helper)
    └── Tone.java                        # NEUTRAL constant + normalize(raw)

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # ADD team agents, endpoint, and a `workflow = [...]` key

# ── Capability 2 tests (Java) ──
src/test/java/com/gwgs/akkaagentic/team/
├── domain/TimeOfDayTest.java
├── application/{ToneAgentTest,GreetingComposerAgentTest,GreetingWorkflowIntegrationTest}.java
└── api/GreetingTeamEndpointIntegrationTest.java

pom.xml   # ADD maven-compiler-plugin (compile + testCompile) with <proc>none</proc>
```

**Structure Decision**: cap-1 stays exactly where it is (Scala, `com.gwgs.akkaagentic.{api,
application,domain}`). cap-2 is a new, self-contained Java module under
`com.gwgs.akkaagentic.team.{api,application,domain}`. The two share **no code** (cap-2 carries its own
`TimeOfDay`/`Tone`), so the build has no cross-language dependency and compile order is irrelevant.
`HealthEndpoint` and `Bootstrap` remain top-level service infrastructure (A-004), unused by cap-2.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Java module in a Scala project** | The Workflow API wires steps only via Java method references (`SerializedLambda`); Scala cannot produce them and `WorkflowClient` has no `dynamicCall` (R1, proven by spikes). | *Pure-Scala workflow* — impossible (steps can't be wired). *Scala workflow + Java method-ref table* / *Java workflow + Scala agents* — both add public-step warts, Scala↔Java wire types, and mixed-compilation ordering; strictly more moving parts than one self-contained Java module. |
| **`TimeOfDay` duplicated in Java** (~15 lines) | cap-2 (Java) needs time-of-day labels; reusing the Scala `TimeOfDay` from Java means `TimeOfDay$.MODULE$` calls and `scala.Option` juggling — friction and coupling. | *Call the Scala domain from Java* — reintroduces exactly the cross-language friction this module is designed to avoid; couples the two capabilities. Duplicating a tiny, stable pure function keeps cap-2 fully decoupled (chosen deliberately per the "least friction" directive). |
