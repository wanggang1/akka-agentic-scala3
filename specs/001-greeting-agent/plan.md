# Implementation Plan: Greeting Agent Service Baseline

**Branch**: `001-greeting-agent` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-greeting-agent/spec.md`

## Summary

Establish a baseline Akka agentic service whose components are written in **Scala 3** on top of
the **Akka Java SDK** (`akka-javasdk-parent` 3.6.0). The feature exposes a single HTTP endpoint
that accepts a typed JSON payload (`{ "user": ..., "text": ... }`) and returns a JSON greeting.
The greeting is composed by a `GreetingAgent` that invokes a language model through the Agent
Effects API (`effects().systemMessage(...).userMessage(...).thenReply()`). The project is laid
out in the standard `domain` / `application` / `api` layers so further agents and components can
be added without rework.

Per clarification, the greeting is **LLM-composed** (not a template), the transport is a
**single HTTP/JSON endpoint**, and the agent returns a **plain greeting string** that the
endpoint wraps in a small JSON response. Per the planning decision, all components are
implemented in **Scala 3 case classes** calling the Akka Java SDK.

## Technical Context

**Language/Version**: Scala 3 (3.3.x LTS or later) compiled to JVM 21, interoperating with the Akka Java SDK
**Primary Dependencies**: Akka Java SDK (`io.akka:akka-javasdk-parent:3.6.0`); `scala3-library_3`; `scala-maven-plugin` for compilation; Jackson (transitive via the SDK) for JSON
**Storage**: N/A — the baseline is stateless; no entity, view, or journal is persisted
**Testing**: Akka `TestKitSupport` + `TestModelProvider` (agent unit test); `httpClient` (endpoint integration test); JUnit 5 + AssertJ, run via Maven Surefire/Failsafe
**Target Platform**: Akka runtime (local `mvn compile exec:java`; deployable container image)
**Project Type**: Single Akka service (web service exposing an HTTP endpoint backed by an Agent)
**Performance Goals**: Baseline skeleton — no specific throughput target; one model call per request, latency dominated by the LLM
**Constraints**: Stateless; no authentication beyond endpoint ACL; tests MUST NOT call a live model (use `TestModelProvider`)
**Scale/Scope**: One agent, one endpoint, one domain request/response model; single-region local development

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| **I. Akka SDK First** | PASS (with documented deviation). Greeting capability built from Akka SDK primitives only: `Agent` + `HttpEndpoint`. No custom/third-party framework introduced. The Scala 3 language choice and its build dependencies (`scala3-library`, `scala-maven-plugin`) are deviations from the Java-centric conventions in AGENTS.md — justified in Complexity Tracking by the explicit user requirement and the repository identity (`akka-agentic-scala3`). |
| **II. Design Principles** | PASS. Domain types (`GreetingRequest`, `GreetingResponse`) live in `domain` with no Akka imports (domain independence). The endpoint defines its own API request/response types and never exposes domain internals (API isolation). One agent = one task; one endpoint = greeting transport (single responsibility). Names are domain-aligned: `GreetingAgent`, `GreetingEndpoint`. |
| **III. Test Coverage** | PASS. Plan includes a `GreetingAgentTest` (mocked model, success + the agent's behavior) and a `GreetingEndpointIntegrationTest` (success path + at least one validation-failure path), satisfying FR-010 and SC-004. |
| **IV. Simplicity** | PASS. No entity, view, workflow, session memory tuning, or speculative enum is added. The typed payload is a single case class; intent adaptation (US3) is delegated to the LLM rather than modeled as extra code. |

**Result**: GATE PASS. Deviations recorded in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-greeting-agent/
├── plan.md              # This file (/akka.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── greeting-api.md  # HTTP contract for POST /greet
├── checklists/
│   └── requirements.md  # From /akka.specify
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
pom.xml                         # Add scala-maven-plugin + scala3-library; src/main/scala source root
src/
├── main/
│   ├── scala/com/example/
│   │   ├── domain/
│   │   │   └── Greeting.scala          # GreetingRequest, GreetingResponse case classes (+ validation), no Akka deps
│   │   ├── application/
│   │   │   └── GreetingAgent.scala     # extends akka.javasdk.agent.Agent, @Component(id="greeting-agent")
│   │   └── api/
│   │       └── GreetingEndpoint.scala  # @HttpEndpoint, POST /greet, owns API request/response types
│   └── resources/
│       └── application.conf            # akka.javasdk.agent.model-provider config (env-driven API key)
└── test/
    └── scala/com/example/
        ├── application/
        │   └── GreetingAgentTest.scala         # TestKitSupport + TestModelProvider
        └── api/
            └── GreetingEndpointIntegrationTest.scala  # TestKitSupport + httpClient
```

**Structure Decision**: Single Akka service using the mandated three-layer package structure
(`com.example.domain`, `com.example.application`, `com.example.api`) with **no dependency from
`domain` → `application` and none from `application` → `api`**. Scala sources live under
`src/main/scala` / `src/test/scala` (added as Maven source roots by `scala-maven-plugin`). The
existing Java `package-info.java` scaffolding under `src/main/java` is retained or removed during
implementation; new code is Scala. Components are discovered by the Akka runtime from compiled
bytecode regardless of source language.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Scala 3 components on a Java SDK (departs from AGENTS.md Java-record conventions) | Explicit user requirement ("baseline **Scala 3** project structure", "using Scala 3 enums or case classes") and repository identity (`akka-agentic-scala3`). Confirmed by clarification. | Pure-Java implementation contradicts the stated goal and repo name; a Scala-domain/Java-component hybrid adds a second language at the component boundary without removing the Scala build setup, so it is more complex, not less. |
| Build deps beyond the Akka SDK tree: `scala3-library_3` + `scala-maven-plugin` | Required to compile and run any Scala 3 code on the JVM alongside the Java SDK. | None — Scala 3 cannot be compiled without the Scala compiler/library. Scope is limited to the language toolchain; no runtime framework is added. |
| Explicit Jackson annotations (`@JsonCreator`/`@JsonProperty`) on cross-boundary case classes | The SDK's managed `ObjectMapper` is not guaranteed to register `jackson-module-scala`, so Scala case-class JSON binding is made explicit and deterministic. | Relying on `jackson-module-scala` auto-registration is unverified for the SDK's mapper and risks runtime deserialization failures; annotations are a smaller, self-contained guarantee. |
