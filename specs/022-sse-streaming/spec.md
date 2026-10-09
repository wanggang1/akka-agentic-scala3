# Feature Specification: SSE Framing for the Streaming Agent Surface

**Feature Branch**: `022-sse-streaming`
**Created**: 2026-10-09
**Status**: Draft
**Input**: User description: "B2 — SSE framing for the streaming agent surface. Add a NEW parallel streaming endpoint (do NOT modify capability 14's existing POST /stream-chat/{sessionId}, which stays as the raw HTTP/1.1 chunked text/plain baseline). The new endpoint streams the same agent's response using Server-Sent Events (text/event-stream) with explicit framing: `event: data` frames carrying generated text fragments and an `event: error` frame emitted when the agent fails mid-stream or before the first token."

## Context & Motivation

Capability 14 added a streaming agent surface (`POST /stream-chat/{sessionId}`) that delivers a
reply as it is generated, over **raw HTTP/1.1 chunked `text/plain`**. A measured weakness of that
wire format (documented in `docs/streaming-vs-request-response.md`) is that it has **no slot for an
error once the response has started**: the `200 OK` and headers are committed before the first token
exists, so a failure *before the first fragment* produces a `200` with an **empty body that is
byte-identical to a successful empty answer** — the client receives no signal that anything went
wrong.

This feature adds a **second, parallel** streaming surface that uses **Server-Sent Events (SSE,
`text/event-stream`)**. SSE frames each message with an event label, which provides exactly the
missing slot: text fragments arrive as `event: data` frames, and a failure is reported as an explicit
`event: error` frame — even after streaming has begun. Capability 14's endpoint is **kept unchanged**
as the honest baseline against which the SSE surface is compared.

This is a **wire-format / design exploration**, not a bug fix for capability 14. The point is to
measure what SSE framing buys (self-describing failure, structured events) against what it costs (a
richer contract every client must parse) — and to record whatever Akka SDK interop facts surface
while building it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Receive a streamed answer as labeled events (Priority: P1)

A client sends a question to the SSE endpoint and receives the agent's answer incrementally as a
sequence of Server-Sent Events. Each text fragment arrives as an `event: data` frame; when the answer
is complete, the stream ends cleanly. The concatenation of the `data` payloads, in order, is exactly
the agent's full answer.

**Why this priority**: This is the core surface. Without it there is nothing to frame, and it is the
only part that must exist for the capability to deliver any value. It establishes parity with
capability 14 (same agent, same answer) over the new wire format.

**Independent Test**: Send a request with a scripted/mocked model reply and assert the response
carries `Content-Type: text/event-stream`, that the payload parses as well-formed SSE frames, and that
the ordered concatenation of `data` fields equals the expected answer.

**Acceptance Scenarios**:

1. **Given** the agent will produce a multi-fragment answer, **When** a client requests the SSE
   endpoint, **Then** the response has `Content-Type: text/event-stream` and the body is a sequence of
   `event: data` frames whose payloads, concatenated in arrival order, reconstruct the full answer.
2. **Given** a completed answer, **When** the last fragment has been sent, **Then** the stream
   terminates cleanly and the client can tell the answer is complete (end-of-stream, and/or a terminal
   completion frame — see FR-005).
3. **Given** the same question and the same (scripted) model reply, **When** compared against
   capability 14's `/stream-chat`, **Then** the reconstructed answer text is identical across both
   surfaces (the surfaces differ only in framing, not in content).

---

### User Story 2 - Learn that a stream failed, after it started (Priority: P1)

A client is consuming the SSE stream when the agent fails — either before any text was produced, or
part-way through. Instead of a silent empty body or a bare connection abort, the client receives an
explicit `event: error` frame describing that the request failed, and the stream then ends.

**Why this priority**: This is the entire reason the capability exists. The measured defect in
capability 14 is that a pre-first-token failure is indistinguishable from success. Self-describing
failure is the differentiating value, so it is co-P1 with the happy path.

**Independent Test**: Drive the agent to fail (a) before the first fragment and (b) after one or more
fragments, using a mocked model, and assert that in both cases the client observes a distinct
`event: error` frame (not an empty `200`, and not only a truncated body) before the stream closes.

**Acceptance Scenarios**:

1. **Given** the agent fails **before** producing any fragment, **When** a client consumes the SSE
   stream, **Then** the client receives an `event: error` frame (carrying a client-safe failure
   description) and the stream ends — the outcome is distinguishable from a successful empty answer.
2. **Given** the agent fails **after** one or more `data` frames have been sent, **When** the failure
   occurs, **Then** the client receives the `data` frames already produced, followed by an
   `event: error` frame, then end-of-stream — i.e. the partial answer is not silently presented as
   complete.
3. **Given** an `event: error` frame is emitted, **When** the client inspects it, **Then** it contains
   a stable, client-safe reason and does **not** leak internal exception detail (stack traces,
   internal class names).

---

### User Story 3 - Capability 14 remains the untouched baseline (Priority: P2)

A client (or a maintainer) can still use the original raw-chunked `/stream-chat/{sessionId}` surface
exactly as before. The SSE endpoint is additive; nothing about capability 14's behavior, wire format,
or tests changes.

**Why this priority**: Preserving the baseline is what makes this a measured comparison rather than a
breaking migration, and it follows the project's established "never edit a prior capability" practice.
It is P2 because it is verified largely by *absence of change* rather than new behavior.

**Independent Test**: Run capability 14's existing integration tests unchanged and confirm they still
pass; confirm `/stream-chat` still returns `text/plain` chunked output.

**Acceptance Scenarios**:

1. **Given** the SSE endpoint has been added, **When** capability 14's existing streaming tests run,
   **Then** they pass without modification.
2. **Given** a client calls the original `/stream-chat/{sessionId}`, **When** it reads the response,
   **Then** the response is still raw-chunked `text/plain` with the pre-existing behavior.

---

### Edge Cases

- **Failure exactly at the boundary** (model errors with zero tokens produced): must yield an
  `event: error` frame, which is the defining improvement over the baseline's empty `200`.
- **Very long answer**: many small `data` frames; the stream must not buffer the whole answer in
  memory before sending (incremental delivery preserved, as in capability 14).
- **Client disconnects mid-stream**: the server should stop producing without error noise; no
  requirement to persist or resume a partial answer.
- **Fragment payload contains newlines**: SSE is a newline-delimited format, so multi-line fragment
  text must be encoded such that the client reconstructs the original text exactly (framing must not
  corrupt content that happens to contain `\n`).
- **A stall with neither tokens nor error** (model never answers and never fails): the stream should
  not hang forever unboundedly; a timeout bound applies (inherited from the capability-14 pattern),
  surfaced to the client as an `event: error` where possible.
- **Empty but successful answer** (agent legitimately returns no text): must be distinguishable from a
  failure — a successful empty answer ends cleanly without an `event: error` frame.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a NEW streaming endpoint, parallel to capability 14's
  `/stream-chat/{sessionId}`, that answers the same kind of question against the same agent and
  delivers the answer incrementally.
- **FR-002**: The new endpoint MUST deliver its response as Server-Sent Events with
  `Content-Type: text/event-stream`, with each generated text fragment carried in an `event: data`
  frame.
- **FR-003**: The ordered concatenation of the text carried by all `data` frames MUST reconstruct
  exactly the agent's full answer (parity with the baseline surface's content). Note: because each
  frame's payload is JSON-encoded (see Assumptions / research Q-D), parity is defined on the **decoded**
  text field of each `data` frame, not on the raw concatenation of the JSON payload lines.
- **FR-004**: When the agent fails — whether **before** the first fragment or **after** some fragments
  have been sent — the system MUST emit an explicit `event: error` frame before ending the stream, so
  that failure is distinguishable from a successful (including a successfully empty) answer.
- **FR-005**: The system MUST give the client a way to tell that a successful answer is complete
  (clean end-of-stream, and/or a terminal completion event), distinct from a failure ending.
- **FR-006**: The `event: error` frame MUST carry a stable, client-safe reason and MUST NOT leak
  internal implementation detail (stack traces, internal class/exception names).
- **FR-007**: Fragment content that contains newlines or other SSE-significant characters MUST be
  framed so the client reconstructs the original text byte-for-byte.
- **FR-008**: The system MUST NOT buffer the entire answer server-side before sending; fragments are
  sent as they are produced (incremental delivery, matching capability 14).
- **FR-009**: A stream that produces neither tokens nor a completion within a bounded time MUST be
  terminated rather than left hanging indefinitely; where possible the termination is surfaced as an
  `event: error` frame.
- **FR-010**: Capability 14's `/stream-chat/{sessionId}` endpoint, its wire format, and its tests MUST
  remain unchanged; this feature is strictly additive.
- **FR-011**: The endpoint MUST carry an access-control annotation consistent with the project's other
  streaming/chat endpoints.
- **FR-012** *(documentation)*: The project documentation MUST record the measured contrast between the
  raw-chunked baseline and the SSE surface — specifically how a pre-first-token failure appears on each
  — and MUST record the interop finding about how SSE framing is produced on this SDK (see Assumptions).

### Key Entities *(include if feature involves data)*

- **SSE event frame**: a single Server-Sent Event with an event type (`data`, `error`, and optionally a
  terminal completion type) and a payload. `data` carries a text fragment of the answer; `error`
  carries a client-safe failure reason.
- **Chat request**: the client's question plus the session identifier, identical in shape to the
  capability-14 streaming request (reused, not redesigned).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a successful answer, a client consuming the SSE stream reconstructs text **identical**
  to what the baseline `/stream-chat` surface produces for the same question and same model reply
  (100% content parity).
- **SC-002**: A failure **before the first fragment** is observed by the client as a distinct error
  signal (an `event: error` frame) in **100%** of trials — i.e. it is never indistinguishable from a
  successful empty answer, which is the measured defect this feature removes.
- **SC-003**: A failure **after** one or more fragments is observed as the already-sent fragments
  followed by an explicit error signal, never as a silently-complete partial answer.
- **SC-004**: All of capability 14's pre-existing streaming tests pass unchanged after this feature is
  added (zero regressions to the baseline surface).
- **SC-005**: First fragment reaches the client before the full answer is generated (time-to-first-byte
  is less than total generation time), confirming incremental delivery is preserved.
- **SC-006**: The error frame never contains internal implementation detail in any tested failure path.

## Assumptions

- **Same agent, reused request shape.** The SSE surface drives the same agent capability 14 uses and
  reuses the existing chat request shape and session-id semantics; it does not introduce a new agent or
  a new conversation model.
- **Session semantics unchanged.** Session memory / session-id behavior is identical to the baseline
  streaming surface; this feature changes only the wire framing of the response, not the conversation
  state.
- **Interop cost: one Java class (expected, to be confirmed).** Per the project's prior finding
  (README §16), consuming the agent token stream requires a Java method reference (the `dynamicCall`
  escape hatch has no streaming counterpart), so the SSE capability is expected to cost exactly one
  Java class in an otherwise-Scala feature. This is an expectation to confirm during planning, not a
  requirement.
- **Open interop question for planning (candidate finding).** Whether the SDK's HTTP response helpers
  expose a first-class SSE / `text/event-stream` construct, or whether the SSE frames must be
  hand-built over the same text source the baseline uses, is **unknown** and is itself a candidate
  interop finding to be measured and recorded — not decided in this spec.
- **Timeout bound reused.** The hang-protection bound is inherited from the capability-14 streaming
  pattern rather than newly designed; the only new behavior is surfacing that termination as an
  `event: error` frame where the framing allows.
- **No client library shipped.** The deliverable is the server surface plus tests and documentation; a
  reference `curl`/client snippet in docs is sufficient to demonstrate consumption. No browser
  `EventSource` front-end is in scope.

## Out of Scope

- Modifying, deprecating, or migrating capability 14's `/stream-chat` surface.
- Streaming a *grounded* answer (citations appended after streamed text) — that is a separate fork
  (B5), explicitly declined in capability 14.
- SSE features beyond `data`/`error`/completion framing (e.g. `id:`/`retry:` reconnection semantics,
  resumable streams, last-event-id replay) unless they fall out naturally from the chosen SDK
  construct.
- Any change to the underlying model, prompt, or agent behavior.
