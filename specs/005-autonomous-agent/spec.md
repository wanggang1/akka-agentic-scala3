# Feature Specification: Autonomous help-desk Agent

**Feature Branch**: `005-autonomous-agent`
**Created**: 2026-07-11
**Status**: Draft
**Input**: User description: "Capability 3 — a single Autonomous Agent (HelpDeskAgent) that answers a
user's help question via a model-driven iteration loop, optionally consulting a knowledge-base tool,
and completes with a typed result. Async start/poll HTTP surface. Single agent, single task type — no
delegation/handoff/teams."

## Overview

This is capability 3 on the learning roadmap: a **model-driven Autonomous Agent** with a typed task.
Where capability 2 orchestrated agents through a *fixed, code-defined* Workflow sequence, capability 3
lets the **model itself drive the loop**: given a user's question, the agent decides on its own whether
to consult a knowledge-base tool (one or more times) before it produces its answer, then **completes a
typed task** carrying the structured answer.

The unit of work is a durable **task** with its own identity and lifecycle: it is started, runs
asynchronously while the model iterates, and its typed result outlives the request — queryable at any
time by its handle. As with capability 2, the HTTP surface is therefore **start-then-poll**.

Scope is deliberately one agent with one task type. Multi-agent coordination (delegation, handoff,
teams, moderation) is explicitly deferred to a future capability. Capabilities 1 and 2 are preserved
unchanged so all three remain independently demonstrable.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ask a help question and retrieve a typed answer (Priority: P1) 🎯 MVP

A caller submits a help question. The request returns immediately with a handle to the in-progress
work. The agent then reasons about the question — consulting a knowledge base of the caller's own
accord when it judges that useful — and produces a structured answer `{answer, category, citedTopics,
confidence}`. The caller retrieves that answer using the handle.

**Why this priority**: This is the core of the capability — a durable, model-driven task that iterates
and yields a typed result. Without it there is nothing to demonstrate.

**Independent Test**: With the model mocked (no live model), submit a question, then poll the retrieve
endpoint until it returns the structured answer; assert all four fields are present and reflect the
mocked completion, including a case where the mocked model consults the knowledge-base tool before
answering.

**Acceptance Scenarios**:

1. **Given** a valid question, **When** the caller submits it, **Then** the caller receives an
   acknowledgement with a handle for retrieving the result, and no result is available yet.
2. **Given** a submitted question that has completed, **When** the caller retrieves it by its handle,
   **Then** the caller receives a structured answer containing `answer`, `category`, `citedTopics`, and
   `confidence`.
3. **Given** a question that benefits from the knowledge base, **When** the agent iterates, **Then** it
   may consult the knowledge-base capability one or more times of its own accord, and the returned
   answer reflects what it found (the cited topics are populated).
4. **Given** a question the agent can answer directly, **When** it iterates, **Then** it may complete
   without consulting the knowledge base — the decision is the model's, not a fixed step.

---

### User Story 2 - Observe the asynchronous task lifecycle (Priority: P2)

Because the answer is produced asynchronously while the model iterates, a caller may retrieve it before
the task is complete, or ask about a handle that was never started, or retrieve a task that the agent
determined it could not answer. Each of these is clearly distinguished from success.

**Why this priority**: The durable-task lifecycle is inherent to an Autonomous Agent; callers must be
able to tell "still running" from "finished", "never existed", and "finished as a failure".

**Independent Test**: Start a task and immediately retrieve it (assert a distinct not-ready response);
retrieve a random handle (assert not-found); mock the model to abandon the task and assert the caller
sees a distinct failure signal, never a fabricated answer.

**Acceptance Scenarios**:

1. **Given** a task that has been started but not yet completed, **When** the caller retrieves it,
   **Then** the caller receives a "not ready" response distinct from success.
2. **Given** a handle that was never started, **When** the caller retrieves it, **Then** the caller
   receives a "not found / not ready" response and never a fabricated answer.
3. **Given** a task the agent reports it cannot complete, **When** the caller retrieves it, **Then** the
   caller receives a response that is distinct from both success and not-ready, and never a fabricated
   answer.

---

### User Story 3 - Capabilities 1 and 2 remain independently usable (Priority: P2)

The existing synchronous greeting (capability 1) and the multi-agent greeting workflow (capability 2)
continue to work exactly as before, at their own endpoints, unaffected by the new agent, task, and
package additions.

**Why this priority**: Each roadmap capability must stay independently demonstrable; regressing an
earlier capability would defeat the learning structure.

**Independent Test**: Exercise capabilities 1 and 2 with their existing test suites unchanged and
confirm identical behavior; confirm the service discovers and serves all three capabilities' components
at startup.

**Acceptance Scenarios**:

1. **Given** the capability-1 and capability-2 endpoints, **When** valid requests are submitted, **Then**
   they behave exactly as before this feature.
2. **Given** the service, **When** it starts up, **Then** the capability-1, capability-2, and
   capability-3 components are all discovered and served.

---

### User Story 4 - Invalid input is rejected before any work (Priority: P3)

A caller who submits a blank question or a malformed body is rejected up front; no task is started and
no agent/model is invoked.

**Why this priority**: Cheap, fast failure and cost control (no wasted model calls) — but secondary to
the core capability.

**Independent Test**: Submit a blank question and a malformed body; assert each is rejected and that no
task is created.

**Acceptance Scenarios**:

1. **Given** a blank question, **When** the caller submits it, **Then** the request is rejected with a
   validation error and no task is started.
2. **Given** a malformed request body, **When** the caller submits it, **Then** the request is rejected
   with a validation error and no task is started.

---

### Edge Cases

- **Retrieve before completion / unknown handle**: distinct "not ready / not found" — never a fabricated
  answer.
- **Agent abandons the task** (model reports it cannot make progress): the task ends in a terminal
  failure state, surfaced to the caller as distinct from success and not-ready.
- **Runaway iteration**: a bounded per-task iteration limit terminates a task that reaches neither a
  completion nor a failure, so a task can never loop forever.
- **Knowledge-base miss**: when the consulted topic is unknown, the tool returns a clear "no entry"
  result and the agent still completes with an answer (empty cited topics), rather than erroring.
- **Unknown JSON properties** in the request body: tolerated (ignored), consistent with capabilities 1–2.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST let a caller submit a help question, returning immediately with a handle
  that identifies the in-progress task.
- **FR-002**: The system MUST let a caller retrieve the result by its handle, returning the structured
  answer `{answer, category, citedTopics, confidence}` once the task has completed.
- **FR-003**: While a task is not yet complete (or its handle is unknown), retrieval MUST return a
  response clearly distinct from success and MUST NOT fabricate an answer.
- **FR-004**: The answer MUST be produced by a **model-driven** agent that decides for itself whether and
  how often to consult the knowledge-base capability before completing — not by a fixed, code-defined
  sequence of steps.
- **FR-005**: The agent MUST expose a **knowledge-base lookup** capability that, given a topic, returns a
  canned reference entry or a clear "no entry" result; the agent MUST be able to invoke it zero or more
  times within a single task.
- **FR-006**: The task MUST be **durable and independently queryable**: its status and typed result
  survive restarts and are retrievable by its handle at any time, without wrapping it in a workflow.
- **FR-007**: The agent MUST run a **bounded** iteration loop; a per-task iteration limit MUST terminate
  work that reaches neither completion nor failure.
- **FR-008**: A task the agent reports it cannot complete MUST end in a terminal **failure** state that
  retrieval surfaces distinctly from success and from not-ready.
- **FR-009**: The system MUST validate the question before starting any task; invalid or malformed input
  MUST be rejected without starting a task or invoking any agent/model.
- **FR-010**: Capabilities 1 and 2 MUST remain behavior-identical and independently usable.
- **FR-011**: All capabilities' components MUST be discoverable and served at startup (the
  hand-maintained component descriptor MUST list every component, including the new ones).
- **FR-012**: The full test suite MUST run offline with a mocked model — no API key or network required —
  covering the model-driven task end to end (including a tool-consulting iteration and a failure path)
  and the async start/poll lifecycle.
- **FR-013**: Code MUST be organized into its own api / application / domain layering for this
  capability, with no dependency from domain outward or from application to api.

### Key Entities *(include if feature involves data)*

- **Help question (inbound)**: the caller's free-text question.
- **Help task**: the durable unit of work created for one question — has a handle, a status
  (in progress / completed / failed), and, on success, a typed result.
- **Help answer (outbound / task result)**: `answer` (the response text), `category` (a short
  classification of the question), `citedTopics` (knowledge-base topics the agent consulted, possibly
  empty), and `confidence` (the agent's self-reported confidence).
- **Knowledge-base entry**: a canned reference keyed by topic that the agent may consult; a lookup either
  returns an entry or a "no entry" result.
- **Task handle**: an identifier for an in-progress, completed, or failed task, used to retrieve the
  result.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A caller can submit a question and, by polling, retrieve a structured answer containing all
  four fields (`answer`, `category`, `citedTopics`, `confidence`) in 100% of valid, completing requests.
- **SC-002**: When the (mocked) agent consults the knowledge base before answering, the retrieved answer
  reflects that consultation (cited topics populated) in the tested scenario.
- **SC-003**: Retrieving a task before completion, or with an unknown handle, never returns a fabricated
  answer — it is always distinguishable from success.
- **SC-004**: A task the agent abandons ends in a failure state that is distinguishable from both success
  and not-ready in the tested scenario.
- **SC-005**: 100% of blank-question and malformed-body requests are rejected without starting a task or
  invoking the model.
- **SC-006**: Capabilities 1 and 2 behave unchanged — their test suites pass without modification.
- **SC-007**: The entire suite passes with no API key and no network access.

## Assumptions & Constraints

- **A-001 (async by design)**: An Autonomous Agent processes its task asynchronously while the model
  iterates, so the work is exposed as start-then-retrieve (two interactions), not a single synchronous
  request. This is the intended demonstration of a durable, model-driven task.
- **A-002 (model-driven, not step-wired)**: Unlike capability 2, the *sequence* of what the agent does
  (consult the knowledge base or answer directly, and how many times) is a model judgment, not code. The
  developer declares the agent's purpose, its accepted task type and result shape, its tool, and a
  bounded iteration limit — the runtime drives the loop.
- **A-003 (single agent, single task type)**: No delegation, handoff, teams, or moderation in this
  capability. Those multi-agent coordination patterns are a deliberate future capability.
- **A-004 (task result stays platform-shaped)**: Per the feature-003 two-mapper finding, the task result
  (and any instruction payload) is serialized between components by the SDK's internal serializer, not
  the public one, so these types stay platform-shaped (explicit serialization annotations). Only the
  HTTP request/response bodies may be idiomatic optional-typed.
- **A-005 (offline, deterministic tests)**: The model is mocked for all automated tests — including the
  agent's built-in task-completion and tool-invocation behavior — so the suite runs with no key/network.
- **A-006 (bounded cost)**: The per-task iteration limit is small, to demonstrate the loop while avoiding
  runaway model cost.
- **A-007 (canned knowledge base)**: The knowledge base is a small, in-memory set of canned entries — the
  point is to demonstrate a model-invoked tool within the iteration loop, not to build a real retrieval
  system.

## Out of Scope

- Multi-agent coordination — delegation, handoff, teams, moderation — is a future capability.
- Real retrieval / vector search / external knowledge sources — the knowledge base is canned.
- Real-time push of intermediate progress to the caller (e.g. a live notification stream) — polling the
  task result is sufficient here.
- Multi-turn session memory across separate questions — that is capability 4.
- Changing capabilities 1 or 2, or their contracts.
