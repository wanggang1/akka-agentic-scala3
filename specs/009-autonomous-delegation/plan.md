# Implementation Plan: Autonomous-agent delegation (activity-suggestion coordinator)

**Branch**: `009-autonomous-delegation` | **Date**: 2026-08-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/009-autonomous-delegation/spec.md`

## Summary

Capability 7: an `ActivityCoordinator` (AutonomousAgent) accepts a `SUGGEST` task and, via the SDK's
built-in `Delegation` capability, lets the model dynamically consult a `WeatherSpecialist` and an
`ActivitySpecialist` (both **request-based Agents** — research D1), then **synthesizes** a single typed
`ActivitySuggestion`. Exposed start-then-poll over HTTP (like cap-3). All Scala (no method-ref wall — D2).
Purpose: showcase the **recommended** dynamic-delegation primitive and contrast it head-to-head with cap-6's
hand-rolled `ForwardTool` chaining and cap-2's fixed Java Workflow — documented in
`docs/multi-agent-delegation-patterns.md`.

## Technical Context

**Language/Version**: Scala 3 on the Akka Java SDK 3.6.0 (via `scala-maven-plugin`).
**Primary Dependencies**: `akka-javasdk` — `AutonomousAgent`, `Agent`, `Delegation`, `TaskAcceptance`,
`Task`, `ComponentClient`, HTTP endpoint annotations. No new external deps (constitution I).
**Storage**: none of ours — the `Task` is the durable record (runtime-owned), like cap-3/cap-5. No Entity.
**Testing**: `TestModelProvider` (per-agent), `TestKitSupport`, JUnit 5 + AssertJ; `mvn verify`.
**Target Platform**: local (Ollama qwen3:8b default) / deployed Akka runtime.
**Project Type**: single service (this repo).
**Performance/Scale**: coordinator is per-request (fresh UUID instance id), cluster-sharded by id — no
singleton bottleneck. Specialists are stateless request-based agents.
**Constraints**: offline & deterministic for tests (canned weather, mock model — FR-010/SC-005); Java-shaped
task result vs idiomatic HTTP DTOs (two-mapper boundary — D3).

## Constitution Check

*GATE: must pass before Phase 0; re-checked after Phase 1.*

- **I. Akka SDK First**: ✅ Built entirely on SDK primitives (AutonomousAgent + Delegation + request-based
  Agents + Task + HTTP endpoint). No new external dependency. Delegation is the SDK-*recommended* primitive
  for this pattern — no deviation to justify.
- **II. Design Principles**: ✅ Domain (`SuggestionQuestion`, `WeatherData`) is framework-free and unit
  testable; endpoint defines its own DTOs with a `toApi` converter (API isolation); one focused component
  each (coordinator, two specialists); domain-aligned names (no `Manager`/`Service`).
- **III. Test Coverage**: ✅ Domain unit tests; agent/endpoint integration tests with mock model; live smoke
  for the delegation adaptation + end-to-end. Tests precede/accompany each component (task plan).
- **IV. Simplicity**: ✅ Request-based specialists over AutonomousAgent workers (D1) — the simpler option
  that meets the requirement; no subtask task types, no Entity, no Workflow; canned weather, not an API.
  YAGNI: no extra specialists, no reusable delegation framework (spec Out of Scope).

**Post-Phase-1 re-check**: ✅ still holds — Phase 1 added only DTOs, a Task holder, and a canned domain
object; no new abstractions or dependencies.

**Complexity Tracking**: no violations to justify.

## Project Structure

### Documentation (this feature)
```text
specs/009-autonomous-delegation/
├── plan.md              # this file
├── research.md          # Phase 0 — decisions D1–D9 + SDK verifications
├── data-model.md        # Phase 1 — types by layer
├── quickstart.md        # Phase 1 — curl + run/verify
├── contracts/
│   └── activity-endpoint.md
├── checklists/
│   └── requirements.md  # spec quality (all pass)
└── tasks.md             # Phase 2 — created by /akka.tasks (NOT here)
```

### Source Code (repository root)
```text
src/main/scala/com/gwgs/akkaagentic/activities/
├── domain/
│   ├── SuggestionQuestion.scala     # validation + task-instruction rendering
│   └── WeatherData.scala            # canned, deterministic weather (offline)
├── application/
│   ├── ActivityCoordinator.scala    # AutonomousAgent: TaskAcceptance + Delegation.to(...)
│   ├── WeatherSpecialist.scala      # request-based Agent (delegation worker)
│   ├── ActivitySpecialist.scala     # request-based Agent (delegation worker)
│   ├── ActivityTasks.scala          # Task holder (SUGGEST) — not a component
│   └── ActivitySuggestion.scala     # Java-shaped task result (crosses internal mapper)
└── api/
    └── ActivityEndpoint.scala       # POST /activities, GET /activities/{taskId} (idiomatic DTOs)

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
    # += autonomous-agent: ActivityCoordinator; agent: WeatherSpecialist, ActivitySpecialist;
    #    http-endpoint: ActivityEndpoint

src/test/scala/com/gwgs/akkaagentic/activities/
├── domain/       # SuggestionQuestion, WeatherData unit tests
├── application/  # coordinator delegation integration test (mock per agent)
└── api/          # endpoint integration test (start-poll, 400/404/422)

docs/multi-agent-delegation-patterns.md   # cross-capability rubric (folded into this plan phase)
```

**Structure Decision**: single-service layout, new `activities` package mirroring cap-3/cap-5. No Entity,
no Workflow (the Task is the durable record). Descriptor hand-maintained (README §1).

## Implementation sequence (high level — detailed in /akka.tasks)

Per CLAUDE.md, one component + its test at a time, STOP for approval between:
1. **Delegation adaptation smoke (verify-early, D1)** — coordinator + one request-based specialist; a live
   check that the request-based-worker adaptation works. Decide primary vs fallback here.
2. **Domain** — `SuggestionQuestion`, `WeatherData` + unit tests.
3. **Specialists** — `WeatherSpecialist`, `ActivitySpecialist` (+ `onFailure`) + tests.
4. **Coordinator + tasks + result** — `ActivityCoordinator`, `ActivityTasks`, `ActivitySuggestion`;
   descriptor entries; delegation integration test (resolve D9 mocking here — read testing.html.md).
5. **Endpoint** — `ActivityEndpoint` + integration test (start-poll, validation, 404/422).
6. **Docs** — README cap-7 section + interop §9; `docs/multi-agent-delegation-patterns.md` (started this
   phase, finalized with real code refs).
7. **Live smoke** — end-to-end on Ollama; confirm dynamic delegation + synthesis + isolation.

## Risks (from research)
- **R-D1** request-based-worker adaptation under-documented → mitigated by step 1 smoke; fallback to
  AutonomousAgent workers (localized).
- **R-D9** offline delegation mocking unproven in this project → resolved in step 4; fallback to live proof.
- **Model quality** — delegation is function calling; weak local models may mis-delegate. Mitigate with a
  strong tool-calling model; `onFailure` degrades gracefully (cap-6 lesson).

## Next
`/akka.tasks` to generate the dependency-ordered `tasks.md`.
