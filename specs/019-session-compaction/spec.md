# Feature Specification: Session compaction

**Feature Branch**: `019-session-compaction`
**Created**: 2026-09-26
**Status**: Draft
**Input**: Capability 17 — session compaction, closing capability 6's known defect (ROADMAP fork **B1**).

## Why this capability exists

Every capability so far **added** a surface. This one **closes a defect that is in the repository today**,
and it is the only remaining candidate that does.

Capability 6's assistant keeps its **entire** session history, deliberately. The SDK's
`MemoryProvider…readLast(N)` window is a naive "keep the last N messages" trim, and once a *tool-using*
session exceeds N it cuts between a tool call and its response. The model provider then rejects the
assembled request — reported, misleadingly, as `argument "content" is null`. That was capability 6's real
live failure, proven by removing `readLast`, and the fix was to keep **full history** and accept the cost.

The cost is **unbounded token growth**: every turn of a long conversation re-sends every earlier turn, so
a session gets steadily slower and more expensive until it is abandoned. Capability 6 recorded compaction
as the proper bound and deferred it.

Compaction is the right answer precisely because it **summarises instead of slicing**. A summary is one
user message and one AI message of ordinary prose, so there is no tool-call pair left to orphan — the
mechanism that broke capability 6 cannot occur. Bounding the history and keeping tool-using sessions
working are the same act, not a trade.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A long conversation stops growing without bound (Priority: P1)

Someone holds a long conversation with their assistant. Today every turn carries the whole history, so the
conversation gets more expensive the longer it is useful. With compaction, once the stored history passes a
configured size it is replaced by a short summary, and the next turn costs a bounded amount again.

**Why this priority**: this *is* the defect. Without it there is no capability — the remaining stories make
it safe and operable, but this one is the reason to build anything.

**Independent Test**: drive one session past the threshold and observe that the stored history is
measurably smaller afterwards, and that the next turn still answers. Fully offline — the sizes are
observable without a model.

**Acceptance Scenarios**:

1. **Given** a session whose stored history is below the threshold, **When** another turn is taken,
   **Then** the history grows and is **not** compacted.
2. **Given** a session whose stored history has just passed the threshold, **When** compaction runs,
   **Then** the stored history is replaced by a summary and is smaller than before.
3. **Given** a session that has just been compacted, **When** the user takes another turn,
   **Then** the turn succeeds and the reply is a normal reply.

---

### User Story 2 - What the conversation established survives being compacted (Priority: P1)

Compaction must not amount to forgetting. A user who told their assistant something early on, or asked it
to do something whose result matters, keeps getting answers that reflect it after the history has been
summarised.

**Why this priority**: a bound that loses the conversation is not a fix, it is a regression. Equal in
priority to US1 because shipping US1 alone would trade one defect for a worse one.

**Independent Test**: assert on the *content* of the compacted history — that it still carries the facts
and tool outcomes established before compaction. That much is offline. Whether the **model** then uses
them is a separate claim, and capabilities 4 and 6 both measured that a mocked model is fed only the
current turn — so *recall through the model* is a **live** criterion and is labelled as one, not asserted
offline.

**Acceptance Scenarios**:

1. **Given** a session in which a fact was established several turns ago, **When** the history is
   compacted, **Then** the summary still contains that fact.
2. **Given** a session in which a tool was called and returned a result, **When** the history is
   compacted, **Then** the summary carries the **substance** of that result as ordinary prose.
3. **Given** a compacted session, **When** the user asks about something established before compaction,
   **Then** the assistant's answer reflects it *(live criterion)*.

---

### User Story 3 - Compaction cannot break a turn, and cannot recreate capability 6's defect (Priority: P1)

Compaction runs because the history got large, which is a condition of success, not of user intent. It
must therefore be invisible when it works and harmless when it fails: a user mid-conversation never sees
an error because the system was busy summarising, and the summarised history is never left in a shape the
model provider will reject.

**Why this priority**: capability 6's original failure was exactly this class — a memory mechanism leaving
history in a shape that broke every later turn, reported as something else entirely. Re-introducing it
while fixing it would be the worst outcome available.

**Independent Test**: force summarisation to fail and assert the turn still returns a normal reply and the
history is left as it was; and assert directly on the stored messages that no compacted history contains a
tool-call request without its matching response. Both offline.

**Acceptance Scenarios**:

1. **Given** summarisation fails (the model errors, times out, or returns something unusable),
   **When** a user takes a turn, **Then** the turn succeeds and the history is unchanged.
2. **Given** any compacted session, **When** its stored messages are inspected,
   **Then** there is no tool-call request without its response, and no response without its request.
3. **Given** a turn arrives while compaction is in flight for the same session, **When** both complete,
   **Then** no message the user sent is silently discarded.

---

### User Story 4 - An operator can set the bound, or turn it off, without a rebuild (Priority: P2)

Whoever runs the service decides how much history is worth paying for, and can switch compaction off
entirely — to reproduce a problem, or because a deployment would rather pay for full history.

**Why this priority**: valuable and cheap, but the capability is already worth having with a fixed
default. Every earlier capability that shipped a bound made it configurable, so this is consistency as
much as function.

**Independent Test**: run the same session past the same point with two different configured thresholds and
with compaction disabled, and observe three different outcomes. Offline.

**Acceptance Scenarios**:

1. **Given** a configured threshold, **When** the service starts with a different one, **Then** compaction
   triggers at the new point with no code change.
2. **Given** compaction is disabled by configuration, **When** a session grows past any threshold,
   **Then** nothing is compacted and behaviour matches capability 6 as it is today.
3. **Given** a configured value outside the permitted range, **When** the service handles a request,
   **Then** the failure is reported as the **server's** fault, not as the caller's *(capability 15's §7d
   distinction)*.

---

### User Story 5 - The interop question is answered in public (Priority: P3)

A reader of this repository can find out what this capability proved about writing Akka components in
Scala 3, in the same places every earlier capability recorded it, with measurements rather than claims.

**Why this priority**: the interop finding is this project's actual product and carries equal weight to
the feature — but it is written **after** the measurements exist, so it is last in sequence, not last in
importance.

**Independent Test**: README §19, FINDINGS and the ROADMAP row state the outcome; a test pins the size of
the Java quarantine so that growth becomes a recorded finding rather than silent drift.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a reader opens README's interop notes, **Then** §19
   states which classes are Java, which are Scala, and the measured reason for each.
2. **Given** a future change adds a Java class to this capability, **When** the suite runs, **Then** a
   test fails and names the drift.

---

### Edge Cases

- **A turn arrives while compaction is running for the same session.** The history read for summarising is
  already stale when the summary is written. The newer messages must not be discarded.
- **Two compactions are triggered for one session at once** (two events cross the threshold in quick
  succession). The second must not undo the first or double-summarise.
- **A session that is already just a summary.** Repeated compaction must stay bounded rather than
  summarising summaries of summaries until meaning is gone.
- **A history that is entirely tool traffic** with little user text — the summary still has to be
  well-formed.
- **Summarisation returns unusable output** (not the expected shape, or empty).
- **Summarisation is slow.** A long summarisation must not hold up the user's next turn.
- **The history is below the threshold but a single turn is enormous** — one very large message.
- **Sessions belonging to other capabilities.** Session memory is shared infrastructure: capability 4's
  chat and capability 14's streamed chat store history in the same place as capability 6's assistant.
  Compaction applies to **all** of them (FR-014), so each of their surfaces must keep working unchanged —
  including capability 14's *streamed* turn, where a summary is written to the same history a stream is
  assembling into.
- **A session is deleted while compaction is in flight.**

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When a session's stored history exceeds a configured size, the system MUST replace that
  history with a summary of it.
- **FR-002**: The summary MUST preserve what the conversation established, including the substance of tool
  calls and their results, expressed as ordinary prose rather than retained as structured tool calls.
- **FR-003**: A compacted history MUST NOT contain a tool-call request without its matching response, nor
  a response without its request — the shape that broke capability 6 MUST be unreachable by construction.
- **FR-004**: A session whose history is below the configured size MUST NOT be compacted.
- **FR-005**: Compaction MUST NOT cause a user's turn to fail. If summarising fails for any reason, the
  session MUST remain usable and its history MUST be left exactly as it was.
- **FR-006**: Capability 6's HTTP contract MUST be unchanged — same request shape, same response shape, no
  new required field, and no new failure mode visible to its callers.
- **FR-007**: The size threshold MUST be configurable without recompiling, and compaction MUST be able to
  be disabled entirely by configuration.
- **FR-008**: Compaction MUST be safe against concurrent updates: if a session's history changed between
  being read for summarising and being replaced, the replacement MUST NOT silently discard the newer
  messages.
- **FR-009**: Repeated compaction of one session MUST stay bounded — a long-lived session MUST NOT
  accumulate summaries of summaries without limit.
- **FR-010**: The system MUST NOT use the SDK's `readLast(N)` history window anywhere, in any capability.
- **FR-011**: Whether a session has been compacted, how many times, and the size of its history before and
  after MUST be observable without reading the service log.
- **FR-012**: The capability MUST be verifiable offline with no model, mocked or live. Any behaviour that
  can only be demonstrated with a live model MUST be stated as such rather than asserted offline.
- **FR-013**: The language boundary MUST be recorded with its measured cause, and the size of any Java
  quarantine MUST be pinned by a test, as capabilities 14, 15 and 16 do.
- **FR-014**: Compaction MUST apply to **every session in the service**, not only capability 6's.
  Session memory is shared infrastructure — capability 4's chat and capability 14's streamed chat store
  history in the same runtime-owned place — so this is a decision, not a side effect: **compaction is a
  property of the service's memory, not of one agent**. Consequences that MUST be honoured rather than
  discovered: capabilities 4 and 14 gain a bounded history without being modified, their behaviour under a
  long session changes, and their existing tests MUST still pass against a memory that can compact.
- **FR-015**: Capability 6 MUST be modified as little as possible; any change to it MUST be justified by a
  requirement here and called out, as capability 13's one-line change to capability 8 was.

### Key Entities

- **Session history**: the stored record of one conversation, keyed by session id. Owned by the **runtime**,
  not by this capability — this capability reads and replaces it, it does not define it.
- **Summary**: the single user message and single AI message that replace a compacted history. Prose, with
  no structured tool calls.
- **Threshold**: the configured history size past which a session is summarised; also the switch that
  disables compaction.
- **Compaction record**: what is observable about a session afterwards — that it was compacted, how many
  times, and the size before and after.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A session driven past the threshold has a **smaller** stored history afterwards, and its next
  turn still succeeds. Verified offline, with no model.
- **SC-002**: A fact established before compaction is still present in the compacted history. Verified
  offline; the assistant's *use* of it is a **live** criterion, labelled as one.
- **SC-003**: No compacted history contains an orphaned tool-call pair, asserted directly on the stored
  messages. This is the criterion that says capability 6's defect cannot recur.
- **SC-004**: A turn taken while summarisation is failing returns a normal reply, and the history is
  unchanged.
- **SC-005**: Capability 6's request and response shapes are unchanged, demonstrated rather than asserted.
- **SC-006**: The same session reaches compaction at two different points under two different configured
  thresholds, and never under the disabled setting — all three without a rebuild.
- **SC-007**: A session compacted repeatedly stays bounded: after many compaction cycles its stored history
  is still no larger than a stated multiple of the threshold.
- **SC-008**: The whole capability is verified with **no model call**, live or mocked, outside of the
  summariser itself — and the summariser is mocked.
- **SC-009**: Capabilities 4, 6 and 14 all still pass their existing tests unmodified against a memory
  that can compact, and a session from **each** of the three is shown to compact — the service-wide scope
  of FR-014 demonstrated rather than assumed.

## Assumptions

- **The threshold is measured in bytes of stored history**, because the SDK reports history size in bytes
  on the event that would trigger compaction. A message count would need the history to be read first.
- **Summarisation uses a model.** It is the only model in this capability, it is mocked in every test, and
  the *quality* of a summary is not asserted — only its shape and the presence of what it must preserve. A
  summary from a live model is not deterministic, so no test may depend on its wording.
- **Recall across compaction is live-only.** Capabilities 4 and 6 both measured that a mocked model is fed
  only the current turn, so the mock cannot show the model *using* a summary. Stated, not worked around.
- **Compaction replaces; it does not archive.** The turns that were summarised are not recoverable
  afterwards. Session history is a working context, not an audit log — if an audit trail were wanted it
  would be a different capability.
- **Observability is a small read-only surface**, matching every other capability, rather than log output —
  because FR-011 has to be testable and SC-001 has to be measurable.
- **No new dependency.** The SDK documents this whole mechanism; nothing here needs anything the project
  does not already have.
- **Capability 6 remains the only write path** for to-dos, and its assistant remains the subject. This
  capability adds no conversational surface of its own.

## Open interop questions *(recorded because they shape scope; resolved in planning, by measurement)*

- **Q-A — Can a Scala consumer subscribe to the runtime-owned session memory entity's events?**
  Capability 16 measured that a Scala consumer *can* read the runtime-owned `TaskEntity`. Session memory's
  events are a **sealed hierarchy** that must cross the SDK's internal serializer (README §3), which
  `TaskEntity`'s did not exercise in the same way. If a Scala handler cannot match on them, the trigger
  moves to Java and the quarantine grows.
- **Q-B — How small can the Java quarantine be?** Reading and replacing the history needs the
  event-sourced-entity client, which capability 4 measured as **method-reference only with no
  `dynamicCall`**. So *some* Java is expected. The question is whether it can be exactly one class, as in
  capabilities 11, 14 and 15, or whether it spreads to the trigger and the write-back both.
- **Q-C — Does the agent client's `dynamicCall` escape hatch carry a detailed reply?** The documented
  compaction call asks for token usage alongside the summary. Capability 14 measured that `dynamicCall`
  returns a handle covering **request/response only** — it has no streaming member. If it has no detailed
  reply either, then either token usage on a compacted message is droppable, or this call is Java too.
- **Q-D — RESOLVED by decision, not measurement (2026-09-26): service-wide.** FR-014 accepts
  compaction for every session, because scoping to one capability would mean distinguishing session ids by
  convention — capability 6 keys them by username, capabilities 4 and 14 by opaque ids — which would ship
  as a documented weakness rather than a boundary. What planning must still measure is the *consequence*:
  whether capability 14's **streamed** turn tolerates its history being replaced mid-assembly, since that
  is the one surface where a write to session memory races something the SDK is doing for us.
- **Q-E — Is the history's size available on the event, or must the history be read to learn it?** Decides
  whether the common case (below threshold, do nothing) costs an entity read per turn, which would make
  the bound more expensive than the problem.
