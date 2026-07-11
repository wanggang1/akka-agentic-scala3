# Feature Specification: Multi-agent greeting Workflow

**Feature Branch**: `004-multi-agent-workflow`
**Created**: 2026-07-04
**Status**: Draft
**Input**: User description: "Capability 2 — Multi-agent greeting Workflow. Orchestrate two request-based agents through an Akka Workflow in a fixed two-step sequence."

## Overview

This is capability 2 on the learning roadmap: **orchestrating two agents through an Akka Workflow**.
The existing one-shot greeting (capability 1) is split into two single-responsibility agents — one
that *detects the tone* of a message and one that *composes the greeting* given that tone — driven in
a fixed two-step sequence by a durable Workflow. Because a Workflow runs its steps asynchronously,
callers **start** a greeting and later **retrieve** the result, rather than getting it in one
request/response.

Capability 1 (the direct, synchronous greeting) is preserved unchanged so both capabilities remain
independently demonstrable.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start a greeting and retrieve the composed result (Priority: P1)

A caller submits a name, a message, and an optional timezone to begin a greeting. The request returns
immediately with a handle to the in-progress greeting. Once the two agents have run — first detecting
the message's tone, then composing a greeting that reflects that tone and the caller's time of day —
the caller retrieves a structured greeting `{greeting, tone, timeOfDay}` using the handle.

**Why this priority**: This is the core of the capability — the end-to-end orchestration of two agents
through a durable workflow. Without it there is nothing to demonstrate.

**Independent Test**: With both agents mocked (no live model), start a greeting, then poll the retrieve
endpoint until it returns the structured greeting; assert all three fields are present and that the
composed greeting reflects the tone produced by the first agent.

**Acceptance Scenarios**:

1. **Given** a valid name and message, **When** the caller starts a greeting, **Then** the caller
   receives an acknowledgement with a handle for retrieving the result, and no result is available yet.
2. **Given** a greeting that has been started and has completed, **When** the caller retrieves it by its
   handle, **Then** the caller receives a structured greeting containing `greeting`, `tone`, and
   `timeOfDay`.
3. **Given** a message whose tone is a question/help request, **When** the greeting completes, **Then**
   the `tone` reflects that intent and the `greeting` acknowledges it warmly (not a fixed template).
4. **Given** a caller-supplied timezone, **When** the greeting completes, **Then** `timeOfDay` reflects
   that timezone; **and Given** no/blank/invalid timezone, **Then** `timeOfDay` falls back to UTC.

---

### User Story 2 - Poll a greeting that is still in progress (Priority: P2)

Because the greeting is produced asynchronously, a caller may retrieve it before the workflow has
finished. The caller receives a clear "not ready yet" signal and can retry until the result is
available.

**Why this priority**: The async lifecycle is inherent to workflow orchestration; callers must be able
to distinguish "still running" from "finished" and from "never existed".

**Independent Test**: Start a greeting and immediately retrieve it; assert a distinct not-ready
response; keep polling and assert it eventually returns the completed greeting.

**Acceptance Scenarios**:

1. **Given** a greeting that has been started but not yet completed, **When** the caller retrieves it,
   **Then** the caller receives a "not ready" response distinct from success.
2. **Given** a handle that was never started, **When** the caller retrieves it, **Then** the caller
   receives a "not found / not ready" response and never a fabricated greeting.

---

### User Story 3 - Capability 1 remains independently usable (Priority: P2)

The original synchronous greeting continues to work exactly as before, at its own endpoint, with its own
single agent — unaffected by the new workflow, agents, and package restructuring.

**Why this priority**: Each roadmap capability must stay independently demonstrable; regressing
capability 1 would defeat the learning structure.

**Independent Test**: Exercise the capability-1 endpoint with the existing test suite unchanged (aside
from package relocation) and confirm identical behavior.

**Acceptance Scenarios**:

1. **Given** the capability-1 endpoint, **When** a valid request is submitted, **Then** it returns a
   structured greeting synchronously in a single request, exactly as before this feature.
2. **Given** the service, **When** it starts up, **Then** both the capability-1 and capability-2
   components are discovered and served.

---

### User Story 4 - Invalid input is rejected before any work (Priority: P3)

A caller who submits a blank name/message or a malformed body is rejected up front; no workflow is
started and no agent/model is invoked.

**Why this priority**: Cheap, fast failure and cost control (no wasted model calls) — but secondary to
the orchestration itself.

**Independent Test**: Submit blank `user`, blank `text`, and a malformed body; assert each is rejected
and that no workflow instance is created.

**Acceptance Scenarios**:

1. **Given** a blank name or blank message, **When** the caller starts a greeting, **Then** the request
   is rejected with a validation error and no workflow is started.
2. **Given** a malformed request body, **When** the caller starts a greeting, **Then** the request is
   rejected with a validation error and no workflow is started.

---

### Edge Cases

- **Tone step fails** (model returns unusable output or errors): after bounded retries the greeting
  still completes with a safe fallback tone rather than failing the whole workflow.
- **Compose step fails** (model returns non-parseable output): the greeting completes with a safe,
  model-free fallback (names the caller, neutral tone, time-of-day computed directly), never a server
  error.
- **Retrieve before start / unknown handle**: distinct "not ready / not found" — never a fabricated
  greeting.
- **Absent, blank, or invalid timezone**: falls back to UTC; never a validation error.
- **Unknown JSON properties** in the request body: tolerated (ignored), consistent with capability 1.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST let a caller start a greeting by submitting a name, a message, and an
  optional timezone, returning immediately with a handle that identifies the in-progress greeting.
- **FR-002**: The system MUST let a caller retrieve a greeting by its handle, returning the structured
  greeting `{greeting, tone, timeOfDay}` once available.
- **FR-003**: While a greeting is not yet complete (or its handle is unknown), retrieval MUST return a
  response that is clearly distinct from success and MUST NOT fabricate a greeting.
- **FR-004**: The greeting MUST be produced by orchestrating two agents in a fixed order: first a
  **tone-detection** agent that derives a tone/intent label from the message, then a **composition**
  agent that produces the structured greeting using that tone, the caller's name, message, and timezone.
- **FR-005**: The composition agent MUST report the caller's current time of day via its time-of-day
  capability, honoring the supplied timezone and falling back to UTC when it is absent/blank/invalid.
- **FR-006**: The orchestration MUST be durable: a step that fails is retried a bounded number of times,
  and terminal failure of a step degrades to a safe fallback so the greeting still completes rather than
  erroring out.
- **FR-007**: The two agents participating in one greeting MUST share a single conversation session
  (scoped to that greeting), so context flows between the steps.
- **FR-008**: The system MUST validate the name and message before starting any workflow; invalid or
  malformed input MUST be rejected without starting a workflow or invoking any agent/model.
- **FR-009**: Capability 1 (the synchronous single-agent greeting at its own endpoint) MUST remain
  behavior-identical and independently usable.
- **FR-010**: Both capabilities' components MUST be discoverable and served at startup (the
  hand-maintained component descriptor MUST list every component, including moved and new ones).
- **FR-011**: The full test suite MUST run offline with a mocked model — no API key or network required —
  covering the two-agent orchestration end to end and the async start/poll lifecycle.
- **FR-012**: Code MUST be organized into two symmetric modules — one for capability 1 and one for
  capability 2 — each with its own api / application / domain layering, with no dependency from domain
  outward or from application to api.

### Key Entities *(include if feature involves data)*

- **Greeting request (inbound)**: the caller's name, message, and optional timezone.
- **Detected tone**: a short label for the message's tone/intent (e.g. casual, question, formal),
  produced by the first agent and consumed by the second.
- **Greeting result (outbound)**: `greeting` (the composed text), `tone` (the detected label), and
  `timeOfDay` (morning/afternoon/evening/night).
- **Greeting handle**: an identifier for an in-progress or completed greeting, used to retrieve the
  result.
- **Workflow state**: the in-flight record tying a greeting request to its detected tone and final
  result as the two steps progress.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A caller can start a greeting and, by polling, retrieve a structured greeting containing
  all three fields (`greeting`, `tone`, `timeOfDay`) in 100% of valid requests.
- **SC-002**: The composed greeting reflects the tone detected in the first step (a question/help
  message yields a question-tone greeting; a casual message yields a casual one) in the tested scenarios.
- **SC-003**: Retrieving a greeting before completion, or with an unknown handle, never returns a
  fabricated greeting — it is always distinguishable from success.
- **SC-004**: 100% of blank-name, blank-message, and malformed-body requests are rejected without
  starting a workflow or invoking the model.
- **SC-005**: A terminal failure in either agent step still yields a completed greeting (safe fallback),
  never a server error, in the tested failure scenarios.
- **SC-006**: Capability 1's existing behavior is unchanged — its test suite passes without modification
  beyond package relocation.
- **SC-007**: The entire suite passes with no API key and no network access.

## Assumptions & Constraints

- **A-001 (async by design)**: A Workflow executes its steps asynchronously, so the greeting is
  exposed as start-then-retrieve (two interactions), not a single synchronous request. This is the
  intended demonstration of durable orchestration, chosen over an endpoint that blocks/polls internally.
- **A-002 (component payloads stay Java-shaped)**: Per the feature-003 two-mapper finding, everything
  serialized between components — the workflow state, both agents' request/result types, and step
  inputs — is handled by the SDK's internal serializer, not the public one, so these types stay
  Java-shaped (explicit serialization annotations, nullable fields converted to optional at the
  boundary). Only the HTTP request/response bodies may be idiomatic optional-typed.
- **A-003 (shared stable domain)**: The time-of-day computation and the name/message validation from
  capability 1 are reused, not duplicated.
- **A-004 (module placement of infrastructure)**: `HealthEndpoint` and the startup `Bootstrap`
  (JSON-module registration) are **service-wide infrastructure**, not greeting-specific. Default
  assumption: they remain at a top-level/shared location rather than moving under the capability-1
  module. *(Open for review — the requester may instead fold them into the capability-1 module.)*
- **A-005 (bounded retries)**: Agent steps use long per-step timeouts (LLM latency) and a small,
  bounded retry count to avoid runaway model cost.
- **A-006 (provider constraint)**: The composition agent both uses a tool and returns structured
  output; on the configured provider these cannot be combined via native JSON-schema mode, so it
  instructs the model to emit JSON and parses the reply, with a safe fallback — consistent with
  capability 1.

## Out of Scope

- Real-time push of progress to the caller (e.g. server-sent events) — polling is sufficient here.
- Model-driven (autonomous) orchestration — that is capability 3; this feature is a fixed, code-defined
  sequence.
- Multi-turn session memory across separate greetings — that is capability 4.
- Changing capability 1's behavior or its synchronous contract.
