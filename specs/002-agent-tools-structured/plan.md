# Implementation Plan: Structured, context-aware greeting

**Branch**: `002-agent-tools-structured` | **Date**: 2026-06-28 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-agent-tools-structured/spec.md`

## Summary

Evolve the baseline greeting agent from a plain-string reply into a **structured, typed result**
that also carries **request-time context**. The agent gains a function tool that reports the
current time-of-day, and returns a structured object (`greeting`, `tone`, `timeOfDay`) via the
SDK's structured-response mechanism. The `POST /greet` endpoint returns this as its own API
response type. Input validation (400, no model call) is preserved. All behavior stays testable
offline with a mocked model; the time-of-day computation is a pure domain function with its own
unit tests.

## Technical Context

**Language/Version**: Scala 3.3.x on the Akka Java SDK 3.6.0 (JVM 21)
**Primary Dependencies**: `akka-javasdk` 3.6.0 (Agent, HTTP endpoint, `@FunctionTool`,
`responseConformsTo`); `scala-maven-plugin`; Jackson (via SDK `JsonSupport`); `java.time` (stdlib)
**Storage**: N/A (stateless, single request/response — no entity or memory in this feature)
**Testing**: JUnit 5 + AssertJ; `TestKitSupport` + `TestModelProvider` (mocked model, no network);
`httpClient` for endpoint integration tests
**Target Platform**: Akka runtime (local `exec:java`; container image for platform deploy)
**Project Type**: Single Akka service (Scala 3), three-layer packages `domain`/`application`/`api`
**Performance Goals**: N/A for this exploration (correctness + determinism over throughput)
**Constraints**: Tests MUST run with no API key / no network (SC-004); structured output and the
time tool must not regress the 001 validation contract
**Scale/Scope**: One agent, one function tool, one structured result type, one endpoint; ~6 source
files touched/added plus tests

No NEEDS CLARIFICATION remain (see research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First** — PASS. Time-of-day is exposed as an Akka `@FunctionTool` on the Agent;
  the structured reply uses the SDK's `responseConformsTo`; the API stays an Akka HTTP endpoint.
  Only new "dependency" is `java.time` (JDK standard library) — no third-party additions.
- **II. Design Principles** — PASS. Time-of-day logic lives in `domain` as a pure function
  (domain independence, isolated unit tests). The endpoint defines its own response type
  (`GreetReply`) distinct from the agent's `Result` (API isolation). The agent keeps a single
  command handler (single responsibility). Names are domain-aligned (`TimeOfDay`, `GreetingAgent`).
- **III. Test Coverage** — PASS. New behavior is covered: a domain unit test for the time-of-day
  function, an agent test for the structured reply, and endpoint integration tests for the
  structured 200 plus the preserved 400s.
- **IV. Simplicity** — PASS. One tool, one structured type; reuses the existing endpoint and
  descriptor entry. No new components, no entity/memory, no speculative extension points (YAGNI).

**Post-Phase 1 re-check**: PASS — the design below introduces no new components, no external
dependencies, and no domain→framework coupling.

## Project Structure

### Documentation (this feature)

```text
specs/002-agent-tools-structured/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (greeting-api.md)
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/
├── domain/
│   └── Greeting.scala            # + TimeOfDay pure function (hour/zone -> label, never throws)
├── application/
│   └── GreetingAgent.scala       # + @FunctionTool timeOfDay; Result record; responseConformsTo
└── api/
    └── GreetingEndpoint.scala     # GreetReply gains tone + timeOfDay; maps agent Result -> GreetReply

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # unchanged (tool is not a component)

src/test/scala/com/gwgs/akkaagentic/
├── domain/
│   └── GreetingTest.scala         # + TimeOfDay cases (boundaries, invalid zone -> default, fallback)
├── application/
│   └── GreetingAgentTest.scala    # + structured-response test (fixedResponse of encoded Result)
└── api/
    └── GreetingEndpointIntegrationTest.scala  # structured 200; existing 400s still pass
```

**Structure Decision**: Reuse the established three-layer Scala layout from 001. The only new
domain concept (time-of-day labeling) is a pure function added to the existing `Greeting.scala`;
the agent and endpoint are extended in place. No new descriptor entry is needed — a
`@FunctionTool` is a method on the existing agent, not a separately discovered component.

## Complexity Tracking

> No constitution violations — section intentionally empty.
