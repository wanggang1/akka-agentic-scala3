# Tasks: SSE Framing for the Streaming Agent Surface (capability 20)

**Input**: Design documents from `specs/022-sse-streaming/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/sse-chat.md ✅

**Tests**: INCLUDED. Constitution III mandates test coverage for every behavioral change, and the spec
defines an independent test per user story. Integration tests use `TestKitSupport` + the SDK testkit
`SseRouteTester` + `TestModelProvider`; the error-reason mapping is a pure unit test.

**Organization**: by user story (US1, US2 = P1; US3 = P2). Each phase is an approved gate in the
CLAUDE.md incremental workflow — compile + the gate's tests green, then commit with a scoped message,
then STOP for approval before the next phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- `SseChatEndpoint.java` is ONE file serving US1 and US2 → those endpoint tasks are **sequential**, never `[P]` against each other.

## Path Conventions

Mixed Scala/Java Akka project. Scala under `src/main/scala/com/gwgs/akkaagentic/streaming/`, the two
forced-Java files under `src/main/java/com/gwgs/akkaagentic/streaming/api/`, tests under
`src/test/scala/com/gwgs/akkaagentic/streaming/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the ground; this capability adds no dependency and no component descriptor entry.

- [X] T001 Confirm no new dependency and no SSE opt-in is required: verify `HttpResponses.serverSentEvents` resolves against the existing `akka-javasdk` 3.6.3 on the classpath (research Q-A) — a one-line `mvn compile`-level check, no `pom.xml` edit.
- [X] T002 ⚠️ CORRECTED during implementation: the new Java endpoint **DOES** need a descriptor entry. This mixed build runs the javac annotation processor OFF (`-proc:none`, memory `akka-mixed-java-scala-descriptor-proc-none`), so **every** component is hand-listed — including Java endpoints. Precedent: `com.gwgs.akkaagentic.streaming.api.StreamingChatEndpoint` is listed at line 96 of `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`. Therefore `SseChatEndpoint` must be added to that descriptor (done in US1 / T008b). The envelope (`SseChatEvent`) and reason mapping (`SseErrorReason`) are plain data types, NOT components, so they need no entry.

**Checkpoint**: classpath confirmed, no scaffolding changes needed.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The wire envelope and the pure failure→reason mapping that BOTH US1 and US2 depend on.

**⚠️ CRITICAL**: US1 and US2 cannot be implemented until the envelope type exists.

- [X] T003 [P] Create the SSE event envelope `SseChatEvent.java` in `src/main/java/com/gwgs/akkaagentic/streaming/api/SseChatEvent.java` — a sealed interface with records `Data(String text)` and `ErrorEvent(String reason)`; Java-authored because the SDK's internal Jackson mapper serializes each frame (research Q-D). Keep `ErrorEvent` a flat record of Strings (trivially serializable — research Q-D.4).
- [X] T004 [P] Create the pure error-reason mapping `SseErrorReason.scala` in `src/main/scala/com/gwgs/akkaagentic/streaming/domain/SseErrorReason.scala` — `reasonFor(t: Throwable): String` returning stable, client-safe strings (initial-timeout, idle-timeout, generic), never leaking stack traces or internal class names (FR-006; data-model mapping table).
- [X] T005 [US-shared] Create unit test `SseErrorReasonTest.scala` in `src/test/scala/com/gwgs/akkaagentic/streaming/domain/SseErrorReasonTest.scala` — assert each Throwable class maps to its expected client-safe reason and that no message contains a class name or "Exception" (FR-006). Run with `mvn test -Dtest='SseErrorReasonTest'`.

**Checkpoint**: envelope + reason mapping compile and the mapping is green. COMMIT (`feat(022): cap-20 foundational — SseChatEvent envelope + reasonFor + unit test`). STOP for approval.

---

## Phase 3: User Story 1 - Receive a streamed answer as labeled events (Priority: P1) 🎯 MVP

**Goal**: `POST /sse-chat/{sessionId}` streams the agent's answer as `event: data` frames whose decoded
`text` fields concatenate to the full answer (content parity with cap-14).

**Independent Test**: with a scripted model reply, assert `Content-Type: text/event-stream`, well-formed
SSE frames, and ordered concatenation of `data.text` equals the expected answer (SC-001).

### Implementation for User Story 1

- [X] T006 [US1] Create `SseChatEndpoint.java` in `src/main/java/com/gwgs/akkaagentic/streaming/api/SseChatEndpoint.java`: `@HttpEndpoint` + `@Acl(INTERNET)`, `POST /sse-chat/{sessionId}`, reusing `ChatRequest{message}`; validate via `StreamQuestion.validate(Option.apply(...))` returning `400` on `Left` (non-streamed, as cap-14 — FR-003/FR-011).
- [X] T007 [US1] In `SseChatEndpoint.java`, build the happy-path source: `componentClient.forAgent().inSession(sessionId).tokenStream(StreamingChatAgent::stream).source(question)` (the method-ref wall, research Q-F) → reuse cap-14's `initialTimeout`/`idleTimeout` guards and `groupedWithin` grouping → `map` each group to `new SseChatEvent.Data(joined)`.
- [X] T008 [US1] In `SseChatEndpoint.java`, return `HttpResponses.serverSentEvents(source, idFn, typeFn)` where `typeFn` maps `Data→"data"`/`ErrorEvent→"error"` and `idFn` is a constant/no-op (no reconnection — research Q-E). Happy path now emits `event: data` frames.
- [X] T008b [US1] Add `"com.gwgs.akkaagentic.streaming.api.SseChatEndpoint"` to the hand-maintained component descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` (required because `-proc:none` disables auto-discovery — see corrected T002). Without it the endpoint is not registered and the route 404s at runtime.
- [X] T009 [US1] Create integration test `SseChatEndpointIntegrationTest.scala` in `src/test/scala/com/gwgs/akkaagentic/streaming/api/SseChatEndpointIntegrationTest.scala` with the US1 case: register `TestModelProvider` for `StreamingChatAgent`, script a multi-fragment reply, call `/sse-chat/{id}` via `getSelfSseRouteTester`, assert `text/event-stream`, parse frames, and assert the ordered concatenation of the **decoded** `data.text` fields equals the expected answer (SC-001). Assert the happy stream ends cleanly with **no** `event: error` frame (FR-005 completion-distinguishable). Include a fragment containing an embedded newline (`\n`) and assert it round-trips byte-for-byte through the JSON `data` payload (FR-007 — the one assertion that verifies newline/SSE-significant content survives framing). Also assert a blank `message` yields `400` (FR-003). Run with `mvn verify -Dit.test='SseChatEndpointIntegrationTest' -Dtest='!*' -DfailIfNoTests=false`.

**Checkpoint**: US1 streams labeled data frames with parity; MVP works. COMMIT (`feat(022): cap-20 US1 — SSE data-frame surface + happy-path IT`). STOP for approval.

---

## Phase 4: User Story 2 - Learn that a stream failed, after it started (Priority: P1)

**Goal**: a failure before OR during generation produces a distinct `event: error` frame — never a
silent empty success. This is the capability's reason to exist.

**Independent Test**: drive the agent to fail (a) before the first fragment and (b) after N fragments;
assert the client observes a single `event: error` frame (fail@0) or N `data` frames then `event: error`
(fail@N), both distinguishable from an empty success (SC-002, SC-003).

### Implementation for User Story 2

- [X] T010 [US2] In `SseChatEndpoint.java`, insert `.recover(...)` on the token source **upstream of** `serverSentEvents` (between the `Data` mapping and the `serverSentEvents` call) that maps any `Throwable` to a final `new SseChatEvent.ErrorEvent(SseErrorReason.reasonFor(t))` — this converts failure-as-Throwable into a final element BEFORE the SDK's inner `recoverWith` can silently empty it (research Q-C, the headline finding). This also catches the `initialTimeout`/`idleTimeout` `TimeoutException`s (FR-009).
- [X] T011 [US2] Add the fail-after-N case to `SseChatEndpointIntegrationTest.scala`: script some fragments then a materialized failure via `TestModelProvider` `whenMessage(...).failWith(...)`; assert the already-sent `data` frames arrive, followed by exactly one `event: error` frame carrying `{"reason":...}`, then end-of-stream (SC-003), and that the reason contains no internal detail (FR-006).
- [X] T012 [US2] Add the fail-before-first-token case to `SseChatEndpointIntegrationTest.scala`: drive a materialized pre-first-token failure (`failWith`, not an empty scripted reply — research "Testing" caveat); assert the client observes a single `event: error` frame and NOT an empty-but-successful stream (SC-002). If offline cannot materialize a pre-first-token stream failure, record it as a measured test-harness limit (per `akka-testing-slow-and-failing-calls`) and mark this assertion live-only, keeping fail@N offline.
- [X] T013 [US2] Add the successful-empty-answer edge case: assert a legitimately empty answer ends cleanly with NO `event: error` frame (edge case / distinguishability — data-model invariant).

**Checkpoint**: every failure path ends in exactly one `event: error`; SC-002/SC-003 proven. COMMIT (`feat(022): cap-20 US2 — recover-to-error-frame + failure ITs`). STOP for approval.

---

## Phase 5: User Story 3 - Capability 14 remains the untouched baseline (Priority: P2)

**Goal**: the SSE surface is strictly additive; cap-14's `/stream-chat` behavior, wire format, and tests
are unchanged.

**Independent Test**: cap-14's existing streaming tests pass unmodified; `/stream-chat` still returns
`text/plain` chunked (SC-004).

- [X] T014 [US3] Run cap-14's existing streaming tests unchanged and confirm green: `mvn verify -Dit.test='StreamingChat*IntegrationTest' -Dtest='!*' -DfailIfNoTests=false` (plus `StreamQuestionTest`). Confirm `StreamingChatEndpoint.java` has zero diff (FR-010).
- [X] T015 [US3] Update `JavaQuarantineTest` (cap-14's quarantine pin, in `src/test/scala/com/gwgs/akkaagentic/streaming/...`) to expect exactly the two new Java files (`SseChatEndpoint.java`, `SseChatEvent.java`) alongside cap-14's `StreamingChatEndpoint.java`, so quarantine growth is a recorded finding, not silent drift (plan "Structure Decision").

**Checkpoint**: baseline green and untouched; quarantine pinned. COMMIT (`test(022): cap-20 US3 — cap-14 baseline regression + quarantine pin`). STOP for approval.

---

## Phase 6: Polish & Cross-Cutting Concerns (Documentation)

**Purpose**: record the capability and its findings; run the final gate. Docs are their own commit.

- [X] T016 Run quickstart.md validation: start the service and exercise the happy-path `curl --no-buffer` from `specs/022-sse-streaming/quickstart.md`; confirm `event: data` frames and clean end-of-stream (SC-005 incremental delivery observable).
- [X] T017 [P] Add README "Scala interop notes" §20 documenting: the Q-C headline (serverSentEvents silently empties a failing source; `.recover`-to-element is the fix), the one-Java-class cost (Q-F), JSON `data` payloads (Q-D), and the Q-G heartbeat correction (10 s not 5 s).
- [X] T018 [P] Flip ROADMAP.md "Where we are" to capability 20 (fork B2, built & green) and add the table row; note it is the first capability that is neither a new SDK family nor a bug fix — a wire-format refinement (per the "honest shape" caveat).
- [X] T019 [P] Add the FINDINGS.md entry for capability 20 (the silent-empty-on-failure finding + heartbeat correction).
- [X] T020 [P] Update memory: new finding file for the SSE/serverSentEvents behavior and link it from MEMORY.md; update the roadmap memory `akka-agentic-exploration-roadmap.md` (B2 done; remaining forks B4 available, B3 blocked, B5 design).
- [X] T021 Run the full gate `mvn clean verify` and confirm all tests green including cap-14 (SC-004) before declaring done (CLAUDE.md: the gate, not the loop).

**Checkpoint**: docs + memory current; full gate green. COMMIT docs (`docs(022): cap-20 (B2 SSE) — README §20, ROADMAP flip, FINDINGS, memory`). Then open/continue the PR.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: after Setup; BLOCKS US1 and US2 (both need `SseChatEvent`; US2 needs `reasonFor`).
- **US1 (Phase 3)**: after Foundational. MVP.
- **US2 (Phase 4)**: after US1 — it edits the SAME `SseChatEndpoint.java` (adds `.recover`) and extends the same integration test. Sequential, not parallel.
- **US3 (Phase 5)**: after US2 — the quarantine pin (T015) must assert the final set of Java files, so it runs once both Java files exist. T014 (baseline run) could run any time but is grouped here.
- **Polish (Phase 6)**: after all stories green.

### User Story Dependencies

- **US1 (P1)**: independent; the MVP (a working labeled data stream).
- **US2 (P1)**: depends on US1 because it modifies US1's endpoint file and test. Independently *testable* (its own failure scenarios) but not independently *authored* — one endpoint file serves both.
- **US3 (P2)**: verification-by-absence-of-change; depends on the final Java file set existing.

### Within Each Story

- Foundational envelope before endpoint; endpoint happy path (US1) before failure wiring (US2); implementation before its integration assertions where the same file is touched.

### Parallel Opportunities

- T003 and T004 are `[P]` (different files: Java envelope vs Scala reason mapping).
- Polish docs T017–T020 are `[P]` (separate doc/memory files).
- US1 and US2 are NOT parallel (shared `SseChatEndpoint.java`).

---

## Parallel Example: Phase 2 Foundational

```bash
# T003 and T004 touch different files and can be authored in parallel:
Task: "Create SseChatEvent.java (sealed Data|ErrorEvent)"
Task: "Create SseErrorReason.scala (reasonFor Throwable->String)"
# then T005 (unit test) depends on T004.
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE**: data-frame streaming
with parity works and is demoable. This alone is a usable SSE surface (minus typed-error framing).

### Incremental Delivery

1. Foundational → envelope + reason mapping ready.
2. US1 → labeled `data` streaming (MVP, demo).
3. US2 → `event: error` framing (the capability's payoff; SC-002/SC-003).
4. US3 → prove cap-14 untouched.
5. Polish → docs, memory, full gate.

Each phase is an approved, committed gate. Do not batch; commit per gate (memory `commit-incrementally-at-gates`).

---

## Notes

- The ONE subtle correctness point: `.recover` must sit upstream of `serverSentEvents` and `ErrorEvent`
  must be trivially serializable, or the SDK's inner `recoverWith` re-swallows the failure (research Q-C/Q-D.4).
- Branch is `022-sse-streaming`; never commit to `main` (memory `github-pr-workflow`). Push after each
  approved gate while the PR is open.
- The full `mvn clean verify` gate (T021) is required before "done" — a selective run can miss a
  cross-capability break, and cap-14 shares this `streaming` tree.
