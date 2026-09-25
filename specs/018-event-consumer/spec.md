# Feature Specification: Reacting to activity (Consumer)

**Feature Branch**: `018-event-consumer`
**Created**: 2026-09-19
**Status**: Draft
**Input**: User description: "Capability 16 — Consumer (react to events, and publish them). Candidate A2 from the ROADMAP: the second of the SDK component families this project has never built, and the largest remaining coverage gap. The headline deliverable is a resolved interop finding, on equal footing with the feature."

## Why this capability exists

Everything this service does today happens **because someone asked**. A request arrives, a component
answers. Capability 15 added "do this later", but it is still a caller asking. Nothing yet **reacts on its
own** to something that happened elsewhere in the service — a change noticed, recorded, and passed on,
with no caller involved at all.

That is what a **Consumer** is for, and it is one of only three SDK component families this project has
never built. It is also where the SDK documents a failure behaviour worth taking seriously: a message the
reaction cannot handle is **redelivered until it succeeds**. Capability 15 found a timer retrying for ever
exactly this way; here the stakes are higher, because a stuck message may hold up every message behind it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Something changes, and it is noticed without anyone asking (Priority: P1)

Activity happens in an existing part of the service. Shortly afterwards, a record of it appears in an
**activity feed** that a reader can consult — no caller told the feed anything; it reacted.

**Why this priority**: This is the capability. Without it there is no reaction to observe, no failure to
contain, and no finding.

**Independent Test**: Cause a change in the source activity, then read the feed and see a matching entry
appear within a bounded time — with the originating request never touching the feed.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a change happens in the source activity, **Then** a
   matching entry appears in the feed within a bounded time, identifying what changed and for whom.
2. **Given** several changes to the same item in sequence, **When** the feed is read, **Then** the
   entries it holds for that item are in the order the changes happened, and the last one reflects the
   item's final state. (One entry per change is not promised for this source — see FR-004.)
3. **Given** an item is removed at the source, **When** the feed is read, **Then** the removal is
   recorded as such — not silently absent, and not mistaken for an ordinary change.
4. **Given** nothing has happened, **When** the feed is read, **Then** it is empty — an empty feed is a
   valid answer, not an error.

---

### User Story 2 - One bad message cannot stop everything behind it (Priority: P1)

A change arrives that the reaction cannot process. It is attempted a **bounded** number of times, then
**set aside and recorded** — and every later change keeps flowing into the feed as normal.

**Why this priority**: Equal to Story 1. The platform's documented default is to redeliver a failing
message until it succeeds; if that also blocks later messages, one bad change halts the capability for
ever, silently. Shipping that would be worse than shipping nothing, so this is a condition of shipping,
not an improvement.

**Independent Test**: Introduce a change the reaction will always fail on, followed by ordinary changes.
Confirm the ordinary changes still appear, and the failed one is visibly recorded as set aside after a
bounded number of attempts.

**Acceptance Scenarios**:

1. **Given** a change the reaction always fails on, **When** it arrives, **Then** it is attempted a
   bounded number of times and then set aside, and the set-aside record says which change and why.
2. **Given** a set-aside change, **When** later changes arrive, **Then** they are processed and appear in
   the feed as normal.
3. **Given** a change that fails once and then succeeds, **When** it is retried, **Then** it appears in
   the feed exactly once and is not recorded as set aside.

---

### User Story 3 - A repeated delivery is not a repeated event (Priority: P2)

The platform may deliver the same change more than once. The feed shows it **once**.

**Why this priority**: At-least-once delivery is documented, so duplicates are a normal condition rather
than an edge case. A feed that double-counts would misreport activity every time the platform
re-delivers.

**Independent Test**: Deliver the same change twice and confirm the feed holds one entry for it.

**Acceptance Scenarios**:

1. **Given** a change already recorded, **When** the same change is delivered again, **Then** the feed
   still holds exactly one entry for it.

---

### User Story 4 - The reaction is passed on to anyone who subscribes (Priority: P2)

Each recorded change is also **published** to a channel other parts of a system could subscribe to, in
the same order the feed shows.

**Why this priority**: "Consume" is half of this component family; "produce" is the other half, and its
interop is the part nothing in the project has tested. The feed alone would answer only half the question.

**Independent Test**: Cause changes and confirm the published messages match the feed's entries, in order.

**Acceptance Scenarios**:

1. **Given** changes happen, **When** the published channel is inspected, **Then** each recorded change
   appears there once, in the same order as the feed.
2. **Given** a change that was set aside, **When** the channel is inspected, **Then** it was not
   published as an ordinary change.

---

### User Story 5 - The interop question is answered in public (Priority: P3)

A developer reading the repository learns whether reacting to activity can be **authored**, **fed**,
**made to act**, and **made to publish** from Scala on this Java-first SDK — and, where any part cannot,
exactly which part and why.

**Why this priority**: The feature is usable without it, but this is the project's stated purpose.

**Independent Test**: Read the findings, README and roadmap; the verdict, its evidence and its boundary
are stated without reading the source.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a reader consults the findings, **Then** they learn
   which parts are Scala-clean and which are not, with evidence.
2. **Given** any part had to be written in Java, **When** a reader consults the findings, **Then** they
   learn which part, the mechanism that forced it, and how far it spreads.

---

### Edge Cases

- **A burst of changes to one item.** Order must hold within the item and the final state must arrive.
  For the to-do source, intermediate states may be folded together by the platform; that is stated, not
  hidden, and the feed never presents itself as a complete history of that item.
- **Changes to many items at once.** Order across different items is not promised, and the feed must not
  imply that it is.
- **A change arriving while an earlier one is being retried.** Whether it waits (head-of-line) or
  proceeds must be measured and stated; either way it must not be lost.
- **A restart.** Whether the reaction resumes where it left off, replays from the beginning, or loses
  changes must be measured and stated — and the feed's own contents must be consistent with whichever it
  is (a feed emptied by a restart while the reaction does not replay would silently lose history).
- **The feed growing without limit.** Capability 15 shipped an in-process store that never evicts and had
  to state it after review. This capability states its retention bound up front.
- **A removal of an item the feed has never seen.** Recorded as a removal, not rejected.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST react to **capability 6's to-do changes** — the committed source, known
  to be reachable. **Capability 7's delegation tasks** are a second, *probed* source: whether they can be
  consumed at all is measured first (Q-G), and they are added only if reachable **and** approved at a
  checkpoint after the probe. Scope does not grow automatically. *(Clarified 2026-09-19: option C.)*
- **FR-002**: The reaction MUST happen without any caller requesting it, and MUST be observable within a
  bounded, stated time after the change.
- **FR-003**: A reader MUST be able to read the activity feed and see, per entry: which item, whose, what
  kind of change (including removal), and in what order.
- **FR-004**: For each item, the feed MUST never show changes out of order, and its latest entry MUST
  reflect the item's most recent state. For the to-do source the feed MUST NOT claim to hold *every*
  change: that source delivers the most recent state, and the platform may fold intermediate changes
  together under a fast update pace (a newly started reaction also does not see history from before it
  existed). If the delegation-task source is added, it delivers **every** event, and the feed MUST then
  hold one entry per event, in order.
- **FR-005**: A change delivered more than once MUST appear in the feed once.
- **FR-006**: A change the reaction cannot process MUST be attempted a **bounded** number of times, then
  set aside and recorded in a form a reader can see, with the reason.
- **FR-007**: A set-aside change MUST NOT prevent later changes from being processed. Halting the
  capability on one bad message is a defect, not a configuration choice.
- **FR-008**: Each recorded change MUST be published to a subscribable channel, in feed order; a
  set-aside change MUST NOT be published as an ordinary change.
- **FR-009**: The feed MUST have a stated retention bound, so memory cannot grow without limit.
- **FR-010**: No existing capability's production sources or tests may be modified. The source activity
  is read, never changed; where this capability needs behaviour a source lacks, it introduces its own.
- **FR-011**: The capability MUST be verifiable without a live model and without network access. Where a
  behaviour can only be observed by waiting, the wait MUST be small and its cost stated; where a behaviour
  cannot be observed offline at all (e.g. a real message broker), that MUST be stated rather than implied.
- **FR-012**: The capability MUST publish its interop verdict — which parts of consuming and producing
  are expressible in the project's primary language, the deciding mechanism, and the precise boundary of
  any part that must be Java — in the project's findings, README and roadmap.
- **FR-013**: If a part cannot be written in the primary language, the **attempt and its failure mode**
  MUST be kept as evidence beside the working version, and the non-primary-language part confined to the
  smallest unit that works, with a test that keeps it that small.
- **FR-014**: Any component introduced MUST be registered so the runtime discovers it, consistent with
  how this project registers components.
- **FR-015**: The behaviour across a restart MUST be measured and stated (FR-011's rule applies).

### Key Entities

- **Activity entry**: one recorded change — which item, whose, what kind (including removal), and its
  position in order. Immutable once recorded.
- **Set-aside record**: a change that could not be processed — which change, how many attempts, and the
  reason. Distinct from an activity entry; never counted as activity.
- **Published message**: the outward form of an activity entry, one per entry, in the same order.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A change at the source appears in the feed within a stated bound (seconds, not minutes),
  demonstrated rather than argued.
- **SC-002**: Across a sequence of at least five changes to one item, the entries the feed holds are
  never out of order and the last one matches the item's final state. (If the delegation-task source is
  added: every one of at least five events appears, in order.)
- **SC-003**: A change delivered twice appears once.
- **SC-004**: A change that always fails is attempted a bounded number of times — shown by count — and
  then set aside, while at least three later changes still reach the feed.
- **SC-005**: Every recorded change is published exactly once, in feed order; no set-aside change is.
- **SC-006**: Every existing capability's production sources and tests are unchanged, provable by
  inspecting the change set.
- **SC-007**: The findings, README and roadmap state the interop verdict with its evidence and boundary,
  including which part — if any — had to be Java.
- **SC-008**: The capability is proven without a live model; the suite stays fast enough to run routinely,
  and any unavoidable waiting is stated with its duration.
- **SC-009**: The feed's retention bound and its behaviour across a restart are stated, the latter
  measured.

## Assumptions

- **A surface of its own.** The reaction and its feed are new; the source activity is read, never
  modified (FR-010). Whichever source is chosen, the capability does not change how that source behaves.
- **No model in the reaction.** Recording and publishing a change needs no language model, which keeps
  the capability deterministic — the shape capabilities 11 and 15 used. Having the reaction call an agent
  is a recorded fork, not scope.
- **"Published" is proven with the test toolkit's own channel, not a real broker.** The local runtime is
  not configured with a message broker, so publication is verified offline through the toolkit's mocked
  channel. What a real broker would do (delivery, redelivery across services) is out of reach here and is
  stated as such (FR-011).
- **The feed is in-process and bounded** (FR-009). A durable feed is out of scope unless the restart
  measurement (FR-015) shows the reaction does not replay — in which case the trade-off is written down,
  not hidden.
- **Retries are bounded by the capability itself** if the platform's own redelivery is unbounded
  (capability 15's lesson: the platform's bound, if any, may be silent).
- **The to-do source is "latest state", not "every change".** It is a key-value source: the platform
  guarantees the most recent state reaches the reaction, not every intermediate one, and a reaction that
  starts later does not see earlier history. The feed is therefore an *activity* record, not an audit
  log, and says so (FR-004).
- **No new dependency is expected.**

## Open interop questions *(recorded because they shape scope; resolved in planning, by measurement)*

Stated as questions with a decided fallback each, in the project's established style.

- **Q-A — Can a Consumer be authored in Scala?** It is declared by an annotation carrying a class and
  returns effects built by the base class. Capability 11's Scala View already consumed a key-value
  entity's state through such an annotation, so this is *predicted* clean — but capability 15 showed one
  family can split across the wall by operation. *Fallback*: confine Java to the one class that needs it.
- **Q-B — What arrives in the handler, and through which serializer?** A consumer receives what the source
  serialized with the SDK's **internal** mapper (README §3). Consuming an existing Java-authored type is
  one case; whether this capability can reach the question of a **Scala-authored event type** at all,
  without authoring an event-sourced entity (candidate A3, out of scope), is to be stated either way.
- **Q-C — What can the reaction do from Scala?** Calling an agent is Scala-clean; writing to an entity is
  method-reference-only; **producing to a topic** is untested. *Fallback*: the feed is in-process and the
  publication is the effect under test.
- **Q-D — Is redelivery of a failing message really unbounded, and does it block later messages?**
  The docs say it is redelivered "until the application processes it without failures". Measure the count,
  the spacing, and whether later messages for the same source wait behind it. *Fallback*: the reaction
  bounds its own attempts and sets the message aside (FR-006/007).
- **Q-E — Can duplicates and restarts be produced deliberately in a test?** At-least-once delivery is
  documented; a duplicate is needed to prove FR-005, and a restart to answer FR-015. *Fallback*: prove
  idempotence directly on the recording rule, and measure restart behaviour live.
- **Q-F — What does the test toolkit prove, and what does it not?** It offers mocked incoming and outgoing
  message channels. Establish whether they exercise the real consumer path or bypass it, and the suite cost.
- **Q-G — Can a Scala reaction subscribe to capability 7's delegation tasks?** Those tasks live in a
  component the **runtime** owns, not this service. Capability 13 found the SDK's own documentation
  consuming exactly that component, and that `dynamicCall` reaches runtime-owned *agents* — but whether a
  reaction can subscribe to a runtime-owned *event stream* is untested. It would supply what capability 7
  cannot: which specialists were *actually* consulted, instead of what the model reports. *Fallback*: the
  to-do source alone (FR-001), and the result — reachable or not, and why — recorded as a finding either
  way. **If reachable, adding it is a user checkpoint, not an automatic scope increase.**
