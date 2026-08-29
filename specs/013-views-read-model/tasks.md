# Tasks: To-do Read Model — a View over the assistant's to-dos (cap-11)

**Input**: Design documents from `specs/013-views-read-model/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md

**Tests**: INCLUDED — the spec's Success Criteria are test-shaped, and this capability is the project's
**first whose entire test suite runs with no model at all** (no `TestModelProvider`, mocked or live).

**Organization**: Grouped by user story. The projection itself is shared by every story, so the View
component + its row record + the derivation are **Foundational** (Phase 2); each story phase then adds
its own query, endpoint method, and test slice.

**Incremental workflow (CLAUDE.md, MANDATORY)**: build one component + its test at a time and **STOP
for user approval** at each 🛑 gate below. The gates are checkpoints in this list, not an afterthought
— do not start the next phase until the user says proceed.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable — different file, no dependency on an incomplete task
- **[Story]**: `[US1]`/`[US2]`/`[US3]`; Setup/Foundational/Polish carry no story label

## Path Conventions

New capability package `com.gwgs.akkaagentic.todos.*`, **split by language along the boundary research
proved**: Scala for the domain + the View; Java for the endpoint and the row records (research R1/R3).

**No `pom.xml` change** — the mixed Scala/Java build (README §4) already compiles both, and no new
dependency is added (Constitution I).

---

## Phase 1: Setup

**Purpose**: directories only. The descriptor edit is deliberately **not** here — see T007.

- [x] T001 Create the cap-11 package directories: `src/main/scala/com/gwgs/akkaagentic/todos/domain/`, `src/main/scala/com/gwgs/akkaagentic/todos/application/`, `src/main/java/com/gwgs/akkaagentic/todos/application/`, `src/main/java/com/gwgs/akkaagentic/todos/api/`, `src/test/scala/com/gwgs/akkaagentic/todos/domain/`, `src/test/scala/com/gwgs/akkaagentic/todos/api/`, `src/test/java/com/gwgs/akkaagentic/todos/application/`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the pure derivation, the row record, and the View component itself. **No user story can be
tested until the projection exists.**

**⚠️ T006 contains the capability's sharp edge (research R2). Read it before writing the file.**

- [x] T002 [P] Implement the pure counts derivation in `src/main/scala/com/gwgs/akkaagentic/todos/domain/TodoSummary.scala`: `def from(username: String, list: TodoList): TodoSummaryEntry`, computing `totalCount = list.todos.size`, `completedCount` = count of completed items, and `openCount = totalCount - completedCount` (**by subtraction**, so the invariant `open + completed == total` cannot drift). No Akka import, no I/O — pure and total. Do **not** project `nextId` (a write-side id-allocation detail, per data-model.md). *(FR-002; satisfies Constitution II domain-independence)*
- [x] T003 [P] Write the pure unit test `src/test/scala/com/gwgs/akkaagentic/todos/domain/TodoSummaryTest.scala` — no TestKit, no runtime, no model. Cases: a mixed list; an all-open list; an all-completed list (`openCount == 0`); an **empty** `TodoList` yielding `(0,0,0)`; and the invariant `open + completed == total` across all of them. *(FR-002; SC-001, SC-003)*

### 🛑 GATE 1 — STOP. Report the domain layer and wait for approval before continuing.

- [x] T004 [P] Create the view row record `src/main/java/com/gwgs/akkaagentic/todos/application/TodoSummaryEntry.java`: `public record TodoSummaryEntry(String username, int totalCount, int openCount, int completedCount) {}`. **Java, not Scala** — it crosses the SDK's internal serializer *and* is consumed by the Java endpoint, and README §8 forbids a Java→Scala dependency (research R3). Javadoc both reasons. *(FR-002; FR-010)*
- [x] T005 Wire `TodoSummary.from` in T002 to return this record (Scala→Java dependency — the permitted direction), and confirm `mvn compile` is green.
- [x] T006 Implement the View in `src/main/scala/com/gwgs/akkaagentic/todos/application/TodoSummaryView.scala`.
  - **⚠️ SHARP EDGE (research R2) — the `TableUpdater` MUST be declared in the companion `object TodoSummaryView`, NOT as an inner class of `class TodoSummaryView`.** A Scala *inner* class compiles to a non-static class carrying a `private final $outer` field with **no zero-argument constructor**; the SDK builds updaters via `getDeclaredConstructor()` + `newInstance()` and would fail at runtime. The companion-object form compiles to a `public static` member class *of the View class* with a synthesized no-arg constructor — byte-for-byte the shape the SDK reflects on. This was proven by compile spike; do not "simplify" it back to an inner class.
  - `@Component(id = "todo-summary-view")` on `class TodoSummaryView extends View`.
  - In the companion object: `@Table("todo_summaries") @Consume.FromKeyValueEntity(classOf[TodoEntity]) class Updater extends TableUpdater[TodoSummaryEntry]` with `def onUpdate(list: TodoList): TableUpdater.Effect[TodoSummaryEntry] = effects().updateRow(TodoSummary.from(updateContext().eventSubject().get, list))`. The row key is the source entity id (= username).
  - On the View class, the keyed query: `@Query("SELECT * FROM todo_summaries WHERE username = :username")` returning `QueryEffect[Optional[TodoSummaryEntry]]`, body `queryResult()`. **This is the R6 experiment** — see T009.
  - Scaladoc the R1 finding (the querying *client* is method-ref-only, so the endpoint is Java) and the R2 placement rule. *(FR-001, FR-002, FR-003)*
- [x] T007 Add the View to the hand-maintained descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` under a **new `view` key**: `view = ["com.gwgs.akkaagentic.todos.application.TodoSummaryView"]` (key confirmed from `ComponentType$`'s constant pool, research R4). **Load-bearing**: the annotation processor is disabled (`-proc:none`), so an unlisted component is silently never discovered. Comment the entry in the file's established style. *(FR-001)*
- [x] T008 Run `mvn verify` to confirm the service still boots with the new component registered and **every existing test stays green** (the descriptor is global — a malformed entry breaks all TestKit tests, which is the fastest signal that T006/T007 are correct).

### 🛑 GATE 2 — STOP. Report the View + descriptor and wait for approval before continuing.

**Checkpoint**: the projection exists and is registered. User story work can begin.

---

## Phase 3: User Story 1 — Look up one assistant's to-do standing (P1) 🎯 MVP

**Goal**: a keyed lookup returns one username's counts; an unknown username is a clean not-found.

**Independent Test**: publish a `TodoList` for a username, query by that username, assert the counts;
query an unknown username, assert not-found.

- [x] T009 [US1] Write the view integration test `src/test/java/com/gwgs/akkaagentic/todos/application/TodoSummaryViewIntegrationTest.java`. **Java** — it queries the View through `componentClient.forView().method(TodoSummaryView::getByUsername)`, a method reference Scala cannot express (research R1); this is the same forced-Java-test precedent as cap-4's `SessionMemoryIntegrationTest`. Extend `TestKitSupport`; override `testKitSettings()` with `TestKit.Settings.DEFAULT.withKeyValueEntityIncomingMessages(TodoEntity.class)`; drive the projection with `getKeyValueEntityIncomingMessages(TodoEntity.class).publish(todoList, username)` — **no entity command, no agent, no model** (research R5). Assert through `Awaitility.await().atMost(...).untilAsserted(...)`, **never a bare assert**, because views are eventually consistent (FR-009). Cases: counts match a published list; an update to the same username replaces the row (not accumulates); a username with an emptied list yields all-zero counts; an unknown username yields not-found. *(FR-001, FR-003, FR-004, FR-009, FR-012; SC-001, SC-002, SC-007)*
- [x] T010 [US1] **R6 DECISION POINT — settle what a keyed no-match query actually returns.** Run T009 and observe.
  - **Branch A (preferred, if `QueryEffect[Optional[TodoSummaryEntry]]` works)**: keep T006's signature; not-found is `Optional.empty`.
  - **Branch B (documented fallback, if `Optional` is unsupported)**: change the keyed query in `TodoSummaryView.scala` to the wrapper shape `@Query("SELECT * AS entries FROM todo_summaries WHERE username = :username")` returning `QueryEffect[TodoSummaryEntries]` (create that record early, from T013), and treat an **empty list as not-found**. Deterministic regardless of SDK behavior.
  - Either branch satisfies FR-004 — record which one held in research.md R6, and carry it into the README in T023. *(FR-004)*
- [x] T011 [US1] Create the endpoint `src/main/java/com/gwgs/akkaagentic/todos/api/TodoSummaryEndpoint.java`. **Java — forced by research R1**, and Javadoc that reason. `@HttpEndpoint("/todo-summaries")` + `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`, constructor-injected `ComponentClient`. Define its **own** response record `TodoSummaryResponse(String username, int total, int open, int completed)` — never return the view row outward (FR-010). Implement `@Get("/by-user/{username}")` returning `HttpResponse`: `200` with the mapped response when the row exists, `HttpResponses.notFound()` when it does not. Use `.invoke()` (synchronous), not `invokeAsync`. *(FR-003, FR-004, FR-008, FR-010, FR-011)*
- [x] T012 [US1] Add the endpoint to the descriptor's existing `http-endpoint` list in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` (`com.gwgs.akkaagentic.todos.api.TodoSummaryEndpoint`), with a comment in the file's established style noting it is Java because `ViewClient` is method-reference-only.
- [x] T013 [US1] Write `src/test/scala/com/gwgs/akkaagentic/todos/api/TodoSummaryEndpointIntegrationTest.scala`. **Scala** — it uses only `httpClient` and the `Class`-keyed `withKeyValueEntityIncomingMessages`, neither of which involves a method reference (research R5); this is the concrete proof that the wall claimed only the endpoint, not the test surface. Seed via the testkit publisher, then `Awaitility` around the HTTP call. Cases: `200` with correct counts for a known username; `404` for an unknown one. **For the `404`, omit `responseBodyAs` and assert on `response.status()` alone** — `responseBodyAs` throws on non-2xx (established project finding). *(FR-003, FR-004; SC-001, SC-002, SC-007)*

### 🛑 GATE 3 — STOP. Report US1 (MVP) and wait for approval before continuing.

**Checkpoint**: US1 is independently functional — the read model works end to end over HTTP.

---

## Phase 4: User Story 2 — Find every assistant that still has open work (P2)

**Goal**: the cross-user query no other surface in this project can answer.

**Independent Test**: publish lists for several users in differing states; assert exactly those with at
least one open item come back; assert an all-completed world returns an empty collection, not an error.

- [x] T014 [P] [US2] Create the multi-row wrapper `src/main/java/com/gwgs/akkaagentic/todos/application/TodoSummaryEntries.java`: `public record TodoSummaryEntries(List<TodoSummaryEntry> entries) {}`. Java, for the same reason as T004. *(FR-005)*
- [x] T015 [US2] Add the filtered query to `src/main/scala/com/gwgs/akkaagentic/todos/application/TodoSummaryView.scala`: `@Query("SELECT * AS entries FROM todo_summaries WHERE openCount > 0")` returning `QueryEffect[TodoSummaryEntries]`. **The `SELECT * AS entries` wrapper form is mandatory** for a query that can select many rows (AGENTS.md) — a bare `SELECT *` returning a list is wrong. *(FR-005, FR-006)*
- [x] T016 [US2] Extend `TodoSummaryViewIntegrationTest.java` with the cross-user cases: several usernames in differing states return exactly those with `openCount > 0`; a user whose items are all completed is **excluded** while still being findable by the T009 keyed lookup; when no user has open work the query returns an **empty list, successfully**. Compare order-insensitively — no `ORDER BY` is declared (contracts/http-api.md). *(FR-005, FR-006; SC-003, SC-004)*
- [x] T017 [US2] Add `@Get("/with-open-work")` to `TodoSummaryEndpoint.java`, returning the endpoint's own `TodoSummariesResponse(List<TodoSummaryResponse> summaries)`. An empty result is `200` with `{"summaries":[]}` — **never `404`**. *(FR-005, FR-006, FR-010)*
- [x] T018 [US2] Extend `TodoSummaryEndpointIntegrationTest.scala`: `200` with multiple users; the all-completed user absent; the empty-world case returning `200` with an empty array. *(FR-006; SC-003, SC-004)*

### 🛑 GATE 4 — STOP. Report US2 and wait for approval before continuing.

---

## Phase 5: User Story 3 — Reject a malformed lookup before doing any work (P3)

**Goal**: the project's uniform validation-first contract, on a read surface.

**Independent Test**: request a blank username; assert `400` and that no query ran.

- [x] T019 [US3] Add blank/whitespace-username validation to `TodoSummaryEndpoint.java`'s `by-user` method, **before** any `componentClient` call: return `HttpResponses.badRequest("username must not be blank")`. Keep it an explicit guard returning an error response, not a thrown exception (AGENTS.md). *(FR-007)*
- [x] T020 [US3] Extend `TodoSummaryEndpointIntegrationTest.scala` with the `400` case for a blank/whitespace username, asserting it is **distinct from the `404`** of T013. **Omit `responseBodyAs`** and assert on status only. *(FR-007; SC-005)*

### 🛑 GATE 5 — STOP. Report US3 and wait for approval before continuing.

---

## Phase 6: Polish, Regression Guard & Documentation

- [x] T021 **SC-006 regression guard.** Verify capability 6 is untouched: `git diff --stat main -- src/main/java/com/gwgs/akkaagentic/a2a src/main/scala/com/gwgs/akkaagentic/a2a` must report **no changes** to `TodoEntity.java`, `TodoTools.java`, `Todo.java`, `TodoList.java`, `PersonalAssistantAgent.scala`, `PersonalAssistantEndpoint.scala`. Then run a full `mvn verify` and confirm **every pre-existing test still passes** and no write path was added to the new endpoint. *(FR-008; SC-006)*
- [x] T022 Confirm the whole suite runs with **no `TestModelProvider` anywhere in the cap-11 tests** and no network/API key — `grep -r TestModelProvider src/test/{scala,java}/com/gwgs/akkaagentic/todos` must return nothing. Note this milestone (the project's first model-free capability) for the docs tasks. *(FR-011, FR-012; SC-007)*
- [x] T023 [P] Add **README "Scala interop notes" §13** recording the resolved finding: (1) **R1 confirmed** — `ViewClient` exposes only `method(akka.japi.function.Function…)` with no `dynamicCall`, and that `Function` is `Serializable`, so the querying endpoint must be Java, exactly like the Workflow/entity clients; (2) **R2, the new result** — the View component itself **stays Scala**, because a `TableUpdater` placed in the **companion object** compiles to the `public static`, zero-arg nested class the SDK's `getDeclaredClasses()` + `getDeclaredConstructor()` requires, whereas a Scala *inner* class does not (include the spike evidence); (3) row types are Java by the two-mapper boundary **and** §8's language-of-consumer rule; (4) descriptor key `view`; (5) the R6 outcome from T010. Lead with the through-line sharpening: **this is the first capability split across the component/caller boundary — the wall claimed only the caller, not the component.** *(FR-013; SC-008)*
- [x] T024 [P] Add the **README capability 11 usage section** with the curl examples from `specs/013-views-read-model/contracts/http-api.md` (`GET /todo-summaries/by-user/{username}`, `GET /todo-summaries/with-open-work`, the `404` vs `400` distinction, the empty-collection case). State plainly that to-dos are still written **only** through cap-6's assistant, and include the **eventual-consistency caveat** (a read right after a write may briefly show old counts; it converges). Note this is the first capability with **no model in the request path at all**.
- [x] T025 [P] Update the **project layout tree** at the top of README.md with the new `todos` package, annotating the Scala/Java split and why each side is the language it is.
- [x] T026 [P] Update `ROADMAP.md`: add the capability 11 row to the path table (status ✅ done — merged); replace the "You are here" block with cap-11; and **remove the Views row from the "Candidate next capabilities" table** (it has been built), leaving guardrails / eval / streaming as the remaining candidates.
- [x] T027 [P] Update `FINDINGS.md` with the View data point, extending the client-property through-line to its sharper form: *the wall is a property of the client, and it travels no further than the class that holds the method reference* — plus the new, distinct hazard class R2 surfaced (a **bytecode-shape** requirement that only one of Scala's two nesting forms satisfies, unlike every previous finding, which turned on `Class`/`String`-keyed APIs).
- [~] T028 *(SKIPPED — Branch A held in T010, as the task directs.)* If **Branch B** was taken in T010, record the `Optional`-return limitation in `docs/sdk-3.6.0-limitations.md` alongside cap-9's `maxResults` entry, as a re-check-on-upgrade item. *(Skip entirely if Branch A held.)*
- [x] T029 Run `specs/013-views-read-model/quickstart.md` end to end against a locally running service (`mvn compile exec:java`) to validate the documented curl flows, then final `mvn verify`.

### 🛑 GATE 6 — STOP. Report the finished capability and wait for approval before opening the PR.

---

## Dependencies & Execution Order

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** blocks everything: no story is testable before the
  projection exists and is registered (T006–T008).
- **US1 (Phase 3)** is the MVP and also the phase that **settles R6** (T010); US2's fallback branch
  depends on that outcome.
- **US2 (Phase 4)** depends on Foundational only, but shares two files with US1
  (`TodoSummaryView.scala`, `TodoSummaryEndpoint.java`), so it runs **after** US1 rather than beside it.
- **US3 (Phase 5)** is a small guard on the US1 endpoint method; it depends on T011.
- **Phase 6** depends on all stories being complete.

### Parallel Opportunities

- **T002 ∥ T003** — derivation and its unit test (written together, different files).
- **T004** is `[P]` against nothing else in flight; **T014** is `[P]` (new file, no dependants yet).
- **T023 ∥ T024 ∥ T025 ∥ T026 ∥ T027** — the documentation tasks touch different files (README §13,
  README usage, README tree, ROADMAP, FINDINGS) and can be written in one pass. *(T024/T025 both touch
  README.md — sequence them if editing literally in parallel.)*
- Nothing in Phase 2 or Phase 3 is parallel across the 🛑 gates: the gates are approval points, not
  scheduling hints.

---

## Implementation Strategy

### MVP (User Story 1 only)

Phases 1–3 deliver a working read model: the projection, the keyed query, the HTTP surface, and both
tests. That alone answers the capability's interop question and is demonstrable on its own.

### Incremental delivery

1. Foundational → the projection exists and is registered (GATE 2).
2. + US1 → **MVP**: keyed lookup over HTTP, R6 settled (GATE 3).
3. + US2 → the cross-user query, the payoff no entity read can provide (GATE 4).
4. + US3 → the uniform validation contract (GATE 5).
5. + Polish → regression guard, four docs, quickstart validation (GATE 6).

---

## Requirements Coverage

| Requirement | Tasks |
|---|---|
| FR-001 projection maintained | T006, T007, T009 |
| FR-002 one row per username, counts | T002, T003, T004 |
| FR-003 keyed lookup | T006, T011, T013 |
| FR-004 not-found distinguishable | T009, T010, T011, T013 |
| FR-005 cross-user open-work query | T014, T015, T016, T017 |
| FR-006 empty collection is success | T015, T016, T017, T018 |
| FR-007 blank username rejected | T019, T020 |
| FR-008 read-only, cap-6 untouched | T011, T021 |
| FR-009 eventual consistency tolerated | T009, T013, T024 |
| FR-010 endpoint owns its types | T004, T011, T017 |
| FR-011 no model in the request path | T011, T022, T024 |
| FR-012 fully offline verification | T009, T022 |
| FR-013 interop finding recorded | T023, T027, T028 |
| SC-001 counts match | T003, T009, T013 |
| SC-002 not-found vs zero-counts | T009, T013 |
| SC-003 exactly the open-work users | T003, T016, T018 |
| SC-004 empty world succeeds | T016, T018 |
| SC-005 blank rejected, no query | T020 |
| SC-006 no write path, cap-6 green | T021 |
| SC-007 offline, no model | T009, T013, T022 |
| SC-008 finding recorded with evidence | T023, T027 |

---

## Notes

- `[P]` = different file, no dependency on an incomplete task.
- **Do not skip a 🛑 gate.** CLAUDE.md makes the approval checkpoints mandatory.
- Commit after each task or logical group; the descriptor edits (T007, T012) are the two that break
  *everything* when wrong, so verify with `mvn verify` immediately after each.
- Views are eventually consistent — if a test is flaky, the fix is a longer `Awaitility` window, never
  a `Thread.sleep` or a bare assert.
