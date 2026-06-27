# Tasks: Greeting Agent Service Baseline

**Input**: Design documents from `/specs/001-greeting-agent/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/greeting-api.md

**Tests**: INCLUDED — the spec requires automated tests (FR-010) and a passing suite on clean checkout (SC-004).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- All paths are relative to the repository root.

## Path Conventions

Single Akka service (Scala 3 on the Akka Java SDK). Sources under `src/main/scala/com/example/{domain,application,api}`, tests under `src/test/scala/com/example/{domain,application,api}`, per plan.md.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Make the project compile and run Scala 3 on the Akka Java SDK.

- [X] T001 Add `scala-maven-plugin` (bound to `compile`/`test-compile`) and a `scala3-library_3` dependency to `pom.xml`, and register `src/main/scala` + `src/test/scala` as source roots (keep `akka-javasdk-parent` 3.6.0 as parent) — per research.md R2
- [X] T002 [P] Configure the default model provider under `akka.javasdk.agent` with an env-driven API key in `src/main/resources/application.conf` — per research.md R5
- [X] T003 [P] Create Scala package directories `src/main/scala/com/example/{domain,application,api}` and `src/test/scala/com/example/{domain,application,api}`; remove the placeholder Java `package-info.java` files under `src/main/java/com/example` that are being replaced by Scala sources

**Checkpoint**: `mvn compile` succeeds on an empty Scala source tree.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain model shared by every user story.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T004 Create `GreetingRequest` and `GreetingResponse` case classes with an immutable `validate(): Either[String, GreetingRequest]` (rejects blank `user`/`text`) and no Akka imports in `src/main/scala/com/example/domain/Greeting.scala` — per data-model.md

**Checkpoint**: Domain compiles and is unit-testable in isolation; user stories can now begin.

---

## Phase 3: User Story 1 - Receive a personalized greeting (Priority: P1) 🎯 MVP

**Goal**: A caller POSTs `{user, text}` and receives a personalized, LLM-composed greeting that names the user.

**Independent Test**: `POST /greet` with a valid body returns `200` and a non-empty greeting referencing the user (mocked model in tests).

### Tests for User Story 1

> Write these first; ensure they FAIL before implementation.

- [ ] T005 [P] [US1] Agent unit test in `src/test/scala/com/example/application/GreetingAgentTest.scala`: extend `TestKitSupport`, register `TestModelProvider` for `GreetingAgent`, set a `fixedResponse` greeting, invoke `componentClient.forAgent().inSession(<uuid>).method(GreetingAgent::greet).invoke(request)`, assert the mocked greeting is returned — per research.md R6
- [ ] T006 [P] [US1] Endpoint success integration test in `src/test/scala/com/example/api/GreetingEndpointIntegrationTest.scala`: extend `TestKitSupport`, register `TestModelProvider` for `GreetingAgent`, `httpClient.POST("/greet")` with a valid body asserts `200` and a non-empty `greeting` — per contracts/greeting-api.md

### Implementation for User Story 1

- [ ] T007 [US1] Implement `GreetingAgent` in `src/main/scala/com/example/application/GreetingAgent.scala`: `extends akka.javasdk.agent.Agent`, `@Component(id = "greeting-agent")`, inner `Request(user, text)` (Jackson-annotated), single `greet` handler returning `Effect[String]` via `effects().systemMessage(SYSTEM_MESSAGE).userMessage(...).thenReply()` — per research.md R4
- [ ] T008 [US1] Implement `GreetingEndpoint` in `src/main/scala/com/example/api/GreetingEndpoint.scala` (depends on T007 — references `GreetingAgent::greet`): `@HttpEndpoint`, `@Acl(allow = Internet)`, inner `GreetRequest`/`GreetReply` (Jackson-annotated, `GreetRequest` set to ignore unknown properties for the in-scope edge case in spec.md → Edge Case Handling), `@Post("/greet")` that validates via domain `GreetingRequest.validate`, on success calls `GreetingAgent` through injected `ComponentClient` with a fresh session id, and wraps the reply in `GreetReply` — per contracts/greeting-api.md

**Checkpoint**: MVP — a valid request returns a personalized greeting end to end. T005/T006 pass.

---

## Phase 4: User Story 2 - Reject malformed or incomplete requests (Priority: P2)

**Goal**: Invalid input (blank `user`/`text`, malformed JSON) is rejected with a clear `400` and no greeting.

**Independent Test**: `POST /greet` with empty `user` (or missing `text`) returns `400` with a validation message and never invokes the model.

### Tests for User Story 2

- [ ] T009 [P] [US2] Domain validation unit test in `src/test/scala/com/example/domain/GreetingTest.scala`: assert `GreetingRequest.validate` returns `Left` with a message for blank `user` and for blank `text`, and `Right` for valid input
- [ ] T010 [P] [US2] Endpoint failure cases in `src/test/scala/com/example/api/GreetingEndpointIntegrationTest.scala`: empty `user` → `400`, missing/blank `text` → `400`, and a malformed-JSON body → `400` (FR-006)

### Implementation for User Story 2

- [ ] T011 [US2] Harden `src/main/scala/com/example/api/GreetingEndpoint.scala` so validation failures return `HttpResponses.badRequest(<message>)` without calling the agent. Verify the status the SDK returns for a body that fails `GreetRequest` deserialization; if it is not 4xx, add explicit handling so malformed payloads yield `400` (FR-006). Record the observed default in contracts/greeting-api.md — per contracts/greeting-api.md

**Checkpoint**: US1 and US2 both pass independently; no model call on invalid input.

---

## Phase 5: User Story 3 - Adapt greeting to message intent (Priority: P3)

**Goal**: The greeting reflects the message's intent (a question vs. a casual hello) rather than a fixed template.

**Independent Test**: Two messages of different intent for the same user produce greetings consistent with each intent (asserted with a mocked model keyed on message content).

### Tests for User Story 3

- [ ] T012 [P] [US3] Intent-adaptation test in `src/test/scala/com/example/application/GreetingAgentTest.scala`: use `TestModelProvider` `.whenMessage(predicate).reply(...)` to return distinct greetings for a question-style vs. casual message and assert they differ (SC-005, US3)

### Implementation for User Story 3

- [ ] T013 [US3] Refine `SYSTEM_MESSAGE` in `src/main/scala/com/example/application/GreetingAgent.scala` to instruct the model to detect the message's intent/tone and match it in the greeting

**Checkpoint**: All three user stories pass independently.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T014 [P] Update `README.md` with Scala 3 build/run notes and the `POST /greet` curl examples from quickstart.md
- [ ] T015 [P] Verify `application.conf` ACL/model config and that no secrets are committed (env-driven key only)
- [ ] T016 Run `mvn verify` from a clean checkout and confirm the full suite passes (SC-003, SC-004); validate quickstart.md steps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately. T001 must precede any compilation; T002/T003 are parallel.
- **Foundational (Phase 2)**: Depends on Setup — T004 needs the Scala source root (T001). BLOCKS all user stories.
- **User Stories (Phase 3+)**: All depend on Foundational (T004).
  - US1 (P1) → US2 (P2) → US3 (P3) in priority order, or in parallel by different developers.
- **Polish (Phase 6)**: After the desired user stories are complete.

### User Story Dependencies

- **US1 (P1)**: Needs T004. No dependency on other stories. Within the story, T008 (endpoint) depends on T007 (agent) — the endpoint references `GreetingAgent::greet` at compile time. MVP.
- **US2 (P2)**: Needs T004. Shares `GreetingEndpoint.scala` with US1 (T011 hardens T008) — sequence US1→US2 if one developer edits that file.
- **US3 (P3)**: Needs T004 and the `GreetingAgent` from US1 (T007/T013 edit the same agent file). Independently testable.

### Within Each User Story

- Tests (T005/T006, T009/T010, T012) are written first and must FAIL before implementation.
- Domain before agent/endpoint; agent and endpoint before integration assertions.

### Parallel Opportunities

- Setup: T002 and T003 run in parallel (after T001).
- US1: T005 and T006 (different test files) run in parallel. T007 (agent) must precede T008 (endpoint) — the endpoint references `GreetingAgent::greet` via the ComponentClient and will not compile without the agent.
- US2: T009 and T010 (different files) run in parallel.
- Cross-story: with multiple developers, US1/US2/US3 can proceed in parallel after T004, coordinating edits to the shared `GreetingEndpoint.scala` (US1/US2) and `GreetingAgent.scala` (US1/US3).

---

## Parallel Example: User Story 1

```bash
# Tests first (different files):
Task: "Agent unit test in src/test/scala/com/example/application/GreetingAgentTest.scala"
Task: "Endpoint success integration test in src/test/scala/com/example/api/GreetingEndpointIntegrationTest.scala"

# Then implementation (sequential — endpoint depends on the agent type):
# 1) Implement GreetingAgent in src/main/scala/com/example/application/GreetingAgent.scala
# 2) Implement GreetingEndpoint in src/main/scala/com/example/api/GreetingEndpoint.scala
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (T001–T003)
2. Phase 2: Foundational (T004)
3. Phase 3: User Story 1 (T005–T008)
4. **STOP and VALIDATE**: `mvn verify` — valid request returns a personalized greeting
5. Demo the MVP

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → test → demo (MVP: working greeting)
3. US2 → test → demo (robust validation / clear 400s)
4. US3 → test → demo (intent-adaptive greetings)

---

## Notes

- [P] = different files, no dependencies.
- Tests use `TestModelProvider` — no live model, no network, deterministic (SC-004).
- Endpoint tests use `httpClient`, not `componentClient`.
- Wire types carry explicit Jackson annotations (research.md R3).
- Commit after each task or logical group; validate at each checkpoint.
