# Feature Specification: Streaming agent responses

**Feature Branch**: `016-streaming-responses`
**Created**: 2026-09-12
**Status**: Draft
**Input**: User description: "Capability 14 — streaming agent responses. Stream a model's reply token-by-token instead of returning it whole: an Akka `Agent` command handler returning `StreamEffect` rather than `Effect<T>`, exposed over HTTP as a chunked/SSE response, so a caller sees the answer being typed. … The headline deliverable is a resolved interop finding, on equal footing with the feature."

## Why this capability exists

Every answer this service produces today arrives as one blob, after the whole reply has been
generated. For a long answer on a local model that is many seconds of nothing. Capability 14 makes a
reply visible **as it is produced**.

It is also the project's last unexplored interop axis and the one with the highest risk. Every
capability since capability 2 has been decided by a single property: is the SDK API keyed on a
`Class`/`String` (Scala-friendly) or on a Java method reference (Scala-impossible)? Agents have always
been on the friendly side, because the agent client alone offers `dynamicCall(componentId)`. The
documented way to consume a token stream is `tokenStream(SomeAgent::method).source(arg)` — a **method
reference** — and the object `dynamicCall` returns has no streaming counterpart. If that holds, this is
the **first capability where the wall bites the agent client**, and the answer changes the project's
central finding rather than merely extending it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the answer as it is written (Priority: P1)

A caller asks a question on the streaming surface and receives the answer **incrementally** — readable
text arriving while the model is still working — instead of waiting for the complete reply and
receiving it at once.

**Why this priority**: This is the capability. Everything else here is a property of it.

**Independent Test**: Ask a question whose answer is long enough to arrive in several pieces, and
observe that readable output arrives measurably before the response is complete. Delivers the whole
user-visible value on its own.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a caller submits a question to the streaming surface,
   **Then** the first readable fragment of the answer reaches the caller well before the complete
   answer is finished, and the fragments concatenated form the complete answer.
2. **Given** a caller submitted a question, **When** the answer completes, **Then** the caller can tell
   the answer has ended, without having to guess from a pause.
3. **Given** a blank or absent question, **When** it is submitted, **Then** it is rejected before any
   model call and nothing is streamed.

---

### User Story 2 - A conversation that streams (Priority: P2)

A caller holds a multi-turn conversation on the streaming surface: a second question on the same
conversation is answered in the light of the first, even though the first answer was delivered as a
stream rather than as a single value.

**Why this priority**: A streamed reply that is not remembered would make the streaming surface a
second-class version of the existing conversation surface. It also answers a real question about the
platform — whether a reply assembled from fragments is recorded once complete.

**Independent Test**: Ask two questions on one conversation id where the second depends on the first,
and confirm the second answer reflects the first. Also confirm a different conversation id knows
nothing of it.

**Acceptance Scenarios**:

1. **Given** a caller stated a fact in turn 1 on a conversation id, **When** they ask about it in
   turn 2 on the same id, **Then** the streamed answer reflects turn 1.
2. **Given** turn 1 happened on one conversation id, **When** the same question is asked on a
   different id, **Then** the answer shows no knowledge of it.

---

### User Story 3 - An interrupted answer is not passed off as a complete one (Priority: P3)

When generation breaks after the caller has already received part of the answer, the caller can tell
that what they received is **incomplete**.

**Why this priority**: Lower than the two above because it is a failure path, but it cannot be skipped:
a truncated answer that looks finished is worse than an error. This capability cannot reuse the
existing fallback technique, because a fallback value is only expressible *before* the first fragment
has been sent.

**Independent Test**: Force generation to fail after the first fragments have been delivered, and
confirm the caller's view of the answer is distinguishable from a completed one.

**Acceptance Scenarios**:

1. **Given** a stream has delivered some fragments, **When** generation fails, **Then** the caller can
   distinguish the result from a normally completed answer.
2. **Given** generation fails **before** any fragment is sent, **When** the caller submitted the
   question, **Then** they receive a single clear failure rather than an empty successful answer.

---

### User Story 4 - The interop question is answered in public (Priority: P3)

A developer reading this repository learns whether a streamed response can be produced **and consumed**
from Scala on this Java-first SDK, what the deciding mechanism is, and — if some part must be Java —
exactly which part and why.

**Why this priority**: The feature is usable without it, but this is the project's stated purpose, and
this capability is the one that tests the project's central claim rather than repeating it.

**Independent Test**: Read the repository's findings, README and roadmap; the verdict, its evidence and
its boundaries are stated without needing to read the source.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a reader consults the project's findings, **Then**
   they find whether authoring and consuming a streamed reply are Scala-clean, with the evidence.
2. **Given** any part of the capability had to be written in Java, **When** a reader consults the
   findings, **Then** they learn which part, the mechanism that forced it, and how far that spreads.

---

### Edge Cases

- **The caller goes away mid-answer.** What happens to work already in progress when the caller stops
  reading or disconnects?
- **One fragment per word is wasteful.** A client must not be forced to process one network event per
  token; fragments should be grouped without making the answer feel un-streamed.
- **Two streams on one conversation at once.** What a caller sees if they start a second question on
  the same conversation id before the first has finished.
- **An answer that never ends.** A model that keeps producing output, or stalls mid-answer, must not
  hold a caller open forever.
- **A question rejected before any streaming.** Validation failure must be an ordinary, non-streamed
  error response.
- **Governance.** The rules that guard capability 8's assistant do not guard this new agent; nothing in
  this capability configures rules of its own, and the spec does not assume any apply.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST expose a surface that answers a question by delivering the answer in
  fragments as it is generated, on its own path, without altering any existing surface.
- **FR-002**: The fragments, in order and concatenated, MUST form exactly the answer the caller would
  have received from a non-streamed equivalent for the same input and the same model behaviour.
- **FR-003**: The surface MUST reject a blank or absent question before any model call, and MUST do so
  as an ordinary error response rather than as a stream.
- **FR-004**: A caller MUST be able to tell that an answer has completed normally.
- **FR-005**: A caller MUST be able to tell that an answer ended **abnormally** after fragments had
  already been delivered, distinguishably from FR-004.
- **FR-006**: A failure that occurs **before** the first fragment MUST reach the caller as a single
  clear failure, not as an empty successful answer.
- **FR-007**: Fragments MUST be grouped so that a client is not required to handle one event per token,
  while keeping the answer perceptibly incremental.
- **FR-008**: Continuing a conversation MUST work across streamed turns: the complete answer of a
  streamed turn MUST be available as context to later turns on the same conversation, and MUST NOT be
  visible to other conversations.
- **FR-009**: No existing capability's production sources or tests may be modified. Where this
  capability needs behaviour an existing component does not have, it MUST introduce its own component
  rather than change one. *(Capability 13 broke this rule once, deliberately and with the reason
  recorded; that is the bar for doing so again.)*
- **FR-010**: The capability MUST be verifiable without a live model and without network access, or —
  if the platform's test tooling cannot script a streamed reply — the gap MUST be documented and the
  behaviour proven by a recorded live run instead. Which of the two applies is a research question, not
  an assumption.
- **FR-011**: The capability MUST publish its interop verdict — whether producing and consuming a
  streamed reply are each Scala-clean, the deciding mechanism, and the precise boundary of any part
  that must be Java — in the project's findings, README and roadmap.
- **FR-012**: Any component this capability introduces MUST be registered so the runtime discovers it,
  consistent with how this project registers Scala components.
- **FR-013**: If a part of this capability cannot be written in the project's primary language, the
  **attempt and its failure mode** MUST be recorded as evidence alongside the working version — not
  silently replaced by it — and the non-primary-language part MUST be confined to the smallest unit that
  works (user decision, 2026-09-12).

### Key Entities

- **Question**: the caller's input, plus the conversation it belongs to when the subject is
  conversational. Validated before anything else happens.
- **Answer fragment**: one piece of a reply, meaningful only in order and only as part of a whole.
- **Answer**: the complete reply, assembled from its fragments; the unit that a conversation remembers
  and that parity (FR-002) is judged against.
- **Stream outcome**: how a delivery ended — completed, or interrupted after partial delivery, or
  failed before it began. The distinction FR-004 to FR-006 rest on.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an answer that takes several seconds to generate, the caller receives readable output
  in a small fraction of that time, rather than only at the end.
- **SC-002**: For the same input and the same model behaviour, the streamed answer assembled from its
  fragments is identical to the answer the non-streamed equivalent produces — demonstrated, not argued.
- **SC-003**: A blank question is rejected with no model call, on the streaming surface exactly as on
  every other surface in this project.
- **SC-004**: A completed answer, an answer interrupted mid-delivery, and a failure before delivery are
  three outcomes a caller can tell apart.
- **SC-005**: A streamed turn is remembered by its conversation and invisible to other conversations.
- **SC-006**: Every existing capability's production sources and tests are unchanged, provable by
  inspection of the change set rather than by assertion.
- **SC-007**: The project's findings, README and roadmap state the streaming interop verdict with its
  evidence and its boundary, including which part — if any — had to be Java and how far that reaches.
- **SC-008**: The capability's behaviour is proven without a live model, or the inability to do so is
  documented with what was tried and what proves the behaviour instead.

## Assumptions

- **The subject is a streaming conversation surface** — a question, a conversation id, a text answer —
  on its own new path, so parity (SC-002) is judged against the project's existing non-streamed
  conversation surface (user decision, 2026-09-12).
- **A streaming *grounded* answer is a documented fork, not scope** (same decision). Retrieving before
  the stream begins would be straightforward, but a tool call *inside* a stream is undocumented on this
  SDK, and citations cannot honestly be appended after the text has already been delivered. Recorded as
  future work, the way capability 8 recorded retrieval-as-a-tool.
- **Streaming is delivered over the service's existing HTTP surface style** — a caller posts a question
  and reads the answer as it arrives — rather than introducing a new transport or a client library.
- **No new dependency is expected.** If one proves necessary it must be justified, as the project's
  constitution requires.
- **Governance and evaluation are out of scope.** This capability configures no rules and judges
  nothing; the guarded assistant of capability 12 and the judges of capability 13 are untouched and do
  not apply to a new agent.
- **The model is the same local, offline model the rest of the project uses**, so a slow answer is
  normal and streaming is worth having.
- **The answer is text.** Streaming structured or typed results is not in scope.

## Open interop questions *(recorded here because they shape scope, resolved in planning)*

These are stated as questions, with a decided fallback each, rather than guessed at now.

- **Q-A — Can a Scala agent author a streamed reply?** Expected yes: the builder appears to be keyed on
  values and strings, like every other agent API. *Fallback if no*: the capability becomes a documented
  negative finding, which would be a first.
- **Q-B — Can a Scala caller consume it?** Expected **no**, and this is the headline. The documented
  path is keyed on a Java method reference, and the project's one escape hatch appears to have no
  streaming counterpart. *Decided approach (user, 2026-09-12)*: attempt the Scala-only path first, and
  if it is genuinely unreachable, quarantine **exactly one** Java class — the one holding the method
  reference — as capability 11 did for its View caller. The failed Scala attempt is part of the
  deliverable evidence (FR-013), not a discarded detour.
- **Q-C — Is a streamed reply scriptable offline?** Unknown; the platform's testing documentation does
  not mention streaming at all. *Fallback if no*: FR-010's second branch — document the gap, prove the
  behaviour live.
- **Q-D — Is a streamed reply remembered?** Unknown whether the assembled answer is recorded once the
  stream completes. *Fallback if no*: FR-008 and SC-005 are withdrawn and the limitation documented,
  making the surface single-turn.
