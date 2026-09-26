# Tasks: Session compaction (capability 17)

**Input**: Design documents from `/specs/019-session-compaction/`
**Prerequisites**: spec.md, plan.md, research.md, data-model.md, contracts/compaction.md — all complete

**Tests**: included. This project treats the interop finding as a deliverable and verifies offline; the
spec names tests as acceptance criteria, so test tasks are not optional here.

**Organization**: by user story, in the spec's priority order. **Each phase gate is a commit** (CLAUDE.md).

**Which test run to use**: per CLAUDE.md "Choosing which tests to run", each task names the *smallest* run
that checks it. `mvn clean verify` appears exactly twice — once after the descriptor change (T016, a global
file) and once as the final gate (T047).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1–US5 from spec.md

---

## Phase 1: Setup

- [x] T001 Create the package directories: `src/main/scala/com/gwgs/akkaagentic/compaction/{domain,application,api}`, `src/main/java/com/gwgs/akkaagentic/compaction/application`, and the matching `src/test/scala/com/gwgs/akkaagentic/compaction/{domain,application,api}` — the layout in plan.md. The `probe/` directories already exist from Phase 0
- [x] T002 Add a `compaction { max-bytes, enabled, max-sessions }` block to `src/main/resources/application.conf` with the defaults from contracts/compaction.md (131072 / true / 1000), env-overridable as `COMPACTION_MAX_BYTES` / `COMPACTION_ENABLED` / `COMPACTION_MAX_SESSIONS`, and a comment stating why the max is 256 KiB: it must stay below the SDK's own 510 KiB eviction (research S-1)

**Checkpoint**: directories and configuration exist. No behaviour yet. *(Verify: `mvn -q compile`.)*

---

## Phase 2: Foundational — the pure domain (blocking)

**Purpose**: every rule this capability enforces, as pure Scala with no Akka import, unit-tested with no
runtime. Nothing here may use `var` or a mutable collection.

**⚠️ CRITICAL**: no user story work begins until this phase is green.

- [x] T003 [P] Create `CompactionThreshold` in `src/main/scala/com/gwgs/akkaagentic/compaction/domain/CompactionThreshold.scala` — `maxBytes: Long`, `enabled: Boolean`, and a smart constructor enforcing **1 KiB ≤ maxBytes ≤ 256 KiB**; the upper bound carries a comment naming research S-1 (above it the SDK's eviction reaches the oldest turns first and compaction would summarise a history whose beginning is already gone)
- [x] T004 [P] Create `CompactionDecision` in `src/main/scala/com/gwgs/akkaagentic/compaction/domain/CompactionDecision.scala` — `decide(historyBytes: Long, threshold: CompactionThreshold): Decision` returning `Leave` or `Compact(historyBytes)`; total, and `Leave` for anything below the threshold or when disabled
- [x] T005 [P] Create `SummaryRequest` in `src/main/scala/com/gwgs/akkaagentic/compaction/domain/SummaryRequest.scala` — **amended while implementing**: works on a neutral `HistoryLine` type (also added), not `SessionMessage`, because the constitution's Principle II keeps `domain` free of Akka; the application layer maps at the boundary. Turns a list of lines into the single text a summariser reads, rendering a tool request and its response as readable `TOOL_CALL_REQUEST:` / `TOOL_CALL_RESPONSE:` lines so the *substance* of what a tool did survives as prose (FR-002). Carries `messageCount` so the ledger can record what was replaced
- [x] T006 [P] Create `ConversationSummary` in `src/main/scala/com/gwgs/akkaagentic/compaction/application/ConversationSummary.scala` — **amended while implementing**: in `application`, not `domain`, because its `@Description` hints are an Akka annotation; capability 3's `HelpAnswer` sits there for the same reason — the summariser's result type, **Java-shaped** (Jackson-annotated plain fields, the `HelpAnswer` shape) because it crosses the SDK's internal serializer (README §3); `userMessage` and `aiMessage` must both be non-blank, since an empty summary is a failed compaction and not a small one
- [x] T007 [P] Create `CompactionLedger` + `SessionRecord` + `Outcome` in `src/main/scala/com/gwgs/akkaagentic/compaction/domain/CompactionLedger.scala` — one immutable value with `since`, `records`, `maxSessions`; transitions return `(CompactionLedger, Result)` and never mutate the receiver; bounded retention evicting the least-recently-changed session. `Outcome` is `Compacted | SkippedStale | Failed(reason)` — three distinct facts, because research R-4 measured that "we asked" and "it happened" are different
- [x] T008 Create `CompactionSettings` in `src/main/scala/com/gwgs/akkaagentic/compaction/application/CompactionSettings.scala` — reads the `compaction` block into a `CompactionThreshold` plus `maxSessions`, raising **`ConfigException.BadValue`** on an out-of-range value, never `require` (capability 15, `docs/sdk-3.6.0-limitations.md` §7d — this is what makes the failure the server's fault rather than the caller's)
- [x] T009 [P] Unit-test the threshold and decision in `src/test/scala/com/gwgs/akkaagentic/compaction/domain/CompactionDecisionTest.scala` — below/at/above the threshold; disabled always `Leave`; the 256 KiB ceiling and the 1 KiB floor both rejected
- [x] T010 [P] Unit-test summary formatting in `src/test/scala/com/gwgs/akkaagentic/compaction/domain/SummaryRequestTest.scala` — user and AI turns in order; a tool request and its response both rendered as prose lines; an empty history yields an empty request rather than throwing
- [x] T011 [P] Unit-test the ledger in `src/test/scala/com/gwgs/akkaagentic/compaction/domain/CompactionLedgerTest.scala` — a transition never changes its receiver; `compactions` accumulates per session; the three outcomes are distinguishable; retention at `maxSessions` evicts the least-recently-changed; `since` is preserved across transitions
- [x] T012 [P] Unit-test settings in `src/test/scala/com/gwgs/akkaagentic/compaction/application/CompactionSettingsTest.scala` — defaults; each override; and an out-of-range value raising `ConfigException.BadValue` (asserted by type, since that is what makes it a 500 rather than a 400)

**Checkpoint**: every rule is proven with no runtime and no model.
*(Verify: `mvn test -Dtest='CompactionDecisionTest,SummaryRequestTest,CompactionLedgerTest,CompactionSettingsTest'` — seconds, not minutes.)* **Commit the gate.**

---

## Phase 3: User Story 1 — a long conversation stops growing (Priority: P1) 🎯 MVP

**Goal**: once a session's stored history passes the threshold, it is replaced by a summary and the next
turn costs a bounded amount again.

**Independent Test**: drive one session past the threshold and observe the stored history is measurably
smaller afterwards and the next turn still answers — offline, with a mocked summariser.

- [ ] T013 [US1] Create `CompactionAgent` in `src/main/scala/com/gwgs/akkaagentic/compaction/application/CompactionAgent.scala` — a Scala `Agent` taking the text from `SummaryRequest` and returning `ConversationSummary`; `.memory(MemoryProvider.none())` (a summariser needs no session memory of its own, and giving it one would feed compaction into the thing it compacts); a system message instructing one user message and one AI message of prose that keeps tool outcomes; `.onFailure` degrading to a signal the caller can treat as `Failed` rather than a thrown error
- [ ] T014 [US1] Create `SessionMemoryGateway` in `src/main/java/com/gwgs/akkaagentic/compaction/application/SessionMemoryGateway.java` — **the one Java class**, holding all three method references: `.method(SessionMemoryEntity::getHistory)`, `.method(CompactionAgent::summarize).withDetailedReply()` (so the summary's token usage survives — research S-4, and a Java method reference to a Scala agent is already proven in production by capability 14's `tokenStream(StreamingChatAgent::stream)`), and `.method(SessionMemoryEntity::compactHistory)`. It **re-reads the history after writing** and returns which of the three outcomes occurred, because research R-4 measured that a stale write is accepted silently. Javadoc states that measured reason
- [ ] T015 [US1] Create `CompactionStore` in `src/main/scala/com/gwgs/akkaagentic/compaction/application/CompactionStore.scala` — **one** `AtomicReference[CompactionLedger]`, a `@tailrec` compare-and-set `modify`, and nothing else mutable; the capability 15/16 pattern. CAS functions must be pure, because they retry
- [ ] T016 [US1] Create `SessionMemoryConsumer` in `src/main/scala/com/gwgs/akkaagentic/compaction/application/SessionMemoryConsumer.scala` — `@Component(id = "session-memory-consumer")` + `@Consume.FromEventSourcedEntity(classOf[SessionMemoryEntity])`, with a handler on `SessionMemoryEntity.Event` that acts **only** on `AiMessageAdded` (research S-5: the only event carrying `historySizeInBytes`, so the check costs no entity read and can never fire mid-turn), applies `CompactionDecision`, and on `Compact` calls the gateway and records the outcome. Add it to `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` under `consumer`, and `CompactionAgent` under `agent` — and **not** `SessionMemoryEntity`, which the runtime owns (capability 4). Scaladoc explains handler-selection-by-parameter-type, as capability 16's consumer does
- [ ] T017 [US1] Create `CompactionEndpoint` in `src/main/scala/com/gwgs/akkaagentic/compaction/api/CompactionEndpoint.scala` — `GET /compaction` and `GET /compaction/{sessionId}` per contracts/compaction.md, with its own response types (never `SessionHistory` or a domain type on the wire); `404` for an unknown session, `400` for a blank one, and an empty collection as a `200`. Add it to the descriptor under `http-endpoint`
- [ ] T018 [US1] Integration-test the bound in `src/test/scala/com/gwgs/akkaagentic/compaction/api/CompactionIntegrationTest.scala` — with a low threshold and a mocked summariser, drive one session past it, then assert the stored history shrank, the next turn still succeeds, and `GET /compaction/{id}` reports `compacted` with `lastBytesAfter < lastBytesBefore`. Also assert a session **below** the threshold is never compacted and costs no summariser call

**Checkpoint**: the MVP works — a long session is bounded, observably.
*(Verify: `mvn clean verify` once here, because T016/T017 touch the shared component descriptor, which has a measured precedent for breaking every capability at startup.)* **Commit the gate.**

---

## Phase 4: User Story 2 — the conversation survives being compacted (Priority: P1)

**Goal**: what the conversation established is still there after the history is summarised.

**Independent Test**: assert on the *content* of the compacted history. Whether the model then *uses* it is
a separate, live-only claim.

- [ ] T019 [US2] Integration-test preservation in `src/test/scala/com/gwgs/akkaagentic/compaction/api/SummaryContentIntegrationTest.scala` — establish a fact several turns back, compact, and assert the stored summary still contains it; then a session where a tool was called and returned a result, and assert the summary carries the **substance** as prose with no structured tool call surviving
- [ ] T020 [P] [US2] Unit-test in `src/test/scala/com/gwgs/akkaagentic/compaction/domain/SummaryRequestToolProseTest.scala` that a tool request/response pair renders into the summariser's input such that its outcome is recoverable from the text alone — the property T019 depends on, proven without a runtime
- [ ] T021 [US2] Record in the test's scaladoc, and in quickstart.md, that **recall through the model is live-only**: capabilities 4 and 6 both measured that a mocked model receives only the current turn, so the mock cannot show the model using a summary. Label it; do not simulate it

**Checkpoint**: preservation is proven as far as offline can prove it, and the boundary is stated.
*(Verify: `mvn verify -Dit.test='SummaryContentIntegrationTest' -Dtest='!*'` plus the new unit test.)* **Commit the gate.**

---

## Phase 5: User Story 3 — compaction cannot break a turn (Priority: P1)

**Goal**: invisible when it works, harmless when it fails, and never leaving history in a shape the
provider rejects.

**Independent Test**: force each failure and assert the session stays usable and the history stays intact.

- [ ] T022 [US3] Integration-test summariser failure in `src/test/scala/com/gwgs/akkaagentic/compaction/api/CompactionFailureIntegrationTest.scala` — with `TestModelProvider.failWith`, assert the user's turn still returns a normal reply, the history is **byte-for-byte** what it was, and the ledger records `failed` with a reason. Repeat for a **blank** summary, which must be treated as failure and not as a very small summary
- [ ] T023 [US3] Integration-test the silent stale write in `src/test/scala/com/gwgs/akkaagentic/compaction/application/StaleCompactionIntegrationTest.scala` — drive a compaction with a deliberately stale sequence number and assert the ledger records **`skipped-stale`**, not `compacted`, and that the history is unchanged. This is the test that proves the verify-by-re-reading rule: research R-4 measured that the platform accepts a stale write silently, so without the re-read this test would record a bound that was never applied
- [ ] T024 [P] [US3] Test the no-loop property in `src/test/scala/com/gwgs/akkaagentic/compaction/application/NoSelfTriggerIntegrationTest.scala` — after a compaction, assert no second compaction follows, and pin research R-2's measured mechanism: the `AiMessageAdded` that compaction itself emits reports a history size of a couple of dozen bytes (22 when measured), because the size is computed after `HistoryCleared`. Comment that this is the SDK's ordering and not our property, which is why it is pinned rather than assumed
- [ ] T025 [P] [US3] Test the shape guarantee in `src/test/scala/com/gwgs/akkaagentic/compaction/api/NoOrphanedPairIntegrationTest.scala` — inspect the stored messages of a compacted session and assert exactly `UserMessage, AiMessage` with no tool-call request or response present at all. Scaladoc notes this is a property of *our* summary, since research S-2 showed the SDK's own eviction never creates an orphan
- [ ] T026 [US3] **Measure R-5, the one risk left unverified**: does capability 14's *streamed* turn tolerate its history being replaced mid-assembly? Add `src/test/scala/com/gwgs/akkaagentic/compaction/application/StreamingRaceIntegrationTest.scala` that opens a stream on a session and fires a compaction against the same session before it completes. If the race can be made deterministic, assert the outcome. **If it cannot, say so in the test's scaladoc and in research.md R-5 rather than asserting safety** — the trigger fires on `AiMessageAdded`, which a streamed turn writes on completion, so the window is narrow but not proven absent

**Checkpoint**: every failure path is proven, and the one unproven race is labelled as such.
*(Verify: `mvn verify -Dit.test='Compaction*,Stale*,NoSelf*,NoOrphaned*,StreamingRace*' -Dtest='!*'`.)* **Commit the gate.**

---

## Phase 6: User Story 4 — an operator can set the bound or turn it off (Priority: P2)

**Goal**: the threshold, the switch and the retention are configuration, not code.

**Independent Test**: the same session run under three settings gives three outcomes.

- [ ] T027 [P] [US4] Integration-test the two thresholds in `src/test/scala/com/gwgs/akkaagentic/compaction/api/ThresholdConfigIntegrationTest.scala` — two test classes over the same code differing in one config key, as capability 12 did for `report-only`: compaction triggers at the configured point in each
- [ ] T028 [P] [US4] Integration-test disabled in `src/test/scala/com/gwgs/akkaagentic/compaction/api/CompactionDisabledIntegrationTest.scala` — with `compaction.enabled = false`, a session grows past any threshold and nothing is compacted; the ledger stays empty; behaviour matches capability 6 as it is today
- [ ] T029 [US4] Integration-test the out-of-range value in `src/test/scala/com/gwgs/akkaagentic/compaction/api/BadConfigIntegrationTest.scala` — a `max-bytes` above 256 KiB makes the affected request answer **`500`** with a correlation id and a `ConfigException$BadValue` in the log, **not** `400`; the distinction is the point (capability 15 had to engineer it, `docs/sdk-3.6.0-limitations.md` §7d)

**Checkpoint**: the bound is operable without a rebuild.
*(Verify: `mvn verify -Dit.test='ThresholdConfig*,CompactionDisabled*,BadConfig*' -Dtest='!*'`.)* **Commit the gate.**

---

## Phase 7: User Story 5 — the interop question is answered in public (Priority: P3)

**Goal**: a reader finds what this capability proved, with measurements, where every earlier one recorded it.

- [ ] T030 [P] [US5] Add **§19** to `README.md` — the headline is that the wall claims a **third method of the agent client** (`withDetailedReply` after `invoke` and `tokenStream`), that a Scala consumer reaches the runtime-owned `SessionMemoryEntity` (extending capability 13's clause from `TaskEntity`), that **one** Java class holds all three method references, and that the platform's concurrency guard is **silent** so success must be verified. Include the premise correction (S-1/S-2) as a finding in its own right: two history-shrinking mechanisms, differing only in whether the cut is turn-aligned
- [ ] T031 [P] [US5] Add the capability-17 entry to `README.md`'s project layout, and a usage section with the `GET /compaction` examples from contracts/compaction.md
- [ ] T032 [P] [US5] Add the capability-17 section to `FINDINGS.md` — the client-method table gains a `withDetailedReply` row; the narrative records that the probe disproved its own premise, and that a silent platform guard is a hazard class ("we asked" ≠ "it happened") alongside capability 16's set-aside accounting
- [ ] T033 [P] [US5] Add **§7** (silent stale write) to `docs/sdk-3.6.0-limitations.md` — `compactHistory` accepts a stale `sequenceNumber` with no exception and no result, so a caller cannot distinguish success from a discarded write without re-reading. Re-check on upgrade
- [ ] T034 [US5] Update `ROADMAP.md` — row 17, the **B1 flip** to ✅ with the corrected motivation, counts to 1–17, the "Where we are" block, and the remaining candidates (A3 event-sourced entity in Scala, A4 gRPC, and forks B2/B3/B4/B5)
- [ ] T035 [P] [US5] Add `src/test/scala/com/gwgs/akkaagentic/compaction/OneJavaClassTest.scala` — asserts **exactly one** `.java` file under `src/main/java/com/gwgs/akkaagentic/compaction/` (excluding `probe/`), and that it is `SessionMemoryGateway.java`; capabilities 14 and 15's quarantine pin, so growth becomes a recorded finding rather than silent drift

**Checkpoint**: the finding is published and mechanically defended.
*(Verify: `mvn test -Dtest='OneJavaClassTest'`; the rest is documentation.)* **Commit the gate — documentation is its own commit per CLAUDE.md.**

---

## Phase 8: Polish & cross-cutting

- [ ] T036 Trim the Phase 0 probes to the evidence nothing else carries: in `src/test/scala/com/gwgs/akkaagentic/compaction/probe/CompactionProbeIntegrationTest.scala` keep R-1 (a Scala consumer matching the runtime-owned events) and R-4 (the silent stale write, now also covered by T023 — keep the probe's raw-measurement form only if it says something T023 does not); keep `EvictionAlignmentProbeIntegrationTest` in full, because **nothing in production asserts S-2's turn-alignment invariant** and it is the measurement the capability's motivation rests on
- [ ] T037 Delete `src/main/scala/com/gwgs/akkaagentic/compaction/probe/SessionMemoryProbeConsumer.scala` and `src/main/java/com/gwgs/akkaagentic/compaction/probe/SessionMemoryProbeGateway.java` **if** the production consumer and gateway can carry the surviving probe tests; otherwise keep them and say why in their scaladoc. Remove the probe consumer's descriptor line when it goes
- [ ] T038 [P] Verify **FR-015 / SC-009**: `git diff --stat main -- src/main/scala src/main/java src/test ':!*compaction*'` is empty — capability 6 untouched, and capabilities 4 and 14 unmodified while gaining the bound
- [ ] T039 [P] Verify **FR-010**: no `readLast` anywhere — `grep -rn "readLast" src/` returns nothing outside documentation
- [ ] T040 [P] Verify **SC-009** the other half: capabilities 4, 6 and 14's existing tests pass **unmodified** against a memory that can compact — `mvn verify -Dit.test='Chat*,PersonalAssistant*,SessionMemory*,Streaming*,Stream*' -Dtest='!*'`
- [ ] T041 [P] Verify the no-mutable-state rule: `grep -rn "\bvar \|mutable\." src/main/scala/com/gwgs/akkaagentic/compaction/` returns nothing (the capability 15 review rule)
- [ ] T042 [P] Add the capability-17 tags: `@Tag("slow")` on any new integration test whose cost is an unavoidable wall-clock wait, per the criterion in `pom.xml`'s `quick` profile — and only those
- [ ] T043 Update `specs/019-session-compaction/research.md` with R-5's outcome from T026 — measured, or explicitly not measurable and why
- [ ] T044 Walk `specs/019-session-compaction/quickstart.md` against a running service with Ollama, with `COMPACTION_MAX_BYTES` lowered: a long conversation compacts, `GET /compaction/{id}` reports it, a fact established before compaction is still answered afterwards (**the live-only claim — this is the only place it is verified**), capability 4's chat compacts too, and `COMPACTION_ENABLED=false` compacts nothing. Correct the doc where reality differs
- [ ] T045 [P] Update the memory files: the roadmap memory (capability 17 merged, next candidates) and a new or extended finding for the silent-guard hazard and the `withDetailedReply` result
- [ ] T046 Final `mvn clean verify` from a clean tree; record the suite's total time and this capability's share
- [ ] T047 Draft the PR body (handed to the user, no file committed): the premise correction first — the probe disproved its own spec, twice — then the interop findings, then the feature, then the boundaries (in-process ledger, no archive, R-5 unproven, live-only recall)

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)** → no dependencies
- **Phase 2 (Foundational)** → needs Phase 1; **blocks every user story**
- **Phase 3 (US1)** → needs Phase 2. The MVP, and the only phase that touches the shared descriptor
- **Phase 4 (US2)** → needs US1's consumer, gateway and agent
- **Phase 5 (US3)** → needs US1; independent of US2
- **Phase 6 (US4)** → needs US1; independent of US2 and US3
- **Phase 7 (US5)** → needs everything it documents, so it is last in sequence though not in importance
- **Phase 8 (Polish)** → needs all desired stories

### Within each story

Pure domain before anything that uses it; the gateway before the consumer that calls it; the consumer
before the endpoint that reports on it; tests alongside, not after.

### Parallel opportunities

- **Phase 2**: T003–T007 are five separate files, all `[P]`; T009–T012 likewise
- **Phase 5**: T024 and T025 are independent of T022/T023
- **Phase 6**: T027 and T028 are separate test classes over the same production code
- **Phase 7**: T030–T033 and T035 are all different files
- **Phase 8**: T038–T042 are independent checks

---

## Implementation Strategy

### MVP (User Story 1 only)

Phase 1 → Phase 2 → Phase 3, then **stop and validate**: a long session is bounded and the bound is
observable. That is a complete, demonstrable capability even with nothing else built.

### Incremental delivery

1. Foundational → every rule proven with no runtime
2. **US1** → the bound works (MVP)
3. **US2** → and the conversation survives it
4. **US3** → and nothing it does can break a turn
5. **US4** → and an operator controls it
6. **US5** → and the finding is published

### Notes

- `[P]` = different files, no dependencies
- **Every phase checkpoint is a commit**, with a scoped message (CLAUDE.md)
- Each task names the smallest test run that checks it; `mvn clean verify` only at T018 (shared descriptor)
  and T046 (final gate)
- Capability 6 must remain untouched throughout, and T038 proves it mechanically rather than by assertion
- Where a claim cannot be verified offline — recall through the model, and possibly R-5 — **say so**; every
  capability in this project that faked one was corrected later
