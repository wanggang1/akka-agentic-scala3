# Tasks: Structured, context-aware greeting

**Input**: Design documents from `/specs/002-agent-tools-structured/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/greeting-api.md

**Tests**: INCLUDED — the spec requires automated tests (FR-009) and a passing suite on clean checkout (SC-004).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- All paths are relative to the repository root.

## Path Conventions

Single Akka service (Scala 3 on the Akka Java SDK). Sources under `src/main/scala/com/gwgs/akkaagentic/{domain,application,api}`, tests under `src/test/scala/com/gwgs/akkaagentic/{domain,application,api}`, per plan.md. This feature extends files created in 001 (already on `main`).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish a clean regression baseline before changing behavior. No new dependencies or build config are needed — the Scala-on-Akka toolchain is already in place from 001.

- [ ] T001 Confirm baseline: run `mvn verify` on branch `002-agent-tools-structured` and confirm the existing suite is green, so any later failure is attributable to this feature

**Checkpoint**: Baseline green; safe to start changes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The time-of-day computation is shared by the agent's function tool (US1, compile-time) and asserted by US2.

**⚠️ CRITICAL**: Blocks US1 and US2 (the agent tool references it).

- [ ] T002 [P] Create `TimeOfDay` as a pure, total helper (no Akka imports) in `src/main/scala/com/gwgs/akkaagentic/domain/Greeting.scala`: `of(instant, zone): String`, `now(timezone: String): String`, private `resolveZone` (blank/invalid → UTC default); labels morning 05–11 / afternoon 12–16 / evening 17–20 / night 21–04; never throws — per data-model.md and research.md R4

**Checkpoint**: Domain time logic compiles and is unit-testable in isolation.

---

## Phase 3: User Story 1 - Receive a structured greeting response (Priority: P1) 🎯 MVP

**Goal**: `POST /greet` returns a structured object `{greeting, tone, timeOfDay}` instead of a bare string.

**Independent Test**: A valid request returns `200` and a JSON object with all three fields present and non-empty, the greeting naming the user (mocked model).

### Tests for User Story 1

> Write first; ensure they FAIL (compile failure until the new types exist) before implementation.

- [ ] T003 [P] [US1] Agent structured-response test in `src/test/scala/com/gwgs/akkaagentic/application/GreetingAgentTest.scala`: set `greetingModel.fixedResponse(JsonSupport.encodeToString(result))` with a `GreetingAgent.Result`, `dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")`, assert the returned `Result` has the mocked `greeting`/`tone`/`timeOfDay` — per research.md R5
- [ ] T004 [P] [US1] Endpoint structured-success test in `src/test/scala/com/gwgs/akkaagentic/api/GreetingEndpointIntegrationTest.scala`: `httpClient.POST("/greet")` valid body, `responseBodyAs(classOf[GreetingEndpoint.GreetReply])`, assert `200` and `greeting`/`tone`/`timeOfDay` all present and non-empty — per contracts/greeting-api.md

### Implementation for User Story 1

- [ ] T005 [US1] In `src/main/scala/com/gwgs/akkaagentic/application/GreetingAgent.scala`: add inner `Result(greeting, tone, timeOfDay)` (Jackson-annotated); add a `@FunctionTool` method `currentTimeOfDay(@Description timezone)` delegating to `TimeOfDay.now`; change the handler to `.responseConformsTo(classOf[Result]).thenReply()` returning `Agent.Effect[Result]` (depends on T002). Verify the SDK reflects the Scala `@FunctionTool` method; if a `private` method isn't discovered, make it public — per research.md R1/R3
- [ ] T006 [US1] In `src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala`: extend `GreetReply` with `tone` and `timeOfDay`; change the call to `dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")` and map `Result` → `GreetReply` (API isolation) (depends on T005) — per contracts/greeting-api.md

**Checkpoint**: MVP — a valid request returns the structured greeting end to end. T003/T004 pass.

---

## Phase 4: User Story 2 - Greeting reflects the current time of day (Priority: P2)

**Goal**: The `timeOfDay` field reflects real request-time context (optional caller timezone), with a safe fallback.

**Independent Test**: `TimeOfDay` maps controlled hours to the right labels and falls back to the default zone for an invalid timezone; a question vs. casual message yield distinct structured results.

### Tests for User Story 2

- [ ] T007 [P] [US2] `TimeOfDay` domain unit tests in `src/test/scala/com/gwgs/akkaagentic/domain/GreetingTest.scala`: boundary hours map to morning/afternoon/evening/night; blank and invalid timezone fall back to UTC; the function never throws — per data-model.md
- [ ] T008 [P] [US2] Intent + time agent test in `src/test/scala/com/gwgs/akkaagentic/application/GreetingAgentTest.scala`: use `greetingModel.whenMessage(predicate).reply(JsonSupport.encodeToString(result))` to return distinct `Result`s (different `tone`) for a question-style vs. casual message, asserting the tone differs and `timeOfDay` is carried — per research.md R5 (SC-002, SC-005)

### Implementation for User Story 2

- [ ] T009 [US2] Plumb optional `timezone`: add it to `GreetingEndpoint.GreetRequest` (`src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala`) and `GreetingAgent.Request` (`src/main/scala/com/gwgs/akkaagentic/application/GreetingAgent.scala`), and include it in the agent `userMessage` so the model calls the time tool with it (depends on T005, T006) — per data-model.md
- [ ] T010 [US2] Refine `SystemMessage` in `src/main/scala/com/gwgs/akkaagentic/application/GreetingAgent.scala` to instruct the model to call the time tool, report the `timeOfDay`, and keep matching the message's tone/intent

**Checkpoint**: US1 and US2 both pass independently; structured result carries a context-correct time-of-day.

---

## Phase 5: User Story 3 - Invalid input is still rejected cleanly (Priority: P3)

**Goal**: Validation behavior from 001 is preserved under the structured response.

**Independent Test**: Blank `user`/`text` and malformed JSON each return `400` with no model call.

### Tests for User Story 3

- [ ] T011 [P] [US3] Confirm the existing endpoint failure cases still hold in `src/test/scala/com/gwgs/akkaagentic/api/GreetingEndpointIntegrationTest.scala`: empty `user` → `400`, blank `text` → `400`, malformed JSON → `400`, none invoking the model (these tests carry over from 001; ensure they still pass after the structured-response change)

### Implementation for User Story 3

- [ ] T012 [US3] Confirm the validation branch in `src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala` still returns `HttpResponses.badRequest(...)` before any `dynamicCall` (no functional change expected; adjust only if the T006 edits altered the early-return path)

**Checkpoint**: All three user stories pass independently; no model call on invalid input.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T013 [P] Update `README.md` with the structured `POST /greet` response and the new `timezone` field, using the curl examples from quickstart.md
- [ ] T014 [P] Verify the component descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` is unchanged (a `@FunctionTool` is a method, not a component) and that no secrets are committed (env-driven key only)
- [ ] T015 Run `mvn clean verify` from a clean checkout and confirm the full suite passes (SC-004); validate quickstart.md steps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: T002 has no dependencies but BLOCKS US1 (the agent tool references `TimeOfDay`) and is asserted by US2.
- **User Stories (Phase 3+)**: depend on Foundational (T002).
  - US1 (P1) → US2 (P2) → US3 (P3) in priority order. US1 and US2 edit the same agent/endpoint files, so sequence them if one developer.
- **Polish (Phase 6)**: after the desired user stories are complete.

### User Story Dependencies

- **US1 (P1)**: Needs T002. MVP. T006 (endpoint) depends on T005 (agent `Result` type).
- **US2 (P2)**: Needs T002; builds on US1's agent/endpoint (T009 edits the same files as T005/T006). The `TimeOfDay` unit test (T007) asserts the foundational function.
- **US3 (P3)**: Needs the US1/US2 endpoint edits in place; otherwise unchanged from 001. Independently testable.

### Within Each User Story

- Tests (T003/T004, T007/T008, T011) are written first and must FAIL before implementation.
- Domain (T002) before agent (T005); agent before endpoint (T006); timezone plumbing (T009) after the structured handler exists.

### Parallel Opportunities

- US1: T003 and T004 (different test files) run in parallel; then T005 → T006 (sequential, endpoint depends on agent type).
- US2: T007 and T008 (different test files) run in parallel; then T009 → T010.
- Polish: T013 and T014 run in parallel.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (T001)
2. Phase 2: Foundational (T002)
3. Phase 3: User Story 1 (T003–T006)
4. **STOP and VALIDATE**: `mvn verify` — a valid request returns a structured `{greeting, tone, timeOfDay}`
5. Demo the MVP

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → test → demo (structured response, MVP)
3. US2 → test → demo (context-correct time-of-day, tone)
4. US3 → test → demo (validation preserved)

---

## Notes

- [P] = different files, no dependencies.
- Tests use `TestModelProvider` — no live model, no network, deterministic (SC-004).
- Endpoint failure tests use `httpClient.invoke().status()` without `responseBodyAs` (it throws on non-2xx); structured success uses `responseBodyAs` — see [[akka-httpclient-failure-status-testing]].
- Wire types carry explicit Jackson annotations (001 research R3).
- A `@FunctionTool` is a method on the agent — no component-descriptor change.
- Commit after each task or logical group; validate at each checkpoint.
