# Feature Specification: gRPC endpoint fronting an agent (gRPC Endpoint)

**Feature Branch**: `021-grpc-endpoint`
**Created**: 2026-10-07
**Status**: Draft
**Input**: User description: "Capability 19 (A4) — a Scala gRPC endpoint that fronts an existing request-based Agent via the ComponentClient agent client (dynamicCall). The SDK's Java-flavor codegen produces the service interface + message classes; our Scala class implements that generated Java interface under @GrpcEndpoint and converts the reply via toApi. Primary research axis: BUILD ORDERING — the generated Java interface must be on scalac's classpath because this pom sets sendJavaToScalac=true and scala-compile is bound to process-resources. Secondary axis: can a Scala class implement a generated Java interface and call a component from a gRPC endpoint. The last untouched SDK component family."

## Why this capability exists

This service has exposed every surface it has over **HTTP**. One SDK component family has never been
built here at all: the **gRPC endpoint** — a `.proto`-defined service whose Java stubs are generated at
build time and implemented by a hand-written class under `@GrpcEndpoint`. It is the last of the SDK's
component families the project has not touched, and building it closes the map.

Unlike the capabilities before it, the interesting risk is **not the method-reference wall**. gRPC's wall
answer is already known from the HTTP endpoints and from capability 13: a Scala class can implement an
SDK-shaped boundary, and the one client that reaches a component without a Java method reference is the
**agent client** (`dynamicCall` — see the project's dynamicCall notes). Fronting a request-based **Agent**
is therefore the honest way to keep the endpoint itself in Scala: the greeting agent from capability 1 takes
a request and returns a structured reply in a single model call, and the agent client is on the right side
of the wall. An entity-fronting endpoint would force Java (entity client is method-ref-only, as capability
18's wallet showed), which would move the probe off the question this capability is built to answer.

The real risk is a **different axis: build ordering.** The SDK's gRPC support generates **Java** — a Java
service interface and Java message classes — from the `.proto`. This project's build joint-compiles Java
through scalac (`sendJavaToScalac = true`), and the Scala compile is bound to the `process-resources` phase.
So a Scala class that implements the generated interface can only compile if the gRPC code generation has
**already run and placed those sources on scalac's classpath** by the time `process-resources` reaches the
Scala plugin. The mixed Java/Scala build arrangement (§13 R3) is the one part of this build that has broken
before, under exactly this kind of ordering pressure, and `mvn clean verify` vs. an incremental build have
disagreed before. The knowledge this capability produces is narrow and unmeasured: **does the SDK's
gRPC codegen interleave with this project's scalac-then-javac arrangement, and can a Scala class implement a
Java interface whose source does not exist until mid-build** — measured from a clean build, not an
incremental one.

A second, smaller finding rides along: the generated gRPC types are **Java-shaped on purpose** (builders,
nullable fields), so the Scala endpoint consumes Java-generated code at the boundary and converts it to and
from the agent's Scala types with `toApi`/`toDomain`. This is the same wire-type exception the project has
carried since capability 3, now reaching a second code-generated boundary. The capability is **"gRPC with a
Scala-authored endpoint over Java-generated stubs,"** not "gRPC in Scala end to end" — and that asymmetry is
a result, not a workaround.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A gRPC client gets a greeting from the agent (Priority: P1)

A client calls the service's gRPC greeting method with a user name and a message, and receives a structured
greeting — the greeting text, a detected tone, and the current time of day — produced by the existing
greeting agent. The gRPC endpoint is a thin front: it forwards the request to the agent and maps the agent's
reply onto the protobuf response.

**Why this priority**: This is the capability. Without a gRPC method that reaches the agent and returns its
reply there is no gRPC endpoint to build, nothing for the build to generate stubs for, and no finding about
whether a Scala class can implement them.

**Independent Test**: Using the testkit's generated gRPC client with the agent's model mocked to a fixed
structured reply, call the greeting method with a name and a message and confirm the response carries all
three fields with the mocked values.

**Acceptance Scenarios**:

1. **Given** the service is running and the greeting agent's model returns a known structured reply,
   **When** a gRPC client calls the greeting method with a user name and a message, **Then** the response
   contains the greeting text, the tone, and the time of day from the agent's reply.
2. **Given** a timezone is supplied in the gRPC request, **When** the greeting method is called, **Then** the
   timezone is forwarded to the agent (so the time of day reflects it) and the response is returned normally.
3. **Given** no timezone is supplied in the gRPC request, **When** the greeting method is called, **Then** the
   call still succeeds and the agent falls back to its default (UTC) time-of-day behavior.

---

### User Story 2 - A malformed request is rejected with a client error, not a crash (Priority: P2)

A client calls the greeting method with an empty user name (or empty message). The service rejects it with a
gRPC client-error status describing the problem, rather than failing with an internal/server error or calling
the model with empty input.

**Why this priority**: It exercises the gRPC error contract (`INVALID_ARGUMENT` via the SDK's exception
handling) and proves validation happens at the Scala boundary before the agent is called — a thin endpoint
still owns its input contract. It is independently testable but not the reason the capability exists.

**Independent Test**: Call the greeting method with an empty user name and confirm the call fails with the
gRPC invalid-argument status and a message naming the missing field, and that the model was not invoked.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a gRPC client calls the greeting method with an empty user name,
   **Then** the call fails with the gRPC invalid-argument status and the model is not called.
2. **Given** the service is running, **When** a gRPC client calls the greeting method with an empty message,
   **Then** the call fails with the gRPC invalid-argument status.

---

### User Story 3 - The build generates the stubs and the Scala endpoint compiles against them from clean (Priority: P1)

A developer builds the project from a clean state. The gRPC code generation runs, the Java service interface
and message classes are produced, and the Scala endpoint class — which implements that generated interface —
compiles against them. The service starts with the gRPC endpoint registered alongside the existing HTTP ones.

**Why this priority**: This is the capability's actual research axis. The method-reference answer is known;
whether the Java codegen interleaves correctly with this project's scalac-then-javac build, from clean, is
not. If this fails, the feature does not exist regardless of how the endpoint is written.

**Independent Test**: Run `mvn clean verify` from a clean checkout and confirm it is green — the gRPC stubs
are generated, the Scala endpoint compiles against them, and the gRPC integration test passes against a
started runtime. Confirm the result holds from **clean**, not only on an incremental build.

**Acceptance Scenarios**:

1. **Given** a clean working tree, **When** `mvn clean verify` runs, **Then** the gRPC Java stubs are
   generated before the Scala compile, the Scala endpoint compiles, and all tests pass.
2. **Given** the built service, **When** it starts, **Then** the gRPC endpoint is registered and callable
   and the existing HTTP endpoints and components are unaffected.

---

### Edge Cases

- **Empty or whitespace-only user / message** → rejected with the gRPC invalid-argument status before the
  agent is called (US2).
- **Unknown or malformed timezone string** → the call still succeeds; the agent's existing behavior falls
  back to UTC (capability 1 already handles this in the agent, not the endpoint).
- **Agent returns its model-free fallback** (the greeting agent degrades to a neutral greeting when the
  model reply is unparseable) → the gRPC response is still a normal, populated reply, not an error.
- **Clean build vs. incremental build disagree** → the capability is only satisfied if the **clean** build
  is green; an incremental-only pass is a failure to record, not a pass (§13 R3 precedent).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST expose a gRPC service, defined by a `.proto` file under `src/main/proto`, with
  a unary method that accepts a user name, a message, and an optional timezone, and returns a greeting, a
  tone, and a time of day.
- **FR-002**: The gRPC endpoint MUST forward each request to the existing request-based greeting agent
  (capability 1) via the component client's agent client, and MUST NOT re-implement the greeting logic.
- **FR-003**: The gRPC endpoint MUST convert between the generated protobuf message types and the agent's
  request/reply types at the boundary (a `toApi`/`toDomain` conversion), and MUST NOT expose the agent's or
  domain's own types on the wire.
- **FR-004**: The gRPC endpoint MUST reject a request with an empty user name or empty message with the gRPC
  invalid-argument status, before calling the agent.
- **FR-005**: The gRPC endpoint MUST be authored in Scala and implement the Java interface generated from the
  `.proto`, annotated as a gRPC endpoint, with an access-control annotation consistent with the project's
  other endpoints.
- **FR-006**: The build MUST generate the gRPC Java stubs before the Scala compile so the Scala endpoint
  compiles against them, and `mvn clean verify` MUST be green from a clean state (not only incrementally).
- **FR-007**: The service MUST register the gRPC endpoint via the hand-maintained component descriptor (new
  descriptor key `grpc-endpoint`) without disturbing the existing registered components, and MUST start
  cleanly with it present.
- **FR-008**: The capability MUST record, in the feature's research notes, the measured build-ordering
  behavior (which phase runs the codegen, whether the generated sources reach scalac, clean-vs-incremental)
  and the Scala-implements-generated-Java-interface result — including anything that had to be corrected.

### Key Entities *(include if feature involves data)*

- **GreetRequest (protobuf message)**: the gRPC request — a user name, a message, and an optional timezone.
  Java-generated, Java-shaped (builders, nullable fields); a boundary/wire type, not a domain type.
- **GreetReply (protobuf message)**: the gRPC response — greeting text, tone, and time of day. Java-generated,
  Java-shaped; mapped from the agent's structured reply.
- **Greeting agent request/reply (existing, capability 1)**: the agent's own `Request`/`Result` Scala types.
  Reused unchanged; the endpoint converts to and from the protobuf messages.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A gRPC client calling the greeting method receives a response in which all three fields
  (greeting, tone, time of day) are populated from the agent's reply.
- **SC-002**: `mvn clean verify` is green from a clean checkout — the gRPC stubs are generated, the Scala
  endpoint compiles against them, and the gRPC integration test passes against a started runtime.
- **SC-003**: A request with an empty user name or empty message returns the gRPC invalid-argument status and
  does not invoke the model.
- **SC-004**: The started service registers the new gRPC endpoint and the full existing test suite still
  passes — no existing HTTP endpoint, component, or capability regresses.
- **SC-005**: The feature's research notes state, as measured facts, whether the gRPC Java codegen interleaves
  with this project's scalac-then-javac build from clean, and whether a Scala class can implement the
  generated Java interface — with any corrected assumptions called out.

## Assumptions

- **Fronted agent**: the existing capability-1 greeting agent (`greeting-agent`), reused unchanged. It is the
  simplest deterministic front (structured reply, already mockable with `TestModelProvider`). The capability-8
  RAG agent was considered and rejected as unnecessarily heavy for a build-ordering probe.
- **Java-flavor codegen**: the SDK parent is assumed to bind gRPC code generation in **Java** flavor (Java
  interface + Java message classes), consistent with the SDK's `@GrpcEndpoint` contract and testkit client. A
  Scala protobuf generator (ScalaPB) is **not** usable here — the SDK recognizes only the Java-generated
  interface. This assumption is verified during planning/implementation, not taken on faith.
- **No new agent, no new domain logic**: this capability adds a `.proto`, a Scala endpoint, build
  configuration for the codegen, a descriptor entry, and an integration test — nothing else.
- **Unary only**: a single unary RPC. gRPC server streaming is a possible later fork but is out of scope here
  (it would reopen the streaming questions of capability 14/16, which are not what A4 is for).

## Dependencies

- **Capability 1 (greeting agent)** — fronted unchanged; editing it would pull capability 1's own tests into
  scope. The endpoint calls it; it does not modify it.
- **The mixed Java/Scala build arrangement (§13 R3)** — the `scala-maven-plugin` / `maven-compiler-plugin`
  phase and `sendJavaToScalac` settings this capability depends on and may have to adjust for the codegen.
- **The hand-maintained component descriptor** — a new `grpc-endpoint` entry; the javac annotation processor
  is disabled in this project (`-proc:none`), so the descriptor is authoritative and edited by hand.

## Out of Scope

- gRPC streaming (client, server, or bidirectional).
- Fronting an entity, workflow, or view over gRPC (would force a Java endpoint and move the probe off A4's
  question).
- A gRPC *client* calling another service's gRPC endpoint (external protobuf message types).
- Changing or re-testing capability 1's agent behavior.
