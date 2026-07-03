# Implementation Plan: Scala-native JSON for wire types

**Branch**: `003-scala-native-json` | **Date**: 2026-07-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-scala-native-json/spec.md`

## Summary

Register `jackson-module-scala`'s `DefaultScalaModule` on the SDK's shared Jackson `ObjectMapper`
at service startup, then convert the greeting service's wire types to annotation-free Scala case
classes with `Option` fields and delete the manual `null → None` boundary conversions. The
startup registration is performed by a single `@Setup` `ServiceSetup` class (`Bootstrap`), which —
because our components are Scala and the SDK's annotation processor never scans them — is
discovered via a new hand-maintained descriptor entry at the top-level path
`akka.javasdk.service-setup` (a single FQCN string, sibling of `akka.javasdk.components`). The
externally observable `POST /greet` contract is unchanged; `HealthEndpoint.Health` deliberately
stays Java-shaped as the coexistence witness.

## Technical Context

**Language/Version**: Scala 3.3.4 (LTS), compiled by `scala-maven-plugin` 4.9.2, JDK 21
**Primary Dependencies**: Akka Java SDK 3.6.0 (`io.akka:akka-javasdk`); Jackson 2.21.2;
`jackson-module-scala_2.13` 2.21.2 — **already on the classpath transitively**, no new dependency
**Storage**: N/A
**Testing**: JUnit 5, AssertJ, Akka `TestKitSupport` + `TestModelProvider` (mocked model, offline);
plus a manual live Gemini smoke test of `POST /greet`
**Target Platform**: Akka runtime (local `exec:java` and Akka platform), JDK 21
**Project Type**: Single Akka service (Scala 3 on the Java-first SDK)
**Performance Goals**: N/A — one-time module registration at startup; no request-path cost change
**Constraints**: `POST /greet` wire contract MUST be unchanged; offline suite MUST stay green with
no API key; only **one** `ServiceSetup` class is permitted per service
**Scale/Scope**: 4 greeting wire types converted; 1 new `Bootstrap`; 1 descriptor line; docs

### Resolved unknowns (see research.md)

- **`@Setup` discovery (the crux, FR-002/SC-005)** — RESOLVED. The runtime `ComponentLocator`
  reads the setup class from `akka.javasdk.service-setup` (top-level, `getString`, single value),
  distinct from `akka.javasdk.components.<key>` (string lists). Add
  `akka.javasdk.service-setup = "com.gwgs.akkaagentic.application.Bootstrap"` to the descriptor.
- **Module already present** — RESOLVED. `jackson-module-scala_2.13:2.21.2` matches
  `jackson-databind:2.21.2`; spike confirmed it is on the classpath but not registered.
- **Scala 3 with the `_2.13` module** — the artifact is binary-compatible; validated by the US1
  round-trip test (no separate `_3` build exists).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — PASS. Startup logic uses the SDK's own `ServiceSetup`
  primitive; module registration uses the SDK-sanctioned `JsonSupport.getObjectMapper()`
  customization hook (documented in `serialization.html.md`). **No new external dependency** —
  `jackson-module-scala` is already in the SDK's transitive tree.
- **II. Design Principles** — PASS. *Domain independence*: the domain (`GreetingRequest`,
  `TimeOfDay`) is already `Option`-based and is untouched. *API isolation*: the endpoint keeps its
  own request/reply types (now idiomatic) distinct from domain and agent types. *Single
  responsibility*: `Bootstrap` does exactly one thing — register the module. *Descriptive naming*:
  `Bootstrap` follows the SDK convention for the setup class.
- **III. Test Coverage** — PASS. Adds a serialization round-trip test proving the module works;
  all existing endpoint/agent/domain tests are retained and must stay green; a live smoke test
  covers the provider-specific path. Coverage does not decrease.
- **IV. Simplicity** — PASS. Net simplification: one small setup class + one descriptor line, and
  the change **deletes** boundary-conversion code and per-type annotations. YAGNI honored — only
  the four current greeting wire types are converted; no speculative abstraction.

**Result**: All gates pass. No entries in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/003-scala-native-json/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (greeting-api.md — unchanged contract, pinned)
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/
├── application/
│   ├── Bootstrap.scala          # NEW — @Setup ServiceSetup; registers DefaultScalaModule
│   └── GreetingAgent.scala      # CONVERT Request + Result to idiomatic Scala (Option, no annotations)
├── api/
│   ├── GreetingEndpoint.scala   # CONVERT GreetRequest + GreetReply; delete null→None (Option(...))
│   └── HealthEndpoint.scala     # UNCHANGED — stays Java-shaped (US3 coexistence witness)
└── domain/
    └── Greeting.scala           # UNCHANGED — already Option-based

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf  # ADD akka.javasdk.service-setup = "…Bootstrap"

src/test/scala/com/gwgs/akkaagentic/
├── application/
│   ├── BootstrapSerializationTest.scala   # NEW — round-trip an annotation-free Option case class
│   └── GreetingAgentTest.scala            # UPDATE — construct idiomatic Request/Result (Option)
├── api/
│   ├── GreetingEndpointIntegrationTest.scala  # UPDATE — idiomatic GreetRequest; same 200/400 assertions
│   └── HealthEndpointIntegrationTest.scala    # UNCHANGED — coexistence witness stays green
└── domain/
    └── GreetingTest.scala                 # UNCHANGED
```

**Structure Decision**: Single Akka service, standard `domain`/`application`/`api` packages
(constitution II). The only structural addition is the `Bootstrap` setup class in `application`
(alongside the agent) and its descriptor entry. All conversions are in-place edits to existing
files; no new packages.

## Complexity Tracking

> No constitution violations — section intentionally empty.
