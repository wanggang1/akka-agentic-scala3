# Tasks: Usage-accurate citations

**Input**: Design documents from `/specs/023-usage-accurate-citations/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/cited-ask.md ✅

**Tests**: INCLUDED — the constitution (§III) mandates tests for every behavioral change, and the
CLAUDE.md incremental workflow interleaves each component with its test.

**Organization**: The feature is one endpoint fronting one new agent, so the shared machinery (pure
selection logic, agent, endpoint, descriptor) is **Foundational** — every user story exercises it. Each
user-story phase then adds the integration test that proves *that story's* behavior end-to-end, and is
independently runnable. Commit at each approved gate (CLAUDE.md), not once at the end.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (maps to spec.md). Setup/Foundational/Polish carry no story label.

## Path Conventions

Single Scala project: `src/main/scala/...`, `src/test/scala/...` at repo root. Feature package
`com.gwgs.akkaagentic.docs.{domain,application,api}` (matches cap-8's layout).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm a clean baseline before adding anything.

- [ ] T001 Confirm the baseline is green on branch `023-usage-accurate-citations` with `mvn test -Pquick` (pure-logic suite), so any later red is attributable to this feature.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared machinery every user story depends on. **No user-story phase can begin until this
is complete.** cap-8's `DocsAgent.scala`, `KnowledgeStore.scala` and `DocsEndpoint.scala` are **not edited**
(FR-001, SC-004).

- [ ] T002 Create the pure selection logic `UsageCitations` (object) in `src/main/scala/com/gwgs/akkaagentic/docs/domain/UsageCitations.scala` — `select(reportedUsed: List[String], retrievedLabels: List[String], declined: Boolean): List[String]` implementing data-model.md R4 rules: declined → `Nil`; else `retrievedLabels` filtered to those in `reportedUsed`, de-duplicated, in `retrievedLabels` order; empty intersection on a non-decline → `Nil` (FR-004/FR-005/FR-007). No Akka imports.
- [ ] T003 [P] Unit-test `UsageCitations` in `src/test/scala/com/gwgs/akkaagentic/docs/domain/UsageCitationsTest.scala` — cases: subset-and-order preserved; de-duplication; a reported label absent from `retrievedLabels` is dropped (invariant `result ⊆ retrievedLabels`, SC-002); `declined=true` → empty even when `reportedUsed` is non-empty (FR-005); non-decline with empty/all-non-retrieved `reportedUsed` → empty (FR-007). Run `mvn test -Dtest='UsageCitationsTest'`. **Gate → commit** (`feat(023): B4 domain — UsageCitations.select + unit tests`).
- [ ] T004 Create the structured-output agent `CitingDocsAgent` in `src/main/scala/com/gwgs/akkaagentic/docs/application/CitingDocsAgent.scala` — `@Component(id = "citing-docs-agent")`; command `ask(request: DocsAgent.Request): Agent.Effect[CitedAnswer]` using `.systemMessage(...)` (instruct: answer ONLY from numbered sources; after answering, list in `usedSources` the exact parenthesized source labels actually used; if unanswerable, set `answer` to `DocsAgent.DontKnow` and `usedSources` to `[]`), `.userMessage(...)` (reuse cap-8's numbered `[n] (label) text` rendering), `.responseConformsTo(classOf[CitedAnswer])`, `.onFailure(...)`→`CitedAnswer(DocsAgent.DontKnow, java.util.List.of())` (R3, with a `logger.warn`), `.thenReply()`. Define nested Java-shaped `CitedAnswer(@JsonProperty answer: String, @JsonProperty usedSources: java.util.List[String])` with `@JsonCreator`. Reuse `DocsAgent.Request`/`Passage`/`DontKnow` read-only.
- [ ] T005 Register the new components in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` — add `"com.gwgs.akkaagentic.docs.application.CitingDocsAgent"` under `agent` and `"com.gwgs.akkaagentic.docs.api.CitedDocsEndpoint"` under `http-endpoint`, each with a one-line comment keying it to cap-B4. (Scala components are not auto-discovered — `[[scala-akka-component-descriptor]]`; a missing line fails startup for the whole service.)
- [ ] T006 Create the endpoint `CitedDocsEndpoint` in `src/main/scala/com/gwgs/akkaagentic/docs/api/CitedDocsEndpoint.scala` — `@HttpEndpoint`, `@Acl(INTERNET)`, inject `(ComponentClient, KnowledgeStore)`. `@Post("/cited-ask") def ask(request: CitedAskRequest): HttpResponse`: validate via `AskQuestion.validate` (Left → `badRequest`); retrieve `knowledgeStore.retrieve(valid.question, TopK=3)`; build `DocsAgent.Request`; call `componentClient.forAgent().inSession(UUID.random).dynamicCall[DocsAgent.Request, CitingDocsAgent.CitedAnswer]("citing-docs-agent").invoke(...)`; `declined = reply.answer` is the decline sentinel (reuse cap-8's `isDecline` logic); `retrievedLabels = retrieved.map(_.source).distinct`; `cited = UsageCitations.select(reply.usedSources.asScala.toList, retrievedLabels, declined)`; return `ok(CitedAskReply(reply.answer, cited, retrievedLabels))`. Define API records `CitedAskRequest(question: Option[String])` (`@JsonIgnoreProperties(ignoreUnknown=true)`) and `CitedAskReply(answer: String, citedSources: List[String], retrievedSources: List[String])`. Verify `mvn compile`. **Gate → commit** (`feat(023): B4 agent + endpoint — citing-docs-agent, POST /cited-ask`).

**Checkpoint**: service compiles and starts; `/cited-ask` wired; all stories can now be verified.

---

## Phase 3: User Story 1 — Precise citations for a focused answer (Priority: P1) 🎯 MVP

**Goal**: `/cited-ask` cites only the sources the answer used — a strict subset of what was retrieved.

**Independent Test**: POST an in-corpus question whose answer uses one of three retrieved passages; assert
`citedSources ⊊ retrievedSources`, contains the used label, and `citedSources ⊆ retrievedSources`.

- [ ] T007 [US1] Add `CitedDocsEndpointIntegrationTest` in `src/test/scala/com/gwgs/akkaagentic/docs/api/CitedDocsEndpointIntegrationTest.scala` extending `TestKitSupport` with a `TestModelProvider` registered for `classOf[CitingDocsAgent]`. First test: mock a `CitedAnswer("<grounded answer>", List("durability-tasks"))` via `fixedResponse(JsonSupport.encodeToString(...))`; `POST /cited-ask` the restart question; assert `200`, `citedSources == List("durability-tasks")`, `retrievedSources.size >= 2` and strictly contains `citedSources` (SC-001/SC-002, US1). Run `mvn verify -Dit.test='CitedDocsEndpointIntegrationTest' -Dtest='!*' -DfailIfNoTests=false`.

**Checkpoint**: US1 provable end-to-end with a deterministic model. (Commit with US2/US3 tests as one gate — same file.)

---

## Phase 4: User Story 2 — Honest decline still cites nothing (Priority: P1)

**Goal**: An out-of-corpus question declines and cites nothing; a failed turn behaves the same.

**Independent Test**: Mock the decline sentinel → assert decline + empty `citedSources`; mock a model
failure → assert the same (no 500).

- [ ] T008 [US2] In `CitedDocsEndpointIntegrationTest`, add: (a) `fixedResponse(encode(CitedAnswer(DocsAgent.DontKnow, List.of())))` → `POST` out-of-corpus question → `200`, `answer` is the decline sentinel, `citedSources.isEmpty` (SC-003, FR-005). (b) A decline whose mocked `usedSources` is **non-empty** still yields `citedSources.isEmpty` (decline overrides, US2 scenario 2). (c) `whenMessage(_ => true).failWith(new RuntimeException("simulated timeout"))` → `200` decline, `citedSources.isEmpty`, no 500 (R3). Re-run the IT command.

**Checkpoint**: decline/failure honesty proven.

---

## Phase 5: User Story 3 — Measure self-report faithfulness (Priority: P2)

**Goal**: The response exposes both lists; a hallucinated/non-retrieved label is dropped; and the
self-report faithfulness is measured live and recorded.

**Independent Test**: Mock `usedSources` containing a label that was *not* retrieved → assert it is absent
from `citedSources` but `retrievedSources` is complete; then a live run records divergence.

- [ ] T009 [US3] In `CitedDocsEndpointIntegrationTest`, add: (a) mock `CitedAnswer("<answer>", List("durability-tasks","totally-made-up-label"))` → `citedSources` contains `durability-tasks` and NOT `totally-made-up-label`; `citedSources ⊆ retrievedSources` (FR-004, SC-002, US3). (b) mock `CitedAnswer("<answer>", List("only-non-retrieved"))` → `citedSources.isEmpty`, `answer` unchanged (FR-007). (c) assert every response carries a non-empty `retrievedSources` (FR-006). Re-run the IT command. **Gate → commit** (`test(023): B4 integration — subset, decline, drop-non-retrieved, FR-007`).
- [ ] T010 Run the full gate `mvn clean verify` (catches a missing/mis-typed descriptor line, which breaks startup for every capability — CLAUDE.md). **Must be green before docs.**
- [ ] T011 [US3] **Live measurement** (needs Ollama + qwen3:8b): `mvn compile exec:java`, then run the quickstart.md in-corpus questions against `/cited-ask`. Record per question — `retrievedSources`, model `usedSources`, resulting `citedSources`, and a subset-faithfulness judgement — into `specs/023-usage-accurate-citations/research.md` R6 (replace the UNVERIFIED note with the measured finding) and capture the raw Q-H for the README. This is the capability's headline deliverable (SC-005).

**Checkpoint**: all three stories proven; the reopened self-report tension is documented from evidence.

---

## Phase 6: Polish & Documentation (Cross-Cutting)

**Purpose**: Make the capability discoverable and leave nothing in this session's head (CLAUDE.md).

- [ ] T012 Add a new README "Scala interop notes" section for cap-B4 (next § number): the design (usage-accurate vs ground-truth, the intersection honesty floor), the `/cited-ask` curl examples, the live Q-H from T011, and the honest statement of the cap-7 D6 tension reopened + what the measurement showed.
- [ ] T013 [P] Flip the ROADMAP **B4** row to done (with the measured verdict), and update FINDINGS with the self-report-faithfulness result.
- [ ] T014 [P] Add a memory file for the B4 finding (self-report faithfulness of qwen3:8b; the intersection floor as the honest middle ground) and a one-line pointer in `MEMORY.md`; link `[[akka-scala-rag-di-clean]]` (cap-8) and `[[gemini-tools-vs-structured-output]]`.
- [ ] T015 Open the PR: push the branch, hand the user the compare URL + PR body (no `gh` here — `[[github-pr-workflow]]`). Docs are their own final commit (`docs(023): B4 — README §, ROADMAP flip, FINDINGS, memory`).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**: none — start immediately.
- **Foundational (P2)**: after Setup. **Blocks all user stories.** Within it: T002→T003 (test needs the
  object); T004 and T006 both need T005's descriptor entries to *start* at runtime but compile independently;
  order T002→T003→T004→T005→T006.
- **User Stories (P3–P5)**: all depend on Foundational. They touch the **same** test file
  (`CitedDocsEndpointIntegrationTest`), so run **sequentially** (T007→T008→T009), not in parallel.
- **T010 (clean verify)**: after all integration tests.
- **T011 live**: after T010 green.
- **Polish (P6)**: after T011 (docs quote its result).

### Within Each User Story

- US1/US2/US3 are additive test methods on one file; each is an independently *runnable* assertion of its
  story's behavior, but they are authored in order to avoid edit conflicts.

### Parallel Opportunities

- T003 is [P] relative to later-file work but gated before its own commit.
- T013 and T014 are [P] (different files: ROADMAP/FINDINGS vs memory).
- The user stories themselves are **not** parallel here (shared test file) — an honest deviation from the
  template's usual independence, because the feature is a single endpoint.

---

## Implementation Strategy

### MVP (User Story 1)

Setup → Foundational (T001–T006) → US1 (T007). At the T007 checkpoint the usage-accurate citation is
demonstrable end-to-end with a deterministic model — the smallest thing that shows B4 working.

### Incremental delivery

US1 (subset) → US2 (decline/failure honesty) → US3 (drop-non-retrieved + live measurement) → gate →
docs. Each adds assertions without breaking the prior ones; cap-8's `/ask` suite stays green throughout
(SC-004), re-confirmed by T010.

---

## Notes

- `mvn clean verify` is the **gate**, not the loop — the per-change selection rule is in CLAUDE.md. The
  descriptor edit (T005) is exactly the class of change that mandates a clean verify (T010).
- Commit at each approved gate (domain, agent+endpoint, integration tests, docs) — not one big commit.
- The live measurement (T011) cannot be faked offline; if Ollama is unavailable, record it as UNVERIFIED
  and say so rather than writing a confident sentence over a gap (CLAUDE.md).
