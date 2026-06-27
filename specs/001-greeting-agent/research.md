# Phase 0 Research: Greeting Agent Service Baseline

This document resolves the open technical decisions for implementing the greeting agent in
**Scala 3 on the Akka Java SDK**. Each item records the decision, rationale, and rejected
alternatives.

## R1. Language interop — Scala 3 components on the Akka Java SDK

- **Decision**: Write `domain`, `application` (Agent), and `api` (Endpoint) in Scala 3. Extend
  the Java SDK base types directly (`class GreetingAgent extends akka.javasdk.agent.Agent`) and
  apply Java annotations (`@Component`, `@HttpEndpoint`, `@Post`, `@Acl`) on Scala
  classes/methods. The Akka runtime discovers components from compiled JVM bytecode, so the
  source language is transparent to component scanning.
- **Rationale**: The user explicitly asked for Scala 3 and the repo is named `akka-agentic-scala3`.
  Scala 3 compiles to standard bytecode and can subclass Java classes and carry Java annotations,
  so no SDK fork is required.
- **Alternatives considered**:
  - *Pure Java* — contradicts the explicit requirement and repo identity.
  - *Scala domain + Java components* — keeps the component layer on the documented path but
    introduces a two-language boundary and still requires the Scala toolchain; rejected as more
    complex, not less (Constitution IV).

## R2. Build tooling — adding Scala 3 to a Maven Java SDK project

- **Decision**: Add `scala-maven-plugin` bound to the `compile` and `test-compile` phases, add a
  `scala3-library_3` dependency, and register `src/main/scala` and `src/test/scala` as source
  roots. Target the same JVM bytecode level the Akka parent uses (Java 21). Keep
  `akka-javasdk-parent` as the POM parent so SDK dependency management and the run/build plugins
  are inherited unchanged.
- **Rationale**: `scala-maven-plugin` is the standard way to compile Scala alongside Java in
  Maven and coexists with the Akka parent's Java compilation. No change to how the service is
  packaged or run (`mvn compile exec:java`, `mvn install`).
- **Alternatives considered**:
  - *sbt* — would replace the SDK-provided Maven build and its `exec:java`/image plugins; high
    friction, rejected.
  - *Mixed Java+Scala in one compile pass without the plugin* — not supported by Maven's default
    compiler; rejected.

## R3. JSON serialization of Scala case classes (and enums)

- **Decision**: Make every type that crosses a serialization boundary (HTTP request/response,
  the agent's request parameter) a Scala 3 `case class` annotated for Jackson:
  `@JsonCreator` on the constructor with `@JsonProperty("...")` on each field. Avoid Scala
  collections/`Option` on the wire for the baseline; use plain `String` fields. If a Scala 3
  `enum` is later serialized, annotate it so Jackson maps it to/from its string name.
- **Rationale**: The Akka SDK manages its own `ObjectMapper` and does not guarantee that
  `jackson-module-scala` is registered. Explicit Jackson annotations make round-tripping
  deterministic regardless of which Jackson modules the SDK enables, and they are local and
  self-documenting.
- **Alternatives considered**:
  - *Rely on `jackson-module-scala` auto-registration* — unverified for the SDK's mapper; risks
    runtime deserialization failures.
  - *Java records for wire types* — would defeat the Scala 3 goal for the very types the user
    cares about (the typed payload).

## R4. Agent shape and Effects API usage

- **Decision**: `GreetingAgent` has exactly one command handler taking a single
  `GreetingAgent.Request(user, text)` parameter and returning `Effect[String]`. It uses
  `effects().systemMessage(SYSTEM_MESSAGE).userMessage(<built from user+text>).thenReply()`.
  Validation of `user`/`text` is delegated to the domain `GreetingRequest` and surfaced by the
  endpoint *before* the agent is called (the agent receives already-valid input).
- **Rationale**: Matches the Akka rule of one well-defined task per agent and the documented
  Effects chain (`agents.html.md`). Keeping validation at the API/domain boundary means the
  agent stays focused on composing the greeting and tests can assert validation without a model.
- **Alternatives considered**:
  - *Structured response via `responseConformsTo`* — rejected by clarification (plain string).
  - *Validation inside the agent via `effects().error()`* — possible, but the endpoint can reject
    malformed payloads earlier and more cheaply (no model invocation); domain holds the rules.

## R5. Model provider configuration

- **Decision**: Configure a default model provider in `application.conf` under
  `akka.javasdk.agent` with the API key supplied via environment variable (e.g.
  `${?ANTHROPIC_API_KEY}` or `${?OPENAI_API_KEY}`). The agent does not hard-code a model;
  it relies on the default. Tests override the model with `TestModelProvider` so no live call is
  made.
- **Rationale**: AGENTS.md and `agents.html.md` both prefer a default model in config. Env-driven
  keys keep secrets out of source. `TestModelProvider` keeps the suite deterministic and offline
  (Constitution III / SC-004).
- **Alternatives considered**:
  - *Hard-coded `ModelProvider` in the agent* — couples the agent to one vendor; rejected.

## R6. Testing strategy

- **Decision**: Two test classes:
  1. `GreetingAgentTest` extends `TestKitSupport`, registers a `TestModelProvider` for
     `GreetingAgent`, sets a `fixedResponse` greeting, and invokes the agent via
     `componentClient.forAgent().inSession(<uuid>).method(GreetingAgent::greet).invoke(request)`,
     asserting the mocked greeting is returned.
  2. `GreetingEndpointIntegrationTest` extends `TestKitSupport`, registers a `TestModelProvider`
     for `GreetingAgent`, and drives the endpoint with `httpClient.POST("/greet")` — asserting a
     success path returns a greeting and at least one validation-failure path returns a
     client error (e.g., empty `user`).
- **Rationale**: Mirrors the documented Agent and Endpoint test patterns; satisfies FR-010 and
  SC-004 with both a success and a failure path, using `httpClient` (not `componentClient`) for
  the endpoint test.
- **Alternatives considered**:
  - *Live model in tests* — non-deterministic, networked, and costly; rejected.

## Resolved unknowns

All `NEEDS CLARIFICATION` items from Technical Context are resolved: language/version (R1, R2),
serialization (R3), agent design (R4), model configuration (R5), and testing (R6). No open
questions remain for Phase 1.
