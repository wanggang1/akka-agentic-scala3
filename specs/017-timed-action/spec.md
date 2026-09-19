# Feature Specification: Scheduled reminders (Timed Action)

**Feature Branch**: `017-timed-action`
**Created**: 2026-09-18
**Status**: Draft
**Input**: User description: "Capability 15 — Timed Action (scheduled work) … candidate A1 … one of the four SDK component families this project has never built, and the one whose interop outcome nothing in capabilities 1–14 predicts."

## Why this capability exists

Everything this service does today happens **because a caller asked for it, now**. Fourteen capabilities
in, nothing is scheduled: no reminder, no expiry, no deadline, no retry-later. A **Timed Action** is the
SDK's answer to "do this in five minutes", and it is one of only four component families the project has
never built.

It is also the candidate whose interop outcome was least predictable — which is why it was picked first.
An early reading of the SDK already shows an **asymmetry no previous capability has met**: the API that
*schedules* work is keyed on a Java method reference, while the API that *cancels* it is keyed on a
plain string. If that holds, this family lands on **both sides of the method-reference wall at once**,
for a reason different from capability 14's (there, one client had two kinds of method; here, two
different APIs govern two halves of one feature).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ask for something later, and get it (Priority: P1)

A caller schedules a reminder with a note and a delay. Nothing happens immediately. When the delay has
passed, the reminder **fires**, and the caller can see that it fired and what it said.

**Why this priority**: This is the capability. Without it there is nothing to cancel, nothing to
observe, and no finding.

**Independent Test**: Schedule a reminder with a short delay, observe it is pending, wait past the
delay, and observe it has fired with the note intact. Delivers the whole user-visible value alone.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a caller schedules a reminder with a note and a delay,
   **Then** they receive a handle for it and the reminder reads as **pending**.
2. **Given** a reminder was scheduled, **When** the delay has elapsed, **Then** the reminder reads as
   **fired** and carries the note it was given.
3. **Given** a reminder was scheduled, **When** the delay has *not* yet elapsed, **Then** it still reads
   as pending — firing early would be as wrong as never firing.
4. **Given** a blank note or a missing/implausible delay, **When** it is submitted, **Then** it is
   rejected and nothing is scheduled.

---

### User Story 2 - Change your mind before it fires (Priority: P2)

A caller cancels a reminder that has not fired yet. It never fires.

**Why this priority**: Scheduling without cancelling is half a feature — anything that can be booked
must be cancellable. It is also where the interop asymmetry shows itself, since cancelling and
scheduling are governed by different APIs.

**Independent Test**: Schedule a reminder, cancel it, wait past its delay, and confirm it never fired.

**Acceptance Scenarios**:

1. **Given** a pending reminder, **When** the caller cancels it, **Then** it reads as **cancelled** and
   does not fire when its delay elapses.
2. **Given** a reminder that already fired, **When** the caller tries to cancel it, **Then** the outcome
   is unambiguous — cancelling a thing that already happened must not report success.
3. **Given** an unknown handle, **When** the caller cancels it, **Then** they are told it is unknown
   rather than being told it was cancelled.

---

### User Story 3 - A reminder that goes wrong does not loop for ever (Priority: P2)

When the work a reminder triggers fails, the system retries a **bounded** number of times and then
stops. It never retries indefinitely.

**Why this priority**: The SDK's own guidance warns that a failing timed action can reschedule for ever.
A capability that can spin unattended is worse than one that does nothing, so this is a requirement of
shipping it at all — not a nice-to-have.

**Independent Test**: Make the triggered work fail, and confirm that retries stop after a bounded count
and that the reminder ends in a state a caller can see.

**Acceptance Scenarios**:

1. **Given** a reminder whose work fails, **When** it fires, **Then** it is retried a bounded number of
   times and then stops.
2. **Given** retries are exhausted, **When** the caller reads the reminder, **Then** it does not claim to
   have succeeded.

---

### User Story 4 - The interop question is answered in public (Priority: P3)

A developer reading the repository learns whether scheduled work can be **scheduled**, **cancelled** and
**performed** from Scala on this Java-first SDK — and, where any part cannot, exactly which part and why.

**Why this priority**: The feature is usable without it, but this is the project's stated purpose, and
this capability tests a family none of the previous fourteen touched.

**Independent Test**: Read the project's findings, README and roadmap; the verdict, its evidence and its
boundaries are stated without reading the source.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a reader consults the findings, **Then** they learn
   which halves of scheduling are Scala-clean and which are not, with the evidence.
2. **Given** any part had to be written in Java, **When** a reader consults the findings, **Then** they
   learn which part, the mechanism that forced it, and how far it spreads.

---

### Edge Cases

- **The same handle scheduled twice.** Whether the second schedule replaces the first, is refused, or
  produces two firings must be decided and stated, not discovered by a caller.
- **A reminder cancelled while it is firing.** The window between "due" and "done" is real; the outcome
  must not be a lie in either direction.
- **A delay of zero or a negative delay.** Rejected as invalid input, not scheduled as "immediately".
- **A very long delay.** The capability must state what it supports rather than silently accepting a
  delay it cannot honour.
- **Restart.** Whether a pending reminder survives the service restarting is a property callers will
  assume one way or the other; it must be measured and documented rather than left implicit.
- **Retries and the note.** A retried firing must not duplicate its visible effect in a way that makes a
  caller think two reminders fired.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A caller MUST be able to schedule a reminder carrying a short note and a delay, and receive
  a handle that identifies it.
- **FR-002**: A scheduled reminder MUST NOT take effect before its delay has elapsed, and MUST take
  effect after it has.
- **FR-003**: A caller MUST be able to read a reminder by its handle and see which state it is in:
  pending, fired, cancelled, or failed.
- **FR-004**: A fired reminder MUST carry the note it was scheduled with, unaltered.
- **FR-005**: A caller MUST be able to cancel a reminder that has not fired, after which it MUST NOT fire.
- **FR-006**: Cancelling a reminder that has already fired, or one that does not exist, MUST be reported
  distinguishably from a successful cancellation.
- **FR-007**: Invalid input — a blank note, a missing delay, a zero or negative delay — MUST be rejected
  before anything is scheduled.
- **FR-008**: Work triggered by a reminder that fails MUST be retried a **bounded** number of times and
  then stop. Unbounded retrying is a defect, not a configuration choice.
- **FR-009**: The capability MUST state whether a pending reminder survives a service restart, based on
  measurement rather than assumption.
- **FR-010**: No existing capability's production sources or tests may be modified. Where this capability
  needs behaviour an existing component lacks, it MUST introduce its own.
- **FR-011**: The capability MUST be verifiable without a live model and without network access. Where a
  behaviour can only be observed by waiting real time, the wait MUST be small enough to keep the test
  suite fast, and the constraint MUST be stated.
- **FR-012**: The capability MUST publish its interop verdict — which parts of scheduling are expressible
  in the project's primary language, the deciding mechanism, and the precise boundary of any part that
  must be Java — in the project's findings, README and roadmap.
- **FR-013**: If a part cannot be written in the primary language, the **attempt and its failure mode**
  MUST be recorded as evidence alongside the working version, and the non-primary-language part MUST be
  confined to the smallest unit that works.
- **FR-014**: Any component introduced MUST be registered so the runtime discovers it, consistent with
  how this project registers components.

### Key Entities

- **Reminder**: what was asked for — a note, a delay, and a handle. Immutable once scheduled.
- **Reminder state**: pending, fired, cancelled, or failed. The four outcomes FR-003 and FR-006 rest on;
  collapsing any two of them would mislead a caller.
- **Note**: the caller's text, carried through firing unchanged (FR-004). Validated before scheduling.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reminder scheduled with a delay is observably pending before that delay elapses and
  observably fired after it — demonstrated, not argued.
- **SC-002**: A fired reminder carries its original note exactly.
- **SC-003**: A cancelled reminder never fires, proven by waiting past its delay.
- **SC-004**: Pending, fired, cancelled and failed are four states a caller can tell apart.
- **SC-005**: Invalid input is rejected with nothing scheduled.
- **SC-006**: A reminder whose work always fails stops retrying after a bounded count, shown by count
  rather than by inspection.
- **SC-007**: Every existing capability's production sources and tests are unchanged, provable by
  inspecting the change set.
- **SC-008**: The project's findings, README and roadmap state the scheduling interop verdict with its
  evidence and its boundary, including which part — if any — had to be Java.
- **SC-009**: The capability's behaviour is proven without a live model, and the full test suite remains
  fast enough to run routinely; any unavoidable real-time waiting is stated with its duration.

## Assumptions

- **The subject is a reminder surface of its own** — scheduled, readable, cancellable — rather than a
  scheduling feature bolted onto an existing capability. Capability 5's approval gate and capability 6's
  to-dos are deliberately untouched (FR-010).
- **No model is involved.** A reminder does not need a language model to carry a note, and leaving one
  out keeps the whole capability deterministic — the shape capability 11 used to good effect. Having the
  fired reminder call an agent is a recorded fork, not scope.
- **Delays are short by design.** This is a sandbox capability: seconds, not days. It exists to prove the
  mechanism, and long-horizon scheduling raises operational questions (clock skew, redeploys) that are
  out of scope.
- **The note is text.** No attachments, no structured payload.
- **No new dependency is expected**; if one proves necessary it must be justified.
- **Governance and evaluation are out of scope** — this capability configures no guardrails and is judged
  by nothing.

## Open interop questions *(recorded here because they shape scope; resolved in planning)*

Stated as questions with a decided fallback each, in the project's established style.

- **Q-A — Can the reminder be *scheduled* from the primary language?** An early jar reading says the
  scheduling API takes a **Java method reference** (`TimedActionClient` exposes only `method(...)`, with
  no id-keyed form), which would force the scheduling caller into Java. *Fallback if so*: confine Java
  to that one class, as capability 11 and 14 did, and keep the failed attempt as evidence (FR-013).
- **Q-B — Can it be *cancelled* from the primary language?** The cancelling API appears to be keyed on a
  plain **string** name, which would make cancellation Scala-clean. If both readings hold, **one family
  sits on both sides of the wall** — a new shape, and the headline.
- **Q-C — Is a pending reminder durable?** Unknown whether a scheduled timer survives a restart the way
  capabilities 3 and 5's tasks do. *Fallback if not*: FR-009 is satisfied by documenting the limit.
- **Q-D — What is the real retry contract?** The scheduling API appears to accept a **retry count**, and
  the SDK's guidance warns about infinite rescheduling. The actual behaviour on failure must be measured
  before FR-008 can be claimed. *Fallback*: bound it explicitly rather than relying on a default.
- **Q-E — Can firing be observed offline without slow tests?** The testing toolkit appears to invoke a
  timed action **directly**, which would prove the action's behaviour but not that the timer fired. If
  proving the timer requires real waiting, delays must stay small and the cost stated (FR-011).
