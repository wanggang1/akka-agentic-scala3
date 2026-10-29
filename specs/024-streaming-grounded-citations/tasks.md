# Tasks: Streaming grounded answer with honest citations (capability 22, fork B5)

**Input**: Design documents from `specs/024-streaming-grounded-citations/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R9), data-model.md, contracts/sse-cited-ask.md

**Tests**: INCLUDED — the project constitution (III. Test Coverage) and CLAUDE.md's gate workflow require a
test with every behavioral change. Per CLAUDE.md the convention is component-then-test within an approved
gate (not strict test-first); each gate is a compiling, test-green, committable unit.

**Numbering note**: this is the **24th** feature directory (`specs/024`) and the **22nd** capability. README
interop note is **§24** (that counter tracks the feature number). Do not relabel.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1–US4 (user-story phases only)
- Exact file paths included. Scala under `src/main/scala/...`, the two forced-Java files under `src/main/java/...`.

## Path Conventions

Single Akka project, mixed Scala/Java, existing `docs` package. New code joins
`com.gwgs.akkaagentic.docs` (`.domain` / `.application` / `.api`), reusing capability 8/21 retrieval and
the honesty floor, and capability 20's `SseErrorReason`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the capability's footprint and the gates that will catch wiring mistakes.

- [x] T001 Confirm branch `024-streaming-grounded-citations` and a green baseline (`mvn test -Pquick`) before adding code, so later breaks are attributable to this capability. — DONE: 274 tests green.
- [x] T002 Record in plan/tasks the two descriptor lines this capability will add (research R8) and the JavaQuarantine expectation (exactly two new Java files: `docs/api/StreamingCitedDocsEndpoint.java`, `docs/api/GroundedSseEvent.java`). No code yet. — DONE: recorded in plan.md "Setup record (T002)"; measured that the docs tree has zero Java files today and no docs quarantine test exists, so T020 creates one.

**Checkpoint**: Baseline green; the descriptor + quarantine obligations are written down.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The pure/shared pieces every user story depends on — the stripper, the Java-callable
floor/prep façade, the wire envelope, and the streaming agent. **No user story can be exercised until
these exist.** These carry no method-ref wall except the two Java files (research R6/R7).

**⚠️ CRITICAL**: Phase 3+ cannot begin until this phase is complete.

- [ ] T003 [P] Implement the pure incremental stripper `CitationMarkers` in `src/main/scala/com/gwgs/akkaagentic/docs/domain/CitationMarkers.scala`: `empty`, `step(state,chunk) -> (emit, carry, numbers)`, `finish(state) -> (emit, numbers)`; marker grammar `[` + digits + `]`; carry holds a trailing partial-marker tail across chunks; non-numeric brackets left literal (data-model "CitationMarkers", research R3). Immutable, no `var`.
- [ ] T004 [P] Unit-test `CitationMarkers` in `src/test/scala/com/gwgs/akkaagentic/docs/domain/CitationMarkersTest.scala`: no-marker passthrough; single/multiple markers extracted and removed; **marker split at every position across a chunk boundary** (`"...restart [1"`+`"2] because…"` → number 12, no `[1` leak); trailing `[` held then flushed as literal by `finish`; decline text passes through unchanged. Assert the boundary property (concatenated emits = answer minus numeric markers; concatenated numbers = markers in order).
- [ ] T005 [P] Implement the Java-callable façade `GroundedCitedPrep` in `src/main/scala/com/gwgs/akkaagentic/docs/application/GroundedCitedPrep.scala`: `prepare(store, question, topK) -> Prepared(request: DocsAgent.Request, sources: RetrievedSources, retrievedLabels: java.util.List[String])` (reuse `KnowledgeStore.retrieve`, build `DocsAgent.Request`/`Passage`, `RetrievedSources.of` in presented order) and `citationsEvent(sources, reportedNumbers: java.util.List[Integer], declined, retrievedLabels) -> GroundedSseEvent.Citations` applying the floor via `sources.cite(...)` (data-model "GroundedCitedPrep", research R7). Keep all opaque-newtype conversions on the Scala side.
- [ ] T006 [P] Unit-test `GroundedCitedPrep` in `src/test/scala/com/gwgs/akkaagentic/docs/application/GroundedCitedPrepTest.scala` against a stub corpus: reported subset → cited subset; out-of-range/non-retrieved number dropped (floor); decline flag → empty citations; non-decline with empty/unverifiable report → empty citations, retrieved list still populated (FR-007/FR-008, reuse cap-21 cases).
- [ ] T007 Implement the wire envelope `GroundedSseEvent` in `src/main/java/com/gwgs/akkaagentic/docs/api/GroundedSseEvent.java`: sealed interface with records `Data(String text)`, `Citations(List<String> usedSources, List<String> retrievedSources)`, `ErrorEvent(String reason)` (data-model "GroundedSseEvent"). Java-shaped for the internal mapper.
- [ ] T008 Implement the streaming grounded agent `StreamingCitedDocsAgent` in `src/main/scala/com/gwgs/akkaagentic/docs/application/StreamingCitedDocsAgent.scala`: `@Component(id = "streaming-cited-docs-agent")`, `def stream(request: DocsAgent.Request): Agent.StreamEffect` with `MemoryProvider.none()`; system message = ground-only + **emit inline `[n]` markers** for used sources + reply EXACTLY `DocsAgent.DontKnow` when unanswerable (research R6; note: re-admits markers that cap-21's follow-up suppressed, because the stream has no structured channel — R1).
- [ ] T009 Register the agent in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`: add `com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent` (research R8). Verify startup with `mvn clean verify` (or a focused run) — a missing line fails the `tokenStream` lookup.

**Checkpoint**: Stripper, façade, envelope, and agent exist and are unit-green; the agent is discoverable.

---

## Phase 3: User Story 1 — A grounded answer that streams and then cites honestly (Priority: P1) 🎯 MVP

**Goal**: `POST /sse-cited-ask` streams clean grounded prose as `event: data`, then one terminal
`event: citations` frame with usage-accurate citations (⊆ retrieved) plus the retrieved list.

**Independent Test**: Ask *"what makes agent work survive a restart?"* (scripted model answer with `[n]`
markers); assert concatenated `data` text reconstructs the answer with markers removed, and one terminal
`citations` frame carries a subset `usedSources` + full `retrievedSources`.

### Implementation for User Story 1

- [ ] T010 [US1] Implement `StreamingCitedDocsEndpoint` in `src/main/java/com/gwgs/akkaagentic/docs/api/StreamingCitedDocsEndpoint.java`: `@HttpEndpoint` + `@Acl(INTERNET)`; `ChatRequest(String question)` (`@JsonIgnoreProperties`); `@Post("/sse-cited-ask")`. Pipeline (plan "The pipeline, precisely"): validate via `AskQuestion.validate` (400 on blank, no retrieval) → `GroundedCitedPrep.prepare` → `tokenStream(StreamingCitedDocsAgent::stream).source(request)` → `initialTimeout`/`idleTimeout`/`groupedWithin` (reuse cap-20 constants) → `statefulMap` running `CitationMarkers` (emit stripped `Data`; accumulate numbers into an `AtomicReference`; flush carry on completion) → `.concat(Source.lazySource(citations from GroundedCitedPrep.citationsEvent))` → `.recover(_ -> ErrorEvent(SseErrorReason.reasonFor(t)))` → `HttpResponses.serverSentEvents(events, _ -> "", eventType)` where `eventType` maps `Data→"data"`, `Citations→"citations"`, `ErrorEvent→"error"`.
- [ ] T011 [US1] Register the endpoint in the component descriptor: add `com.gwgs.akkaagentic.docs.api.StreamingCitedDocsEndpoint` (research R8) — else the route 404s. (The data types need no entry.)
- [ ] T012 [US1] Happy-path integration test in `src/test/scala/com/gwgs/akkaagentic/docs/api/StreamingCitedDocsEndpointIntegrationTest.scala` via `SseRouteTester` + `TestModelProvider` scripting a marker-bearing answer: assert (a) `data` frames' decoded text concatenates to the answer **with `[n]` removed** (SC-001, FR-004); (b) exactly one terminal `citations` frame **after** all `data` frames (SC-001, FR-005); (c) `usedSources ⊆ retrievedSources` and matches the scripted markers (SC-002); (d) a blank question returns 400 with no stream (FR-012).

**Checkpoint**: MVP — a grounded answer streams clean and cites honestly, provable offline.

---

## Phase 4: User Story 2 — An honest decline streams and cites nothing (Priority: P1)

**Goal**: An out-of-corpus question streams the decline and the `citations` frame cites nothing.

**Independent Test**: Scripted `DontKnow` answer (no markers) → prose is the decline, `usedSources` empty,
`retrievedSources` still shown.

### Implementation for User Story 2

- [ ] T013 [US2] In `StreamingCitedDocsEndpoint`, determine the `declined` flag from the stripped answer (normalized trim/case, mirroring `CitedDocsEndpoint.isDecline`) without buffering the whole answer — track the leading clean prefix (plan "Determining declinedFlag"); pass it to `GroundedCitedPrep.citationsEvent` so a decline forces empty citations (FR-007).
- [ ] T014 [US2] Decline + empty-report integration tests in `StreamingCitedDocsEndpointIntegrationTest.scala`: (a) scripted `DontKnow` → decline prose, `usedSources` empty, `retrievedSources` non-empty (SC-003, FR-007); (b) scripted non-decline answer with an **out-of-range** marker only → `usedSources` empty, `retrievedSources` shown (FR-008); assert no fabricated source ever appears.

**Checkpoint**: US1 and US2 both hold; the honesty floor survives the streaming surface.

---

## Phase 5: User Story 3 — A failure mid-stream is self-describing (Priority: P1)

**Goal**: A failure before or during the stream ends with a terminal `event: error` and **no** `citations`
frame.

**Independent Test**: Force a failing model turn → `event: error` with a client-safe reason; assert no
`citations` frame follows.

### Implementation for User Story 3

- [ ] T015 [US3] Verify/complete the failure path in `StreamingCitedDocsEndpoint`: the `.recover` already turns a stream failure into a terminal `ErrorEvent`, and `.concat` after a lazy source guarantees the citations source is never subscribed when the token stream fails (research R5). Confirm `statefulMap`'s completion hook does **not** fire the carry-flush on failure (plan risk); if it does, move the tail flush so a failing stream emits no `Data`/`Citations` after the point of failure.
- [ ] T016 [US3] Failure integration tests in `StreamingCitedDocsEndpointIntegrationTest.scala`: (a) fail **after** N `data` frames (`whenMessage(...).failWith`) → terminal `event: error`, client-safe `reason`, **no** `citations` frame (FR-009, FR-010); (b) fail **before** first token — materialize offline if possible, else record as a live-only check per research R9 / cap-20 precedent and verify the error reason leaks no internals.

**Checkpoint**: All P1 stories hold; failure is never a silent or half-cited stream.

---

## Phase 6: User Story 4 — Measure end-of-stream self-report vs the batch path (Priority: P2)

**Goal**: Record whether the model's `[n]` self-report at end-of-stream is consistent with capability 21's
non-streamed `/cited-ask` on the same questions (SC-006).

**Independent Test**: Run the small in-corpus question set live on `qwen3:8b` over `/sse-cited-ask` and
compare `usedSources` with cap-21's `/cited-ask`; evidence recorded.

### Implementation for User Story 4

- [ ] T017 [US4] Run `/sse-cited-ask` live on `qwen3:8b` for the cap-21 question set (`mvn compile exec:java` + curl from quickstart); capture per-question `usedSources` and `retrievedSources`.
- [ ] T018 [US4] Record the SC-006 finding in `specs/024-streaming-grounded-citations/research.md` (new R10): is the streamed end-of-stream self-report consistent with cap-21's non-streamed path? State as evidence, including "not equivalent" if so; note any marker-stripping edge seen live.

**Checkpoint**: The capability's measurement deliverable is recorded, not assumed.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T019 Regression: run the cap-8/14/20/21 suites unchanged and green (SC-005) — includes `CitedDocsEndpointIntegrationTest`, `SseChatEndpointIntegrationTest`, `DocsEndpointIntegrationTest`.
- [ ] T020 Update `JavaQuarantineTest` (if present for the docs/streaming trees) to expect exactly the two new Java files; record quarantine growth as a finding.
- [ ] T021 [P] Documentation (own final commit): add README **§24** (the R1 finding — no structured output over a stream; the strip-to-clean-prose design; the `concat`/`recover` wiring); flip ROADMAP "Where we are" + the B5 fork row + capability row 22; add FINDINGS entry; update memory ([[akka-b5-streaming-grounded-design]] → built; [[akka-agentic-exploration-roadmap]]).
- [ ] T022 Run `mvn clean verify` as the gate (descriptor + cross-capability edges) before "done"; then validate `quickstart.md` end-to-end.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**: immediate.
- **Foundational (P2)**: after Setup — **blocks all user stories** (stripper, façade, envelope, agent).
- **US1 (P3)**: after Foundational. The MVP; builds the endpoint + descriptor + happy test.
- **US2 (P4)**: after US1 (adds decline-flag logic to the same endpoint + tests).
- **US3 (P5)**: after US1 (the failure path is intrinsic to US1's pipeline; US3 verifies + tests it).
- **US4 (P6)**: after US1–US3 are green (needs the working surface to measure).
- **Polish (P7)**: after all desired stories.

### Within-story notes

- US2 and US3 both edit `StreamingCitedDocsEndpoint.java` and the single integration test file, so they are
  **sequential, not parallel** (same-file). They remain independently *testable* (distinct assertions).
- Foundational tasks T003/T005/T007/T008 are `[P]` (different files); their tests T004/T006 are `[P]`.

### Parallel Opportunities

```bash
# Phase 2 — independent files, can proceed together:
T003 CitationMarkers.scala        |  T005 GroundedCitedPrep.scala
T007 GroundedSseEvent.java        |  T008 StreamingCitedDocsAgent.scala
# with their unit tests T004 / T006
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational (CRITICAL) → 3. Phase 3 US1 → **STOP & validate** the happy path
offline via `SseRouteTester`. That is a demoable MVP: a grounded answer that streams clean and cites
honestly.

### Incremental delivery (gate-committed, per CLAUDE.md)

Commit at each approved gate with a scoped message (e.g. `feat(024): cap-22 domain — CitationMarkers
stripper + unit tests`). Order: stripper → façade → envelope+agent → endpoint(happy) → decline → failure →
measurement → docs. Documentation is its own final commit. SDD artifacts (this spec/plan/tasks) are
committed **before** T001, then pushed with a Draft PR (CLAUDE.md rule #8).

### Notes

- The one true risk is the stripper boundary case (T004) and the `statefulMap`-completion-vs-failure
  interaction (T015) — exercise both deliberately.
- `mvn clean verify` is the gate specifically because this capability edits the component descriptor
  (T009, T011); an incremental build can mask a missing line.
