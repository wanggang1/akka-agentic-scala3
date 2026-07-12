# Feature Specification: Session memory (multi-turn chat)

**Feature Branch**: `006-session-memory`
**Created**: 2026-07-12
**Status**: Draft
**Input**: User description: "Capability 4 — Session memory (multi-turn chat). Demonstrate that an agent maintains conversational context across multiple HTTP requests using Akka session memory, keyed by a stable session id supplied by the client."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Multi-turn conversation remembers earlier turns (Priority: P1)

A user holds a short conversation with the assistant across several separate requests. Each request
carries a conversation id the user chose. The user tells the assistant a fact in one turn, and in a
later turn — a separate request — the assistant answers a question that can only be answered by
recalling that earlier fact.

**Why this priority**: This is the entire point of the capability — conversational context that
survives across independent requests. It is the smallest slice that delivers the feature's value and
is demonstrable on its own; everything else refines or protects it.

**Independent Test**: Send one request on conversation `C` stating "my name is Ada", then a second
request on the same conversation `C` asking "what is my name?". The second reply references "Ada".
Fully testable without any other story.

**Acceptance Scenarios**:

1. **Given** a fresh conversation id `C`, **When** the user sends "my name is Ada", **Then** the
   assistant replies and the exchange is retained for `C`.
2. **Given** conversation `C` already contains the "my name is Ada" exchange, **When** the user sends
   "what is my name?" on `C`, **Then** the reply references "Ada".
3. **Given** a conversation with several prior turns, **When** the user sends another message on the
   same id, **Then** the reply is consistent with the accumulated context of that conversation.

---

### User Story 2 - Conversations are isolated from each other (Priority: P2)

Two users (or the same user in two separate conversations) each hold their own conversation. What one
says in conversation `C1` must never surface in conversation `C2`.

**Why this priority**: Context that leaked between conversations would be worse than no memory — a
privacy and correctness failure. It protects US1's value, so it ranks immediately after it.

**Independent Test**: State "my name is Ada" on conversation `C1`, then ask "what is my name?" on a
different conversation `C2`. The reply on `C2` does not claim the name is Ada (it has no such
knowledge).

**Acceptance Scenarios**:

1. **Given** conversation `C1` contains "my name is Ada", **When** the user asks "what is my name?" on
   a different conversation `C2`, **Then** the reply does not report "Ada" as a known fact.
2. **Given** two active conversations, **When** each receives its own messages, **Then** neither
   conversation's replies reflect the other's content.

---

### User Story 3 - Invalid input is rejected before the assistant is engaged (Priority: P3)

A malformed or empty request must be turned away immediately, with a clear reason and without engaging
the assistant.

**Why this priority**: A guardrail consistent with the rest of the service. It improves robustness and
cost control but is not the feature's core value, so it ranks last.

**Independent Test**: Send a request with a blank message and observe a client-error response and no
assistant engagement; send a malformed request body and observe a client-error response.

**Acceptance Scenarios**:

1. **Given** any conversation id, **When** the user sends a blank or absent message, **Then** the
   request is rejected with a client error and the assistant is not engaged.
2. **Given** any conversation id, **When** the request body is malformed, **Then** the request is
   rejected with a client error.
3. **Given** a request body carrying extra unrecognized fields alongside a valid message, **When** it
   is received, **Then** the extra fields are ignored and the message is processed normally.

### Edge Cases

- **First turn of a conversation**: an id never seen before simply starts a new, empty conversation —
  it is not an error.
- **Very long conversations**: the retained history is bounded; the oldest turns may fall out of the
  context window once a size limit is reached. Recent context is always preserved.
- **Same id reused after a long gap**: the conversation continues from its retained history; there is
  no session expiry within the scope of this feature.
- **Concurrent messages on the same id**: out of scope to guarantee strict ordering; the expected
  usage is turn-by-turn (send, await reply, send next).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST accept a conversational message together with a caller-supplied
  conversation id and return a single assistant reply.
- **FR-002**: The system MUST retain each turn (the user's message and the assistant's reply) against
  its conversation id so that later turns on the same id can draw on it.
- **FR-003**: The system MUST include a conversation's retained history as context when producing the
  reply to a subsequent message on the same id.
- **FR-004**: The system MUST keep conversations identified by different ids fully isolated from one
  another — no content from one conversation may influence another.
- **FR-005**: The system MUST treat a previously unseen conversation id as the start of a new, empty
  conversation rather than an error.
- **FR-006**: The system MUST reject a blank or absent message with a client error and MUST NOT engage
  the assistant in that case.
- **FR-007**: The system MUST reject a malformed request body with a client error.
- **FR-008**: The system MUST tolerate unrecognized extra fields in an otherwise valid request body.
- **FR-009**: The system MUST bound the retained history per conversation so that unbounded growth does
  not occur, while always preserving the most recent context.
- **FR-010**: The reply MUST identify the conversation it belongs to so the caller can correlate it and
  continue the same conversation.
- **FR-011**: The capability MUST be additive — the existing greeting, multi-agent, and help-desk
  capabilities MUST remain unchanged in behavior and continue to pass their tests.

### Key Entities

- **Conversation**: an ongoing exchange identified by a caller-supplied id. Holds the ordered history
  of turns and is the unit of isolation. Its lifetime spans multiple independent requests.
- **Turn**: a single user message paired with the assistant's reply, retained as part of a
  conversation's history.
- **Message**: the user's inbound text for one turn. Must be non-blank to be processed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a two-request exchange on one conversation id — state a fact, then ask about it — the
  second reply reflects the fact from the first request, verified against the live model (smoke test).
  *Offline note:* the mocked model (`TestModelProvider`) receives only the current turn, so recall is
  not offline-observable through the mock; the offline suite instead pins that behavior as a regression
  (see the Assumptions "Offline testability" note and research R6).
- **SC-002**: A fact stated in one conversation never appears as known in a reply to a different
  conversation id (cross-conversation isolation), verified against the live model (smoke test); the
  offline suite covers the HTTP surface's per-session wiring but cannot observe isolation through the
  mock (same reason as SC-001).
- **SC-003**: A blank or absent message is rejected with a client error and the assistant is never
  engaged (verified with no model interaction recorded).
- **SC-004**: A malformed request body is rejected with a client error; a body with extra unrecognized
  fields plus a valid message is processed successfully.
- **SC-005**: Each reply carries the conversation id it belongs to, letting a caller continue the same
  conversation without server-side lookup.
- **SC-006**: The greeting, multi-agent, and help-desk capabilities' existing test suites remain green
  and unmodified after this feature is added.
- **SC-007**: The full test suite runs offline with no API key or network access.

## Assumptions

- **Client-supplied conversation id.** The caller chooses and supplies the conversation id (in the
  request path). The service does not mint or hand out ids in this feature; a client that wants a fresh
  conversation simply picks a new id. (Rationale: makes the multi-turn demonstration explicit and
  keeps the surface RESTful. Server-minted ids were considered and deferred.)
- **Synchronous request/response.** Each turn is a single request that returns the reply directly —
  no start-then-poll. This contrasts deliberately with the async capabilities 2 and 3 and suits a
  one-model-call-per-turn interaction.
- **Memory is managed by the platform.** History retention, replay as context, per-conversation
  isolation, and the size bound are provided by the underlying platform's session-memory facility,
  keyed by the conversation id. This feature does not implement its own storage. Durability of the
  history is therefore intrinsic (as with capability 3's task), not something this feature's code opts
  into.
- **Bounded history via a recent-window policy.** The retained context uses the platform default
  (a limited window that drops the oldest turns past a size threshold). Summarization/compaction of old
  history is out of scope for this feature.
- **No authentication or per-user identity.** Conversation ids are opaque strings; there is no login
  or ownership model. Anyone presenting an id continues that conversation. (Consistent with the other
  capabilities in this learning service.)
- **Offline determinism.** Tests substitute a deterministic assistant so results do not depend on a
  live model, key, or network.
- **Offline testability of memory (discovered during build, research R6).** The deterministic test
  assistant receives only the *current* turn's message — the platform does not replay a session's
  stored history into a test model provider's input. So the memory *behavior* (recall in one session,
  isolation across sessions) is verified against the **live model** (a smoke test), while the offline
  suite verifies the request/response wiring, input validation, and the HTTP contract, and pins the
  test-assistant behavior as a regression. This does not change the runtime behavior — only where each
  guarantee is checked.

## Out of Scope

- Server-minted conversation ids or a "start conversation" handshake endpoint.
- Streaming/token-by-token replies.
- Summarization/compaction of long histories.
- Listing, exporting, or deleting a conversation's history through the API.
- Session expiry, TTL, or garbage collection of idle conversations.
- Authentication, authorization, or per-user ownership of conversations.
- Multi-turn coordination across *different* agents sharing one conversation (single assistant only
  here).
