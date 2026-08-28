# Feature Specification: MCP Client Agent (cap-10)

**Feature Branch**: `012-mcp-client`
**Created**: 2026-08-23
**Status**: Draft
**Input**: User description: "MCP client agent (cap-10): a request-based Agent that consumes our own cap-9 MCP server at /mcp via .mcpTools(url), letting the model call the remote `retrieve` tool to ground its answers (agentic RAG — model decides when/whether to retrieve, contrasting cap-8's endpoint-orchestrated pre-retrieval). Closes the loop end-to-end (agent → MCP client → our MCP server → KnowledgeStore), fully offline (in-jar ONNX embeddings, no external dependency, no API key). Synchronous HTTP surface like cap-4/cap-8. Primary goal is to verify the interop question: is .mcpTools(url) Scala-clean (no method-ref wall)? Same validation-first contract as the other capabilities."

## Context

This is capability 10 in a learning sandbox that explores Akka agentic features on Scala 3, one
concept at a time. Cap-9 already ships an **MCP server** at `/mcp` that exposes semantic retrieval
over the Model Context Protocol (a `retrieve` tool + a corpus-sources resource). Cap-10 builds the
**consuming side**: an agent that *uses* a remote MCP tool, closing the loop end-to-end within this
one service (agent → MCP client → our own MCP server → the shared knowledge store).

Cap-10 also cashes in the "retrieval-as-a-tool / agentic RAG" fork that cap-8 flagged: where cap-8
retrieves passages in the endpoint *before* the model runs (pre-retrieval, always, once), cap-10
hands the model a retrieval **tool** and lets the model decide **whether, when, and with what query**
to retrieve. Same underlying knowledge, a different orchestration.

As with every capability in this sandbox, the headline deliverable is not the feature alone but a
**resolved interop question**: is consuming a remote MCP server from Scala (`.mcpTools(url)`)
friction-free, or does it hit the method-reference wall like Workflows and entities do?

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ask a grounded question through an MCP-tool-using agent (Priority: P1)

A caller asks the assistant a question about the sandbox's own knowledge (the corpus describing its
capabilities and interop findings). The agent has access to a remote retrieval tool; the model
decides to call it, gets back grounded passages, and answers using them — or honestly declines when
the corpus doesn't cover the question. The exchange is synchronous: one request in, the answer out.

**Why this priority**: This is the whole capability — an agent that grounds its answers by *calling
a remote MCP tool* rather than being handed passages up front. Without it there is nothing to
demonstrate and the interop question stays unanswered. It is the MVP on its own.

**Independent Test**: Send a question the corpus covers to the endpoint; confirm a grounded answer
comes back and that the remote retrieval tool was actually exercised (an in-corpus answer that could
only come from the retrieved passages). Fully testable end-to-end against the service's own MCP
server with no external dependency.

**Acceptance Scenarios**:

1. **Given** the service is running with its MCP server available, **When** a caller asks an
   in-corpus question, **Then** the response is a grounded answer drawn from the corpus.
2. **Given** the same setup, **When** a caller asks a question the corpus does not cover, **Then**
   the assistant declines honestly rather than fabricating an answer.
3. **Given** the same setup, **When** a caller sends a question, **Then** the reply is returned
   synchronously in a single request/response (no polling handle).

---

### User Story 2 - Reject malformed input before doing any work (Priority: P2)

A caller sends a blank or absent question, or a malformed request body. The service rejects it
immediately with a clear client error, before the agent or the remote tool is engaged.

**Why this priority**: Consistency with every other capability's validation-first contract, and it
protects the (comparatively expensive) model + tool round-trips from being spent on junk input. It
is secondary to the core flow but expected behavior for the surface.

**Independent Test**: Send a blank question and a malformed body; confirm each is rejected with a
client error and that no retrieval or model call happens.

**Acceptance Scenarios**:

1. **Given** the endpoint, **When** a caller sends a blank/whitespace-only question, **Then** the
   request is rejected with a validation error and no model or tool call is made.
2. **Given** the endpoint, **When** a caller sends a malformed request body, **Then** the request is
   rejected with a client error.
3. **Given** the endpoint, **When** a caller sends a body with an unexpected extra field alongside a
   valid question, **Then** the request is still accepted (tolerant of unknown fields).

---

### User Story 3 - Confirm the same knowledge is reachable both ways (Priority: P3)

A maintainer wants to confirm that cap-10's tool-based retrieval reaches the *same* knowledge as
cap-8's direct pre-retrieval and cap-9's direct MCP server call — i.e. the loop genuinely closes on
the shared store, and cap-10 is a different *path* to the same corpus, not a separate corpus.

**Why this priority**: This is the "closes the loop" verification that gives the capability its
teaching value, but it is a confirmation on top of an already-working feature, so it ranks after the
core flow and validation.

**Independent Test**: For a question, confirm the passages the agent's tool call surfaces correspond
to the passages a direct retrieval against the shared store returns for the same question.

**Acceptance Scenarios**:

1. **Given** a question, **When** it is retrieved via the agent's remote MCP tool and, separately,
   via a direct call to the shared knowledge store, **Then** both surface the same top passage(s) for
   that question.

---

### Edge Cases

- **Model chooses not to retrieve**: the model may answer a question without calling the tool at all
  (e.g. it judges the question out of scope, or trivially answerable). The assistant should still
  behave sensibly — decline or answer — and not error. The model's freedom to skip retrieval is a
  deliberate property of this capability (the cap-8 contrast), not a failure.
- **Remote MCP server unreachable**: if the retrieval tool cannot be reached (or the agent turn
  otherwise fails), a single request should degrade gracefully — a clean `200` with an honest
  "unavailable" message and the real cause logged server-side — rather than hang indefinitely, return a
  raw `500`, or leak a stack trace to the caller.
- **Retrieval returns weak/low-relevance passages**: for an out-of-corpus question the tool still
  returns its best (weak) matches; the assistant must rely on the grounding instruction to decline
  rather than answer from weak passages.
- **Blank or whitespace-only question**: rejected up front (User Story 2).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a synchronous endpoint that accepts a natural-language question
  and returns the assistant's answer in a single request/response (no start-then-poll handle).
- **FR-002**: The assistant MUST have access to a remote retrieval tool served by this service's own
  MCP server, and the model MUST be free to decide whether and when to call it for a given question.
- **FR-003**: When the model calls the retrieval tool, the assistant's answer MUST be grounded in the
  passages the tool returns.
- **FR-004**: When the corpus does not cover the question, the assistant MUST decline honestly
  instead of fabricating an answer.
- **FR-005**: The system MUST reject a blank/whitespace-only or absent question with a client
  validation error, before any model or tool call is made.
- **FR-006**: The system MUST reject a malformed request body with a client error.
- **FR-007**: The system MUST tolerate unknown extra fields in an otherwise-valid request body.
- **FR-008**: The capability MUST operate fully offline — no external network dependency and no API
  key required for retrieval (the knowledge and embeddings are in-process), consistent with the rest
  of the sandbox.
- **FR-009**: The capability MUST reuse the existing shared knowledge store and the existing MCP
  server surface rather than introduce a second corpus or a second retrieval implementation.
- **FR-010**: If the remote retrieval tool is unreachable (or the agent turn otherwise fails) during a
  request, the system MUST degrade gracefully — return a clean response carrying an honest
  "unavailable" message (no hang, no raw stack trace to the caller) — and log the real cause
  server-side. (Implemented as the cap-6 pattern: the agent's `onFailure` degrades a failed turn to a
  fixed honest message returned as a normal `200`, plus a `logger.warn` of the underlying exception —
  not a raw `500`.)
- **FR-011**: The capability MUST be verified against the interop question — whether consuming a
  remote MCP server from Scala is friction-free — and the outcome MUST be recorded as a project
  finding (as every prior capability has done).

### Key Entities *(include if feature involves data)*

- **Question**: the caller's natural-language input. Must be non-blank. The single required field of
  the request.
- **Answer**: the assistant's synchronous reply — either a grounded answer or an honest decline.
- **Retrieved passage** (transient, not persisted here): a grounding snippet returned by the remote
  retrieval tool, carrying at least a source label and the passage text; supplied by the existing
  shared knowledge store via the existing MCP server. Cap-10 introduces no new persisted data.

## Assumptions

- **Reuses cap-9's MCP server and cap-8's knowledge store verbatim.** No new corpus, no new retrieval
  logic; cap-10 is purely the consuming side plus a thin agent + endpoint.
- **The remote MCP endpoint is this same service's `/mcp`.** The loop is closed *within one service*
  for a self-contained, offline, no-external-dependency demonstration. The tool URL points at the
  service's own MCP surface.
- **Synchronous surface, like cap-4 and cap-8.** A tool-using single-shot agent answers within one
  request; no durable task or polling is needed.
- **Citations are out of scope for cap-10.** Unlike cap-8 (which computes ground-truth citations
  endpoint-side because it owns retrieval), cap-10 hands retrieval to the model via a tool, so the
  endpoint no longer knows which passages grounded the answer. Reporting per-answer citations would
  require capturing tool-call results from inside the model loop — deliberately deferred (it is
  exactly the cap-8 fork tradeoff). The reply is the answer text; no `citedSources` field.
- **Offline testing follows the established split.** Retrieval is deterministic and offline-provable;
  the generative half is exercised with the test model provider. Whether the model's *tool-calling
  loop over a real MCP tool* is faithfully reproducible offline in this SDK version is itself part of
  the interop question to be settled during planning/implementation; if it is not, tool-driven
  grounding is proven by a live smoke test (as delegation was in cap-7), and offline tests assert
  everything reproducible (validation contract, wiring, the shared-store parity of User Story 3).
- **Same model-agnostic setup as the rest of the project.** Local Ollama by default; tests use the
  test model provider so no key/network is required for the build.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A caller asking an in-corpus question through the endpoint receives a grounded answer
  in a single synchronous request/response.
- **SC-002**: A caller asking a question the corpus does not cover receives an honest decline, not a
  fabricated answer.
- **SC-003**: The remote retrieval tool is demonstrably exercised end-to-end — an in-corpus answer is
  produced that could only come from the retrieved passages (the loop closes: agent → MCP client →
  the service's own MCP server → the shared knowledge store).
- **SC-004**: A blank/whitespace-only question, and a malformed body, are each rejected with a client
  error, and no model or retrieval call is made for them.
- **SC-005**: For a given question, the passage(s) surfaced through the agent's remote MCP tool match
  the top passage(s) a direct retrieval against the shared knowledge store returns — confirming one
  corpus reached two ways.
- **SC-006**: The whole capability runs with no external network dependency and no API key for
  retrieval; the automated build passes without a live model.
- **SC-007**: The interop question is answered and recorded: a clear project finding states whether
  consuming a remote MCP server from Scala (`.mcpTools(url)`) is friction-free or hits the
  method-reference wall, with evidence.
