# Feature Specification: Agent-to-agent delegation (personal assistants)

**Feature Branch**: `008-agent-to-agent`
**Created**: 2026-07-31
**Status**: Draft
**Input**: User description: "Capability 6 — Agent-to-agent delegation with local Ollama (qwen3:8b). A
PersonalAssistant agent per unique username (distinct durable instances), holding persisted chat history
and a persisted TODO list, over POST /request/{username}. The assistant uses tools to manage its own
TODO list and to DELEGATE a request to another user's assistant by username, returning that assistant's
reply. Prove agent-to-agent delegation works as idiomatic Scala on the local Ollama model."

## Overview

This is **capability 6** on the learning roadmap — an **exploratory follow-up** beyond the original
four, and the first capability where **one agent calls another agent** while serving a request.

Each user has a **personal assistant** addressed by a **username**. The assistant remembers the
conversation (multi-turn chat), keeps a personal **to-do list**, and — the headline — can **delegate**
to another user's assistant: when the user asks their assistant to "check with Bob's assistant" or "ask
Bob to add a to-do", the assistant forwards the request to *Bob's* assistant (running with Bob's own
history and list) and relays the reply. The request is answered **synchronously**.

The capability's **learning goal** is a two-sided interop result (details in `plan.md` / `research.md`):
delegation between agents is achievable as **idiomatic Scala**, while a per-user **mutable to-do list**
forces a small, deliberately-quarantined boundary — a contrast worth demonstrating. Capabilities 1–5 are
preserved unchanged so all six remain independently demonstrable on the default local Ollama model.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Manage my own to-do list by chatting (Priority: P1)

A user talks to their assistant in natural language to add, list, complete, and remove to-dos. The
assistant figures out the intent and updates the persisted list.

**Why this priority**: This is the assistant's baseline value and the foundation delegation builds on;
it is a viable MVP on its own (a single-user to-do assistant with memory).

**Independent Test**: Drive one username through add → list → complete → delete via `POST
/request/{username}` and confirm each reply reflects the change and the list persists across requests.

**Acceptance Scenarios**:

1. **Given** a fresh username `alice`, **When** she sends "add a to-do to buy milk", **Then** the reply
   confirms the item was added and it is stored under `alice`.
2. **Given** `alice` has to-dos, **When** she sends "what's on my list?", **Then** the reply enumerates
   her items with their ids and completed status.
3. **Given** "buy milk" is item 1, **When** she sends "mark item 1 done", **Then** item 1 becomes
   completed and the reply confirms it.
4. **Given** item 1 exists, **When** she sends "delete item 1", **Then** it is removed and the reply
   confirms; deleting a non-existent id reports "not found" without error.

---

### User Story 2 - Delegate to another user's assistant (Priority: P1)

A user asks their assistant to get something done by another user's assistant. The assistant recognizes
the delegation intent, forwards the request to the named user's assistant, and relays that reply.

**Why this priority**: This is the defining capability — agent-to-agent delegation — and the reason the
feature exists.

**Independent Test**: With two usernames, send a delegating request to the first and confirm the effect
lands under the second, and that the first user's reply carries the second assistant's response.

**Acceptance Scenarios**:

1. **Given** users `alice` and `bob`, **When** alice sends "ask bob's assistant to add a to-do: prepare
   slides", **Then** the item is added under **bob** and alice's reply relays bob's confirmation
   (verbatim, with a brief attribution).
2. **Given** the delegation above completed, **When** `bob` sends "what's on my list?", **Then** his
   list includes "prepare slides" — proving the effect landed under bob, not alice.
3. **Given** alice sends "ask bob what's on his list", **When** bob has items, **Then** alice's reply
   contains bob's list as bob's assistant reported it.

---

### User Story 3 - Remembered, isolated conversations (Priority: P2)

Each username is its own ongoing conversation; the assistant recalls earlier turns and never mixes one
user's history or to-dos with another's.

**Why this priority**: Memory and isolation make the assistant coherent and safe, but the to-do and
delegation flows are demonstrable without exercising recall directly.

**Independent Test**: State a fact on a username, then ask about it on the same username (recall); ask
the same on a different username (isolation).

**Acceptance Scenarios**:

1. **Given** `alice` earlier said "my name is Alice", **When** she later asks "what's my name?", **Then**
   the reply recalls "Alice". *(Observable only in a live run — the offline mock sees only the current
   turn; see Assumptions.)*
2. **Given** separate usernames `alice` and `carol`, **Then** neither sees the other's history or to-dos.

---

### User Story 4 - Safe, bounded, validated requests (Priority: P3)

The system rejects malformed input before doing any work, and a delegated request cannot itself
delegate onward (no runaway agent-to-agent loops).

**Why this priority**: Robustness and safety; important for correctness but not part of the core demo
flow.

**Independent Test**: Send blank/malformed requests and assert rejection; assert a request handled *as a
delegate* offers no further delegation.

**Acceptance Scenarios**:

1. **Given** a blank or absent message, or a blank username, **When** the request is sent, **Then** it
   is rejected with a client error and no assistant is engaged.
2. **Given** a malformed request body, **When** it is sent, **Then** it is rejected with a client error.
3. **Given** a request that reaches an assistant **as a delegate**, **When** that assistant processes it,
   **Then** it has no delegation tool available and cannot forward again — bounding any chain to a single
   hop.

### Edge Cases

- **Delegating to a never-seen username**: the target simply starts as a fresh assistant (empty history
  and list); this is allowed, not an error.
- **Self-delegation** (asking your own assistant to ask your own assistant): bounded by the same one-hop
  guard; the delegate cannot re-delegate.
- **Server restart mid-delegation**: the persisted history and to-dos survive, but an in-flight
  synchronous request is not resumed — the caller retries (see Assumptions).
- **Growing conversation**: history replayed to the model is bounded to a recent window so token usage
  does not grow without limit.
- **A delegated reply that is long**: it is relayed as-is; it counts toward the caller's bounded history
  window.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST answer `POST /request/{username}` synchronously with the assistant's reply
  for that user.
- **FR-002**: Each distinct username MUST have its own **persisted chat history** and its own
  **persisted to-do list**, independent of every other username.
- **FR-003**: The assistant MUST let the user **list**, **add**, **delete**, and **mark complete/not
  complete** to-do items through natural-language requests; adding returns a new unique item id, and
  deleting reports whether the item existed.
- **FR-004**: The assistant MUST be able to **delegate** a request to another user's assistant, named by
  username, and return that assistant's reply (verbatim, with a brief attribution).
- **FR-005**: The user MUST be able to trigger delegation purely by phrasing it in the message; the API
  caller MUST NOT have to pass any delegation flag or parameter.
- **FR-006**: A delegated request MUST NOT be able to delegate onward — delegation is bounded to **one
  hop** to prevent assistant-to-assistant loops.
- **FR-007**: A blank/absent message or blank username MUST be rejected with a client error before any
  assistant or model is engaged; a malformed body MUST likewise be rejected.
- **FR-008**: The conversation context replayed to the model MUST be **bounded** (a recent window), not
  the entire unbounded history.
- **FR-009**: To-dos MUST be readable only **through the assistant** (by asking it); the system does NOT
  expose a separate direct to-do read endpoint.
- **FR-010**: Capabilities 1–5 MUST remain unchanged and continue to pass their tests.

### Key Entities *(include if feature involves data)*

- **To-do item**: one task with an id (unique within a user), a description, and a completed flag.
- **To-do list**: a user's collection of to-do items; the assistant's persisted per-user state.
- **Assistant request**: a validated inbound request naming a (non-blank) user and carrying a (non-blank)
  message.
- **Delegated request**: an internal, one-hop request from one assistant to another; not part of the
  public API surface.

## Assumptions

- **Identity**: `username` is a plain routing handle, not an authenticated account; no auth in scope.
- **Durability model (synchronous)**: chat history and to-dos are durable and survive restarts; an
  in-flight request (including a delegation waiting on another assistant) is **not** resumed after a
  restart — the caller retries. A "resume mid-delegation" guarantee is explicitly **out of scope** (it
  would require an asynchronous start-then-poll surface instead of the synchronous one).
- **Offline testing limit**: multi-turn *recall* (the model using earlier turns) is verified in a live
  run; the offline test harness surfaces only the current turn to the model, so recall is not observable
  offline (carried over from capability 4). Retention and isolation of stored state are verifiable
  offline.
- **Model**: runs on the default local model (`qwen3:8b` via Ollama), which does reliable tool calling;
  no cloud key required.
- **Verbatim relay**: a delegated reply is returned unchanged plus a one-line attribution, rather than
  re-summarized by the delegating assistant.

## Out of Scope

- Durable resume of an in-flight delegation across a restart (would require an asynchronous surface).
- LLM-driven compaction/summarization of chat history.
- A direct to-do query/list endpoint (to-dos are surfaced only through the assistant).
- Authentication, authorization, or real user accounts.
- Delegation chains longer than one hop, or parallel fan-out to multiple assistants.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can add, list, complete, and delete to-dos entirely through natural-language
  requests, with each change reflected in the reply and persisted across requests (User Story 1).
- **SC-002**: A delegating request causes the intended effect under the **target** user and returns the
  target assistant's reply to the caller (User Story 2, scenarios 1–2).
- **SC-003**: Two different usernames never observe each other's history or to-dos (User Story 3,
  scenario 2).
- **SC-004**: A request handled as a delegate cannot delegate again — no chain exceeds one hop (User
  Story 4, scenario 3), verifiable offline.
- **SC-005**: Every blank/malformed request is rejected before any model call (User Story 4, scenarios
  1–2).
- **SC-006**: All six capabilities remain independently demonstrable; capabilities 1–5 stay green
  (FR-010).
