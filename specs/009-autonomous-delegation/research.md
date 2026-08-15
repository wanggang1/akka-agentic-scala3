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
- **RISK / verify-early — RESOLVED ✅ (T006, live 2026-08-07)**: the request-based-worker *adaptation* was
  under-documented. The T006 spike (coordinator + request-based `WeatherSpecialist`, live on Ollama qwen3:8b)
  **confirmed it works**: `POST /activities {location:"Boston",preferences:"outdoorsy, with kids"}` →
  `200 {"suggestion":"...Franklin Park...playgrounds...","weatherConsidered":"Clear skies, 20°C...",
  "consultedSpecialists":["weather-specialist"]}`. The framework delivers the subtask instruction to the
  request-based Agent's single command handler and returns its `String` reply to the coordinator, which
  synthesizes the typed result. **Primary approach (request-based specialists) stands; the AutonomousAgent-
  worker fallback is NOT needed.**

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
  as cap-3's `citedTopics`. Simpler than reading delegation notifications.
- **LIVE FINDING (T010/T011, 2026-08-07) — self-report is UNRELIABLE on qwen3:8b.** Proven: a "London"
  request returned `weatherConsidered:"lightly raining, 12°C"` (the exact canned London weather) but
  `consultedSpecialists:["activity-specialist"]` — under-reporting the weather delegation. An "Atlantis"
  (unknown) request returned `weatherConsidered:"Mild, partly cloudy, 18°C"` = `WeatherData`'s default
  (un-hallucinatable for a mythical city), **proving weather WAS consulted** despite not being listed. So
  the model does the delegation but does not faithfully report it.
- **Consequence**: **offline tests are unaffected** — the mock coordinator is *scripted* to call specific
  delegation tools and to complete with an exact `consultedSpecialists`, so SC-002 (choice varies by request)
  is asserted deterministically. **Live**, `consultedSpecialists` is best-effort only. If reliable *live*
  provenance is wanted, switch to the runtime's delegation records/notifications (ground truth) instead of
  self-report — heavier; deferred unless the user wants live SC-002 proof. Documented as a model limitation
  (like cap-6's qwen3 quirks).

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
- **RESOLVED ✗ (T012, 2026-08-07) — request-based delegation is NOT faithfully mockable in the 3.6.0
  testkit; fell back to the live-proof plan.** The testkit's `AutonomousAgentTools.delegateTo(Class, String)`
  (the request-based worker form) builds `ToolInvocationRequest(toolName, argsVerbatim)` and the runtime
  delivers the payload to the worker as a **generic `json.akka.io/object`** with no specific type tag, so
  the worker's command handler cannot deserialize it — proven with **both** a raw `String` param (`Could not
  deserialize [json.akka.io/object] to [java.lang.String]`) **and** a Java-shaped record (`… to
  [SpecialistBrief]`). The 3-arg autonomous-worker form `delegateTo(Task, AutonomousAgent, String)` carries
  the task's result type and would round-trip, but our workers are request-based (D1). **Live is unaffected**
  — the real runtime tags the delegation payload with the worker's type (proven in T006 via `WeatherData`'s
  un-hallucinatable canned default), so live delegation works.
- **Consequence (implemented)**: the offline coordinator test
  ([`ActivityCoordinatorIntegrationTest`](../../src/test/scala/com/gwgs/akkaagentic/activities/application/ActivityCoordinatorIntegrationTest.scala))
  asserts the coordinator's task → typed-result → endpoint path with a **direct** `completeTask` (no
  delegation mock — a delegation mock produced a silent false-green: the WARN-level delegation failure did
  not fail the test because the scripted completion fired regardless). **Delegation + synthesis is proven
  live** (T006/T011 + the live smoke), the same live-only pattern cap-4/cap-6 used for recall.
- **TODO (logged, deferred — see [[akka-agent-todos]])**: revisit offline request-based-delegation mocking on
  a **newer SDK** (>3.6.0). The `delegateTo(Class,String)` helper exists but its payload lacks a type tag;
  a newer testkit may fix the round-trip. The SDK bump is repo-wide (all 7 caps) → its own branch + full
  `mvn verify` + live spot-checks, not part of cap-7.

## Open items carried into implementation

- ~~Verify D1 adaptation (request-based worker)~~ — ✅ done live (T006).
- ~~Verify D9 delegation mocking~~ — ✗ not mockable in 3.6.0 (see D9); delegation is live-proven. TODO to
  revisit on a newer SDK.
- Decide WeatherSpecialist canned-data access (direct vs `@FunctionTool`) — chose `@FunctionTool`
  (`currentConditions`), verified live in T006.
