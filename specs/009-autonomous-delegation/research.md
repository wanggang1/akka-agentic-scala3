# Research: Autonomous-agent delegation (activity-suggestion coordinator)

**Feature**: 009-autonomous-delegation · **Date**: 2026-08-07

Phase 0 decisions and the SDK verifications behind them. Consistent with the project's prior interop
findings (README "Scala interop notes" §2/§3/§5).

## D1 — Delegation targets: request-based Agents (locked, option 2)

- **Decision**: The coordinator is an `AutonomousAgent`; the two specialists (`WeatherSpecialist`,
  `ActivitySpecialist`) are **request-based `Agent`s** delegated to via `Delegation.to(...)`.
- **Verification (bytecode, SDK 3.6.0)**: `Delegation.to(Class<? extends AgentDelegationWorker>,
  Class<? extends AgentDelegationWorker>...)`. **Both** `akka.javasdk.agent.Agent` and
  `akka.javasdk.agent.autonomous.AutonomousAgent` `implements AgentDelegationWorker`, so a request-based
  Agent is a valid delegation worker. This resolves the apparent conflict between AGENTS.md's dynamic-team
  snippet (request-based workers) and `capabilities.html.md`'s canonical example (AutonomousAgent workers) —
  both are valid; the target base class is not constrained beyond the marker interface.
- **Rationale**: Simplicity (constitution IV) — the specialists are single-model-call agents; typed subtask
  results and per-subtask durable task records (what AutonomousAgent workers add) are not required here (the
  *coordinator's* result is typed either way; "which specialists were consulted" is the coordinator's own
  delegation activity, observable regardless). Also the cleanest contrast with cap-6 (same request-based
  agent style, blessed `Delegation` wiring vs hand-rolled `ForwardTool`).
- **Alternatives considered**: AutonomousAgent workers (canonical `capabilities.html.md` form) — fuller
  primitive, typed/validated subtask results, but more moving parts (3 autonomous agents + 3 task types) and
  benefits that are speculative for this tool-light domain (YAGNI).
- **RISK / verify-early**: the request-based-worker *adaptation* is under-documented — how the delegation
  subtask instruction is delivered to a request-based Agent's single command handler, and how its `String`
  reply becomes the delegation tool's return value. **Mitigation**: the FIRST implementation step is a
  minimal live delegation smoke (coordinator → one request-based specialist) to confirm the adaptation
  before building both specialists + the endpoint. **Fallback**: if awkward, promote specialists to
  AutonomousAgent workers with typed subtask tasks (`WEATHER`/`ACTIVITIES`) — a localized change (agents +
  ActivityTasks), endpoint/domain untouched.

## D2 — No method-reference wall; whole capability is Scala

- **Decision**: coordinator, specialists, endpoint, and tests all in Scala (package
  `com.gwgs.akkaagentic.activities`).
- **Verification**: the delegation surface is keyed on `Class` refs and `Task` constants, not Java method
  references — `Delegation.to(Class...)`, `TaskAcceptance.of(Task)`, `forAutonomousAgent(Class, id)
  .runSingleTask(Task)`, `forTask(id).get(Task)`. Same Scala-clean surface proven in cap-3/cap-5
  (README §5). The specialists are called *by the framework* (delegation tools), not by our code, so there
  is no `componentClient.forAgent().method(...)` at all.
- **Rationale/alternatives**: none needed — Scala is the default; the Workflow-style method-ref wall (§4)
  does not apply to the autonomous-agent/delegation API.

## D3 — Two-mapper boundary (Java-shaped result, idiomatic HTTP DTOs)

- **Decision**: the coordinator's task result `ActivitySuggestion` is a **Java-shaped** Jackson-annotated
  Scala case class with a `java.util.List` (like cap-3's `HelpAnswer`), because `resultConformsTo(...)`
  drives the `complete_task` tool schema and deserializes through the SDK *internal* mapper. HTTP endpoint
  request/response bodies are **idiomatic** `Option`-typed Scala case classes. The coordinator's task
  *instruction* and the specialists' request/reply are plain `String`s (no wire type to annotate).
- **Verification**: established in feature 003 / README §3 (two Jackson mappers; internal mapper is not
  Scala-aware). Re-applied unchanged.

## D4 — Descriptor entries (hand-maintained)

- **Decision**: add to `META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`:
  `autonomous-agent += ActivityCoordinator`; `agent += WeatherSpecialist, ActivitySpecialist`;
  `http-endpoint += ActivityEndpoint`. `ActivityTasks` (Task holder) and `WeatherData` (canned domain) are
  **not** components.
- **Rationale/verification**: Scala components are invisible to the annotation processor (README §1); the
  descriptor is hand-maintained. `autonomous-agent` and `agent` are distinct keys (confirmed cap-5).

## D5 — Async start-then-poll surface

- **Decision**: `POST /activities` → `runSingleTask(SUGGEST.instructions(...))` returns the task id
  immediately (202 + `Location`); `GET /activities/{taskId}` → `forTask(taskId).get(SUGGEST)` maps the
  snapshot to 200 (result) / 404 (not-ready or unknown) / 422 (task reported it cannot complete).
- **Rationale**: coordination is multi-round-trip (delegation), so blocking the HTTP request is wrong;
  identical contract to cap-3's help-desk (README cap-3). The task is the durable record — no wrapping
  Workflow, no Entity of ours (AGENTS.md).

## D6 — "consultedSpecialists" provenance

- **Decision**: the coordinator **self-reports** the consulted specialists inside its typed
  `ActivitySuggestion` result (a `List[String]`), instructed by the task/agent guidance — the same pattern
  as cap-3's `citedTopics` (populated when the KB tool was used). Simpler and deterministic to assert than
  reading delegation notifications.
- **Alternatives**: query the coordinator's notifications / delegated task records for ground truth — richer
  but heavier; deferred (YAGNI). Tests assert the self-reported list; SC-002 (choice varies by request) is
  demonstrated by scripting the mock coordinator to delegate differently per request.

## D7 — Canned, deterministic weather (offline)

- **Decision**: `WeatherData` is a pure-domain canned lookup (a small map, deterministic per location; an
  unknown location returns a default condition, never an error) — like cap-3's `KnowledgeBase`. No network.
- **Rationale**: FR-010/SC-005 (offline, deterministic). The WeatherSpecialist reads it (directly or via a
  `@FunctionTool`); decision on tool-vs-direct deferred to implementation (either is fine — the specialist
  is a single call).

## D8 — Gemini tools-vs-JSON does NOT bite

- **Decision/finding**: the coordinator exposes delegation tools AND returns a typed result via the built-in
  `complete_task` tool. Per the cap-3 finding (README §5, Gemini note), the typed completion is delivered by
  *function calling* (`complete_task`), not a JSON response mime type, so it coexists with the delegation
  tools — no tools-vs-JSON conflict. Same as cap-3/cap-5.

## D9 — Offline testing of delegation (known-unknown)

- **Decision/approach**: register a `TestModelProvider` per agent class (coordinator + each specialist) via
  `withModelProvider(Class, provider)`. Script the coordinator's mock to invoke the delegation tool(s), and
  each specialist's mock to return a canned finding. Assert the synthesized result and the
  `consultedSpecialists` list; drive two differently-shaped requests for SC-002.
- **RISK / verify-early**: the exact mock wiring for delegation tools is not yet proven in this project
  (cap-3/cap-5 mocked a single agent's loop; delegation adds framework-provided tools + worker agents). Read
  `akka-context/sdk/autonomous-agents/testing.html.md` (and `AutonomousAgentTools`) at the first test step;
  if delegation can't be faithfully mocked offline, fall back to asserting the coordinator/specialist wiring
  in isolation offline and proving the end-to-end delegation **live** (as cap-4/cap-6 did for recall).

## Open items carried into implementation

- Verify D1 adaptation (request-based worker) — first live smoke.
- Verify D9 delegation mocking — first test step; read testing.html.md.
- Decide WeatherSpecialist canned-data access (direct vs `@FunctionTool`) — implementation detail.
