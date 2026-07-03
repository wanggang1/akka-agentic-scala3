# Feature Specification: Scala-native JSON for wire types

**Feature Branch**: `003-scala-native-json`
**Created**: 2026-07-03
**Status**: Draft
**Input**: User description: "Make the SDK's Jackson ObjectMapper Scala-aware so wire types can be idiomatic Scala 3 instead of Java-shaped. Register DefaultScalaModule on the SDK's shared ObjectMapper via a @Setup/ServiceSetup Bootstrap, then convert the greeting service's wire types to annotation-free Scala case classes with Option fields and remove the null→None boundary conversions."

## Context & Motivation *(informative)*

This is an internal, developer-facing infrastructure feature — it pays down "Java-ish"
wire-type debt before the remaining roadmap capabilities (multi-agent Workflow, Autonomous
Agent, session memory) add more of it. The "users" are the developers maintaining this Scala 3
service on the Java-first Akka SDK.

Today every type that crosses an SDK serialization boundary (HTTP request/response bodies,
agent request/reply) must be written Java-shaped: explicit `@JsonCreator`/`@JsonProperty`
annotations and nullable `String` fields, with a manual `null → None` conversion (`Option(...)`)
at each endpoint/agent boundary. This is because the SDK's shared Jackson `ObjectMapper` does
not understand Scala (a spike confirmed the Scala module is present on the classpath but **not
registered**). Registering the Scala module once, at service startup, lets these types become
ordinary immutable Scala case classes with `Option` fields — no annotations, no boundary
conversion — which is the idiomatic style the rest of the domain already follows.

The externally observable HTTP contract of `POST /greet` MUST NOT change: same accepted request
shape (`user`, `text`, optional `timezone`), same structured response (`greeting`, `tone`,
`timeOfDay`), same `400`/`200` behavior.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register the Scala module so idiomatic types serialize (Priority: P1) 🎯 MVP

As a developer, I want the service's JSON serialization to understand Scala case classes and
`Option`, so that a wire type written as a plain, annotation-free Scala case class with `Option`
fields round-trips correctly through the SDK without any Java-style annotations.

**Why this priority**: Nothing else in this feature is possible until the module is registered
and proven to work end to end. It is the enabling change and, on its own, is a demonstrable MVP:
one converted type round-tripping proves the approach.

**Independent Test**: Convert a single wire type (the endpoint request) to an annotation-free
Scala case class with `Option` fields, run the offline suite, and issue a live `POST /greet` —
the request deserializes and the endpoint returns `200`, proving the module is active.

**Acceptance Scenarios**:

1. **Given** the service has started, **When** a JSON body is deserialized into an annotation-free
   Scala case class with `Option` fields, **Then** present fields become `Some(value)` and absent
   fields become `None` (never `null`), with no per-type Jackson annotations required.
2. **Given** an annotation-free Scala case class with `Option` fields, **When** it is serialized to
   JSON, **Then** the output uses the plain field names and omits/renders `None` in a way the
   round-trip can read back to an equal value.
3. **Given** the module registration is the only change, **When** the offline test suite runs,
   **Then** it passes with no regression.

---

### User Story 2 - Convert the greeting wire types to idiomatic Scala (Priority: P2)

As a developer, I want the greeting service's boundary types — the endpoint request/reply and the
agent request/reply — expressed as annotation-free Scala case classes with `Option` fields, and
the manual `null → None` conversions removed, so the code reads consistently with the idiomatic
domain layer and there is one fewer boundary concept to maintain.

**Why this priority**: This is the actual debt paydown and the visible payoff, but it depends on
US1 being proven first. It is independently testable and independently valuable.

**Independent Test**: With the module registered, convert the endpoint and agent wire types,
delete the `Option(...)` boundary conversions, and confirm the full offline suite plus a live
`POST /greet` still behave identically (same `200` structured body, same `400` rejections).

**Acceptance Scenarios**:

1. **Given** the converted wire types, **When** a valid `POST /greet` request is made (with and
   without `timezone`), **Then** the response is `200` with `greeting`, `tone`, and `timeOfDay`
   populated — identical to the pre-conversion behavior.
2. **Given** the converted wire types, **When** a request has blank/absent `user` or `text`, or a
   malformed JSON body, **Then** the response is still `400` and the model is not called.
3. **Given** the conversion is complete, **When** the code is reviewed, **Then** the converted
   types carry no `@JsonCreator`/`@JsonProperty` annotations and the endpoint/agent no longer
   perform `null → None` (`Option(...)`) conversions on their fields.

---

### User Story 3 - Coexistence with types that stay Java-shaped (Priority: P3)

As a developer, I want any wire types that intentionally remain Java-shaped (retaining
`@JsonCreator`/`@JsonProperty`) to keep working unchanged after the module is registered, so that
the change is safe and can be adopted incrementally rather than all-or-nothing.

**Why this priority**: A safety guarantee rather than new capability. It protects against the
risk that registering the Scala module changes how annotated types (or any not-yet-converted
type) serialize.

**Independent Test**: Keep at least one existing annotated wire type unconverted, register the
module, and confirm that type still round-trips and its endpoint still behaves identically.

**Acceptance Scenarios**:

1. **Given** the Scala module is registered, **When** a still-annotated Java-shaped wire type is
   (de)serialized, **Then** it behaves exactly as before registration.
2. **Given** a mix of converted and unconverted wire types in the same service, **When** the suite
   runs, **Then** both styles pass.

---

### Edge Cases

- **Descriptor discovery of the startup hook** — the service's components are declared in a
  hand-maintained descriptor because the SDK's annotation processor does not scan Scala sources.
  The startup/registration hook must actually run on service start; if it is not discovered, the
  module is never registered and converted types fail to deserialize. This must be resolved and
  verified early (it is the primary risk).
- **Absent vs. explicit-null JSON** — a missing property and an explicit `null` in the JSON body
  must both map to `None` for an `Option` field, matching today's behavior.
- **Blank strings still invalid** — `Option` distinguishes absent from present, but a present but
  blank `user`/`text` must still be rejected by existing validation (blankness is a domain rule,
  not a JSON-shape concern).
- **Live-only serialization quirks** — provider-specific behavior (e.g. the agent's model call)
  can differ from the mocked path, so the change must be validated on a live run, not only offline.
- **Single startup hook constraint** — the platform permits only one startup/setup class per
  service; the design must account for that (one hook, potentially several responsibilities).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST register Scala-aware JSON support on the SDK's shared serialization
  mechanism exactly once, at service startup, so it applies to all subsequent (de)serialization.
- **FR-002**: The startup registration MUST reliably run on service start given the project's
  Scala-source constraints (i.e. the hook must be discoverable/registered despite the SDK's
  annotation processor not scanning Scala). The mechanism by which this is achieved MUST be
  documented.
- **FR-003**: After registration, an annotation-free Scala case class with `Option` fields MUST
  round-trip through JSON: present → `Some`, absent-or-null → `None`, with no per-type Jackson
  annotations.
- **FR-004**: The greeting endpoint's request and reply, and the greeting agent's request and
  reply, MUST be expressed as annotation-free Scala case classes using `Option` for optional
  fields (at least one such type end to end for the MVP; the full set for US2).
- **FR-005**: The manual `null → None` boundary conversions performed for the converted types MUST
  be removed; the converted types MUST never expose `null` to the domain/application layers.
- **FR-006**: The externally observable `POST /greet` contract MUST be unchanged: the same request
  shape is accepted (including optional `timezone`), the same structured response is returned, and
  the same `200`/`400` outcomes occur (invalid input still `400` with no model call).
- **FR-007**: Wire types that intentionally remain Java-shaped MUST continue to (de)serialize
  correctly after registration; the change MUST NOT force a big-bang conversion.
- **FR-008**: The change MUST be validated by the offline, mocked-model test suite AND by a live
  smoke test of `POST /greet` against the real model provider, because serialization quirks can be
  provider- and runtime-specific.
- **FR-009**: The developer-facing documentation (README and/or interop notes) MUST be updated to
  describe that the service registers Scala-aware JSON, why, and what that means for how new wire
  types should be written; any now-obsolete guidance about mandatory Java-style annotations for
  wire types MUST be corrected.

### Key Entities *(include if feature involves data)*

- **Startup hook (Bootstrap)**: A single service-lifecycle class that runs at instance startup and
  performs the one-time registration of Scala-aware JSON support. Owns the registration side
  effect; holds no mutable request state.
- **Greeting request (wire)**: The inbound HTTP body for `POST /greet` — `user`, `text`, and an
  optional `timezone`. Target for conversion to an annotation-free `Option`-bearing case class.
- **Greeting reply (wire)**: The outbound HTTP body — `greeting`, `tone`, `timeOfDay`. Target for
  conversion.
- **Agent request/reply (wire)**: The typed parameter and structured result exchanged with the
  greeting agent. Targets for conversion; the reply is parsed from model text (not native schema
  mode), so it deserializes through the shared serialization mechanism.

## Assumptions

- The Scala JSON support library is already available on the classpath at a compatible version, so
  no new third-party dependency needs to be introduced (a prior spike confirmed its presence and
  that it is not yet registered).
- The greeting agent continues to parse its structured reply from model text (the mode chosen in
  feature 002 to satisfy the model provider's tool-vs-structured-output constraint); this feature
  does not change that choice, so the agent reply benefits from the shared serialization mechanism.
- Registering Scala-aware JSON is additive and does not remove existing Java/JDK/date handling, so
  types relying on those continue to work.
- A real model-provider API key is available in the local environment for the required live smoke
  test; offline tests continue to need no key.

## Out of Scope

- Switching any agent to native JSON-schema structured-output mode, or changing model providers.
- Any roadmap capability-2/3/4 work (multi-agent Workflow, Autonomous Agent, session memory).
- Converting non-greeting or future types beyond what is needed to prove the approach and pay down
  the current greeting-service debt.
- Replacing the underlying JSON library or introducing an alternative serialization stack.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least one wire type is expressed as an annotation-free Scala case class with
  `Option` fields and round-trips correctly end to end — verified by the offline suite passing and
  a live `POST /greet` returning `200`.
- **SC-002**: For the converted types, the count of `@JsonCreator`/`@JsonProperty` annotations and
  the count of manual `null → None` (`Option(...)`) boundary conversions are both reduced to zero.
- **SC-003**: The `POST /greet` behavior is unchanged: valid requests (with and without
  `timezone`) return `200` with all three response fields populated, and blank/absent `user`/`text`
  and malformed JSON return `400` with no model call — 100% of the existing endpoint tests still
  pass.
- **SC-004**: `mvn clean verify` passes offline (mocked model, no API key) from a clean checkout,
  with no test regressions relative to the pre-change suite.
- **SC-005**: The mechanism that makes the startup hook run despite the Scala-source constraint is
  documented, so a future developer can add or modify service-startup logic without rediscovering
  it.
- **SC-006**: At least one intentionally Java-shaped wire type remains in the codebase and still
  passes its tests after registration, demonstrating safe coexistence.
