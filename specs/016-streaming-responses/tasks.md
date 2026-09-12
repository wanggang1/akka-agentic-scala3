# Tasks: Streaming agent responses (capability 14)

**Input**: Design documents from `/specs/016-streaming-responses/`
**Prerequisites**: spec.md, plan.md, research.md (all four questions measured), data-model.md,
contracts/stream-chat-endpoint.md, quickstart.md

**Tests**: Included. The spec requires them (FR-010, SC-001–SC-008) and the constitution's principle
III makes them non-optional for a behavioural change. Every test below is **offline** — no model, no
API key, no network — which research Q-C established is possible for streaming.

**Organization**: by user story, so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4, matching spec.md
- Paths are exact and relative to the repository root

## Non-negotiables for every task below

- **No new dependency.** `akka.stream.javadsl` is already inside the SDK's tree (plan, Technical Context).
- **FR-009 — nothing outside this capability's own packages may be edited**, except the four shared
  documents in US4 and the one descriptor file. Capability 4's chat surface in particular stays exactly
  as it is; the streaming surface is a *sibling*, not a replacement.
- **FR-013 — exactly one Java class.** `StreamingChatEndpoint` is Java because it holds the
  `tokenStream` method reference (research Q-B). **If a second Java class ever seems necessary, stop and
  record it as a finding** — that would mean the wall reaches further than capability 11 measured, which
  is a result, not a detail to concede quietly.
- **Commit at each approved gate** (CLAUDE.md), with the scope in the message. Documentation is its own
  final commit.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirm there is nothing to set up — recorded rather than assumed.

- [ ] T001 Confirm no `pom.xml` change is required: `akka.stream.javadsl` resolves from the existing
  `akka-javasdk` 3.6.3 dependency (already proven — the Phase 0 probes compile and run against it), and
  the mixed Scala/Java build already handles a Java class referencing Scala classes (README §13 R3).
  No task follows from this; it exists so a reader knows the question was asked.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the probe subject, the measurements, and the pure domain type every story needs.

**⚠️ T002–T005 are already done and committed in `852e47c`** — listed so the phase map is honest, not
to be redone.

- [x] T002 `src/main/scala/com/gwgs/akkaagentic/streaming/application/StreamingChatAgent.scala` —
  `StreamEffect` handler with `MemoryProvider.limitedWindow()`. **Done** (852e47c).
- [x] T003 `StreamingChatAgent` registered under `agent` in
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`.
  **Done** (852e47c) — the descriptor key does not change for a streaming agent.
- [x] T004 `src/test/java/com/gwgs/akkaagentic/streaming/application/StreamProbeIntegrationTest.java`
  — Q-A (a pre-token failure never terminates the stream), Q-C (57 fragments, exact concatenation),
  Q-D (the assembled reply is persisted), plus the **Java positive control** for Q-B. **Done** (852e47c).
- [x] T005 `src/test/scala/com/gwgs/akkaagentic/streaming/probe/ScalaTokenStreamProbeIntegrationTest.scala`
  — records the wall: the lambda form compiles, then fails at run time. Kept permanently as FR-013
  evidence. **Done** (852e47c).
- [x] T006 Unit test `src/test/scala/com/gwgs/akkaagentic/streaming/domain/StreamQuestionTest.scala`:
  `None` and blank/whitespace → `Left("question must not be blank")`; a padded value → `Right` with the
  text trimmed. Written **first**, and must fail because the type does not exist yet.
- [x] T007 Implement `src/main/scala/com/gwgs/akkaagentic/streaming/domain/StreamQuestion.scala` —
  `validate(question: Option[String]): Either[String, StreamQuestion]`, parse-don't-validate, **no Akka
  import** (Constitution II). Do **not** reuse capability 8's `AskQuestion`: that would couple two
  capabilities for a non-blank check and put a capability-14 edit one step from capability 8 (FR-009,
  data-model.md).

**Checkpoint**: `mvn test` green. Domain ready; user stories can begin.

---

## Phase 3: User Story 1 — See the answer as it is written (Priority: P1) 🎯 MVP

**Goal**: `POST /stream-chat/{sessionId}` delivers an answer in fragments as it is generated, and
rejects an invalid question before any model call.

**Independent Test**: post a question with a scripted multi-sentence reply; assert more than one
fragment arrives (incrementality) and that the fragments concatenated equal the scripted reply exactly
(parity). Delivers the whole user-visible capability on its own.

### Tests for User Story 1 ⚠️ write first, must fail

- [x] T008 [US1] `src/test/scala/com/gwgs/akkaagentic/streaming/api/StreamingChatEndpointIntegrationTest.scala`
  — **Scala**, because `httpClient` holds no method reference (the wall claims only the endpoint, as in
  capability 11). Three assertions: (a) SC-002 parity — the response body equals the scripted reply
  exactly; (b) SC-001 incrementality — the body arrives as **more than one** chunk, asserted from the
  response's stream rather than from the assembled string, since a buffered read would prove nothing;
  (c) SC-003 — `{"message":"  "}` returns `400 question must not be blank` with **no** model call.

### Implementation for User Story 1

- [x] T009 [US1] `src/main/java/com/gwgs/akkaagentic/streaming/api/StreamingChatEndpoint.java` — the
  **only** Java class in this capability (FR-013). `@HttpEndpoint`, `@Acl(INTERNET)`,
  `@Post("/stream-chat/{sessionId}")`, its own request record. It must:
  validate through the Scala `StreamQuestion` first and return `400` before touching the agent;
  obtain the stream with `componentClient.forAgent().inSession(sessionId).tokenStream(StreamingChatAgent::stream).source(question)`;
  group fragments with `groupedWithin(20, Duration.ofMillis(100))` joined into one string per group
  (FR-007, research D3); apply `initialTimeout` **and** `idleTimeout` (FR-005/FR-006 — without the
  first, a pre-token failure hangs the caller forever, measured at 240 s); return
  `HttpResponses.streamText(...)` (research D4).
- [x] T010 [US1] Add `com.gwgs.akkaagentic.streaming.api.StreamingChatEndpoint` under `http-endpoint`
  in the descriptor, with a comment saying **why this one is Java** and that the agent beside it is
  Scala — the descriptor is where a reader meets the capability first.
- [x] T011 [US1] Make T008 green; keep the guard durations in one place in the endpoint so the tests in
  US3 can drive them down without touching production behaviour for real callers.
- [x] T012 [US1] `src/test/scala/com/gwgs/akkaagentic/streaming/api/JavaQuarantineTest.scala` — pins
  FR-013 mechanically, the way capability 12 pinned "the agent names no rule": exactly **one** `.java`
  file exists under `src/main/java/com/gwgs/akkaagentic/streaming/`, and it is the endpoint. A failure
  means the quarantine grew, which must be a recorded finding rather than a silent drift.

**Checkpoint**: the MVP works. A caller sees the answer typed, and an invalid question still costs nothing.

---

## Phase 4: User Story 2 — A conversation that streams (Priority: P2)

**Goal**: a streamed turn is remembered by its conversation, and invisible to others.

**Independent Test**: two turns on one `sessionId` where the second depends on the first; then the same
question on a fresh id, which must show no knowledge of it.

- [x] T013 [US2] Extend `StreamingChatEndpointIntegrationTest.scala` with SC-005: two streamed turns on
  one `sessionId`, then a third on a different id. Assert what the **mock** can actually show —
  retention and isolation — and do **not** assert recall: capability 4 and 6 established that a mocked
  model sees only the current turn, so recall is a live claim, not an offline one.
- [x] T014 [US2] Strengthen the existing Java probe's `aStreamedTurnIsWrittenToSessionMemory` from
  *logging* its measurement to *asserting* it: after a streamed turn, session memory holds one user and
  one AI message, and the AI text equals the **whole** streamed answer. Reuse this class rather than
  adding a second Java one — reading `SessionMemoryEntity` needs Java (capability 4 §6) and it lands in
  the class the wall already claimed, so the quarantine does not grow and T012 still passes.

**Checkpoint**: US1 and US2 both work; the streaming surface is a real conversation.

---

## Phase 5: User Story 3 — An interrupted answer is not passed off as complete (Priority: P3)

**Goal**: a caller can tell an interrupted answer from a finished one, and never waits forever.

**Independent Test**: force the model to fail before the first fragment and confirm the request
terminates promptly instead of hanging; force a stall after fragments and confirm the body ends
abnormally rather than looking complete.

- [x] T015 [US3] `src/test/scala/com/gwgs/akkaagentic/streaming/api/StreamingChatFailureIntegrationTest.scala`
  with a short `initialTimeout` override: a scripted model failure (`failWith`) returns **no** fragments
  and the request **terminates within the bound** (FR-006). This is the test that would have hung for
  ever before the guard existed — assert the bound, not merely the outcome.
- [x] T016 [US3] **Revised while implementing (2026-09-12), because the planned test could not observe
  its own claim.** `TestModelProvider` produces the whole reply and tokenizes afterwards, so every
  fragment flows at once: a sleep produces a gap *before* the first token (that is T015's
  `initialTimeout` case), and **no scripted model can produce a gap between tokens**. So instead, in
  the same class: (a) a false-positive guard — a slow-but-answering model must still complete, which is
  what would catch a badly-chosen default (cap-12's regression-guard technique); and (b) FR-005's
  mechanism pinned on a **synthetic source** — `idleTimeout` delivers what arrived, then *fails*
  rather than completing, which is what makes a truncated answer distinguishable from a finished one.
  The genuine mid-stream model failure stays live-only, recorded in T017 and in research.
- [x] T017 [US3] Record in that test class's ScalaDoc that a **genuine mid-stream model failure is not
  scriptable offline** — the test provider produces the whole reply and tokenizes afterwards, so an
  injected failure always lands before the first token. The stall above is the honest proxy; the real
  case belongs to T023 and to `docs/sdk-3.6.0-limitations.md`, not to a test pretending to cover it.

**Checkpoint**: all three behavioural stories are independently functional.

---

## Phase 6: User Story 4 — The interop verdict published (Priority: P3)

**Goal**: a reader learns the streaming verdict, its evidence and its boundary without reading source.

**Independent Test**: read README, FINDINGS and ROADMAP; the verdict, what was measured, and which
single class is Java are all stated.

- [x] T018 [P] [US4] `README.md` — new **§16** in "Scala interop notes": authoring is Scala-clean;
  consuming is not; the four attempts with their exact diagnostics; the Java positive control; the
  never-terminating stream and the absent `onFailure`. Plus a **Capability 14 usage section** (curl with
  `--no-buffer`, the conversation, validation-first) and the **project-layout** entry.
- [x] T019 [P] [US4] `FINDINGS.md` — the amendment: **`dynamicCall` covers the agent client's
  request/response calls only**, so the agent client sits on **both** sides of the wall; and streams are
  not the deciding axis, since `AutonomousAgentClient.notificationStream()` and
  `TaskClient.notificationStream()` are zero-arg and Scala-clean. Include the client-by-client
  inventory from research Q-B.
- [x] T020 [P] [US4] `ROADMAP.md` — row 14 (✅ with the finding in one line) and "Where we are" moved
  forward, with capability 13 demoted to "Previously".
- [x] T021 [P] [US4] `docs/sdk-3.6.0-limitations.md` — a new section for the two SDK behaviours this
  capability measured: a failed model call never terminates a token stream (240 s, no event), and
  `StreamEffect` has no `onFailure`, so no fallback is expressible once streaming starts. Both to be
  re-tested on an SDK upgrade.
- [x] T022 [US4] `README.md` §13 — sharpen the existing claim that `dynamicCall(String)` exists on
  `AgentClientInSession` **only**: still true, and now shown to be insufficient, because it does not
  cover `tokenStream`. Same file as T018, so **not** parallel with it.

- [x] T027 [P] [US4] `docs/streaming-vs-request-response.md` — when to stream an agent and when not
  to, written for someone choosing between capability 4's surface and this one. Covers what a chunked
  response actually is (with the measured three-chunk example), what streaming buys, what it costs (no
  `onFailure`, no status after the first byte, mandatory guards, reassembly on the client, nothing
  appendable, one Java class), why **payload size is not a reason** to stream (the agent stream is
  text by type; binaries go to `of`/`staticResource`/`ObjectStorage`), and how far backpressure
  verifiably reaches. *(Added 2026-09-12 at the user's request, mid-Phase 4 — hence the out-of-order
  id; it belongs to US4.)*

**Checkpoint**: the capability's headline deliverable is published.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T023 Live smoke test against Ollama `qwen3:8b`, recording results in the README the way
  capabilities 10–13 do — including anything unflattering. It must cover the **two items research lists
  as offline-unverifiable**: whether a real provider failure before the first token also fails to
  terminate the stream, and whether a **mid-stream** failure is reachable at all. Also confirm by eye
  that the answer visibly types out, which is the point of the capability and the one thing no
  assertion captures.
- [ ] T024 `mvn clean verify` — **clean**, not incremental (a stale `target/classes` once hid a
  capability 11 build that failed from clean).
- [ ] T025 FR-009 check, mechanical: `git diff --stat main -- src/main/scala/com/gwgs/akkaagentic/{application,team,assistant,chat,approvals,a2a,activities,docs,mcp,mcpclient,todos,eval} src/main/java src/test` must show **no** change outside this capability's own packages, the one descriptor file, and the Java endpoint's new directory. Report it, do not assert it in prose.
- [ ] T026 Walk `quickstart.md` end to end against the running service and fix anything that has drifted.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** — nothing to do; a recorded check.
- **Phase 2** — T002–T005 already done; **T006 → T007** (test first). Blocks every story.
- **Phase 3 (US1)** — needs T007. **T008 → T009 → T010 → T011**, then T012.
- **Phase 4 (US2)** — needs US1's endpoint (T009). T013 and T014 touch different files, so **[P]** with
  each other in practice, though both are listed under their story.
- **Phase 5 (US3)** — needs US1's endpoint and its guard durations (T011). T015 → T016 → T017 (same file).
- **Phase 6 (US4)** — needs the behaviour settled by US1–US3, so its claims are true. T018–T021 are
  **[P]** (four different files); T022 waits for T018 (same file).
- **Phase 7** — after everything.

### Independence

US1 is a complete, demonstrable capability on its own — the MVP. US2 and US3 add properties of it and
are independently testable, but both need the endpoint from US1, so they are not startable in parallel
with it. US4 is documentation and depends on the behaviour being final.

### Parallel opportunities

- T018, T019, T020, T021 — four different documents.
- T013 and T014 — different files (Scala endpoint test, Java probe).
- Nothing else: this capability is small, and most tasks touch the endpoint or one test class. Marking
  more as [P] would be a false claim.

---

## Implementation Strategy

### MVP first

1. Phase 2 (T006, T007) → domain ready.
2. Phase 3 → **stop and validate**: the answer visibly types out, parity holds, a blank question is
   still rejected for free. That is a demonstrable capability.

### Incremental delivery

Phase 3 (MVP) → Phase 4 (it is a conversation) → Phase 5 (it fails honestly) → Phase 6 (the finding is
published) → Phase 7 (live proof and the FR-009 check). Each phase is a commit gate.

---

## Notes

- The four measured answers are in `research.md`; **do not re-measure them in implementation tasks**.
  T015/T016 assert *our guard's* behaviour, not the SDK's hang — that is already recorded, with the kept
  probe asserting it at an affordable 20 s bound.
- `StreamEffect` has **no** `onFailure`. Do not look for one, and do not add a sentinel: no value can
  replace text a caller has already read. This is the first capability in the project that cannot use
  the technique capabilities 8, 12 and 13 rely on.
- Guard durations must be overridable from configuration so T015/T016 can drive them down; the shipped
  defaults are for real callers, not for tests.
