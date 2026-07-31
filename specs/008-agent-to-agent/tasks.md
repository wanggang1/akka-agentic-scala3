# Tasks: Agent-to-agent delegation (personal assistants)

**Input**: Design documents from `/specs/008-agent-to-agent/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md (all present)

**Tests**: Included (requested). Offline only — `TestModelProvider` / `KeyValueEntityTestKit` / `httpClient`;
**no offline recall test** (the mock sees only the current turn — research.md R6; recall is proven live).

**Workflow note (CLAUDE.md)**: build **one component + its test at a time**, stopping for user approval at
each **Checkpoint**. Because these are Akka SDK components, they are **shared across user stories** (one
`PersonalAssistantAgent`, one `PersonalAssistantEndpoint`), so the phases below are sequenced by
component-dependency and each task is mapped to the story it primarily serves — the stories are not fully
independent codepaths. Add each component to the descriptor **as it is created** or the runtime won't find
it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no incomplete dependency)
- **[Story]**: US1 (own to-dos) · US2 (delegation) · US3 (memory/isolation) · US4 (validation/guard)
- Package base: `com.gwgs.akkaagentic.a2a`

---

## Phase 1: Setup

- [ ] T001 Create the `a2a` package directories under `src/main/{scala,java}/com/gwgs/akkaagentic/a2a/{domain,application,api}` and `src/test/{scala,java}/com/gwgs/akkaagentic/a2a/{domain,application,api}` (most already exist from the domain drafts — create the missing ones).

**Checkpoint**: package skeleton present.

---

## Phase 2: Foundational (domain layer — blocks all stories)

**Purpose**: pure domain types every story needs. **These three files already exist on-branch — reconcile
& verify against data-model.md, do not recreate.**

- [ ] T002 [P] Reconcile/verify `Todo` record against data-model.md §1 in `src/main/java/com/gwgs/akkaagentic/a2a/domain/Todo.java` (Java-shaped; entity element).
- [ ] T003 [P] Reconcile/verify `TodoList` (immutable state + `empty`/`nextId`/`add`/`find`/`delete`/`setCompleted`) against data-model.md §1 in `src/main/java/com/gwgs/akkaagentic/a2a/domain/TodoList.java`.
- [ ] T004 [P] Reconcile/verify `AssistantRequest.validate(rawUsername, rawMessage)` against data-model.md §2 in `src/main/scala/com/gwgs/akkaagentic/a2a/domain/AssistantRequest.scala`.
- [ ] T005 [US1] Write `TodoListTest` (JUnit 5 + AssertJ) — add assigns `nextId`, ids never reused after delete, `delete`/`setCompleted` on unknown id are no-ops, immutability (originals unchanged) — in `src/test/java/com/gwgs/akkaagentic/a2a/domain/TodoListTest.java`.
- [ ] T006 Run `mvn -q test -Dtest=TodoListTest` (and `mvn -q compile`) — domain layer green.

**Checkpoint**: domain compiles and `TodoListTest` passes. **STOP for approval.**

---

## Phase 3: User Story 1 - Manage my own to-do list (Priority: P1) 🎯 MVP

**Goal**: a single-user assistant that adds/lists/completes/deletes to-dos via natural language and
remembers the conversation.

**Independent Test**: drive one username through add → list → complete → delete over `POST
/request/{username}`; each reply reflects the change and persists.

### To-do entity (Java KeyValueEntity)

- [ ] T007 [US1] Implement `TodoEntity extends KeyValueEntity<TodoList>` (`@Component(id = "todo-entity")`), `emptyState = TodoList.empty()`, command handlers `list`, `add(description)→id`, `delete(id)→found`, `setCompleted(cmd)→found` (consult `find` for found/not-found), in `src/main/java/com/gwgs/akkaagentic/a2a/application/TodoEntity.java`.
- [ ] T008 [US1] Add the **new** `key-value-entity = ["com.gwgs.akkaagentic.a2a.application.TodoEntity"]` key to the descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`.
- [ ] T009 [US1] Write `TodoEntityTest` using `KeyValueEntityTestKit` — add returns increasing ids, delete reports found/not-found, setCompleted flips the flag, list reflects state — in `src/test/java/com/gwgs/akkaagentic/a2a/application/TodoEntityTest.java`.
- [ ] T010 [US1] Run `mvn -q test -Dtest=TodoEntityTest` — entity green.

**Checkpoint**: to-do store works. **STOP for approval.**

### To-do tool object (Java)

- [ ] T011 [US1] Implement `TodoTools` (plain Java class constructed with `ComponentClient` + the caller's `username`) with four `@FunctionTool` methods — `listTodos()`, `addTodo(description)`, `deleteTodo(id)`, `setCompleted(id, completed)` — each calling `TodoEntity` by method reference (`.forKeyValueEntity(username).method(TodoEntity::...)`), rendering results as concise strings for the model, in `src/main/java/com/gwgs/akkaagentic/a2a/application/TodoTools.java`. **Not a component** (no descriptor entry).

**Checkpoint**: tools compile. (Exercised via the agent test next.)

### Agent (Scala) + wire type + memory

- [ ] T012 [US1] Implement `PersonalAssistantAgent extends Agent` (`@Component(id = "personal-assistant-agent")`) with the nested Java-shaped `Request(username, message, delegated = false)` (Jackson-annotated), one handler `request(Request): Effect[String]`, system message `"You are {username}, a concise and helpful assistant."`, `.memory(MemoryProvider.limitedWindow().readLast(N))`, and (for now) `.tools(new TodoTools(componentClient, req.username))`, in `src/main/scala/com/gwgs/akkaagentic/a2a/application/PersonalAssistantAgent.scala`. (ForwardTool wired in US2.)
- [ ] T013 [US1] Add `"com.gwgs.akkaagentic.a2a.application.PersonalAssistantAgent"` under the `agent` key in the descriptor.
- [ ] T014 [US1] Write `PersonalAssistantAgentTest` (`TestModelProvider`) — mock the model to emit a to-do tool call (e.g. add / list) and assert the reply reflects the tool result; call via `.inSession(username).dynamicCall[Request, String]("personal-assistant-agent")`, in `src/test/scala/com/gwgs/akkaagentic/a2a/application/PersonalAssistantAgentTest.scala`.
- [ ] T015 [US1] Run `mvn -q test -Dtest=PersonalAssistantAgentTest` — agent (todo path) green.

**Checkpoint**: agent uses to-do tools. **STOP for approval.**

### Endpoint (Scala)

- [ ] T016 [US1] Implement `PersonalAssistantEndpoint` (`@HttpEndpoint`, `@Acl` INTERNET) with idiomatic DTOs `RequestBody(message: Option[String])` / `Reply(username, reply)`, `POST /request/{username}` that validates via `AssistantRequest.validate(username, body.message)` (→ `400` on `Left`, no model call) then calls the agent `.inSession(username).dynamicCall[Request, String]("personal-assistant-agent").invoke(Request(username, message))`, in `src/main/scala/com/gwgs/akkaagentic/a2a/api/PersonalAssistantEndpoint.scala`.
- [ ] T017 [US1] Add `"com.gwgs.akkaagentic.a2a.api.PersonalAssistantEndpoint"` under the `http-endpoint` key in the descriptor.
- [ ] T018 [US1] Write `PersonalAssistantEndpointIntegrationTest` (`TestKitSupport` + `httpClient`, mocked model) — own-to-do happy path (POST → 200 with a reply reflecting a tool result) in `src/test/scala/com/gwgs/akkaagentic/a2a/api/PersonalAssistantEndpointIntegrationTest.scala`.
- [ ] T019 [US1] Run `mvn -q verify` — US1 end-to-end green.

**Checkpoint**: **MVP** — a single user manages to-dos by chatting. **STOP for approval.**

---

## Phase 4: User Story 2 - Delegate to another user's assistant (Priority: P1)

**Goal**: an assistant forwards a request to another user's assistant and relays the reply; the effect
lands under the target user.

**Independent Test**: alice asks bob's assistant to add a to-do; bob's list gains it; alice's reply carries
bob's confirmation.

- [ ] T020 [US2] Implement `ForwardTool` (Scala, constructed with `ComponentClient`) with one `@FunctionTool` `askAssistant(username, question)` that calls `.forAgent().inSession(username).dynamicCall[Request, String]("personal-assistant-agent").invoke(Request(username, question, delegated = true))` and returns the reply verbatim + a brief attribution, in `src/main/scala/com/gwgs/akkaagentic/a2a/application/ForwardTool.scala`. **Not a component.**
- [ ] T021 [US2] Wire the **one-hop guard** into `PersonalAssistantAgent.request`: offer tools by request — `if req.delegated then Seq(todoTools) else Seq(todoTools, new ForwardTool(componentClient))` (data-model.md §3) in `PersonalAssistantAgent.scala`.
- [ ] T022 [US2] [US4] Extend `PersonalAssistantAgentTest` — **loop guard**: a `Request(delegated = true)` is offered **no** forward tool (assert the model is not given `askAssistant` / delegation does not occur), plus a top-level request *can* forward (mock the delegate's model), in `PersonalAssistantAgentTest.scala`.
- [ ] T023 [US2] Extend `PersonalAssistantEndpointIntegrationTest` — **A→B delegation**: mock both sessions' models so alice's model calls `askAssistant("bob", …)`; assert alice's reply relays bob's, and bob's `TodoEntity`/reply shows the effect landed under bob (isolation of effect), in `PersonalAssistantEndpointIntegrationTest.scala`.
- [ ] T024 [US2] Run `mvn -q verify` — delegation + loop guard green.

**Checkpoint**: agent-to-agent delegation works, bounded to one hop. **STOP for approval.**

---

## Phase 5: User Story 3 - Remembered, isolated conversations (Priority: P2)

**Goal**: same username = one ongoing conversation (recall); different usernames never share state.

**Independent Test**: recall is **live-only** (mock caveat); isolation is verified offline at the
HTTP/to-do layer.

- [ ] T025 [US3] Confirm `.memory(MemoryProvider.limitedWindow().readLast(N))` is set in `PersonalAssistantAgent` (from T012) and pick a small `N` (e.g. 10) with a code comment tying it to research.md R5 (bounded window; delegated replies + `listTodos` output count against it).
- [ ] T026 [US3] Extend `PersonalAssistantEndpointIntegrationTest` — **isolation**: a to-do added under `alice` is absent for `carol` (query each via the assistant / entity), in `PersonalAssistantEndpointIntegrationTest.scala`. (No offline *recall* test — documented live-only.)
- [ ] T027 [US3] Run `mvn -q verify`.

**Checkpoint**: isolation proven offline; recall deferred to the live quickstart. **STOP for approval.**

---

## Phase 6: User Story 4 - Safe, bounded, validated requests (Priority: P3)

**Goal**: reject bad input before any work; a delegate cannot re-delegate.

**Independent Test**: blank/malformed → 400; delegate has no forward tool (already asserted in T022).

- [ ] T028 [US4] Extend `PersonalAssistantEndpointIntegrationTest` — **validation**: blank `message` → `400` "message must not be blank" (no model call); malformed JSON body → `400`; (blank username path covered by validate), in `PersonalAssistantEndpointIntegrationTest.scala`.
- [ ] T029 [US4] Run `mvn -q verify` — full offline suite green (all six capabilities).

**Checkpoint**: validation-first contract holds; loop guard (T022) satisfies SC-004. **STOP for approval.**

---

## Phase 7: Polish & Documentation

- [ ] T030 Verify the descriptor lists exactly: `PersonalAssistantAgent` (agent), `TodoEntity` (key-value-entity, new key), `PersonalAssistantEndpoint` (http-endpoint); `ForwardTool`/`TodoTools`/`SessionMemoryEntity` **absent**. Confirm **no `pom.xml` change** was needed.
- [ ] T031 [P] Add a "Capability 6 — agent-to-agent delegation" section to `README.md` with the curl walkthrough from `quickstart.md` (own to-dos, delegation lands under target, memory/isolation, 400) and a "Scala interop notes §8" entry summarizing research.md R1 (Scala delegation) / R2 (Java to-do quarantine, tool-object seam).
- [ ] T032 [P] Update the project-layout comment block in `README.md` to include the `a2a` package.
- [ ] T033 Run `mvn -q verify` one final time; then live-smoke the quickstart against local Ollama (`qwen3:8b`) to prove **recall** (step 3) and end-to-end delegation.

**Checkpoint**: capability complete, documented, all tests green, live-verified. **STOP for approval** (then commit / PR per user direction).

---

## Dependencies & Execution Order

- **Phase 1 (Setup)** → **Phase 2 (Foundational domain)** block everything.
- **Phase 3 (US1)** is the MVP and builds the shared agent + endpoint; **Phase 4 (US2)** extends them with
  delegation; **Phase 5 (US3)** and **Phase 6 (US4)** are mostly additional tests over the same components.
- Within a component: implementation → descriptor entry → test → `mvn` gate.
- **Phase 7 (Polish)** after all stories.

### Parallel opportunities

- T002/T003/T004 (three separate domain files) are `[P]`.
- T031/T032 (README sections) are `[P]`.
- Component tasks are otherwise sequential (shared agent/endpoint files, and each must be discoverable
  before the next test run).

## Implementation Strategy

- **MVP = Phases 1–3** (US1): a single-user to-do assistant with memory, fully testable. Demo here.
- **Then US2** (delegation — the headline) → **US3/US4** (isolation, validation) → **Polish**.
- Commit after each Checkpoint per user direction (the repo commits only when asked).

## Notes

- **Recall is live-only** (research.md R6): no offline recall task by design.
- Add every new **component** to the descriptor as created (T008/T013/T017); tool objects
  (`ForwardTool`, `TodoTools`) are **not** components.
- The 3 domain files (T002–T004) already exist on-branch — reconcile, don't duplicate.
