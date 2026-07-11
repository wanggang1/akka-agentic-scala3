# Tasks: Autonomous help-desk Agent

**Input**: Design documents from `/specs/005-autonomous-agent/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/help-api.md

**Tests**: Included — Constitution III and FR-012 mandate them. Per this repo's `CLAUDE.md` incremental
workflow, each component's test is created **right after** the component (component-then-test), not
strict TDD-first. Verify with `mvn compile` / `mvn verify` at the points noted.

**Language**: Capability 3 is **Scala** (`com.gwgs.akkaagentic.assistant.*`), self-contained and
decoupled from cap-1 (Scala) and cap-2 (Java). No method-reference wall applies (research R1). Cap-1 and
cap-2 are not touched.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different file, no dependency on an incomplete task → parallelizable
- **[Story]**: US1–US4 (setup/foundational/polish carry no story label)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: create the module's package skeleton. No build change is needed — the mixed Scala/Java
build (and the `scala-maven-plugin` that compiles cap-3's Scala) is already configured from cap-2.

- [x] T001 [P] Create cap-3 package directories: `src/main/scala/com/gwgs/akkaagentic/assistant/{api,application,domain}` and `src/test/scala/com/gwgs/akkaagentic/assistant/{api,application,domain}`.
- [x] T002 Confirm no `pom.xml` change is required (`mvn compile` still green with no cap-3 sources yet); note in the commit that the mixed build already covers Scala cap-3.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the pure Scala domain + the Java-shaped task-result type that the agent and tasks depend on.
No Akka component code yet.

**⚠️ CRITICAL**: complete before any user-story phase.

- [x] T003 [P] Create `HelpAnswer` in `src/main/scala/com/gwgs/akkaagentic/assistant/application/HelpAnswer.scala` — the task result, a **Java-shaped** Scala case class (`@JsonCreator`/`@JsonProperty`, fields `answer: String`, `category: String`, `citedTopics: java.util.List[String]`, `confidence: Int`), mirroring cap-1's `GreetingAgent.Result` (data-model; research R3). *Residual schema-gen verification happens at T011.*
- [x] T004 [P] Create `KnowledgeBase` in `src/main/scala/com/gwgs/akkaagentic/assistant/domain/KnowledgeBase.scala` — `KnowledgeBaseEntry(topic, summary)` and `KnowledgeBase.lookup(topic: String): Option[KnowledgeBaseEntry]` (pure, case-insensitive, `None` on miss) over a small in-memory map (e.g. `password-reset`, `billing`, `account`, `shipping`). No Akka deps.
- [x] T005 [P] Create `HelpQuestion` in `src/main/scala/com/gwgs/akkaagentic/assistant/domain/HelpQuestion.scala` — `validate(raw: Option[String]): Either[String, HelpQuestion]` (None/blank → `Left("question must not be blank")`; else `Right(HelpQuestion(trimmed))`). Parse-don't-validate, mirrors cap-1's `GreetingRequest.validate`.
- [x] T006 [P] Create `KnowledgeBaseTest` in `src/test/scala/com/gwgs/akkaagentic/assistant/domain/KnowledgeBaseTest.scala` — hit (case-insensitive) and miss (`None`) (depends on T004).
- [x] T007 [P] Create `HelpQuestionTest` in `src/test/scala/com/gwgs/akkaagentic/assistant/domain/HelpQuestionTest.scala` — blank/absent → Left; valid → Right(trimmed) (depends on T005).

**Checkpoint**: `mvn test` green (domain + result type compile; domain unit tests pass).

---

## Phase 3: User Story 1 - Ask a question and retrieve a typed answer (Priority: P1) 🎯 MVP

**Goal**: end-to-end model-driven task — submit a question, the agent iterates (optionally consulting the
knowledge-base tool), completes a typed `HelpAnswer`, retrieved via start→poll.

**Independent Test**: with the model mocked, `POST /help` then poll `GET /help/{taskId}` until `200`;
assert all four fields, including a case where the mocked model consults `lookupPolicy` before completing
(contracts C1, C3, C5, C6).

- [x] T008 [US1] Create `HelpDeskTasks` in `src/main/scala/com/gwgs/akkaagentic/assistant/application/HelpDeskTasks.scala` — `val ANSWER: Task[HelpAnswer] = Task.name("Answer").description("Answer a user's help question").resultConformsTo(classOf[HelpAnswer])` (depends on T003).
- [x] T009 [US1] Create `HelpDeskAgent` in `src/main/scala/com/gwgs/akkaagentic/assistant/application/HelpDeskAgent.scala` — `@Component(id="help-desk-agent", description="…")` extends `AutonomousAgent`; `definition()` returns `define().capability(TaskAcceptance.of(HelpDeskTasks.ANSWER).maxIterationsPerTask(5))` (+ optional `.instructions(...)` for tone/procedure); `@FunctionTool lookupPolicy(topic: String): String` → `KnowledgeBase.lookup(...)` summary or a "no entry for …" string (never throws). NO command handler (depends on T004, T008).
- [x] T010 [US1] Update `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` — add the new key `autonomous-agent = ["com.gwgs.akkaagentic.assistant.application.HelpDeskAgent"]` (research R2).
- [x] T011 [US1] Create `HelpDeskAgentIntegrationTest` in `src/test/scala/com/gwgs/akkaagentic/assistant/application/HelpDeskAgentIntegrationTest.scala` — `TestKitSupport`; register `TestModelProvider` for `HelpDeskAgent`; drive `forAutonomousAgent(classOf[HelpDeskAgent], UUID).runSingleTask(ANSWER.instructions(question))`, poll `forTask(id).get(ANSWER)` with Awaitility. Two cases: (a) **direct** `completeTask(HelpAnswer(...))` → COMPLETED with empty citedTopics; (b) **tool-consulting** — `whenMessage(...)` calls `lookupPolicy`, then `whenToolResult(...)` calls `completeTask` with populated citedTopics. **This is the R3 schema-gen / round-trip verification and the R4 Gemini-path check** — if schema-gen misbehaves for the Scala case class, make `HelpAnswer` a Java record (that one type only) and re-run (depends on T009, T010).
- [x] T012 [US1] Create `HelpDeskEndpoint` in `src/main/scala/com/gwgs/akkaagentic/assistant/api/HelpDeskEndpoint.scala` — `@HttpEndpoint`, `@Acl(INTERNET)`; idiomatic DTOs `AskRequest(question: Option[String])`, `StartAccepted(taskId)`, `HelpReply(answer, category, citedTopics, confidence)`; `POST /help` validates via `HelpQuestion.validate` (else `badRequest`), starts `forAutonomousAgent(classOf[HelpDeskAgent], UUID).runSingleTask(ANSWER.instructions(q))`, returns `202` + `Location: /help/{taskId}` + `{taskId}`; `GET /help/{taskId}` reads `forTask(taskId).get(ANSWER)` and maps snapshot → `200 HelpReply` (COMPLETED, via `toApi`), `422` (FAILED, with reason), `404` (else/unknown/exception) (depends on T008, T009).
- [x] T013 [US1] Update the descriptor — append `"com.gwgs.akkaagentic.assistant.api.HelpDeskEndpoint"` to the `http-endpoint` list.
- [x] T014 [US1] Create `HelpDeskEndpointIntegrationTest` in `src/test/scala/com/gwgs/akkaagentic/assistant/api/HelpDeskEndpointIntegrationTest.scala` — `httpClient`: C1 (`POST`→`202`+Location+taskId), C3 (poll `GET`→`200`, four fields), C5 (mock consults `lookupPolicy` → citedTopics populated), C6 (mock answers directly → citedTopics empty) (depends on T012, T013).

**Checkpoint**: ✅ MVP — start→poll→typed answer works end-to-end offline. `mvn verify` green; cap-1 & cap-2 unaffected.

---

## Phase 4: User Story 2 - Observe the asynchronous task lifecycle (Priority: P2)

**Goal**: not-ready, unknown-handle, and agent-abandoned reads are each distinct from success and never
fabricate an answer (behavior implemented in T012; this phase pins it with tests).

**Independent Test**: immediate `GET` after `POST` → `404`; random id → `404`; a `failTask` mock → `422`.

- [x] T015 [US2] Add to `HelpDeskEndpointIntegrationTest.scala`: C2 (immediate `GET` before completion → `404`), C4 (unknown/never-started id → `404`), C7 (model `failTask("…")` → task FAILED → `GET` returns `422` with the reason, distinct from `200`/`404`) (depends on T014).

**Checkpoint**: async lifecycle fully observable — 200 / 404 / 422 all distinct.

---

## Phase 5: User Story 3 - Capabilities 1 and 2 remain independently usable (Priority: P2)

**Goal**: cap-1 and cap-2 unchanged and all three capabilities discovered at startup.

**Independent Test**: cap-1's and cap-2's existing suites pass unmodified; service starts with all
components including the new `autonomous-agent`.

- [x] T016 [US3] Run `mvn verify`; confirm cap-1 (Scala) and cap-2 (Java) suites pass unchanged and the descriptor lists **all** components — cap-1 agents/endpoints, cap-2 `tone-agent`/`greeting-composer-agent`/`greeting-workflow`/`GreetingTeamEndpoint`, and cap-3 `help-desk-agent` (under `autonomous-agent`) + `HelpDeskEndpoint` — plus the top-level `service-setup` (FR-010/FR-011, SC-006). No cap-1/cap-2 code changes.

**Checkpoint**: all three capabilities coexist and are served.

---

## Phase 6: User Story 4 - Invalid input is rejected before any work (Priority: P3)

**Goal**: blank/absent/malformed input rejected up front; no task started, no model call.

**Independent Test**: blank question, absent question, malformed body each → error; unknown props tolerated.

- [x] T017 [US4] Add to `HelpDeskEndpointIntegrationTest.scala`: C8 (blank `question` → `400`; absent `question`/`{}` → `400`), C9 (malformed JSON → `400`; unknown JSON property tolerated → normal `202` flow) (depends on T014).

**Checkpoint**: validation-first behavior verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T018 [P] Update `README.md` — add "Scala interop notes §5: the Autonomous Agent has no method-reference wall (cap-3 is Scala); descriptor key `autonomous-agent`; task result stays Java-shaped; Gemini tools-vs-JSON does not apply", the cap-3 async `/help` curl examples (start + poll + 422), and the project-layout entry for `assistant/*`.
- [x] T019 [P] Update `ROADMAP.md` — mark capability 3 status (🚧 in progress → ✅ on merge); note it is Scala and *why* (contrast with cap-2: no method-ref wall), narrowing the R6 through-line.
- [ ] T020 Run full `mvn verify` (all green) and perform a manual live Gemini smoke test of the async `/help` flow (documented in quickstart; not part of the offline suite; confirms R4 on a real call).

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T007)** → **user stories**.
- **US1 (T008–T014)** is the MVP and must precede US2/US4 (they extend the US1 endpoint test) and US3 (verifies the whole).
  - T008 needs T003; T009 needs T004/T008; T011 needs T009/T010; T012 needs T008/T009; T014 needs T012/T013.
- **US2 (T015)** and **US4 (T017)** both edit `HelpDeskEndpointIntegrationTest.scala` → run sequentially, after T014.
- **US3 (T016)** after US1 (needs cap-3 components + descriptor complete).
- **Polish (T018–T020)** last.

### Parallel opportunities

- Setup: T001 first, then work proceeds.
- Foundational: T003 ∥ T004 ∥ T005; T006 after T004, T007 after T005.
- Polish: T018 ∥ T019.

---

## Implementation Strategy

**MVP** = Phases 1–3 (Setup + Foundational + US1): a working start→poll→typed answer. Stop and validate
(`mvn verify`) before US2–US4, which add edge-case coverage and the cap-1/cap-2 coexistence check.

Commit after each task or logical group. Verify `.env` is git-ignored before any commit; stage explicit
paths only.

## Notes

- The task result `HelpAnswer` is **Java-shaped** by construction (Jackson annotations) → crosses the
  SDK's internal mapper cleanly (research R3). Everything else is idiomatic Scala.
- The agent, tasks, endpoint, and tests are wired with **`Class`/`Task` references** — no method
  references — which is *why* this capability can be Scala (research R1).
- Keep the annotation processor **off** (`-proc:none`, from cap-2); the hand-maintained descriptor stays
  the single source of truth. Add the `autonomous-agent` key and the endpoint by hand (research R2).
- `HelpDeskTasks` and `HelpAnswer` are **not** components — no descriptor entry.
