# Data Model: Autonomous-agent delegation (activity-suggestion coordinator)

**Feature**: 009-autonomous-delegation · **Date**: 2026-08-07

Types grouped by layer. The two-mapper boundary (research D3) governs shape: **component payloads that cross
the SDK internal mapper are Java-shaped** (Jackson-annotated, `java.util.List`); **HTTP bodies are idiomatic**
Scala (`Option`, `List`). Package root: `com.gwgs.akkaagentic.activities`.

## Domain layer (`activities/domain`, no Akka deps)

### `SuggestionQuestion` (parse-don't-validate)
- Purpose: validate raw HTTP input into a proven-present value before any task starts (FR-007).
- Fields: `location: String` (non-blank), `preferences: Option[String]` (None = no preference).
- Behavior: `validate(location: String, preferences: Option[String]): Either[String, SuggestionQuestion]`
  — trims; rejects blank `location` with a message; blank/absent `preferences` → `None`.
- Also exposes `instruction: String` — renders the coordinator task instruction text from location +
  preferences (e.g. `"Suggest activities in {location}. Preferences: {preferences|none}."`).

### `WeatherData` (canned, deterministic — research D7)
- Purpose: offline weather source (no network). Pure data/logic.
- Behavior: `forLocation(location: String): String` — returns a canned condition summary for known
  locations (a small immutable map, case-insensitive), and a **default** summary for unknown locations
  (never throws). Deterministic.

## Application layer (`activities/application`)

### `ActivityCoordinator` (AutonomousAgent — component, `autonomous-agent`)
- `@Component(id = "activity-coordinator", description = "…")` — the description is injected into the
  delegation/coordinator model context; MUST be meaningful (capabilities.html.md).
- `definition() = define()`
  `.capability(TaskAcceptance.of(ActivityTasks.SUGGEST).maxIterationsPerTask(5))`
  `.capability(Delegation.to(classOf[WeatherSpecialist], classOf[ActivitySpecialist]))`
- Optional `.instructions(...)`: tell the model to consult the weather specialist for conditions and the
  activity specialist for ideas, then synthesize one suggestion and record which specialists it consulted in
  the result. No command handlers (AutonomousAgent).

### `WeatherSpecialist` (request-based Agent — component, `agent`)
- `@Component(id = "weather-specialist", description = "Reports current weather conditions for a location")`
  — the description becomes the delegation tool description the coordinator's model uses to pick it.
- One command handler: `def report(location: String): Agent.Effect[String]` — system prompt frames it as a
  weather reporter; supplies `WeatherData.forLocation(...)` (directly or via a `@FunctionTool`); returns a
  short conditions summary. `.onFailure(...)` fallback (AGENTS.md checklist).

### `ActivitySpecialist` (request-based Agent — component, `agent`)
- `@Component(id = "activity-specialist", description = "Suggests activities suited to weather and preferences")`.
- One command handler: `def suggest(brief: String): Agent.Effect[String]` — `brief` carries conditions +
  preferences (delegation instruction); returns activity ideas. `.onFailure(...)` fallback.

### `ActivityTasks` (Task holder — NOT a component)
- `val SUGGEST: Task[ActivitySuggestion] = Task.name("Suggest").description("…").resultConformsTo(classOf[ActivitySuggestion])`
- Per-request instance adds `.instructions(SuggestionQuestion.instruction)`.

### `ActivitySuggestion` (task result — Java-shaped, crosses internal mapper)
- Jackson-annotated Scala case class (like cap-3 `HelpAnswer`):
  - `suggestion: String` — the synthesized recommendation.
  - `weatherConsidered: String` — the conditions the coordinator factored in (may be empty).
  - `consultedSpecialists: java.util.List[String]` — self-reported (research D6); e.g.
    `["weather-specialist","activity-specialist"]`. Drives US2/SC-002 observability.
- `@JsonCreator`/`@JsonProperty` on all fields; `java.util.List` not Scala `List`.

## API layer (`activities/api`) — idiomatic Scala DTOs

### `ActivityEndpoint.StartRequest`
- `location: Option[String]`, `preferences: Option[String]` — `Option` so absent fields deserialize to
  `None` → 400 (not 500). `@JsonIgnoreProperties` tolerant of unknown fields.

### `ActivityEndpoint.StartReply`
- `taskId: String`.

### `ActivityEndpoint.SuggestionReply` (poll 200 body)
- `suggestion: String`, `weatherConsidered: Option[String]`, `consultedSpecialists: List[String]`.
- Built via a `toApi(ActivitySuggestion)` converter (empty `weatherConsidered` → `None`; Java list → Scala
  `List`). Endpoint never returns the domain/result type directly (constitution II — API isolation).

## Relationships / flow

```
StartRequest ──validate──▶ SuggestionQuestion ──instruction──▶ SUGGEST.instructions(...)
                                                                    │ runSingleTask
                                                                    ▼
                                                           ActivityCoordinator
                                          Delegation.to │ (model chooses)
                             ┌──────────────────────────┼───────────────────────────┐
                             ▼                                                        ▼
                     WeatherSpecialist(location)                        ActivitySpecialist(brief)
                             │ String conditions                                │ String ideas
                             └───────────────▶ coordinator synthesizes ◀────────┘
                                                    │ complete_task
                                                    ▼
                                          ActivitySuggestion ──toApi──▶ SuggestionReply
```

## Validation & state rules
- `location` blank/absent → 400 before any task (FR-007).
- Unknown `taskId` or task not terminal → 404 (FR-008, FR-006).
- Task reported unable to complete → 422 (FR-009), distinct from 404/200.
- `WeatherData` never throws on unknown location (FR-010 edge case).
