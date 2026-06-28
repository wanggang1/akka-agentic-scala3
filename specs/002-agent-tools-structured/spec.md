# Feature Specification: Structured, context-aware greeting

**Feature Branch**: `002-agent-tools-structured`
**Created**: 2026-06-28
**Status**: Draft
**Input**: User description: "Extend the greeting agent with a function tool and a structured (typed) response — the agent calls a tool for contextual data (time-of-day) and returns a structured Greeting object (greeting text, detected tone/intent, time-of-day) instead of a plain string; the endpoint returns this structured JSON; invalid input still returns 400 without calling the model."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Receive a structured greeting response (Priority: P1)

A client that integrates the greeting service wants the response as a structured object with
named fields — the greeting text plus metadata about it — rather than a bare line of text, so it
can use the parts programmatically (display the message, label its tone, show the time context).

**Why this priority**: This is the core shift of the feature: from an opaque string to a typed,
machine-readable result. Everything else builds on it. On its own it already delivers value — a
client gets a predictable object it can parse and reason about.

**Independent Test**: `POST /greet` with a valid body returns `200` and a JSON object containing a
non-empty greeting plus the metadata fields (tone and time-of-day), each present and typed.

**Acceptance Scenarios**:

1. **Given** a valid `{user, text}` request, **When** the caller posts it, **Then** the response is
   a structured object with a non-empty greeting that names the user, a tone/intent label, and a
   time-of-day value.
2. **Given** the same request, **When** the response is parsed, **Then** every documented field is
   present (no field is missing or null).

---

### User Story 2 - Greeting reflects the current time of day (Priority: P2)

The greeting incorporates real, request-time context — the current time of day (e.g. morning,
afternoon, evening, night) — so it reads as situationally appropriate rather than generic, and the
returned `timeOfDay` field reflects the actual time the request was handled.

**Why this priority**: This demonstrates the agent drawing on live contextual data at request time
instead of a fixed template, making greetings feel current. It depends on US1's structured result
to carry the value back to the caller.

**Independent Test**: With the time context controlled, a request handled in the morning yields a
`timeOfDay` of "morning" (and a greeting consistent with it), and a request handled in the evening
yields "evening" — the two differ.

**Acceptance Scenarios**:

1. **Given** the current time is morning, **When** a valid request is greeted, **Then** the
   response's `timeOfDay` is "morning".
2. **Given** an optional caller-provided timezone, **When** a valid request is greeted, **Then** the
   `timeOfDay` is computed for that timezone; with no timezone provided, a documented default zone
   is used.
3. **Given** the contextual time lookup is unavailable, **When** a valid request is greeted,
   **Then** a greeting is still returned (graceful fallback) rather than an error.

---

### User Story 3 - Invalid input is still rejected cleanly (Priority: P3)

Malformed or incomplete requests continue to be rejected with a clear `400` and no greeting, and
without invoking the model — preserving the robustness and cost-control behavior from the baseline
greeting service.

**Why this priority**: Regression protection. The structured-response change must not weaken the
existing validation contract.

**Independent Test**: `POST /greet` with a blank `user` (or blank `text`, or a malformed JSON body)
returns `400` and never invokes the model.

**Acceptance Scenarios**:

1. **Given** a request with a blank `user`, **When** it is posted, **Then** the response is `400`
   with a validation message and no greeting is produced.
2. **Given** a malformed JSON body, **When** it is posted, **Then** the response is `400`.

---

### Edge Cases

- **Unknown / invalid timezone supplied**: the system falls back to the default timezone rather
  than failing the request.
- **Contextual time lookup fails**: the greeting is still produced; `timeOfDay` carries a documented
  fallback value (e.g. "unknown") rather than erroring the whole request.
- **Model returns a result that does not match the expected structure**: the request fails cleanly
  (a server error), and tests assert the structured-happy-path separately from this case.
- **Extra/unexpected fields in the request body**: accepted and ignored (carried from baseline).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The greeting endpoint MUST return a structured response object containing, at minimum:
  the greeting text, a detected tone/intent label, and a time-of-day value.
- **FR-002**: The greeting MUST address the user by name and acknowledge their message (carried from
  baseline US1).
- **FR-003**: The tone/intent label MUST reflect the message's intent (e.g. a question vs. a casual
  hello), consistent with the baseline intent-adaptation behavior.
- **FR-004**: The time-of-day value MUST be derived from the actual current time at request handling,
  obtained through a callable contextual lookup rather than a hardcoded constant.
- **FR-005**: The time-of-day MUST be computable for an optional caller-supplied timezone; when none
  is supplied, a documented default timezone is used.
- **FR-006**: Invalid input (blank `user`/`text`, or a body that cannot be parsed) MUST return `400`
  without invoking the model.
- **FR-007**: The API response type MUST be distinct from internal domain/application types (no
  internal type is exposed directly).
- **FR-008**: If the contextual time lookup fails or returns an unusable value, the system MUST still
  return a greeting with a documented fallback time-of-day value.
- **FR-009**: The behavior MUST be verifiable deterministically with a mocked model — no live model,
  API key, or network required for tests.

### Key Entities

- **Greeting result**: the structured response. Attributes: greeting text (string), tone/intent
  label (short string, e.g. "casual" / "question"), time-of-day (string, e.g.
  "morning"/"afternoon"/"evening"/"night"/"unknown").
- **Time-of-day context**: the contextual value the agent obtains at request time — a coarse label
  derived from the current clock time in a given timezone.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a valid request, 100% of responses are a structured object with all three fields
  (greeting, tone, time-of-day) present and non-empty.
- **SC-002**: With the time context controlled, a morning request and an evening request for the
  same user return different `timeOfDay` values, each matching the controlled time.
- **SC-003**: 100% of invalid requests return `400` with no greeting and no model invocation.
- **SC-004**: The full automated test suite passes from a clean checkout with no API key or network
  (deterministic, mocked model).
- **SC-005**: The greeting names the user and is consistent with both the detected tone and the
  reported time-of-day.

## Assumptions

- The greeting remains a single request/response interaction (no multi-turn memory in this feature —
  that is a later exploration).
- "Time of day" is a coarse label (morning/afternoon/evening/night) derived from the hour; exact
  boundaries are an implementation detail recorded in the plan.
- The default timezone (when the caller supplies none) is UTC unless the plan documents otherwise.
- Tone/intent is a short free-form label produced by the model, not a fixed enumeration.
- This feature builds on `001-greeting-agent` and reuses its request shape (`user`, `text`),
  adding an optional `timezone` field.
