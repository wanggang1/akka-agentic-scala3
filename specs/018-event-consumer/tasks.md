# Tasks: Reacting to activity (Consumer)

**Input**: Design documents from `/specs/018-event-consumer/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/todo-activity.md, quickstart.md
**Branch**: `018-event-consumer`

**Tests**: Included and not optional — every SC is phrased as a demonstration ("shown by count"), and
research Q-F makes *where* a test runs part of its correctness: failure behaviour on the TestKit's mocked
channel would be a false green.

**Organization**: by user story, in the spec's priority order. Each phase is one **gate** — compiling,
test-green, committed and pushed as it lands.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5, for the user-story phases only

## What Phase 0 already settled (research.md — measured, not revisited)

| | Measured |
|---|---|
| **D1** | The whole Consumer family is Scala-clean → **no Java in production** |
| **D2** | Input is capability 6's whole `TodoList` → the reaction **diffs** successive states |
| **D3/D4** | A failing handler is redelivered **without limit** and **blocks every other user**; no attempt number, `ce-id` changes per delivery → attempts counted per **(user, state fingerprint)**, then set aside + `done()` |
| **D5** | Duplicates are delivered → an identical state records nothing |
| **D6/D7** | Publishing is Scala-clean with an idiomatic payload; `eventing.support = logging` (already in `application.conf`) keeps local startup working |
| **D8** | The consumer **resumes** after a restart → the in-process feed states its window (`since`) |
| **D9** | Retention: 500 entries, 100 set-asides, 1000 users (evicted user → `baseline`) |
| **D10** | Failure is tested on the **real** projection path (Java tests — writing `TodoEntity` needs a method reference) |
| **Q-G** | The SDK task source is reachable but carries no delegate record — **out of scope** (user decision) |

---

## Phase 1: Setup

- [x] T001 Confirm the Phase 0 baseline: `mvn clean verify` green; `src/main/scala/com/gwgs/akkaagentic/feed/probe/` holds the three probe consumers; the descriptor has the `consumer` key; `src/main/resources/application.conf` sets `akka.javasdk.dev-mode.eventing.support = "logging"`
- [x] T002 Append a `feed { max-attempts = 3, max-entries = 500, max-set-asides = 100, max-users = 1000 }` block, each env-overridable (`FEED_MAX_ATTEMPTS` …), to `src/main/resources/application.conf`, with a comment citing research Q-D and D9

**Checkpoint**: baseline green; nothing new behaves yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the pure rules, the store and the attempt bound every story uses. **No Akka import in `domain/`.**

- [x] T003 [P] Create `TodoSnapshot` + `TodoItem` in `src/main/scala/com/gwgs/akkaagentic/feed/domain/TodoSnapshot.scala` — `items: Map[Int, TodoItem]`, `nextId: Int`, and a deterministic `fingerprint: String` (`id:description:completed|…#next=n`, ids ascending). Does not import capability 6's types (data-model §1)
- [x] T004 [P] Create `TodoChange` in `src/main/scala/com/gwgs/akkaagentic/feed/domain/TodoChange.scala` — `Added | Completed | Reopened | Removed | ListDeleted | Baseline(open, completed)` with a `kind` wire label (`added`, `completed`, `reopened`, `removed`, `list-deleted`, `baseline`)
- [x] T005 Create `TodoDiff.between(previous: Option[TodoSnapshot], current: TodoSnapshot): List[TodoChange]` in `src/main/scala/com/gwgs/akkaagentic/feed/domain/TodoDiff.scala` — `None` → `Baseline`; identical → `Nil` (the idempotence rule, D5); otherwise ordered by item id
- [x] T006 Create `ActivityFeed` in `src/main/scala/com/gwgs/akkaagentic/feed/domain/ActivityFeed.scala` — `since`, `entries`, `setAsides`, `lastState`, `nextSequence`, limits; `record`, `deleted`, `setAside`, each returning `(ActivityFeed, result)` and never changing the receiver; retention drops oldest entries/set-asides and evicts the least recently changed user (whose next change then yields `Baseline`)
- [x] T007 [P] Unit-test the diff in `src/test/scala/com/gwgs/akkaagentic/feed/domain/TodoDiffTest.scala` — first sighting → `Baseline(open, completed)`, not a run of `added`; each change kind; several changes in one delivery (the platform folds changes); identical snapshots → `Nil`; an item added and removed between two deliveries → nothing; deterministic order
- [x] T008 [P] Unit-test the feed in `src/test/scala/com/gwgs/akkaagentic/feed/domain/ActivityFeedTest.scala` — sequences monotonic; a transition never changes its receiver; retention at 500 / 100 drops the oldest; the 1001st user evicts the least recently changed, whose next change is a `Baseline`; `deleted` forgets the user's last state; set-asides are never counted as entries
- [x] T009 [P] Create `FeedSettings` in `src/main/scala/com/gwgs/akkaagentic/feed/application/FeedSettings.scala` — reads `feed.*`, refuses out-of-range values with `ConfigException.BadValue` (never `require`: the SDK reports that as the caller's `400` — capability 15, limitations §7d), plus `FeedSettingsTest.scala` in `src/test/scala/com/gwgs/akkaagentic/feed/application/`
- [x] T010 Create `ActivityStore` in `src/main/scala/com/gwgs/akkaagentic/feed/application/ActivityStore.scala` — exactly **one** `AtomicReference[ActivityFeed]`, every change a pure transition committed by compare-and-set (capability 15's `ReminderStore` pattern); timestamps taken before the retry loop; a `clear()` test hook
- [x] T011 Create `BoundedDelivery` in `src/main/scala/com/gwgs/akkaagentic/feed/application/BoundedDelivery.scala` — capability 15's `BoundedAttempts`, keyed by **(consumer, user, fingerprint)** in a bounded map inside the same cell: `Succeeded | WillRetry(attempt, cause) | SetAside(attempt, cause)`; at the bound it records the set-aside in `ActivityStore` with the reason. Scaladoc cites research Q-D's measured schedule and the cross-user blocking
- [x] T012 [P] Unit-test `BoundedDelivery` in `src/test/scala/com/gwgs/akkaagentic/feed/application/BoundedDeliveryTest.scala` — below the bound: `WillRetry` and counted; at the bound: `SetAside`, recorded with reason; success: nothing counted; the same state under a *different* fingerprint counts separately; a 32-thread race on one key has a single consistent count

**Checkpoint**: the pure rules and the attempt bound are unit-tested with no runtime. **Gate → commit + push.**

---

## Phase 3: User Story 1 — Something changes, and it is noticed (P1) 🎯 MVP

**Goal**: a change to a user's to-do list appears in the feed, unasked.

**Independent Test**: publish capability 6 states on the mocked channel; `GET /todo-activity/{user}` shows `baseline`, then `added`/`completed`/`removed` in order.

### Tests for User Story 1

- [x] T013 [P] [US1] Write `src/test/scala/com/gwgs/akkaagentic/feed/api/TodoActivityIntegrationTest.scala` on the **mocked** channel (`withKeyValueEntityIncomingMessages(classOf[TodoEntity])` — delivery shape is faithful there, research Q-F): first sighting → `baseline`; then `added`, `completed`, `reopened`, `removed`; a whole-list delete → `list-deleted`; an empty feed is `200` with `entries: []`; the per-user route filters; every response carries `since`. `Awaitility` bounds, no sleeps
- [x] T014 [P] [US1] In the same test, SC-002 on a key-value source: across ≥ 5 successive states for one user, the entries are never out of order and the last reflects the final state — asserted as exactly that, **not** as "one entry per change" (spec FR-004)

### Implementation for User Story 1

- [x] T015 [US1] Create `TodoActivityConsumer` in `src/main/scala/com/gwgs/akkaagentic/feed/application/TodoActivityConsumer.scala` — `@Component(id = "todo-activity-consumer")`, `@Consume.FromKeyValueEntity(classOf[TodoEntity])`; `onUpdate(TodoList)` converts to `TodoSnapshot` at the boundary and records through `BoundedDelivery` (rethrow on `WillRetry`, `done()` otherwise); `@DeleteHandler` records `list-deleted`. A plain top-level class (research Q-A)
- [x] T016 [US1] Create `TodoActivityEndpoint` in `src/main/scala/com/gwgs/akkaagentic/feed/api/TodoActivityEndpoint.scala` — `GET /todo-activity`, `/todo-activity/{username}`, `/todo-activity/set-aside`, each with `since`; `@JsonInclude(NON_ABSENT)` so optional fields are omitted; idiomatic Scala bodies (README §3)
- [x] T017 [US1] Register the consumer under `consumer` and the endpoint under `http-endpoint` in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`, each commented with the measurement that shaped it
- [x] T018 [US1] Run `mvn clean verify`

**Checkpoint**: the MVP — the feed reacts to capability 6 with no caller involved. **Gate → commit + push.**

---

## Phase 4: User Story 2 — One bad message cannot stop everything behind it (P1)

**Goal**: a delivery that keeps failing is set aside after a bounded count, and every other user keeps flowing.

**Independent Test**: on the **real** path, make one user's deliveries fail; see the bound, the set-aside, and another user's change arrive after the give-up.

- [x] T019 [US2] Rewire `src/main/scala/com/gwgs/akkaagentic/feed/probe/TodoProbeConsumer.scala` to run its work through **`BoundedDelivery`** with `feed.max-attempts`, so the always-failing instrument exercises the production code path (capability 15's shared-helper shape); keep `ProbeLog` as the independent witness of how many times the runtime actually delivered
- [x] T020 [US2] Write `src/test/java/com/gwgs/akkaagentic/feed/application/BoundedActivityDeliveryIntegrationTest.java` on the **real** path (no mocks; Java because writing `TodoEntity` needs a method reference — research D10): poison one user; assert deliveries == `feed.max-attempts` (by the witness), the set-aside is visible over `GET /todo-activity/set-aside` with its reason, and a **different** user's change, written *after* the stream entered its failure loop, reaches the probe consumer after the give-up (research Q-D: without the bound it waited indefinitely)
- [x] T021 [US2] In the same test, US2 scenario 3: a delivery that fails once and then succeeds reaches the witness twice, appears in no set-aside, and records its change once
- [x] T022 [US2] Run `mvn clean verify`

**Checkpoint**: FR-006/007 enforced and proven where failure is real. **Gate → commit + push.**

---

## Phase 5: User Story 3 — A repeated delivery is not a repeated event (P2)

- [x] T023 [US3] Extend `src/test/scala/com/gwgs/akkaagentic/feed/api/TodoActivityIntegrationTest.scala`: the same state delivered twice yields one set of entries (SC-003); a state, a new state, then the new state again yields entries only for the first two
- [x] T024 [US3] Record in the test's scaladoc what is **not** promised: with no version on a key-value state, a replay of an *older* committed state after a newer one would surface as a reversal pair (research, "what remains unverified" 1) — FR-004 still holds
- [x] T025 [US3] Run `mvn clean verify`

**Checkpoint**: idempotence proven on the delivery path. **Gate → commit + push.**

---

## Phase 6: User Story 4 — The reaction is passed on (P2)

- [x] T026 [P] [US4] Write `src/test/scala/com/gwgs/akkaagentic/feed/api/TodoActivityPublishIntegrationTest.scala` with `withTopicOutgoingMessages("todo-activity")`: one message per delivery that produced changes, in feed order; `ce-subject` = username; **none** for a duplicate and none for a set-aside (SC-005); the payload's optional fields serialize as plain values (research Q-C)
- [x] T027 [US4] Add `@Produce.ToTopic("todo-activity")` to `TodoActivityConsumer`, returning `effects().produce(TodoActivityMessage(...), Metadata.EMPTY.add("ce-subject", username))` when a delivery produced changes and `effects().ignore()` otherwise; define the idiomatic `TodoActivityMessage` in `src/main/scala/com/gwgs/akkaagentic/feed/application/TodoActivityConsumer.scala`
- [x] T028 [US4] Start the service with plain `mvn compile exec:java` and confirm it starts (no `AK-00406`) — the one behaviour the TestKit cannot show (research Q-C)
- [x] T029 [US4] Run `mvn clean verify`

**Checkpoint**: both halves of the family — consume and produce — shipped in Scala. **Gate → commit + push.**

---

## Phase 7: User Story 5 — The interop question is answered in public (P3)

- [x] T030 [P] [US5] Add **§18** to `README.md` (interop note) plus a project-layout entry and a usage section: the family is Scala-clean end to end, **no Java in production**; the failure trap with the measured schedule; the mock that drops failures; `AK-00406`; the restart/`since` trade-off; Q-G reachable but no delegate record
- [x] T031 [P] [US5] Add a Capability 16 section and a Consumer row to the client table in `FINDINGS.md`, and extend the rubric: "a consumer must bound its own attempts"
- [x] T032 [P] [US5] Update `ROADMAP.md` — row 16 as **🚧 In progress**, "You are here", A2 in flight, and fork B3 marked *measured: not achievable through task events for request-based delegation* (the ✅ flip is a post-merge commit)
- [x] T033 [P] [US5] Add **§8** to `docs/sdk-3.6.0-limitations.md`: 8a unbounded redelivery that blocks every entity (schedule); 8b the TestKit's key-value mock drops failing messages and loses those behind it; 8c one `@Produce.ToTopic` stops local startup (`AK-00406`); 8d no attempt number and a per-delivery `ce-id`
- [x] T034 [US5] Amend README §17's retry-trap bullet with a one-sentence pointer to §18 — the same trap, worse, because a consumer's blocks everyone. Additive only

**Checkpoint**: the verdict is public. **Gate → commit + push.**

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T035 Trim the Phase 0 probes to the evidence nothing else carries: in `src/test/scala/com/gwgs/akkaagentic/feed/probe/ConsumerProbeIntegrationTest.scala` keep Q-A delivery, the Q-F "the mock drops a failing message" observation (short, asserted), Q-E duplicate delivery and Q-G task events, dropping the 30 s sampling loops (measurements cited from research); in `src/test/java/com/gwgs/akkaagentic/feed/probe/ConsumerRealPathProbeIntegrationTest.java` remove what T020/T021 now prove
- [x] T036 Delete `src/main/scala/com/gwgs/akkaagentic/feed/probe/TodoTopicProbeConsumer.scala` and its descriptor line — the production publisher now carries the Q-C evidence (T026 asserts the idiomatic payload). **Keep** `TaskProbeConsumer` as the Q-G evidence
- [x] T037 [P] Add `src/test/scala/com/gwgs/akkaagentic/feed/NoJavaInProductionTest.scala` — asserts **no** `.java` under `src/main/java/com/gwgs/akkaagentic/feed/` (the inverse of capabilities 14 and 15's quarantine pins); if it ever fails, the wall reached this family after all — a finding to record
- [x] T038 [P] Verify FR-010 / SC-006: `git diff --stat main -- src/main/scala src/main/java src/test ':!*feed*'` is empty, and the shared resources were only appended to
- [x] T039 Walk `specs/018-event-consumer/quickstart.md` against a running service with Ollama driving capability 6's writes: entries appear unasked, `since` is present, published messages appear in the log; correct the doc where reality differs. **Two corrections made**: a new user's first delivery already contains the item, so the `baseline` counts include it and there is no `added` entry for it (README/quickstart/contract examples fixed); and the `logging` sink prints nothing by default — it logs at INFO under `kalix.runtime.eventing.LoggingEventingSupport.<topic>` and the dev-mode logback silences the whole `kalix` tree at `WARN`, so `include-dev-loggers.xml` now re-enables that one logger (README §18, limitations §8c, research Q-C updated)
- [x] T040 Final `mvn clean verify` from a clean tree; record the suite's total time and this capability's share. **Green: 4 min 16 s, 417 tests (221 unit + 196 integration), 0 failures.** Capability 16's share: **26 unit tests in ~0.02 s** (`TodoDiffTest` 7, `ActivityFeedTest` 9, `BoundedDeliveryTest` 5, `FeedSettingsTest` 3, `NoJavaInProductionTest` 2) and **20 integration tests in 28.6 s** (`TodoActivityIntegrationTest` 9 / 7.3 s, `TodoActivityPublishIntegrationTest` 5 / 9.2 s, `ConsumerProbeIntegrationTest` 4 / 7.3 s — down from 84.7 s after T035 — and the Java real-path `BoundedActivityDeliveryIntegrationTest` 2 / 4.8 s)
- [x] T041 Draft the PR body (handed to the user — no file committed): the finding first (Scala-clean; its failure mode blocks everyone), then the feature, then the boundaries (in-process feed, no exactly-once display, local broker substitute, Q-G)

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2**: blocks everything
- **Phase 3 (US1)**: needs Phase 2. The MVP
- **Phase 4 (US2)**: needs US1's consumer (T015) and `BoundedDelivery` (T011)
- **Phase 5 (US3)**: needs US1; independent of US2
- **Phase 6 (US4)**: needs US1 — modifies the consumer, so after T015; independent of US2/US3
- **Phase 7 (US5)**: after the behaviour is final
- **Phase 8**: last; T035/T036 must follow T020 and T026, which take over what the probes proved

### Parallel Opportunities

- **Phase 2**: T003, T004 together; T007, T008, T009, T012 are separate files
- **Phase 3**: T013 and T014 are the same file — sequential within it, parallel with T015–T016 authoring
- **Phase 7**: T030–T033 are four different documents
- **Phase 8**: T037 and T038 are independent

## Parallel Example: Phase 2

```bash
Task: "Create TodoSnapshot in src/main/scala/com/gwgs/akkaagentic/feed/domain/TodoSnapshot.scala"
Task: "Create TodoChange in src/main/scala/com/gwgs/akkaagentic/feed/domain/TodoChange.scala"
```

## Implementation Strategy

1. **MVP** = Phases 1–3: the feed reacts to capability 6, unasked
2. **Then US2 immediately** — equal priority, and the one that must not ship missing: without it one bad
   message stalls the stream for every user (measured)
3. US3 → US4 → US5 → Polish, each gate committed and pushed

### Risks carried into implementation

- **The mock lies about failure** (Q-F). Any failure-shaped assertion written against the mocked channel is
  suspect by construction; T020/T021 are real-path for that reason.
- **One producing consumer can take the whole service down locally.** T028 checks the start, not just the tests.
- **The feed forgets on restart and is not refilled.** `since` makes it visible; a durable feed is a fork.

## Notes

- 41 tasks: 2 setup, 10 foundational, 6 US1, 4 US2, 3 US3, 4 US4, 5 US5, 7 polish
- No model in the reaction; Ollama is needed only for T039's live walk (it drives capability 6's writes)
- No new dependency; no Java in production
