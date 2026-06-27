# Feature Specification: Greeting Agent Service Baseline

**Feature Branch**: `001-greeting-agent`
**Created**: 2026-06-26
**Status**: Draft
**Input**: User description: "Create a baseline Scala 3 project structure for an agentic service. Define a greeting agent that accepts a typed JSON payload with user and text fields using Scala 3 enums or case classes, and leverages the Akka Agentic Platform Effects API."

## Clarifications

### Session 2026-06-26

- Q: How should the greeting actually be produced? → A: LLM model call — the greeting agent composes the greeting by calling a language model through the Agentic Platform Effects API (not a fixed template).
- Q: What transport should expose the greeting capability? → A: A single HTTP endpoint accepting the typed JSON payload and returning a JSON response.
- Q: What shape should the agent's response take? → A: The agent returns a plain greeting string; the endpoint wraps it in a small JSON response object.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Receive a personalized greeting (Priority: P1)

A caller submits a request that identifies who they are and what they want to say, and the
service returns a friendly, personalized greeting that incorporates the caller's identity and
message. This is the core value of the service: a working agentic endpoint that turns a typed
request into a tailored response.

**Why this priority**: Without this the service delivers no value. It is the minimum viable
slice — a single request that produces a meaningful greeting proves the project structure,
the typed request contract, and the agent interaction all work end to end.

**Independent Test**: Submit a request containing a user identifier and a text message, and
confirm the response is a coherent greeting that references the supplied user and reflects the
supplied message. Fully testable in isolation with no other stories implemented.

**Acceptance Scenarios**:

1. **Given** a valid request containing a user identifier and a non-empty text message, **When** the caller submits it, **Then** the service returns a greeting that addresses the named user and acknowledges the message.
2. **Given** two requests with the same user but different messages, **When** each is submitted, **Then** each receives a distinct greeting appropriate to its message.

---

### User Story 2 - Reject malformed or incomplete requests (Priority: P2)

A caller submits a request that is missing a required field, has an empty user identifier, or
is otherwise malformed. The service rejects it with a clear, actionable error rather than
producing a low-quality or empty greeting.

**Why this priority**: Validating the typed contract protects response quality and gives
callers fast, understandable feedback. It builds directly on Story 1 but is not required for a
first demonstration of value.

**Independent Test**: Submit requests with a missing user, an empty user, and a missing
message, and confirm each is rejected with an error that identifies the problem.

**Acceptance Scenarios**:

1. **Given** a request with an empty or missing user identifier, **When** the caller submits it, **Then** the service rejects it with a validation error and produces no greeting.
2. **Given** a request with a missing or empty text message, **When** the caller submits it, **Then** the service rejects it with a validation error.
3. **Given** a request whose payload does not match the expected typed structure, **When** the caller submits it, **Then** the service returns a client error indicating the payload is invalid.

---

### User Story 3 - Adapt greeting to message intent (Priority: P3)

A caller's message carries an intent — for example a question, a thanks, or a casual hello —
and the greeting reflects that intent rather than being a single fixed template.

**Why this priority**: Adapting tone demonstrates the agentic nature of the service and makes
responses feel relevant, but the service is already useful with a single warm greeting style,
so this is an enhancement.

**Independent Test**: Submit messages of clearly different intent (a question vs. a casual
hello) for the same user and confirm the greetings differ in a way consistent with the intent.

**Acceptance Scenarios**:

1. **Given** a message phrased as a question, **When** the caller submits it, **Then** the greeting responds in a manner that acknowledges the question.
2. **Given** a casual one-word message, **When** the caller submits it, **Then** the greeting responds in a correspondingly casual tone.

---

### Edge Cases

- What happens when the text message is extremely long? The service should still respond or reject with a clear size-related error rather than hang or fail silently.
- How does the system handle a user identifier containing unusual characters (whitespace, emoji, very long strings)? It should either accept and safely incorporate it or reject it with a clear validation error.
- What happens when the same caller submits many requests in quick succession? Each request should be handled independently and produce its own greeting.
- How does the system handle a payload with extra unexpected fields? It should ignore unknown fields and process the recognized ones, or reject with a clear error.

### Edge Case Handling (Baseline Scope)

For this baseline the edge cases above are handled as follows:

- **Unknown/extra JSON fields**: ignored — deserialization tolerates unknown properties (no rejection). [In scope]
- **Very long `text`, unusual characters in `user`, rapid-succession requests**: out of scope for the baseline. Inputs are assumed short, human-readable text; the stateless design already handles concurrent independent requests without special code. Explicit size limits and input sanitization are deferred to a future hardening feature.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST accept a request containing a user identifier and a text message as a single typed payload.
- **FR-002**: The service MUST produce a personalized greeting that references the supplied user and reflects the supplied message, composed by a language model invoked through the agent.
- **FR-003**: The service MUST validate that the user identifier is present and non-empty before producing a greeting.
- **FR-004**: The service MUST validate that the text message is present and non-empty before producing a greeting.
- **FR-005**: The service MUST return a clear, actionable error when a request fails validation, without producing a greeting.
- **FR-006**: The service MUST return a client-side error when a submitted payload does not conform to the expected typed structure.
- **FR-007**: The service MUST handle each request independently so that one caller's request does not affect another's response.
- **FR-008**: The service MUST expose the greeting capability through a single HTTP endpoint that accepts the typed JSON request payload and returns a JSON response containing the greeting text.
- **FR-009**: The project MUST be organized following the standard layered structure (interface, application, domain) so the feature can be extended with additional agents and components.
- **FR-010**: The service MUST provide automated tests that demonstrate a successful greeting and at least one validation-failure path.

### Key Entities *(include if feature involves data)*

- **Greeting Request**: The typed input from a caller. Key attributes: a user identifier (who the greeting is for) and a text message (what the caller said). Both are required.
- **Greeting Response**: The JSON output returned to the caller. Key attribute: the greeting text (a plain string) personalized to the request.
- **Greeting Agent**: The component responsible for turning a Greeting Request into a Greeting Response by validating the input and invoking a language model to compose the personalized greeting, returning it as plain text.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of valid submissions return a greeting; the agent's system message instructs the model to address the supplied user by name, and the test suite verifies this against a mocked model.
- **SC-002**: 100% of requests missing a user identifier or message are rejected with a validation error and produce no greeting.
- **SC-003**: A new contributor can build, test, and run the service from a clean checkout using only the documented commands, with no manual code changes required.
- **SC-004**: The automated test suite covers at least one successful greeting path and at least one validation-failure path, and passes on a clean checkout.
- **SC-005**: The agent's system message instructs the model to vary the greeting by message content; with a mocked model, two distinct messages from the same user yield two distinct greetings, demonstrating per-request personalization is wired end to end.

## Assumptions

- The service is reached over the network through a single HTTP endpoint that accepts the typed JSON request and returns a JSON response (decided in clarification).
- The greeting is composed by a language model invoked through the agent; planning will select the model provider and supply a deterministic test double so tests do not depend on a live model.
- "Personalized greeting" means the response incorporates the supplied user identifier and is influenced by the supplied message; it need not persist any state between requests for the baseline.
- No authentication or authorization is required for the baseline beyond the platform's default access controls; access policy can be tightened during planning.
- The baseline is stateless — greetings are computed per request and no caller history is retained.
- A single greeting agent is in scope; the layered project structure is established so additional agents and components can be added later without rework.
- User identifiers and messages are short, human-readable text; extreme-size inputs are handled defensively but are not the primary use case.
