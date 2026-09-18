# Tasks: Scheduled reminders (Timed Action)

**Input**: Design documents from `/specs/017-timed-action/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/reminder-endpoints.md, quickstart.md
**Branch**: `017-timed-action`

**Tests**: Included, and not optional here — SC-001…SC-006 are all phrased as demonstrations
("shown by count rather than by inspection"), so each one is a test task rather than a claim.

**Organization**: by user story, in the spec's priority order. Each phase is one **gate**: a coherent,
compiling, test-green unit, committed as it lands (never batched to the end).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4, for the user-story phases only

## What Phase 0 already settled

The probe ran before this plan, and its answers are **measurements** (research.md). They are not
revisited by any task below:

| | Measured |
|---|---|
| **D1** | Scheduling **cannot** be done from Scala → `ReminderSchedulingEndpoint.java` is the one Java class |
| **D2** | Cancelling **can** → `ReminderEndpoint.scala` owns `GET` + `DELETE` |
| **D3** | The `TimedAction` itself is Scala (`ReminderAction` exists) |
| **D4** | The 3-arg `createSingleTimer` still retried at 30 s → the **4-arg** overload is mandatory, and pinned |
| **D5** | Timers do not survive a restart → state is **in-process** (`ReminderStore`), lifetime matched |
| **D6** | ~100 ms overhead → test delays **300 ms–1.5 s** |
| **D7** | The surface is split by language, and the file tree shows it |

---

## Phase 1: Setup

**Purpose**: nothing to scaffold — the package, the action and the descriptor key exist from Phase 0.
This phase only records where the capability starts.

- [x] T001 Confirm the Phase 0 baseline is green and unchanged: `mvn clean verify` passes, and `src/main/scala/com/gwgs/akkaagentic/reminders/` contains `application/ReminderAction.scala` + `application/ReminderLog.scala` + `probe/`
- [x] T002 Confirm `timed-action = ["com.gwgs.akkaagentic.reminders.application.ReminderAction"]` is present in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` (FR-014)

**Checkpoint**: baseline green; no production behaviour added yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the pure domain and the store every story reads and writes.

**⚠️ CRITICAL**: no user story can begin until this phase is complete.

- [x] T003 [P] Create `ReminderRequest` in `src/main/scala/com/gwgs/akkaagentic/reminders/domain/ReminderRequest.scala` — `validate(note: Option[String], delaySeconds: Option[Int]): Either[String, ReminderRequest]`, trimming the note, rejecting blank/over-500-character notes and delays outside 1 s…24 h. **No Akka import** (Constitution II), no `null`, messages exactly as in data-model.md
- [x] T004 [P] Create `ReminderState` in `src/main/scala/com/gwgs/akkaagentic/reminders/domain/ReminderState.scala` — a pure enum `Pending | Fired | Cancelled | Failed` plus `isTerminal`, with the transition rule that a terminal state never moves again (FR-003, FR-006)
- [x] T005 [P] Unit-test the validation in `src/test/scala/com/gwgs/akkaagentic/reminders/domain/ReminderRequestTest.scala` — each rejection message pinned verbatim (so the HTTP contract cannot drift from the rule), plus trimming and the boundary values 1 / 86400 / 0 / -1 / 86401 (FR-007, SC-005)
- [x] T006 [P] Unit-test the state machine in `src/test/scala/com/gwgs/akkaagentic/reminders/domain/ReminderStateTest.scala` — the three legal transitions out of `Pending`, and that no terminal state transitions again (SC-004)
- [x] T007 Create `ReminderStore` in `src/main/scala/com/gwgs/akkaagentic/reminders/application/ReminderStore.scala` — promote `ReminderLog` into the real instrument: `ConcurrentHashMap`-backed, keyed by reminder id, with `record` / `markFired` / `markFailed` / `cancel: CancelOutcome` / `get: Option[Reminder]`. Document the in-process lifetime and **why** (D5 / Q-C), so it reads as a decision rather than an oversight
- [x] T008 Unit-test `ReminderStore` in `src/test/scala/com/gwgs/akkaagentic/reminders/application/ReminderStoreTest.scala` — `cancel` returns `Cancelled` on a pending reminder, `AlreadyTerminal(state)` on a fired/cancelled/failed one, and `Unknown` on an unseen id (FR-006)

**Checkpoint**: domain + store complete and unit-tested with no runtime, no model, no Akka in `domain/`. **Gate → commit.**

---

## Phase 3: User Story 1 — Ask for something later, and get it (P1) 🎯 MVP

**Goal**: a caller schedules a reminder, sees it pending, and sees it fired with the note intact.

**Independent Test**: `POST /reminders` with a 500 ms delay → `201` + pending; wait; `GET` → fired, note unchanged.

### Tests for User Story 1

- [x] T009 [P] [US1] Write `src/test/scala/com/gwgs/akkaagentic/reminders/api/ReminderSchedulingIntegrationTest.scala` covering SC-001 (pending before the delay, fired after — both observed, not argued) and SC-002 (the note comes back byte-identical). Delays **1–1.5 s**: D6's 300 ms floor was the *probe's* measurement, but `POST /reminders` rejects anything under `ReminderRequest.MinDelay` (1 s), so HTTP-level tests cannot go lower. `Awaitility` for the fired assertion, a direct read for the pending one
- [x] T010 [P] [US1] Write the validation-first cases in `src/test/scala/com/gwgs/akkaagentic/reminders/api/ReminderSchedulingIntegrationTest.scala`: blank note, absent note, `delaySeconds` 0 / negative / absent / over-max each return `400` **and leave nothing scheduled** — assert the store has no entry afterwards, since "rejected" and "rejected without side effects" are different claims (FR-007, SC-005)

### Implementation for User Story 1

- [x] T011 [US1] Rewire `ReminderAction.fire` in `src/main/scala/com/gwgs/akkaagentic/reminders/application/ReminderAction.scala` to take the **reminder id** and call `ReminderStore.markFired`; drop the note-keyed `ReminderLog` coupling. Move `failAlways` out to `src/main/scala/com/gwgs/akkaagentic/reminders/probe/FailingReminderAction.scala` (a second `TimedAction`, the Q-D instrument) so the production action has exactly one handler
- [x] T012 [US1] Create `src/main/java/com/gwgs/akkaagentic/reminders/api/ReminderSchedulingEndpoint.java` — `POST /reminders`, `@Acl` INTERNET, validating through the Scala `ReminderRequest.validate` (cap-14's Q-E shape: `Option.apply` at the boundary, two casts on the `Either`), then `componentClient.forTimedAction().method(ReminderAction::fire).deferred(id)` and `timers().createSingleTimer(name, delay, maxRetries, deferred)` — **the 4-arg overload, always** (D4). Returns `201` + `Location`
- [x] T013 [US1] Create `src/main/scala/com/gwgs/akkaagentic/reminders/api/ReminderEndpoint.scala` — `GET /reminders/{id}` returning `200` with the reminder or `404`, with `firedAt` / `cancelledAt` / `failure` as omitted-when-empty `Option` fields (idiomatic Scala bodies; the two-mapper boundary does not bite, since nothing crosses the internal mapper)
- [x] T014 [US1] Register both endpoints under `http-endpoint` in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`, and add `FailingReminderAction` under `timed-action` (FR-014). Comment each entry with the measurement that put it there
- [x] T015 [US1] Run `mvn clean verify`; record the added wall-clock cost of the real-time waits in the test's own comment (FR-011, SC-009)

**Checkpoint**: the capability is usable end to end — schedule, observe pending, observe fired. **Gate → commit + push.**

---

## Phase 4: User Story 2 — Change your mind before it fires (P2)

**Goal**: cancelling a pending reminder stops it for good, and the two non-cancellations are reported distinguishably.

**Independent Test**: schedule, cancel, wait past the delay, confirm it never fired; then cancel again and get `409`; then cancel an unknown id and get `404`.

### Tests for User Story 2

- [ ] T016 [P] [US2] Write `src/test/scala/com/gwgs/akkaagentic/reminders/api/ReminderCancellationIntegrationTest.scala` — SC-003: after a `200` cancel, wait **past** the delay and assert 0 firings. Proving a negative costs one real wait; state that cost in a comment
- [ ] T017 [P] [US2] In `src/test/scala/com/gwgs/akkaagentic/reminders/api/ReminderCancellationIntegrationTest.scala`, pin all three outcomes distinctly (FR-006): `200 {"state":"cancelled"}`, `409` carrying the **actual** terminal state for an already-fired reminder, `404` for an unknown id — and assert the `409` body names `fired`, not `cancelled`, since reporting success for a cancellation that cancelled nothing is the specific failure this requirement exists to prevent

### Implementation for User Story 2

- [ ] T018 [US2] Add `DELETE /reminders/{id}` to `src/main/scala/com/gwgs/akkaagentic/reminders/api/ReminderEndpoint.scala` — `timers.delete(name)` (string-keyed, Scala-clean per D2/Q-B) guarded by `ReminderStore.cancel`, mapping `Cancelled → 200`, `AlreadyTerminal(s) → 409` with `s`, `Unknown → 404`
- [ ] T019 [US2] Handle the fire/cancel race explicitly in `src/main/scala/com/gwgs/akkaagentic/reminders/application/ReminderStore.scala` and `src/main/scala/com/gwgs/akkaagentic/reminders/api/ReminderEndpoint.scala` (spec Edge Cases): decide and implement which side wins when a cancel arrives while the action is running, and comment the decision at the call site so the window is documented rather than discovered
- [ ] T020 [US2] Run `mvn clean verify`

**Checkpoint**: US1 and US2 both work independently. The interop asymmetry is now visible in the surface — Java schedules, Scala cancels. **Gate → commit + push.**

---

## Phase 5: User Story 3 — A reminder that goes wrong does not loop for ever (P2)

**Goal**: bounded retries, proven by count, and the unbounded form kept out of production code.

**Independent Test**: schedule the always-failing action with `maxRetries = 2`, wait, count the attempts, and read the reminder as `failed`.

> **Ordering note (amended in review, 2026-09-18).** The Java probe is retired *inside* this phase, between
> the retry test and the pin, so the pin covers the whole capability — `probe/` included — with no
> exemption. It cannot be retired earlier: the probe is the only route that schedules the always-failing
> action until T021 provides another, and that route needs a Java method reference.

### Tests for User Story 3

- [ ] T021 [US3] Write `src/test/java/com/gwgs/akkaagentic/reminders/application/BoundedRetryIntegrationTest.java` — SC-006: schedule `FailingReminderAction` through the testkit's own `getTimerScheduler()` with the **4-arg** overload, then assert attempts **counted** (not inspected) and capped, and the reminder reads `failed` and never claims success. **Java, by the same measurement as the endpoint**: scheduling the failing action needs a `FailingReminderAction::…` method reference. It is a *test*, so it does not count against the production quarantine (capability 4 and 11 precedent). Do **not** re-run the 30 s sampling loop — research.md Q-D records the unbounded series
- [ ] T022 [US3] Retire the Java probe: delete `src/main/java/com/gwgs/akkaagentic/reminders/probe/JavaReminderProbeEndpoint.java` and its descriptor line, since the real `ReminderSchedulingEndpoint.java` now *is* the Java control. Switch `src/main/scala/com/gwgs/akkaagentic/reminders/probe/ScalaScheduleAttempt.scala` to the **4-arg** overload — the evidence it carries is unaffected, because the failure it demonstrates happens in `deferred()`, before `createSingleTimer` is reached. Rewire `src/test/scala/com/gwgs/akkaagentic/reminders/probe/TimedActionProbeIntegrationTest.scala`: Q-A's control → `POST /reminders`; Q-B → `POST /reminders` then Scala `DELETE /reminders/{id}` (itself a Scala cancel of a Java-scheduled timer); Q-D → removed (T021 covers it); Q-E → removed (a measurement log, recorded in research.md, not a regression check)
- [ ] T023 [US3] Write `src/test/scala/com/gwgs/akkaagentic/reminders/NoUnboundedTimerTest.scala` — read **every** source under the capability's `src/main` tree, `probe/` included, and fail if any calls the **3-argument** `createSingleTimer`. No exemption list: after T022 there is nothing to exempt. This is capability 12's "the agent names no rule" technique applied to FR-008 — a shipping rule a future edit could quietly break becomes a test, not a comment

### Implementation for User Story 3

- [ ] T024 [US3] Make the failure path reach the caller in `src/main/scala/com/gwgs/akkaagentic/reminders/probe/FailingReminderAction.scala`: it records each attempt via `ReminderStore.recordAttempt` and marks the reminder `failed` on its **final** permitted attempt, so an exhausted retry is readable through `GET` rather than only visible in logs (FR-008, SC-006)
- [ ] T025 [US3] Read `maxRetries` from config in `src/main/resources/application.conf` (`reminders.max-retries`, env-overridable) rather than hard-coding it, so the bound is operable without a recompile — and document that **no value makes it unbounded**
- [ ] T026 [US3] Run `mvn clean verify`

**Checkpoint**: all three behavioural stories independently functional; FR-008 is enforced by a test rather than by discipline. **Gate → commit + push.**

---

## Phase 6: User Story 4 — The interop question is answered in public (P3)

**Goal**: a reader learns the verdict, its evidence and its boundary without opening the source.

**Independent Test**: read FINDINGS / README / ROADMAP — which half of scheduling is Scala-clean, which is not, why, and how far the Java reaches.

- [ ] T027 [P] [US4] Add **§17** to `README.md` — the capability's own section: the two-endpoint surface, the `curl` walkthrough from quickstart.md, and the headline stated plainly: *one component family, both sides of the method-reference wall, split by operation — you can cancel what you cannot schedule*
- [ ] T028 [P] [US4] Add the interop entry to `FINDINGS.md` — Q-A's verdict with the **verbatim** diagnostic (`Use dedicated builder for calling Object component method ReminderProbeEndpoint::$anonfun$1`), the Java control that makes it a statement about Scala rather than about our usage, and Q-B's cancel result
- [ ] T029 [P] [US4] Update `ROADMAP.md` — flip A1 (Timed Action) to merged, and record what it settles about the remaining untouched families
- [ ] T030 [P] [US4] Add two entries to `docs/sdk-3.6.0-limitations.md` — (a) a pending timer does **not** survive a restart in local dev mode, with the measurement and the explicit scope caveat that a deployed service is untested here and claimed neither way; (b) the 3-argument `createSingleTimer` retried indefinitely in measurement, with the 5 s-interval series
- [ ] T031 [US4] Amend §16's method-ref-wall paragraph in `README.md` with one sentence pointing to §17 — the wall's shape is now "which client, which method, **and which operation**". Additive only; do not rewrite capability 14's finding

**Checkpoint**: the interop verdict is public and evidenced. **Gate → commit + push.**

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: retire the scaffolding without losing the evidence, and prove the constraints rather than assert them.

- [ ] T032 **Keep** `src/main/scala/com/gwgs/akkaagentic/reminders/probe/ScalaScheduleAttempt.scala` and trim `ReminderProbeEndpoint.scala` to the single route that exercises it (FR-013). The failed Scala attempt is deliverable evidence — it is the only executable proof that the documented form fails, and a prose claim would decay
- [ ] T033 Final pass over `src/test/scala/com/gwgs/akkaagentic/reminders/probe/TimedActionProbeIntegrationTest.scala` after T022's rewiring: it should now carry only the evidence nothing else carries — Q-A's Scala schedule failing at run time. Remove anything the US1/US2/US3 tests already prove, so the suite proves each thing once
- [ ] T034 [P] Add `src/test/scala/com/gwgs/akkaagentic/reminders/api/JavaQuarantineTest.scala` — capability 14's shape: assert **exactly one** `.java` under `src/main/java/com/gwgs/akkaagentic/reminders/`, and that it is the scheduling endpoint. If it ever fails, the wall reaches further than measured, which is a finding to record rather than a line to update (FR-013)
- [ ] T035 [P] Verify FR-010 / SC-007 mechanically: `git diff --stat main -- src/main/scala/com/gwgs/akkaagentic src/main/java/com/gwgs/akkaagentic src/test ':!*reminders*'` must be **empty**. Prose cannot prove this and a diff can
- [ ] T036 Walk `specs/017-timed-action/quickstart.md` against a running service (`mvn compile exec:java`) — every command and every response shape, including the `409` on a second cancel and the `404` after a restart. Correct the doc where reality differs; do not correct reality to match the doc
- [ ] T037 Final `mvn clean verify` from a clean tree, and record the suite's total wall-clock cost plus the portion attributable to this capability's unavoidable real-time waits (FR-011, SC-009)
- [ ] T038 Draft the PR body (handed to the user directly — no file committed, per this project's no-`gh`-CLI workflow) — the finding first (the family split by operation), then the feature, then the boundaries: no durability, bounded retries by construction, and what the live walkthrough did and did not cover

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: no dependencies
- **Phase 2 (Foundational)**: depends on Phase 1 — **blocks every user story**
- **Phase 3 (US1)**: depends on Phase 2. The MVP
- **Phase 4 (US2)**: depends on Phase 2; shares `ReminderEndpoint.scala` with US1, so T018 follows T013
- **Phase 5 (US3)**: depends on Phase 3 **and** Phase 4 — T022 rewires the probe test onto `POST /reminders` and `DELETE /reminders/{id}`, so both must exist before the Java probe can be retired
- **Phase 6 (US4)**: depends on the behaviour being final — documentation written earlier would document intentions
- **Phase 7 (Polish)**: depends on all of the above; T032–T033 must not run before US1–US3 tests cover what the probe used to (the Java probe itself is already retired in T022)

### Within Each Story

- Tests are written before the implementation they describe (T009/T010 before T011–T014; T016/T017 before T018)
- Domain before store, store before action, action before endpoints, endpoints before their tests pass

### Parallel Opportunities

- **Phase 2**: T003, T004, T005, T006 are four separate files — all parallel; T007 then T008
- **Phase 3**: T009 and T010 in parallel; T012 (Java) and T013 (Scala) touch different files and can proceed together once T011 lands
- **Phase 6**: T027–T030 are four different documents — fully parallel
- **Phase 7**: T034 and T035 are independent of each other

---

## Parallel Example: Phase 2

```bash
# Four independent files, no shared state:
Task: "Create ReminderRequest in src/main/scala/com/gwgs/akkaagentic/reminders/domain/ReminderRequest.scala"
Task: "Create ReminderState in src/main/scala/com/gwgs/akkaagentic/reminders/domain/ReminderState.scala"
Task: "Unit-test validation in src/test/scala/com/gwgs/akkaagentic/reminders/domain/ReminderRequestTest.scala"
Task: "Unit-test the state machine in src/test/scala/com/gwgs/akkaagentic/reminders/domain/ReminderStateTest.scala"
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 → Phase 2 → Phase 3
2. **Stop and validate**: a reminder schedules, reads pending, fires, and carries its note
3. That alone demonstrates the family works and the Java/Scala split is real

### Incremental Delivery

1. Foundational → US1 (MVP) → US2 (cancel; the asymmetry becomes visible) → US3 (bounded retries; FR-008 satisfied) → US4 (the verdict published) → Polish
2. Each gate is committed as it lands and pushed while the PR is open, so review follows the reasoning rather than receiving one large diff

### Risks carried into implementation

- **Real-time waits are the suite's only new cost.** D6 bounds them at 300 ms–1.5 s each; if a test needs seconds, say so rather than quietly slowing the build
- **The quarantine is one class, and only by measurement.** If T012 turns out to need a second Java class, that is a **finding** (T034 will fail and say so), not a reason to relax the rule
- **Nothing here is durable, on purpose.** Any later task that makes reminder state survive a restart must first answer what a restored `pending` reminder means when its timer did not survive

## Notes

- 38 tasks: 2 setup, 6 foundational, 7 US1, 5 US2, 6 US3, 5 US4, 7 polish
- No model anywhere, mocked or live — this is the project's second fully model-free capability
- No new dependency; the descriptor gains two `http-endpoint` lines and one `timed-action` line
