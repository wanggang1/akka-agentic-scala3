# Feature Specification: Autonomous-agent delegation (activity-suggestion coordinator)

**Feature Branch**: `009-autonomous-delegation`
**Created**: 2026-08-07
**Status**: Draft
**Input**: User description: "Capability 7 — multi-agent delegation via the AutonomousAgent's built-in Delegation capability (the SDK-recommended dynamic-delegation primitive), as the blessed counterpart to capability 6's hand-rolled request-based agent chaining. A coordinator delegates to specialist agents chosen dynamically by the model and synthesizes their results into a typed answer, exposed over a start-then-poll HTTP contract."

## Context *(project background — this is a learning sandbox)*

This capability is capability 7 in an exploration of Akka agentic patterns on Scala 3. Its purpose is to
realize multi-agent delegation using the platform's **recommended** dynamic-delegation primitive (a
coordinator that delegates to specialist agents the model selects at runtime), and to contrast it head-to-head
with capability 6, which achieved agent-to-agent work through hand-rolled request chaining, and with
capability 2, which orchestrated the same weather+activity idea through a fixed, code-ordered sequence.

To keep the exploration deterministic and offline (no network, no external APIs), specialist knowledge
(e.g. weather) is **canned/simulated**, exactly as capability 3's knowledge base is. The value being
demonstrated is the *coordination behavior*, not real weather data.

## Clarifications

### Session 2026-08-07

- Q: What component type are the two specialists (WeatherSpecialist, ActivitySpecialist)? → A: Request-based
  Agents — each a single-model-call agent; the coordinator (an autonomous agent) delegates to them via its
  built-in dynamic-delegation capability. (Rationale: canonical for the dynamic-delegation primitive, lighter
  for single-call specialists, and the cleanest contrast with cap-6 — same agent style, blessed wiring vs
  hand-rolled chaining. Deterministic operations stay tools, not agents; only distinct *reasoning*
  specialties — weather narration, activity judgment — become delegate agents.)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get a synthesized activity suggestion (Priority: P1)

A person wants a suggestion for what to do in a given place. They submit a location (and optional
preferences), and receive a single, coherent activity suggestion that has taken the local weather into
account — produced by a coordinator that consulted the relevant specialists on their behalf and combined
their findings into one answer.

**Why this priority**: This is the MVP and the whole point of the capability — a coordinator that delegates
to specialists and **synthesizes** a single result (as opposed to capability 6's relayed reply). Without
this, there is no feature.

**Independent Test**: Submit a request with a location and preferences; poll the handle; confirm the returned
suggestion is a coherent activity recommendation that reflects both the (simulated) weather for that location
and the stated preferences, and that it names which specialists were consulted.

**Acceptance Scenarios**:

1. **Given** a valid location and preferences, **When** the request is submitted and then polled to
   completion, **Then** the caller receives a typed result containing an activity suggestion, the weather
   that was considered, and the list of specialists consulted.
2. **Given** a submitted request that is still being worked on, **When** the caller polls, **Then** they get
   a "not ready yet" response until the coordinator finishes, after which polling returns the completed
   result.

---

### User Story 2 - Delegation targets are chosen dynamically by the model (Priority: P2)

The coordinator does not follow a fixed script. Depending on the request, the model decides which
specialist(s) to consult — for example, a request that is purely about conditions may consult only the
weather specialist, while a request for things to do consults the activity specialist (and the weather
specialist for context). The set of specialists actually consulted is reported in the result.

**Why this priority**: This is the defining contrast with capability 2 (a fixed, code-ordered sequence) and
the essence of the "recommended dynamic delegation" being explored. It is valuable but the feature still
delivers a suggestion (US1) even if, in practice, the model always consults both.

**Independent Test**: Submit two differently-shaped requests (one conditions-only, one activity-seeking) and
confirm from each result's reported "consulted specialists" that the coordinator's choice of delegates
varied with the request rather than being identical/hard-coded.

**Acceptance Scenarios**:

1. **Given** a request that only asks about conditions, **When** it completes, **Then** the result's
   consulted-specialists list reflects that the weather specialist was used (and may omit the activity
   specialist).
2. **Given** a request seeking activities, **When** it completes, **Then** the result reflects that the
   activity specialist was consulted, with weather taken into account.

---

### User Story 3 - Predictable request/poll contract with validation (Priority: P3)

Callers get the same disciplined, predictable HTTP contract as the project's other asynchronous
capabilities: invalid input is rejected immediately, a submitted request returns a handle, and polling
distinguishes "not ready", "done", and "unknown".

**Why this priority**: Consistency and robustness across the project; important but subordinate to the core
delegation behavior.

**Independent Test**: Submit blank/malformed input and confirm immediate rejection with no work started; poll
an unknown handle and confirm a not-found response; poll a valid in-progress handle and confirm not-ready
until the result is available.

**Acceptance Scenarios**:

1. **Given** a blank location or malformed request body, **When** submitted, **Then** it is rejected up front
   and no coordination work begins.
2. **Given** a handle that was never issued, **When** polled, **Then** the caller receives a not-found
   response.
3. **Given** a valid handle whose work is complete, **When** polled, **Then** the caller receives the typed
   result.

---

### Edge Cases

- **Coordinator cannot produce a suggestion** (e.g. the model reports it is unable to complete the task):
  polling surfaces a distinct "could not complete" outcome rather than a never-ready poll or a success.
- **Unknown/uninterpretable location**: the coordinator still returns a best-effort suggestion using the
  simulated weather's default handling, rather than failing the whole request.
- **Model produces a malformed final result**: the caller receives a graceful failure/degraded outcome, not
  a raw internal error (consistent with the project's other agents).
- **Empty preferences**: treated as "no particular preference"; the suggestion is still produced.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST accept a request containing a location and optional preferences and start a
  coordination task that returns a handle to the caller.
- **FR-002**: The coordinator MUST be able to consult a weather specialist and an activity specialist, and
  MUST decide which specialist(s) to consult based on the request rather than a fixed, hard-coded sequence.
- **FR-003**: The coordinator MUST combine the specialists' findings into a **single synthesized result** (it
  MUST NOT merely relay one specialist's reply verbatim).
- **FR-004**: The completed result MUST include the activity suggestion, the weather that was considered, and
  the list of specialists that were consulted.
- **FR-005**: The activity suggestion MUST reflect both the (simulated) weather for the location and any
  stated preferences.
- **FR-006**: The system MUST expose the work through a start-then-poll contract: submission returns a handle
  immediately; polling returns "not ready" while in progress and the typed result once complete.
- **FR-007**: The system MUST reject a blank location or a malformed request body up front, before any
  coordination work begins.
- **FR-008**: Polling an unknown/never-issued handle MUST return a not-found response, distinct from
  "not ready".
- **FR-009**: A task the coordinator reports it cannot complete MUST be distinguishable by the caller from
  both "not ready" and a successful result.
- **FR-010**: Specialist knowledge (weather) MUST be simulated/canned so the capability runs deterministically
  offline with no external network dependency.
- **FR-011**: The capability MUST be demonstrable without a live model (deterministic tests) and verifiable
  end-to-end with a live model (smoke test), consistent with the project's other capabilities.

### Key Entities *(include if feature involves data)*

- **Suggestion request**: what the caller submits — a location and optional free-text preferences.
- **Weather finding**: a specialist's (simulated) summary of conditions for a location.
- **Activity finding**: a specialist's suggested activities given conditions and preferences.
- **Coordinated suggestion (result)**: the synthesized answer — the activity suggestion, the weather
  considered, and the list of specialists consulted.
- **Task handle**: the identifier the caller uses to poll for the result.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A caller can submit a location + preferences and, after polling, receive a coherent activity
  suggestion that visibly reflects both the location's simulated weather and the stated preferences.
- **SC-002**: Across a conditions-only request and an activity-seeking request, the result's reported
  "consulted specialists" differs in at least one case — demonstrating the coordinator chose delegates based
  on the request rather than a fixed sequence.
- **SC-003**: The result is a single synthesized suggestion that reflects more than one specialist's input
  (not a verbatim relay of one specialist), for a request that warrants both.
- **SC-004**: 100% of blank/malformed submissions are rejected before any coordination work starts; unknown
  handles always poll as not-found; in-progress handles always poll as not-ready until completion.
- **SC-005**: The full behavior is reproducible offline with deterministic tests (no network, no live model),
  and confirmed once end-to-end against a live model.

## Assumptions & Dependencies

- **Domain**: an activity-suggestion coordinator with a weather specialist and an activity specialist — chosen
  because it is tool-light, intuitive, and directly comparable to capability 2 (same idea, fixed Java
  workflow) and capability 6 (different delegation mechanism). The domain is illustrative; the coordination
  pattern is the point.
- **Input shape**: `location` is required (a place name); `preferences` is optional free text (e.g.
  "outdoorsy, with kids"). Absent preferences means "no particular preference".
- **Simulated weather**: canned/deterministic per location (like capability 3's knowledge base); no external
  weather service. A recognizable-but-unknown location falls back to a default condition rather than erroring.
- **Asynchronous by nature**: coordination involves multiple model round-trips, so the surface is
  start-then-poll (as in capability 3), not synchronous.
- **Recommended-primitive constraint (why this capability exists)**: delegation MUST be realized through the
  platform's built-in dynamic-delegation coordination primitive (a coordinator delegating to specialist agent
  types the model selects), not through hand-rolled request chaining as in capability 6. This is a deliberate
  head-to-head contrast, documented as part of the capability's outcome.
- **Component shape** (per Clarifications): the coordinator is an autonomous agent; the two specialists are
  request-based agents it delegates to via the built-in dynamic-delegation capability. Deterministic work
  (e.g. a canned weather lookup) stays a tool, not an agent; only distinct reasoning specialties are agents.
- **Language/interop**: implemented in Scala, consistent with capabilities 3 and 5 (the autonomous-agent
  surface has no method-reference wall). Implementation/interop specifics are deferred to planning.

## Out of Scope

- Real weather data or any external API integration.
- Human-in-the-loop approval (that is capability 5).
- Per-user memory or conversational state (that is capabilities 4 and 6); each request is self-contained.
- A general re-usable delegation framework; this is a single illustrative coordinator with two specialists.
