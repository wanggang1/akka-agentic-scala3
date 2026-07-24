# Tasks: Human-in-the-loop approval gate

**Input**: Design documents from `/specs/007-human-approval-gate/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/approval-api.md, quickstart.md

**Tests**: INCLUDED — Constitution III (test coverage), FR-014 (offline suite + one live proof), and the
project's per-component build rhythm (CLAUDE.md) each require a test alongside every component.

**Organization**: By user story. The three-task chain and both agents are **foundational** (every story
polls or decides through them). US1 (approve → published) is the MVP and carries the endpoint's submit /
poll / approve routes. US2 adds the reject route. US3, US5 and US4 add **only tests** over that same
code — the lifecycle states, the caps-1–4 regression, and the validation / decision-integrity rules are
all behaviors of code built in US1+US2.

## Path Conventions

Scala sources under `src/main/scala/com/gwgs/akkaagentic/approvals/{domain,application,api}`; tests under
the mirrored `src/test/scala/...`. Single Akka service, mixed Scala/Java module — **this capability is
Scala end-to-end, tests included** (research R1/R6). No `pom.xml` change.

---

## Phase 1: Setup

**Purpose**: Confirm a green baseline before adding code.

- [x] T001 Confirm branch `007-human-approval-gate` and run `mvn verify` at the repo root to establish that capabilities 1–4 are green before any change (SC-007 baseline). ✅ green — 37 unit + 33 integration tests, 0 failures; 5 endpoints / 1 workflow / 4 agents discovered.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The domain validator, the Java-shaped task results, the task definitions, and the two
autonomous agents — every user story polls or decides through this chain.

**⚠️ CRITICAL**: No user-story work can begin until this phase is complete.

- [x] T002 [P] Create `ApprovalQuestion` domain validator in `src/main/scala/com/gwgs/akkaagentic/approvals/domain/ApprovalQuestion.scala` — `final case class ApprovalQuestion(text: String)` + `object ApprovalQuestion { def validate(raw: Option[String]): Either[String, ApprovalQuestion] }` (trim; absent/blank → `Left("question must not be blank")`). No Akka imports (Constitution II, FR-015).
- [x] T003 [P] Create `ApprovalQuestionTest` in `src/test/scala/com/gwgs/akkaagentic/approvals/domain/ApprovalQuestionTest.scala` — assert `None`, `""`, and whitespace-only → `Left`; a normal and a pad-trimmed question → `Right` with trimmed `text`.
- [x] T004 Run `mvn test` to verify the domain layer compiles and `ApprovalQuestionTest` passes. ✅ 4 tests green.
- [x] T005 [P] Create the three **Java-shaped** task-result records (data-model §2, research R5) in `src/main/scala/com/gwgs/akkaagentic/approvals/application/`: `Draft.scala` (`body`), `ApprovalDecision.scala` (`approved`, `note`), `PublishedReply.scala` (`reply`) — each a Jackson-annotated Scala case class with explicit `@JsonCreator`/`@JsonProperty` and `@Description` on fields (mirroring `assistant/application/HelpAnswer.scala`). These cross the SDK's **internal** mapper, so they must NOT use `Option`.
- [x] T006 Create `ApprovalTasks` in `src/main/scala/com/gwgs/akkaagentic/approvals/application/ApprovalTasks.scala` — `object ApprovalTasks` with `DRAFT: Task[Draft]`, `APPROVAL: Task[ApprovalDecision]`, `PUBLISH: Task[PublishedReply]`, each `Task.name(...).description(...).resultConformsTo(classOf[...])` (mirroring `HelpDeskTasks`). Not a component — do NOT add to the descriptor.
- [x] T007 [P] Create `DraftAgent` in `src/main/scala/com/gwgs/akkaagentic/approvals/application/DraftAgent.scala` — `@Component(id = "draft-agent", description = "Drafts a candidate customer reply.")` extends `AutonomousAgent`; `definition()` = `define().instructions(...).capability(TaskAcceptance.of(ApprovalTasks.DRAFT).maxIterationsPerTask(3))`. Stateless, no `@FunctionTool` (A-008).
- [x] T008 [P] Create `PublishAgent` in `src/main/scala/com/gwgs/akkaagentic/approvals/application/PublishAgent.scala` — `@Component(id = "publish-agent", description = "Publishes an approved reply.")` extends `AutonomousAgent`; `definition()` = `define().instructions(...).capability(TaskAcceptance.of(ApprovalTasks.PUBLISH).maxIterationsPerTask(3))`.
- [x] T009 Register both agents under the **`autonomous-agent`** key (not `agent`) in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`: `com.gwgs.akkaagentic.approvals.application.DraftAgent`, `…PublishAgent` (FR-013). Do NOT add task entities — the runtime owns them.
- [x] T010 Run `mvn compile` to verify the application layer compiles against the SDK's `AutonomousAgent`/`Task` API. ✅ BUILD SUCCESS.
- [x] T011 Resolve the **research R2 residual**: write a temporary integration check that calls `componentClient.forTask("never-created-id").get(ApprovalTasks.DRAFT)` and record whether it **throws** (cap-3's assumption → map to 404) or returns a benign empty snapshot. Fold the answer into the endpoint's not-found branch in T013; keep the assertion as a permanent test in T021 rather than leaving a throwaway. ✅ **It THROWS** `akka.javasdk.CommandException: "Task does not exist"` — cap-3's assumption confirmed, so T013 uses the same `Try(...) match` shape as `HelpDeskEndpoint`. Probe deleted; assertion moves to T021. (Note: the discovery log's `Agent: [N]` count does NOT include autonomous agents, so descriptor registration of the two new agents stays unproven until T016 runs them.)

**Checkpoint**: Task chain primitives ready — the endpoint can now be built.

---

## Phase 3: User Story 1 - Approve a drafted reply and see it published (Priority: P1) 🎯 MVP

**Goal**: Submit a question → poll to `awaiting-approval` showing the draft → approve → poll to
`published` with the final reply, and **no reply exists before the approval**.

**Independent Test**: With both models mocked, `POST /approvals` → 202 + handle; poll to
`awaiting-approval` (assert `draft` present, `reply` absent); `POST …/approve` → 200; poll to `published`
(assert `reply` present).

- [x] T012 [US1] Create `ApprovalEndpoint` in `src/main/scala/com/gwgs/akkaagentic/approvals/api/ApprovalEndpoint.scala` with `@HttpEndpoint` + `@Acl(INTERNET)`, the idiomatic Scala DTOs from data-model §4 (`SubmitRequest(question: Option[String])`, `CaseAccepted(caseId)`, `DecisionRequest(note: Option[String])`, `CaseState(state, draft, reply, note)` — inbound types `@JsonIgnoreProperties(ignoreUnknown = true)`), and `@Post("/approvals")`: validate via `ApprovalQuestion.validate` (→ `badRequest`, no case started), else mint `caseId`, `forTask(s"$caseId-draft"|"-approval"|"-publish").create(...)` with `dependsOn` wiring (research R3 — the APPROVAL task stays **unassigned**), `forAutonomousAgent(classOf[DraftAgent]|classOf[PublishAgent], …).assignTasks(...)`, then `accepted(CaseAccepted(caseId))` + `Location: /approvals/{caseId}`.
- [x] T013 [US1] Add `@Get("/approvals/{caseId}")` to `ApprovalEndpoint` — read the three snapshots via `Try(componentClient.forTask(id).get(def))` and map to `CaseState` per the data-model state machine (`drafting`, `draft-failed`, `awaiting-approval`, `publishing`, `published`, `rejected`); unknown `caseId` → `notFound("approval case not found")` using the T011 finding. Empty `Option`s are omitted from the JSON.
- [x] T014 [US1] Add `@Post("/approvals/{caseId}/approve")` to `ApprovalEndpoint` — apply the R4 status guard (unknown → 404; draft not `COMPLETED` → 409 `case is not awaiting approval`; approval not `PENDING` → 409 `case has already been decided`); on the open gate, `forTask(approvalId).assign("reviewer")` then `.complete(ApprovalTasks.APPROVAL, ApprovalDecision(true, note))` → 200 `approved`.
- [x] T013a [US1] **DEVIATION from the plan (user-requested design fix)**: the state machine and decision guard were originally written inline in `ApprovalEndpoint` (T013/T014), which buried the capability's core logic in the API layer and made it testable only by polling over HTTP — contrary to FR-015 / Constitution II. Extracted to pure domain: `approvals/domain/TaskOutcome.scala` (5-way ADT; `Pending` split from `Running` because the gate is decidable only while strictly pending) and `approvals/domain/ApprovalCase.scala` (`CaseProgress` 8 states with spec labels, `Decision` 4 outcomes, pure `progress`/`decide`). Endpoint reduced to adapt → ask domain → render. Added `ApprovalCaseTest` asserting every row of both tables in data-model §3. ✅ 19 unit tests green in 0.094s.
- [x] T015 [US1] Register the endpoint: add `com.gwgs.akkaagentic.approvals.api.ApprovalEndpoint` under the `http-endpoint` key in the descriptor, then run `mvn compile`. ✅ BUILD SUCCESS.
- [x] T016 [US1] Create `ApprovalGateIntegrationTest` in `src/test/scala/com/gwgs/akkaagentic/approvals/api/ApprovalGateIntegrationTest.scala` — register one `TestModelProvider` per agent class in `testKitSettings()` (research R6), drive the flow through `httpClient`, poll with Awaitility. Cover **C1** (202 + `Location` + `caseId`; immediate GET → `drafting`), **C2** (`awaiting-approval` with `draft`, **`reply` absent** — SC-003), **C3** (approve → 200, poll → `published` with `reply` — SC-002). ✅ 3 tests green. C2 waits 2s with the publish model primed and its agent assigned, then asserts no reply; C3 (same setup, approved) proves the agent *can* run — together they make the gate assertion real rather than vacuous. First run of both new agents ⇒ descriptor registration confirmed (endpoint discovered with all 4 routes).
- [x] T017 [US1] Run `mvn verify`; confirm US1 tests pass and capabilities 1–4 remain green. ✅ BUILD SUCCESS — 36 integration tests (up from 33), 0 failures.

**Checkpoint**: The gate demonstrably holds the publish step until a human approves — MVP complete.

---

## Phase 4: User Story 2 - Reject a drafted reply (Priority: P2)

**Goal**: A rejected case reaches a terminal `rejected` state carrying the note, and **no reply is ever
published**.

**Independent Test**: Poll a fresh case to `awaiting-approval`, `POST …/reject` with a note → 200; poll →
`rejected` with the note; assert `reply` is absent now and stays absent.

- [x] T018 [US2] **Done early** (built in the T012-T015 pass — it shares `onOpenGate` with approve; splitting it would have meant reopening the file). Add `@Post("/approvals/{caseId}/reject")` to `src/main/scala/com/gwgs/akkaagentic/approvals/api/ApprovalEndpoint.scala` — same R4 guard as approve; on the open gate, `forTask(approvalId).assign("reviewer")` then `.fail(note)` (the note becomes the task's `failureReason`) → 200 `rejected`. Failing the gate auto-cancels the dependent publish task (research R3), so no reply is produced (FR-006).
- [x] T019 [US2] Add **C4** to `ApprovalGateIntegrationTest` — reject with a note → 200; poll → `rejected` carrying the note; assert `reply` is never present, including after the publish mock would otherwise have fired (SC-003, SC-004). ✅ green — `rejectionStopsPublishAndRetainsTheNote`; proves the note round-trip (in via `fail(reason)`, out via `failureReason`) that the domain unit tests stub.
- [x] T020 [US2] Run `mvn verify` to confirm US1 + US2 pass together. ✅ BUILD SUCCESS — 37 integration tests, 0 failures.

**Checkpoint**: Both halves of the human decision proven — the gate is a real gate.

---

## Phase 5: User Story 3 - Observe the gated lifecycle by polling (Priority: P2)

**Goal**: Every lifecycle state is distinguishable; only an unknown handle is 404.

**Independent Test**: Assert `drafting` ≠ `awaiting-approval`; a never-started handle → 404; the two
terminal states (`published`, `rejected`) are distinct from each other and from in-progress.

- [x] T021 [US3] Add lifecycle tests to `src/test/scala/com/gwgs/akkaagentic/approvals/api/ApprovalGateIntegrationTest.scala` — **C9** (draft agent abandons: `draftModel.fixedResponse(failTask(...))` → GET `draft-failed`, distinct from `awaiting-approval`, gate never opens), **C10** (`published` vs `rejected` vs in-progress all distinct), and the GET half of **C5** (unknown `caseId` → 404, never a fabricated draft/reply — this is also the permanent home of the T011 finding). ✅ green — C9 `draftAbandonedShowsDraftFailedAndNeverOpensGate`, C10 `terminalStatesAreDistinct`, C5-GET `unknownCaseIdReturnsNotFound`. **Deviation:** the transient `drafting` state is NOT asserted via the integration test (racy — the fast mock blows through it); its distinctness from `awaiting-approval` is proven in `ApprovalCaseTest` (pure domain), consistent with the split-by-layer discipline.
- [x] T022 [US3] Run `mvn verify` to confirm the lifecycle states are asserted and everything stays green. ✅ BUILD SUCCESS — 40 integration tests, 0 failures.

**Checkpoint**: The gate's state is observable at every point (FR-002, FR-003).

---

## Phase 6: User Story 5 - Capabilities 1–4 remain independently usable (Priority: P2)

**Goal**: No regression; all five capabilities' components are discovered and served.

**Independent Test**: The caps-1–4 suites pass unmodified, and the descriptor lists every component.

- [x] T023 [US5] Run `mvn verify` and confirm the capability 1–4 test classes pass **unmodified** (no file under `src/{main,test}/{scala,java}/com/gwgs/akkaagentic/{application,api,domain,team,assistant,chat}` was touched by this feature), and review `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` so that every component of all five capabilities is listed exactly once (FR-012, FR-013, SC-007). ✅ git diff confirms only `approvals/` + the shared descriptor changed (no caps 1–4 source touched); T022 verify ran all caps 1–4 tests green unmodified; descriptor cross-checked against annotated classes — 6 endpoint / 4 agent / 3 autonomous-agent / 1 workflow, each exactly once, no duplicates.

**Checkpoint**: All five capabilities coexist.

---

## Phase 7: User Story 4 - Invalid input and invalid decisions are rejected (Priority: P3)

**Goal**: Fast, cheap failure and decision integrity — no fabricated cases, no double publish.

**Independent Test**: Blank question → 400 (no case); malformed body → 400; decision on an unknown handle
→ 404; decision before the gate opens → 409; second decision after a gate is decided → 409 with the
terminal outcome unchanged.

- [ ] T024 [US4] Add the validation and decision-integrity tests to `src/test/scala/com/gwgs/akkaagentic/approvals/api/ApprovalGateIntegrationTest.scala` — **C8** (blank question → 400 and malformed JSON → 400, each with **no case started and no model call**; unknown extra property tolerated → 202), the decision half of **C5** (approve/reject on an unknown handle → 404), **C6** (decision before the draft is ready → 409, state unchanged), **C7** (second decision after the gate is decided → 409, terminal outcome unchanged, **no second publish**). Assert non-2xx statuses by omitting `responseBodyAs` (the project's httpClient failure-status pattern).
- [ ] T025 [US4] Run `mvn verify` to confirm the full offline contract (C1–C10) passes with no API key or network (SC-006, SC-008).

**Checkpoint**: All five user stories independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Docs, the interop finding, and the one live proof the offline suite cannot give.

- [ ] T026 [P] Update `README.md` — add the cap-5 project-layout block (`approvals` package), a "Scala interop notes §7" entry (**`TaskClient` has no method-ref wall** — `create`/`get`/`result`/`assign`/`complete`/`fail` are keyed on value objects and strings, so a human-gated durable flow stays Scala *including its tests*, unlike cap-2's Workflow and cap-4's entity read; plus the no-Entity deterministic-id design that keeps it that way), and a "Capability 5 — human-in-the-loop approval gate (`POST /approvals`, async + decision)" section with the curl examples from `contracts/approval-api.md`.
- [ ] T027 [P] Update `ROADMAP.md` — add/flip the capability 5 row to done, update "You are here", and note the headline result: the method-ref wall is client-specific, and `TaskClient` is on the Scala-friendly side of it.
- [ ] T028 [P] Add a memory file `akka-scala-taskclient-no-methodref-wall.md` (+ a `MEMORY.md` index line) capturing R1/R2: human-in-the-loop via the autonomous-agent external-input task chain is authorable in Scala end-to-end because `TaskClient` takes no `Function`; deriving task ids from one `caseId` (no `KeyValueEntity`) is what keeps it that way, since an entity client would reintroduce the wall. Link `[[akka-scala-autonomous-agent-no-wall]]`, `[[akka-scala-workflow-methodref-wall]]`, `[[akka-scala-session-memory-testkit-blind]]`.
- [ ] T029 Live smoke test against Gemini (**authoritative proof of the end-to-end gate — FR-014 / SC-008**), per `quickstart.md`: submit → poll to `awaiting-approval` (real drafted text, **no `reply`**) → approve → poll to `published`; then a second case → reject → poll to `rejected` with the note and **no reply ever**. Optionally repeat the approve path with `-Dakka.javasdk.dev-mode.persistence.enabled=true` and a JVM restart while the gate is pending, to demonstrate FR-011 durability. Record the outcome in README's cap-5 "Verified live" note.
- [ ] T030 Final `mvn verify` (offline suite green across all five capabilities) and confirm `.env` is still git-ignored (`git check-ignore .env`) before any commit; stage only explicit paths.

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T011)** → **US1 (T012–T017)** → **US2 (T018–T020)** → then US3
  (T021–T022), US5 (T023) and US4 (T024–T025), which add only tests over US1+US2 code → **Polish
  (T026–T030)**.
- Within Foundational: results (T005) before `ApprovalTasks` (T006) before the two agents (T007, T008)
  before the descriptor (T009) and `mvn compile` (T010). T011's finding feeds T013.
- Within US1: the endpoint's three routes (T012 → T013 → T014) are edits to the **same file**, so they
  are sequential (no `[P]`); all production code before the integration test (T016).
- US3, US5 and US4 depend on US1 + US2 being complete (they assert `published` vs `rejected` and the
  decision guard), but are independent of one another.

### Parallel Opportunities

- T002 ∥ T003 (domain code and its test are different files).
- T005 ∥ T007 ∥ T008 — but T007/T008 reference `ApprovalTasks` (T006), so in practice T005 ∥ T006 first,
  then T007 ∥ T008 (two separate agent files).
- T026 ∥ T027 ∥ T028 (README, ROADMAP, memory file are independent).

---

## Implementation Strategy

- **MVP = Phase 1 + 2 + 3 (US1).** Stop and validate that the publish step does not run before approval —
  that single assertion is the capability's reason to exist.
- **Incremental**: US2 adds one route + one test; US3/US5/US4 add tests only — each a small, independently
  green increment over the MVP.
- **Per-component gates** (CLAUDE.md): build one component + its test at a time, pausing for review
  between steps; commit after each logical group; never commit `.env`.

## Notes

- No `pom.xml` change (the mixed Scala/Java build already compiles new Scala sources — plan.md).
- **No Entity, no Workflow** — the durable record is the task chain; the three task ids are derived from
  one `caseId` (research R2). Introducing an entity to store them would reintroduce the method-ref wall
  and defeat the capability's purpose.
- The task-result records (T005) must stay **Java-shaped**; only the HTTP DTOs (T012) are idiomatic
  `Option`-typed (research R5, feature-003 two-mapper finding).
- Total: 30 tasks — Setup 1, Foundational 10, US1 6, US2 3, US3 2, US5 1, US4 2, Polish 5.
