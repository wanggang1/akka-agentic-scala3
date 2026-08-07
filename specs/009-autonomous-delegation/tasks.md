# Tasks: Autonomous-agent delegation (activity-suggestion coordinator)

**Input**: Design documents from `/specs/009-autonomous-delegation/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/activity-endpoint.md

**Tests**: INCLUDED — required by constitution III and this project's convention (every component has a
unit/integration test; `mvn verify` runs offline on `TestModelProvider`).

**Organization**: by user story (US1 P1 = MVP, US2 P2, US3 P3). Follow CLAUDE.md — one component + its test
at a time, STOP for approval between steps.

## Format: `[ID] [P?] [Story] Description`
- **[P]** = parallelizable (different files, no dep on an incomplete task)
- Paths are relative to repo root. Scala pkg root: `src/main/scala/com/gwgs/akkaagentic/activities`,
  tests: `src/test/scala/com/gwgs/akkaagentic/activities`, descriptor:
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`.

---

## Phase 1: Setup

- [ ] T001 Review the Delegation + testing docs (`akka-context/sdk/autonomous-agents/capabilities.html.md`,
  `akka-context/sdk/autonomous-agents/testing.html.md`) and confirm the API used (`define()`,
  `TaskAcceptance.of`, `Delegation.to`, per-agent `TestModelProvider` mocking); note any correction in
  `specs/009-autonomous-delegation/research.md` (Step 0, CLAUDE.md).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: framework-free domain both specialists and the coordinator depend on. No agent work until done.

- [ ] T002 [P] Create `SuggestionQuestion` (validate location/preferences → `Either`; render task
  `instruction`) in `src/main/scala/com/gwgs/akkaagentic/activities/domain/SuggestionQuestion.scala`
- [ ] T003 [P] Unit test `SuggestionQuestionTest` (blank/absent location → Left; valid → Right; instruction
  text) in `src/test/scala/com/gwgs/akkaagentic/activities/domain/SuggestionQuestionTest.scala`
- [ ] T004 [P] Create `WeatherData` (canned, deterministic per location, unknown → default, case-insensitive)
  in `src/main/scala/com/gwgs/akkaagentic/activities/domain/WeatherData.scala`
- [ ] T005 [P] Unit test `WeatherDataTest` (known location; unknown → default; case-insensitive) in
  `src/test/scala/com/gwgs/akkaagentic/activities/domain/WeatherDataTest.scala`

**Checkpoint**: domain compiles + unit tests green (`mvn test`).

---

## Phase 3: User Story 1 - Synthesized activity suggestion (Priority: P1) 🎯 MVP

**Goal**: a coordinator delegates to specialists and returns one synthesized `ActivitySuggestion` over the
start-then-poll HTTP contract.

**Independent Test**: `POST /activities {location,preferences}` → poll → `200` with a coherent suggestion
that reflects the location's simulated weather + preferences, and a non-empty `consultedSpecialists`.

- [ ] T006 [US1] **VERIFY-EARLY delegation adaptation smoke (research D1)** — minimal `ActivityCoordinator`
  (`TaskAcceptance.of(ActivityTasks.SUGGEST)` + `Delegation.to(classOf[WeatherSpecialist])`) + a minimal
  request-based `WeatherSpecialist` + a minimal `ActivityTasks`/`ActivitySuggestion`; add descriptor entries;
  run live (`mvn compile exec:java`) and confirm the coordinator delegates to the request-based worker and
  receives its reply. Record outcome + primary(request-based)/fallback(AutonomousAgent) decision in
  `specs/009-autonomous-delegation/research.md`. Files:
  `application/ActivityCoordinator.scala`, `application/WeatherSpecialist.scala`,
  `application/ActivityTasks.scala`, `application/ActivitySuggestion.scala`, descriptor `.conf`.
- [ ] T007 [US1] Finalize `ActivitySuggestion` Java-shaped result (`suggestion`, `weatherConsidered`,
  `consultedSpecialists: java.util.List[String]`, Jackson-annotated) in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivitySuggestion.scala`
- [ ] T008 [US1] Finalize `ActivityTasks.SUGGEST` (`Task.name(...).description(...).resultConformsTo(...)`) in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivityTasks.scala`
- [ ] T009 [P] [US1] Finalize `WeatherSpecialist` (system prompt, `WeatherData` lookup, `.onFailure`) in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/WeatherSpecialist.scala`
- [ ] T010 [P] [US1] Create `ActivitySpecialist` (system prompt over conditions+preferences, `.onFailure`) in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivitySpecialist.scala` + add to descriptor
  (`agent`)
- [ ] T011 [US1] Finalize `ActivityCoordinator` — `Delegation.to(classOf[WeatherSpecialist],
  classOf[ActivitySpecialist])`, `.instructions(...)` to consult specialists, synthesize, and record
  `consultedSpecialists` — in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivityCoordinator.scala` + descriptor
  (`autonomous-agent`)
- [ ] T012 [US1] Coordinator delegation integration test (`TestModelProvider` per agent — resolve D9 via
  testing.html.md; assert synthesized result + `consultedSpecialists`) in
  `src/test/scala/com/gwgs/akkaagentic/activities/application/ActivityCoordinatorIntegrationTest.scala`
- [ ] T013 [US1] Create `ActivityEndpoint` — `POST /activities` (202 + `Location` + `{taskId}`) and
  `GET /activities/{taskId}` (200 happy path) with `StartRequest`/`StartReply`/`SuggestionReply` DTOs +
  `toApi` — in `src/main/scala/com/gwgs/akkaagentic/activities/api/ActivityEndpoint.scala` + descriptor
  (`http-endpoint`)
- [ ] T014 [US1] Endpoint happy-path integration test (POST 202 → poll 200 with
  suggestion/weatherConsidered/consultedSpecialists; use `httpClient`) in
  `src/test/scala/com/gwgs/akkaagentic/activities/api/ActivityEndpointIntegrationTest.scala`

**Checkpoint**: MVP — start → poll → synthesized suggestion works end to end (`mvn verify`).

---

## Phase 4: User Story 2 - Dynamic, model-chosen delegation (Priority: P2)

**Goal**: the coordinator's choice of delegates varies with the request (not a fixed set), observable via
`consultedSpecialists`.

**Independent Test**: two differently-shaped requests yield results whose `consultedSpecialists` differ in
≥1 case.

- [ ] T015 [US2] Refine `ActivityCoordinator` `.instructions(...)` so the model consults only the relevant
  specialist(s) per request and records exactly those in `consultedSpecialists`, in
  `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivityCoordinator.scala`
- [ ] T016 [US2] Integration test: a conditions-only vs an activity-seeking request → `consultedSpecialists`
  differs (SC-002); a both-warranting request → suggestion reflects >1 specialist, not a verbatim relay
  (SC-003), in
  `src/test/scala/com/gwgs/akkaagentic/activities/application/DelegationChoiceIntegrationTest.scala`

**Checkpoint**: US1 + US2 both pass independently.

---

## Phase 5: User Story 3 - Validation + poll contract (Priority: P3)

**Goal**: validation-first + predictable poll semantics (400 / 404 / 422).

**Independent Test**: blank/absent/malformed input → 400 (no task); unknown/in-progress handle → 404;
cannot-complete → 422.

- [ ] T017 [US3] Add validation-first to `ActivityEndpoint` (blank/absent `location` → 400 via
  `SuggestionQuestion.validate`; malformed JSON → 400 at the SDK boundary; unknown property tolerated) in
  `src/main/scala/com/gwgs/akkaagentic/activities/api/ActivityEndpoint.scala`
- [ ] T018 [US3] Add poll semantics to `GET` (map `forTask(id).get(SUGGEST)` snapshot: unknown/not-terminal
  → 404; task reported cannot-complete → 422) in
  `src/main/scala/com/gwgs/akkaagentic/activities/api/ActivityEndpoint.scala`
- [ ] T019 [US3] Extend endpoint integration test with 400 (blank/absent/malformed), 404 (unknown/
  in-progress), 422 (cannot-complete) cases in
  `src/test/scala/com/gwgs/akkaagentic/activities/api/ActivityEndpointIntegrationTest.scala`

**Checkpoint**: all three stories pass independently.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T020 [P] Verify the descriptor lists all four components — `ActivityCoordinator` (`autonomous-agent`),
  `WeatherSpecialist` + `ActivitySpecialist` (`agent`), `ActivityEndpoint` (`http-endpoint`) — in
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`
- [ ] T021 [P] README: add "Capability 7 — activity coordinator (`POST /activities`)" walkthrough + interop
  "§9" (Delegation findings: `Delegation.to` accepts both Agent/AutonomousAgent; request-based-worker
  adaptation; no method-ref wall) in `README.md`
- [ ] T022 [P] Finalize `docs/multi-agent-delegation-patterns.md` with real code references (coordinator +
  specialists) in `docs/multi-agent-delegation-patterns.md`
- [ ] T023 Live smoke on Ollama `qwen3:8b`: happy path + two-shape dynamic delegation + synthesis +
  validation; record results in README "Capability 7"
- [ ] T024 Run `mvn verify` (all green) and walk `specs/009-autonomous-delegation/quickstart.md`

---

## Dependencies & Execution Order

- **Setup (P1)** → **Foundational (P2)** → **User Stories (P3–P5)** → **Polish (P6)**.
- **T006 (verify-early spike) gates the rest of US1**: if the request-based-worker adaptation fails, switch
  to the AutonomousAgent-worker fallback (localized to T007–T011) before proceeding.
- Within US1: T007/T008 (result + task) before T011 (coordinator) and T012 (test); T009/T010 (specialists)
  before T011; T011 before T013 (endpoint) → T014.
- US2 and US3 build on US1's coordinator/endpoint but are independently testable increments.
- Polish depends on US1–US3.

### Parallel opportunities
- Foundational: T002+T004 (and their tests T003/T005) in parallel.
- US1: T009 (WeatherSpecialist) ∥ T010 (ActivitySpecialist) — different files.
- Polish: T020/T021/T022 in parallel.

---

## Implementation Strategy

- **MVP = Phases 1–3 (US1)**: setup → domain → verify-early spike → specialists + coordinator + endpoint +
  tests. STOP and validate the MVP end-to-end before US2/US3.
- **Incremental**: US2 (dynamic-choice test + instructions) then US3 (validation/poll) — each an independent,
  testable increment.
- **Then Polish**: descriptor check, README §9 + cap-7 walkthrough, finalize the patterns doc, live smoke,
  `mvn verify`.
- Per CLAUDE.md: one component + its test at a time, STOP for approval between steps. Commit after each
  logical group (never commit `.env`; `git check-ignore .env` before staging).

## Notes
- Two verify-early risks (research D1 request-based-worker adaptation; D9 offline delegation mocking) are
  front-loaded (T006, T012) so we hit the SDK unknowns before building breadth — fallbacks documented.
- Two-mapper boundary: `ActivitySuggestion` stays Java-shaped; HTTP DTOs idiomatic (data-model.md).
- All Scala (no method-ref wall — research D2).
