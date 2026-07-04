# Tasks: Multi-agent greeting Workflow

**Input**: Design documents from `/specs/004-multi-agent-workflow/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/greeting-team-api.md

**Tests**: Included — Constitution III and FR-011 mandate them. Per this repo's `CLAUDE.md` incremental
workflow, each component's test is created **right after** the component (component-then-test), not
strict TDD-first. Verify with `mvn compile` / `mvn verify` at the points noted.

**Language**: Capability 2 is **Java** (`com.gwgs.akkaagentic.team.*`), self-contained and decoupled
from the Scala capability 1 (research R1). Cap-1 is not touched.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different file, no dependency on an incomplete task → parallelizable
- **[Story]**: US1–US4 (setup/foundational/polish carry no story label)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: enable mixed Scala+Java compilation without breaking the descriptor.

- [x] T001 Add `maven-compiler-plugin` (default `compile` + `testCompile`) to `pom.xml` with `<proc>none</proc>` to disable the Akka annotation processor (research R2); run `mvn compile` and confirm cap-1 still builds green (no Java sources yet).
- [x] T002 [P] Create cap-2 package directories: `src/main/java/com/gwgs/akkaagentic/team/{api,application,domain}` and `src/test/java/com/gwgs/akkaagentic/team/{api,application,domain}`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: pure Java domain + shared wire records that every user story depends on. No Akka deps.

**⚠️ CRITICAL**: complete before any user-story phase.

- [x] T003 [P] Create `TimeOfDay` in `src/main/java/com/gwgs/akkaagentic/team/domain/TimeOfDay.java` — `of(Instant, ZoneId)` labels (morning 05–11, afternoon 12–16, evening 17–20, night else) and `now(String timezone)` with null/blank/invalid → UTC; never throws (data-model).
- [x] T004 [P] Create `Tone` in `src/main/java/com/gwgs/akkaagentic/team/domain/Tone.java` — `NEUTRAL` constant and `normalize(String)` (trim; null/blank → NEUTRAL).
- [x] T005 [P] Create the shared wire records in `src/main/java/com/gwgs/akkaagentic/team/application/`: `StartGreeting.java` (user, text, timezone), `ComposeRequest.java` (user, text, tone, timezone), `GreetingResult.java` (greeting, tone, timeOfDay) — plain Java records (data-model).
- [x] T006 [P] Create `TimeOfDayTest` in `src/test/java/com/gwgs/akkaagentic/team/domain/TimeOfDayTest.java` — label boundaries + absent/blank/invalid-zone → UTC fallback (depends on T003).

**Checkpoint**: `mvn test` green (domain compiles and TimeOfDay unit test passes).

---

## Phase 3: User Story 1 - Start a greeting and retrieve the composed result (Priority: P1) 🎯 MVP

**Goal**: end-to-end orchestration — start a greeting, two agents run (tone → compose), retrieve the
structured `{greeting, tone, timeOfDay}`.

**Independent Test**: with both agents mocked, `POST /greetings` then poll `GET /greetings/{id}` until
`200`; assert all three fields and that the greeting reflects the detected tone (contracts C1, C3, C6).

- [x] T007 [US1] Create `ToneAgent` in `src/main/java/com/gwgs/akkaagentic/team/application/ToneAgent.java` — `@Component(id="tone-agent")`, `Effect<String> detect(String text)`, system message classifies tone/intent and replies a single lowercase label; no tool; `thenReply()`. *(descriptor: added `tone-agent` — part of T012, done incrementally)*
- [x] T008 [P] [US1] Create `ToneAgentTest` in `src/test/java/com/gwgs/akkaagentic/team/application/ToneAgentTest.java` — `TestModelProvider` fixed label; assert `detect` returns it.
- [x] T009 [US1] Create `GreetingComposerAgent` in `src/main/java/com/gwgs/akkaagentic/team/application/GreetingComposerAgent.java` — `@Component(id="greeting-composer-agent")`, `Effect<GreetingResult> compose(ComposeRequest)`, `@FunctionTool currentTimeOfDay(String timezone)` → `TimeOfDay.now(...)`, `responseAs(GreetingResult.class)` + `onFailure` safe fallback (name user, carry `req.tone()`, `TimeOfDay.now(req.timezone())`); system message composes **given** the tone and emits the exact JSON shape (research R4 — Gemini). *(descriptor: added `greeting-composer-agent`)*
- [x] T010 [P] [US1] Create `GreetingComposerAgentTest` in `src/test/java/com/gwgs/akkaagentic/team/application/GreetingComposerAgentTest.java` — fixed JSON → structured result; non-JSON reply → `onFailure` fallback fields.
- [x] T011 [US1] Create `GreetingWorkflow` in `src/main/java/com/gwgs/akkaagentic/team/application/GreetingWorkflow.java` — `@Component(id="greeting-workflow")`, `State` record (+ String status STARTED/TONE_DETECTED/COMPLETED/FAILED, `with*` helpers), `start(StartGreeting)`/`getResult()` command handlers, steps `toneStep`/`composeStep`/`toneFallbackStep`/`failedStep` wired with native `GreetingWorkflow::` refs, `settings()` (60s step timeouts, `stepRecovery(toneStep, maxRetries(2).failoverTo(toneFallbackStep))`, `defaultStepRecovery(maxRetries(1).failoverTo(failedStep))`), `sessionId()=commandContext().workflowId()`; agents called via `componentClient.forAgent().inSession(sessionId()).method(ToneAgent::detect | GreetingComposerAgent::compose)` (depends on T005, T007, T009).
- [x] T012 [US1] Update `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` — add `tone-agent` + `greeting-composer-agent` to the `agent` list and add a `workflow = ["com.gwgs.akkaagentic.team.application.GreetingWorkflow"]` key. *(agents added in T007/T009; workflow key added here)*
- [x] T013 [US1] Create `GreetingWorkflowIntegrationTest` in `src/test/java/com/gwgs/akkaagentic/team/application/GreetingWorkflowIntegrationTest.java` — `TestKitSupport`; register `TestModelProvider` for both agents; drive `componentClient.forWorkflow(id).method(GreetingWorkflow::start)`, poll `getResult` with Awaitility; assert result reflects the mocked tone; add a tone-step-failover case → final `tone == "neutral"` (depends on T011, T012).
- [x] T014 [US1] Create `GreetingTeamEndpoint` in `src/main/java/com/gwgs/akkaagentic/team/api/GreetingTeamEndpoint.java` — `@HttpEndpoint`, `@Acl(INTERNET)`; records `StartRequest`(`@JsonIgnoreProperties`), `GreetReply`, `StartAccepted`; `POST /greetings` validates non-blank user/text (else `badRequest`), starts workflow with a UUID id via `forWorkflow(id).method(GreetingWorkflow::start)`, returns `202` + `Location: /greetings/{id}` + `{id}`; `GET /greetings/{id}` calls `getResult`, maps success→`200 GreetReply`, not-ready/unknown→`404` (depends on T011). *(build: added `-parameters` + `sendJavaToScalac=false` — see memory)*
- [x] T015 [US1] Update the descriptor — add `com.gwgs.akkaagentic.team.api.GreetingTeamEndpoint` to the `http-endpoint` list.
- [x] T016 [US1] Create `GreetingTeamEndpointIntegrationTest` in `src/test/java/com/gwgs/akkaagentic/team/api/GreetingTeamEndpointIntegrationTest.java` — `httpClient`: C1 (`POST`→`202`+Location+id), C3 (poll `GET`→`200` with 3 fields, tone reflects mock), C6 (casual, no timezone→UTC) (depends on T014, T015).

**Checkpoint**: ✅ MVP — start→poll→structured greeting works end-to-end offline. `mvn verify` green (26 unit + 10 integration); cap-1 unaffected.

---

## Phase 4: User Story 2 - Poll a greeting that is still in progress (Priority: P2)

**Goal**: not-ready and unknown-handle reads are distinct from success and never fabricate a greeting
(behavior implemented in T011/T014; this phase pins it with tests).

**Independent Test**: `GET` immediately after `POST` → `404`; `GET` on a random id → `404`.

- [ ] T017 [US2] Add to `GreetingTeamEndpointIntegrationTest.java`: C2 (immediate `GET` before completion → `404`) and C4 (unknown/never-started id → `404`, no greeting) (depends on T016).

**Checkpoint**: async lifecycle fully observable and correct.

---

## Phase 5: User Story 3 - Capability 1 remains independently usable (Priority: P2)

**Goal**: cap-1 unchanged and both capabilities discovered at startup.

**Independent Test**: cap-1's existing suite passes unmodified; service starts with all components.

- [ ] T018 [US3] Run `mvn verify`; confirm cap-1's Scala tests pass unchanged and the descriptor lists **all** components (cap-1 agents/endpoints + cap-2 `tone-agent`/`greeting-composer-agent`/`greeting-workflow`/`GreetingTeamEndpoint`) plus the top-level `service-setup` (SC-006, FR-010). No cap-1 code changes.

**Checkpoint**: both capabilities coexist and are served.

---

## Phase 6: User Story 4 - Invalid input is rejected before any work (Priority: P3)

**Goal**: blank/malformed input rejected up front; no workflow started, no model call.

**Independent Test**: blank user, blank text, malformed body each → error; unknown props tolerated.

- [ ] T019 [US4] Add to `GreetingTeamEndpointIntegrationTest.java`: C7 (blank user → `400`; blank text → `400`), C8 (malformed JSON → `400`), C9 (unknown JSON property tolerated → normal flow) (depends on T016).

**Checkpoint**: validation-first behavior verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T020 [P] Update `README.md` — add "Scala interop notes §4: the workflow method-reference wall (cap-2 is Java)" and async `/greetings` curl examples (start + poll).
- [ ] T021 [P] Update `ROADMAP.md` — mark capability 2 status (🚧 in progress → ✅ on merge); note it is implemented in Java and why.
- [ ] T022 Run full `mvn verify` (all green) and perform a manual live Gemini smoke test of the async flow (documented in quickstart; not part of the offline suite).

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T006)** → **user stories**.
- **US1 (T007–T016)** is the MVP and must precede US2/US4 (they extend the US1 endpoint test) and US3 (verifies the whole).
  - T011 needs T005/T007/T009; T013 needs T011/T012; T014 needs T011; T016 needs T014/T015.
- **US2 (T017)** and **US4 (T019)** both edit `GreetingTeamEndpointIntegrationTest.java` → run sequentially, after T016.
- **US3 (T018)** after US1 (needs cap-2 components + descriptor complete).
- **Polish (T020–T022)** last.

### Parallel opportunities

- Setup: T002 ∥ (T001 first).
- Foundational: T003 ∥ T004 ∥ T005; T006 after T003.
- US1 tests: T008 ∥ T010 (different files); T008 after T007, T010 after T009.
- Polish: T020 ∥ T021.

---

## Implementation Strategy

**MVP** = Phases 1–3 (Setup + Foundational + US1): a working start→poll→structured greeting. Stop and
validate (`mvn verify`) before US2–US4, which add edge-case coverage and the cap-1 coexistence check.

Commit after each task or logical group. Verify `.env` is git-ignored before any commit; stage explicit
paths only.

## Notes

- Every cap-2 component payload is a **Java record** → Java-shaped by construction; no `Bootstrap`/
  Scala-module needed here (research R3).
- Agents and the workflow are wired with **native Java `::` method references** — the reason this
  module is Java at all (research R1).
- Keep the annotation processor **off** (`-proc:none`); the hand-maintained descriptor stays the single
  source of truth (research R2).
