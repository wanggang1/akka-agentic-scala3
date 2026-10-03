# Feature Specification: Event-sourced wallet (Event Sourced Entity)

**Feature Branch**: `020-event-sourced-wallet`
**Created**: 2026-10-03
**Status**: Draft
**Input**: User description: "Capability 18 (A3) — an event-sourced Wallet entity authored in Scala, probing whether Scala 3 sum types (enum or sealed trait) with @TypeName survive the SDK's internal Jackson mapper as an event hierarchy, and whether snapshots of Scala-authored state round-trip … one of the two SDK component families this project has never built."

## Why this capability exists

Every durable thing this service keeps today it keeps as **current state**. Capability 6's to-do lists are
a **key-value** entity: the SDK stores the latest value and the history that produced it is the runtime's,
not ours (`docs/akka-persistence-models.md`). Seventeen capabilities in, the project has **never written an
event-sourced entity** — the persistence model where the record *is* the sequence of events and the state is
a fold over them. It is one of only two SDK component families the project has never built.

It is also the sharper of the two remaining interop bets. An event-sourced entity forces a **sealed event
hierarchy** across the SDK's internal Jackson mapper — the same mapper that, since capability 3, has refused
Scala-shaped component payloads and made every on-the-wire type stay Java-shaped (§3). Scala 3's natural way
to write "one of these four events" is an **`enum`** or a **sealed trait of case classes**; nothing in
capabilities 1–17 has ever asked whether either of those **sum types** can be the event type the mapper
(de)serializes and dispatches on by `@TypeName`. The honest possibilities span the full range: the `enum`
works; only a sealed trait of case classes works; or events must be **Java-authored** outright. A second,
quieter question rides with it — whether a **snapshot** of Scala-authored *state* round-trips through the
same mapper when the SDK folds the journal from a snapshot rather than from event zero.

Two known walls are expected to hold and are not the finding: the entity **command client** is method-
reference-only with no `dynamicCall` (capability 6 measured this), so the caller — the endpoint — is expected
to be **Java**; and whatever crosses the mapper must be at least Java-*shaped*. The new knowledge this
capability is built to produce is narrower and unmeasured: **can a Scala 3 sum type be the event hierarchy at
all**, and does Scala-authored snapshot state survive the fold.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A wallet records its history and reports its balance (Priority: P1)

A caller opens a wallet, deposits and withdraws amounts against it, and reads its balance. The balance is
always the sum of what was recorded, in order — because the wallet is reconstructed from its events, not
from a stored number.

**Why this priority**: This is the capability. Without a wallet that records events and folds them into a
balance there is no event-sourced entity to probe, no snapshot to exercise, and no finding.

**Independent Test**: Open a wallet, deposit twice and withdraw once, then read the balance and confirm it
equals the signed sum of those three recorded events — and that each produced exactly one event.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a caller opens a wallet with a starting balance, **Then** the
   wallet reads as open with that balance and the open was recorded as an event.
2. **Given** an open wallet, **When** a caller deposits an amount, **Then** the balance increases by exactly
   that amount and a deposit event is recorded.
3. **Given** an open wallet with sufficient funds, **When** a caller withdraws an amount, **Then** the
   balance decreases by exactly that amount and a withdrawal event is recorded.
4. **Given** a wallet that has received a sequence of operations, **When** its balance is read, **Then** it
   equals the signed sum of the recorded events, regardless of how the caller reached it.

---

### User Story 2 - The wallet refuses what the rules forbid, and records nothing when it does (Priority: P1)

A caller attempts an operation the domain forbids — overdrawing, a non-positive amount, operating on a
closed wallet, re-opening an open one. The attempt is rejected, the balance is unchanged, and **no event is
recorded** for the rejected attempt.

**Why this priority**: An event-sourced entity whose rejected commands still left events would corrupt its
own history — every future fold would replay the mistake. That a rejected command persists **nothing** is
the core correctness property of the model, not a nicety.

**Independent Test**: Against an open wallet with a known balance, attempt each forbidden operation; confirm
each is rejected, the balance is unchanged, and the event count did not grow.

**Acceptance Scenarios**:

1. **Given** an open wallet, **When** a caller withdraws more than the balance, **Then** it is rejected, the
   balance is unchanged, and no event is recorded.
2. **Given** an open wallet, **When** a caller deposits or withdraws a zero or negative amount, **Then** it
   is rejected and no event is recorded.
3. **Given** a closed wallet, **When** a caller deposits, withdraws or closes it again, **Then** it is
   rejected and no event is recorded.
4. **Given** an already-open wallet, **When** a caller opens it again, **Then** it is rejected and no event
   is recorded.

---

### User Story 3 - A wallet reconstituted from a snapshot is identical to one replayed from zero (Priority: P2)

A wallet that has accumulated enough events to be snapshotted, then is reloaded, reports the same balance and
state it would have had if every event had been replayed from the beginning.

**Why this priority**: Snapshots are the optimisation that makes event sourcing affordable, and they are the
second interop question — whether Scala-authored state survives the mapper when the fold starts from a
snapshot. A wrong snapshot is invisible until a reload, which is exactly why it must be exercised on purpose.

**Independent Test**: Drive a wallet past the configured snapshot threshold, force a reload, and confirm the
reloaded balance equals the independently computed signed sum — the snapshot took, and it took correctly.

**Acceptance Scenarios**:

1. **Given** a wallet with more events than the snapshot threshold, **When** it is reloaded, **Then** its
   balance and open/closed state match the full replay exactly.
2. **Given** a snapshot has been taken, **When** further events are recorded and the wallet is reloaded,
   **Then** the balance reflects the snapshot **plus** the later events, with nothing double-counted or lost.

---

### User Story 4 - The interop question is answered in public (Priority: P3)

A developer reading the repository learns whether an event-sourced entity's **event hierarchy** can be
authored as a Scala 3 sum type on this Java-first SDK, whether Scala-authored **snapshot state** round-trips,
and — where any part cannot be Scala — exactly which part, the mechanism that forced it, and how far it
spreads.

**Why this priority**: The wallet is usable without this, but publishing the measured verdict is the
project's stated purpose, and this capability tests the persistence model none of the previous seventeen
wrote.

**Independent Test**: Read the project's findings, README and roadmap; the verdict — which of `enum` /
sealed trait / Java-authored the events ended up as, why, and the snapshot result — is stated with its
evidence and without reading the source.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a reader consults the findings, **Then** they learn which
   Scala 3 sum-type form the event hierarchy took (or that events had to be Java-authored), with the
   evidence that settled it.
2. **Given** any part had to be written in Java, **When** a reader consults the findings, **Then** they learn
   which part, the mechanism that forced it, and the precise boundary of its spread.
3. **Given** forms were tried and rejected, **When** a reader consults the findings, **Then** the attempt and
   its failure mode are recorded alongside the working version, not silently dropped.

---

### Edge Cases

- **A command for a wallet that was never opened.** Depositing into or reading a wallet that has no events
  yet must be a defined outcome (an empty/default state or a clear rejection), not an undefined one.
- **Exact-balance withdrawal.** Withdrawing the entire balance is allowed and leaves a zero balance; it must
  not be confused with overdrawing.
- **Close with a non-zero balance.** Whether closing is permitted with funds remaining must be decided and
  stated, not discovered by a caller.
- **Reload with zero events.** A wallet reloaded before any event was recorded must report its empty state,
  not fail.
- **Snapshot boundary.** A wallet reloaded at exactly the snapshot threshold must be identical to one
  reloaded one event before or after it — the optimisation must be invisible to the result.
- **A rejected command must not advance history.** The event count after a rejected command must equal the
  count before it — the property US2 rests on, checked directly.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A caller MUST be able to open a wallet with a starting balance and receive a handle that
  identifies it.
- **FR-002**: A caller MUST be able to deposit and withdraw amounts against an open wallet, and each accepted
  operation MUST be recorded as a distinct event.
- **FR-003**: A caller MUST be able to read a wallet by its handle and see its current balance and whether it
  is open or closed.
- **FR-004**: The balance a caller reads MUST equal the signed sum of the wallet's recorded events — the
  state MUST be a fold over the journal, never a separately stored number.
- **FR-005**: A withdrawal that would overdraw the wallet MUST be rejected, and MUST record no event.
- **FR-006**: A deposit or withdrawal of a zero or negative amount MUST be rejected, and MUST record no event.
- **FR-007**: Any operation on a closed wallet, and re-opening an open wallet, MUST be rejected, and MUST
  record no event.
- **FR-008**: A caller MUST be able to close an open wallet, after which it accepts no further operations.
- **FR-009**: The business rules in FR-004 through FR-008 MUST live in the pure domain model, free of SDK
  types, so they are unit-testable in isolation; the entity chooses effects, the domain decides legality.
- **FR-010**: The entity MUST take snapshots after a configured number of events, and a wallet reconstituted
  from a snapshot MUST be identical to one replayed in full. The threshold MUST be set low enough to be
  exercised by a test of practical length.
- **FR-011**: No existing capability's production sources or tests may be modified. Where this capability
  needs behaviour an existing component lacks, it MUST introduce its own.
- **FR-012**: The capability MUST be verifiable without a live model and without network access.
- **FR-013**: The capability MUST publish its interop verdict — which Scala 3 sum-type form the event
  hierarchy took (or whether events had to be Java-authored), whether Scala-authored snapshot state
  round-trips, the deciding mechanism, and the precise boundary of any part that must be Java — in the
  project's findings, README and roadmap.
- **FR-014**: If a part cannot be written in the primary language, the **attempt and its failure mode** MUST
  be recorded as evidence alongside the working version, and the non-primary-language part MUST be confined
  to the smallest unit that works.
- **FR-015**: Any component introduced MUST be registered so the runtime discovers it, consistent with how
  this project registers components (the hand-maintained Scala descriptor; a new `event-sourced-entity` key).

### Key Entities

- **Wallet**: the state — a balance and whether it is open. Immutable; reconstructed by folding its events.
  Never stored as a bare current value (FR-004).
- **Wallet event**: one of opened, deposited, withdrawn, closed. The sealed hierarchy whose Scala 3 form is
  the capability's central interop question. Each carries a `@TypeName`.
- **Amount**: a positive quantity carried by deposit/withdraw. Validated in the domain before any event is
  recorded (FR-006).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A wallet driven through a sequence of opens, deposits and withdrawals reports a balance equal
  to the independently computed signed sum of those operations — demonstrated, not argued.
- **SC-002**: Each accepted operation produces exactly one event; the event count equals the number of
  accepted operations.
- **SC-003**: Every forbidden operation (overdraw, non-positive amount, operation on a closed wallet,
  re-open) is rejected with the balance unchanged and the event count not advanced — shown by count.
- **SC-004**: A wallet reloaded after passing the snapshot threshold reports the same balance and state as a
  full replay, including when further events follow the snapshot.
- **SC-005**: The domain rules are proven by unit tests that construct no SDK types.
- **SC-006**: Every existing capability's production sources and tests are unchanged, provable by inspecting
  the change set.
- **SC-007**: The project's findings, README and roadmap state the event-hierarchy interop verdict and the
  snapshot result with their evidence and boundaries, including which part — if any — had to be Java.
- **SC-008**: The capability's behaviour is proven without a live model, and the full test suite remains fast
  enough to run routinely.

## Assumptions

- **The subject is a wallet of its own** — opened, operated, read, closed — not a ledger feature bolted onto
  an existing capability. Capability 6's to-dos are deliberately untouched (FR-011).
- **No model is involved.** A ledger needs no language model, and leaving one out keeps the whole capability
  deterministic — the shape capabilities 11, 15 and 16 used to good effect.
- **Amounts are whole units.** Integer minor-units (e.g. cents), not floating-point money; currency and
  rounding are out of scope.
- **A single currency, no transfers.** One wallet, one balance; moving funds between wallets is a recorded
  fork, not scope — it would reintroduce the Workflow wall capability 4 already mapped.
- **Balances are non-negative.** Overdraft/credit is out of scope (FR-005); a wallet never goes below zero.
- **No new dependency is expected**; if one proves necessary it must be justified.
- **Governance and evaluation are out of scope** — this capability configures no guardrails and is judged by
  nothing.

## Open interop questions *(recorded here because they shape scope; resolved in planning, by measurement)*

Stated as questions with a decided fallback each, in the project's established style.

- **Q-A — Can the event hierarchy be authored as a Scala 3 `enum`?** The sharp bet. The internal mapper must
  (de)serialize the event type and dispatch on `@TypeName`; whether it can bind those to an `enum`'s cases is
  unmeasured. *Fallback if not*: a **sealed trait of case classes** (Q-B); failing that, **Java-authored**
  events, with the `enum` attempt and its failure mode kept as evidence (FR-014).
- **Q-B — Does a sealed trait of case classes cross the mapper where the `enum` does not?** The intermediate
  form. If this works and the `enum` does not, the finding is precise about *which* Scala 3 sum type the
  mapper accepts. *Fallback*: Java-authored events.
- **Q-C — Does Scala-authored snapshot state round-trip?** Whether the mapper that (de)serializes events also
  correctly (de)serializes the `Wallet` *state* when the SDK snapshots and later reloads from it. *Fallback
  if not*: Java-shaped state, recorded as the boundary.
- **Q-D — Is the entity command client method-reference-only?** Capability 6 found the event-sourced-entity
  client exposes no `dynamicCall`, which would force the querying/commanding caller — the endpoint — into
  Java (capability 11's shape). *Fallback if so*: confine Java to that one class and keep the failed Scala
  attempt as evidence (FR-014).
- **Q-E — Can the entity be driven from Scala in a unit test?** `EventSourcedTestKit.of(Entity::new)` takes a
  constructor reference; whether a Scala constructor function satisfies it offline is unmeasured. *Fallback
  if not*: exercise the entity through an integration test, and the domain through pure unit tests (FR-009).
