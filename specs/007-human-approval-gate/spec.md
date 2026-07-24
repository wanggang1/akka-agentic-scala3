# Feature Specification: Human-in-the-loop approval gate

**Feature Branch**: `007-human-approval-gate`
**Created**: 2026-07-17
**Status**: Draft
**Input**: User description: "Capability 5 — Human-in-the-loop approval gate (Scala, exploratory
follow-up). A human-approval flow built on the Akka Autonomous Agent 'external input' pattern (NOT an
Akka Workflow — that would force Java per the project's method-ref-wall finding). An agent drafts a
customer reply; a human-approval task (no agent assigned, a task dependency) gates it; on approval a
downstream step produces the published reply, on rejection the chain ends rejected. Start-then-poll
HTTP surface with a human decision endpoint. Prove human-in-the-loop works as idiomatic Scala with no
Workflow/Java detour."

## Overview

This is **capability 5** on the learning roadmap — an **exploratory follow-up** beyond the original
four. Where capability 3 let a single model-driven Autonomous Agent complete a task on its own,
capability 5 puts a **human in the loop**: an agent produces a *draft*, and the work then **pauses at a
gate that only a person can release**. A human reviewer approves the draft (the work continues to a
published reply) or rejects it (the chain ends, carrying the reviewer's note).

The mechanism is the Akka Autonomous Agent **"external input"** pattern: human intervention is modeled
as an ordinary **task with no agent assigned**, sitting as a dependency between an upstream *draft* task
and a downstream *publish* task. The framework makes the rest of the pipeline wait on that gate; an
external caller (the human, via HTTP) **completes** the gate to approve or **fails** it to reject.
Because the draft is produced asynchronously and the gate then waits for a person, the HTTP surface is
**start-then-poll**, plus a **decision** endpoint the reviewer calls.

The capability's **learning goal** is an interop proof: the human-approval gate is built as **idiomatic
Scala with no Java detour**. This is possible because the task client (`create` / `get` / `result` /
`complete` / `fail`) is keyed on task-definition value objects and string handles — **no Java
method-reference parameter** (verified against the SDK 3.6.0 bytecode). The alternative — a Workflow
`pause`/`resume` gate — would force the whole capability into Java, because Workflow step wiring and
`resume(...)` are method-reference-only (the project's recurring "method-ref wall"). Capability 5 is the
counter-example: a durable, human-gated, multi-step flow that stays in Scala.

Capabilities 1–4 are preserved unchanged so all five remain independently demonstrable.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Approve a drafted reply and see it published (Priority: P1) 🎯 MVP

A caller submits a customer question. The request returns immediately with a handle. An agent drafts a
reply, and the work then waits at a human gate. A reviewer retrieves the pending draft, **approves** it,
and the flow proceeds to produce the published reply, which the caller can then retrieve by the handle.

**Why this priority**: This is the core of the capability — a durable flow that halts at a human gate
and only proceeds when a person approves. Without the approve-and-release path there is nothing to
demonstrate.

**Independent Test**: With the model mocked, submit a question and poll until the state is
"awaiting approval" showing the draft text; submit an approval decision on the handle; poll until the
state is "published" and assert the published reply corresponds to the approved draft — and assert that
no published reply existed *before* the approval.

**Acceptance Scenarios**:

1. **Given** a valid question, **When** the caller submits it, **Then** the caller receives an
   acknowledgement with a handle, and no draft or published reply is available yet.
2. **Given** a submitted question whose draft is ready, **When** the reviewer retrieves it by handle,
   **Then** the state is "awaiting approval" and the draft text is shown.
3. **Given** a draft awaiting approval, **When** the reviewer approves it, **Then** the downstream
   publish step runs and, on polling, the state becomes "published" with the final reply.
4. **Given** a draft still awaiting approval (not yet decided), **When** the caller retrieves it,
   **Then** no published reply is present — the publish step has not run.

---

### User Story 2 - Reject a drafted reply (Priority: P2)

A reviewer who is not satisfied with a draft **rejects** it, optionally with a note. The chain ends in a
terminal "rejected" state; no reply is ever published, and the caller can see the rejection (and note)
by the handle.

**Why this priority**: A gate that can only say "yes" is not a gate. Rejection is the other half of the
human decision and proves the downstream step is truly conditional on approval.

**Independent Test**: Submit a question, poll to "awaiting approval", submit a rejection with a note;
poll and assert the state is "rejected" carrying the note, and that no published reply is ever produced.

**Acceptance Scenarios**:

1. **Given** a draft awaiting approval, **When** the reviewer rejects it with a note, **Then** the state
   becomes "rejected" and the note is retained.
2. **Given** a rejected chain, **When** the caller retrieves it, **Then** no published reply is present,
   now or ever.

---

### User Story 3 - Observe the gated lifecycle by polling (Priority: P2)

Because work is produced asynchronously and then waits for a person, a caller may retrieve the handle at
any point and must be able to tell the states apart: draft in progress, awaiting approval, published,
rejected, or an unknown handle.

**Why this priority**: The whole point of a gate is that its state is observable — callers and reviewers
must distinguish "still drafting" from "waiting for you" from "done" from "never existed".

**Independent Test**: Start a chain and immediately retrieve it (assert a "drafting / not ready" state
distinct from "awaiting approval"); retrieve a random handle (assert "not found"); drive it to each
terminal state and assert each is distinct.

**Acceptance Scenarios**:

1. **Given** a chain whose draft is not yet ready, **When** the caller retrieves it, **Then** the state
   is distinct from "awaiting approval", "published", and "rejected".
2. **Given** a handle that was never started, **When** the caller retrieves it, **Then** the caller
   receives "not found" and never a fabricated draft or reply.
3. **Given** chains driven to "published" and to "rejected", **When** retrieved, **Then** the two
   terminal states are clearly distinguishable from each other and from the in-progress states.

---

### User Story 4 - Invalid input and invalid decisions are rejected (Priority: P3)

A caller who submits a blank question or a malformed body is turned away before any work starts. A
decision aimed at an unknown handle, or at a chain that is not awaiting approval, is refused without
corrupting state; a repeated decision on an already-decided gate does not double-publish.

**Why this priority**: Cheap, fast failure and state integrity — but secondary to the core gate.

**Independent Test**: Submit a blank question and a malformed body (assert each rejected, no chain
started); submit a decision to an unknown handle (assert "not found"); submit a second decision after a
gate is already decided (assert it does not change the outcome or publish twice).

**Acceptance Scenarios**:

1. **Given** a blank question, **When** the caller submits it, **Then** the request is rejected with a
   validation error and no chain is started.
2. **Given** a malformed request body (question or decision), **When** submitted, **Then** it is rejected
   with a validation error and no state changes.
3. **Given** an unknown handle, **When** a decision is submitted, **Then** the caller receives "not
   found" and no chain is affected.
4. **Given** a gate that has already been approved or rejected, **When** a second decision is submitted,
   **Then** the outcome is unchanged and no second publish occurs.

---

### User Story 5 - Capabilities 1–4 remain independently usable (Priority: P2)

The existing greeting (1), multi-agent workflow (2), autonomous help-desk (3), and chat/session-memory
(4) capabilities continue to work exactly as before, at their own endpoints, unaffected by the new
agent(s), task chain, and package additions.

**Why this priority**: Each roadmap capability must stay independently demonstrable; regressing an
earlier one would defeat the learning structure.

**Independent Test**: Run capabilities 1–4 with their existing test suites unchanged; confirm the
service discovers and serves all five capabilities' components at startup.

**Acceptance Scenarios**:

1. **Given** the capability 1–4 endpoints, **When** valid requests are submitted, **Then** they behave
   exactly as before this feature.
2. **Given** the service, **When** it starts up, **Then** the components of all five capabilities are
   discovered and served.

---

### Edge Cases

- **Decision before the draft is ready**: a decision submitted while the chain is still drafting (the
  gate is not yet open) is refused distinctly from success — it does not skip the draft.
- **Decision on an unknown handle**: "not found", never a fabricated chain.
- **Double decision**: a second approve/reject after the gate is decided is a safe no-op — never a second
  publish, never a flip from published to rejected.
- **Rejection stops downstream**: after a rejection, the publish step never runs — verifiable by the
  permanent absence of a published reply.
- **Draft agent abandons its task**: if the upstream draft cannot be produced, the chain ends without
  ever reaching the gate, surfaced distinctly from "awaiting approval".
- **No auto-timeout on the gate**: the human gate waits indefinitely in this capability; there is no
  automatic approval/rejection if no one decides (an approval SLA is out of scope).
- **Unknown JSON properties** in either body: tolerated (ignored), consistent with capabilities 1–4.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST let a caller submit a question, returning immediately with a handle that
  identifies the approval case; a draft reply MUST then be produced asynchronously by an agent.
- **FR-002**: The system MUST let a caller retrieve, by handle, the current state of the case — at least:
  *drafting* (not yet ready), *awaiting approval* (with the draft text), *published* (with the final
  reply), *rejected* (with any note), and *not found*.
- **FR-003**: While a case is drafting, or its handle is unknown, retrieval MUST return a state clearly
  distinct from *awaiting approval*, *published*, and *rejected*, and MUST NOT fabricate a draft or
  reply.
- **FR-004**: A human reviewer MUST be able to **approve** or **reject** a draft that is awaiting
  approval, addressing the case by its handle; a rejection MAY carry a free-text note.
- **FR-005**: On approval, the system MUST run the downstream publish step and reach a terminal
  *published* state whose final reply is retrievable by the handle.
- **FR-006**: On rejection, the case MUST reach a terminal *rejected* state that retains any note, and no
  reply MUST ever be published for that case.
- **FR-007**: The publish step MUST be **genuinely gated** on the human decision — it MUST NOT run until,
  and unless, the reviewer approves. (This conditional dependency is the core demonstration.)
- **FR-008**: A decision addressed to an unknown handle, or to a case not currently awaiting approval,
  MUST be refused distinctly (not treated as success) and MUST NOT corrupt or advance the case.
- **FR-009**: A repeated decision on an already-decided gate MUST be a safe no-op: the terminal outcome
  MUST NOT change and no second publish MUST occur.
- **FR-010**: The system MUST validate inputs before doing work: a blank question or a malformed
  question/decision body MUST be rejected without starting a case, changing state, or invoking any
  agent/model.
- **FR-011**: The case MUST be **durable**: the pending gate and the case state survive restarts, so a
  draft awaiting approval can be approved (or rejected) after a restart and still publish (or stop) —
  without wrapping the flow in a Workflow.
- **FR-012**: Capabilities 1–4 MUST remain behavior-identical and independently usable.
- **FR-013**: All capabilities' components MUST be discoverable and served at startup (the
  hand-maintained component descriptor MUST list every component, including the new ones).
- **FR-014**: The full automated test suite MUST run offline with a mocked model — no API key or network
  — covering the approve-and-publish path, the reject path, the gated-lifecycle states, and the
  validation/decision-integrity rules. The end-to-end human gate MUST additionally be proven by a live
  smoke test.
- **FR-015**: Code MUST be organized into its own api / application / domain layering for this
  capability, with no dependency from domain outward or from application to api.

### Key Entities *(include if feature involves data)*

- **Question (inbound)**: the caller's free-text customer question to be answered.
- **Draft**: the agent-produced candidate reply, shown to the reviewer while the case awaits approval.
- **Approval decision (inbound, human)**: an approve-or-reject choice for a case, with an optional
  free-text note (used especially on rejection).
- **Published reply (outbound)**: the final reply, produced only after approval.
- **Approval case**: the durable, gated unit of work spanning draft → human gate → publish; has a
  handle and a state (drafting / awaiting approval / published / rejected).
- **Human gate**: the point in the case, owned by no agent, that a reviewer must complete (approve) or
  fail (reject) for the case to proceed or stop; the rest of the case waits on it.
- **Case handle**: an identifier used to retrieve a case's state and to address a decision to it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A caller can submit a question and, by polling, reach an "awaiting approval" state that
  shows the draft, in 100% of valid requests (mocked model).
- **SC-002**: Approving a case leads, on polling, to a "published" state whose reply corresponds to the
  approved draft, in 100% of approved cases.
- **SC-003**: In every case, no published reply is ever observable before approval, and none is ever
  produced for a rejected case.
- **SC-004**: Rejecting a case leads to a "rejected" state that retains the reviewer's note, distinct
  from success and from the in-progress states.
- **SC-005**: Decisions on unknown handles, on not-yet-gated cases, and repeated decisions on
  already-decided gates are each handled distinctly and never cause a fabricated or duplicated outcome.
- **SC-006**: 100% of blank-question and malformed-body requests are rejected without starting a case or
  invoking the model.
- **SC-007**: Capabilities 1–4 behave unchanged — their test suites pass without modification, and all
  five capabilities' components are served at startup.
- **SC-008**: The entire automated suite passes with no API key and no network access; the end-to-end
  human gate is additionally confirmed by a live smoke test (approve → published; reject → not
  published).

## Assumptions & Constraints

- **A-001 (human-in-the-loop = external input on a task chain)**: Human intervention is modeled as a task
  with **no agent assigned**, sitting as a dependency between an upstream draft task and a downstream
  publish task. The reviewer **completes** that gate to approve or **fails** it to reject; the framework
  makes the downstream wait on the gate. No Akka Workflow is used.
- **A-002 (the interop proof — idiomatic Scala, no Java detour)**: The entire capability, including the
  human decision call, is authored in **Scala**. This is possible because the task client
  (`create`/`get`/`result`/`complete`/`fail`) is keyed on task-definition value objects and string
  handles, with **no Java method-reference parameter** (verified against SDK 3.6.0 bytecode) — so there
  is no "method-ref wall". Contrast: a Workflow `pause`/`resume` gate would force Java, because Workflow
  step wiring and `resume(...)` are method-reference-only. Capability 5 exists to demonstrate this
  difference.
- **A-003 (async + human wait by design)**: A draft is produced asynchronously, then the gate waits for a
  person, so the flow is start → poll → decide → poll (multiple interactions), not one synchronous
  request.
- **A-004 (payloads stay platform-shaped)**: Per the feature-003 two-mapper finding, task results and
  instruction payloads are serialized between components by the SDK's internal serializer, so those
  types stay platform-shaped (explicit serialization annotations). Only the HTTP request/response bodies
  may be idiomatic optional-typed.
- **A-005 (offline, deterministic tests + one live proof)**: The model is mocked for all automated tests
  (draft and publish completions), so the suite runs with no key/network; the human gate's end-to-end
  behavior is proven separately by a live smoke test.
- **A-006 (no gate timeout)**: The human gate waits indefinitely; there is no automatic decision if no
  one responds. An approval SLA / timeout / escalation is out of scope.
- **A-007 (no reviewer identity or authorization)**: Any caller holding the handle may decide; reviewer
  authentication, roles, and audit of *who* decided are out of scope — the focus is the gate mechanism.
- **A-008 (minimal generation)**: The draft and publish steps are intentionally simple (the publish step
  may be a light finalization of the approved draft); the point is the human gate and its downstream
  release, not sophisticated text generation.
- **A-009 (approve/reject only, no edit)**: A decision is approve-or-reject with an optional note; editing
  the draft as part of approval ("approve with changes") is out of scope.

## Out of Scope

- Reviewer authentication, authorization, roles, and an audit trail of who approved/rejected.
- Approval SLA / gate timeout / escalation / reminders.
- Multi-reviewer or quorum approvals (more than one person must sign off).
- "Approve with edits" — modifying the draft text as part of the decision.
- Real-time push/notification to reviewers that a draft is waiting (reviewers poll).
- Sophisticated draft generation, retrieval, or a real knowledge base (that was capability 3).
- Changing capabilities 1–4 or their contracts.
