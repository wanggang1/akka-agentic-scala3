# Implementation Plan: gRPC endpoint fronting an agent

**Branch**: `021-grpc-endpoint` | **Date**: 2026-10-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/021-grpc-endpoint/spec.md`

## Summary

Add the project's first **gRPC endpoint** (capability 19, candidate A4 — the last untouched SDK component
family). A `.proto` under `src/main/proto` defines a unary `Greet` RPC; the SDK's Java-flavor codegen
(`akka-grpc-maven-plugin`, auto-activated by the proto dir) produces a Java service interface and Java
message classes. A **Scala** endpoint class implements that generated interface under `@GrpcEndpoint`,
validates input at the boundary, forwards to the existing capability-1 **greeting agent** via the agent
client's `dynamicCall` (the one client on the right side of the method-ref wall — mirroring the existing
Scala HTTP `GreetingEndpoint`), and maps the agent's reply onto the protobuf response.

The real work and the only genuine unknown is **build ordering**: whether the Java codegen interleaves with
this project's `scalac`-then-`javac` arrangement (`sendJavaToScalac=true`, Scala compile bound to
`process-resources`, `-proc:none` on javac) **from a clean build**. Research (Phase 0) traced the parent
POM's wiring and predicts it works; the prediction is confirmed empirically in implementation by building a
**thin proto + stub endpoint first**, before any logic.

## Technical Context

**Language/Version**: Scala 3.3.8 on the Java-first Akka SDK (`akka-javasdk-parent` 3.6.3), JDK 21.
**Primary Dependencies**: Akka SDK gRPC support via `akka-grpc-maven-plugin` 2.5.10 (provided by the parent's
`generate-protobuf-endpoints` profile); `protobuf-java` 3.25.8; `akka-grpc.version` 2.5.10. No new dependency
is added by hand — the proto dir activates the profile.
**Storage**: N/A (the endpoint is stateless; it calls an agent).
**Testing**: `TestKitSupport` integration test using `getGrpcEndpointClient(GreeterGrpcEndpointClient.class)`;
`TestModelProvider` mocks the greeting agent's model. No unit test (no new domain logic — validation reuses
capability 1's `GreetingRequest`).
**Target Platform**: Akka runtime (local dev / integration test).
**Project Type**: Single service (mixed Scala/Java), web-service surface over gRPC.
**Performance Goals**: N/A — a single unary call; latency is dominated by the (mocked, in tests) model.
**Constraints**: `mvn clean verify` MUST be green **from clean**, not only incrementally (§13 R3 precedent).
**Scale/Scope**: One proto, one unary RPC, one Scala endpoint class, one descriptor entry, one integration
test. No new agent, no new domain logic.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First** — ✅ Uses the SDK's gRPC endpoint component (`@GrpcEndpoint`) and its component
  client. No third-party gRPC stack; the codegen is the SDK parent's own profile. No hand-added dependency.
- **II. Design Principles** — ✅ Domain independence (no new domain logic; validation reuses the pure
  `GreetingRequest`). API isolation (protobuf messages are the wire types; the agent's/domain's types are not
  exposed — `toApi`/`toDomain` at the boundary). Single responsibility (the endpoint only translates and
  forwards). Descriptive naming (`GreeterGrpcEndpoint`, not `Service`).
- **III. Test Coverage** — ✅ A gRPC integration test covers the happy path (US1), the validation reject
  (US2), and — implicitly — the build/startup (US3, by the test running at all against a started runtime).
- **IV. Simplicity** — ✅ Unary only; no streaming; one RPC; reuse the existing agent. YAGNI honored.

**Result: PASS.** No violations; Complexity Tracking omitted.

## Project Structure

### Documentation (this feature)

```text
specs/021-grpc-endpoint/
├── plan.md              # This file
├── research.md          # Phase 0 — build-ordering mechanism (resolved), interop expectations
├── data-model.md        # Phase 1 — the two protobuf messages + the reused agent types
├── quickstart.md        # Phase 1 — grpcurl / test walkthrough
├── contracts/
│   └── greeter.proto    # Phase 1 — the gRPC service contract (the actual proto to drop into src/main/proto)
└── tasks.md             # Phase 2 — created by /akka.tasks, not here
```

### Source Code (repository root)

```text
src/main/proto/
└── com/gwgs/akkaagentic/grpc/greeter.proto        # NEW — the service + message definitions

src/main/scala/com/gwgs/akkaagentic/grpc/api/
└── GreeterGrpcEndpointImpl.scala                   # NEW — Scala class implementing the generated Java
                                                    #       interface; @GrpcEndpoint; calls greeting-agent

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # EDIT — add `grpc-endpoint = [ ... ]`

src/test/scala/com/gwgs/akkaagentic/grpc/
└── GreeterGrpcEndpointIntegrationTest.scala        # NEW — TestKitSupport + getGrpcEndpointClient + TestModelProvider

target/generated-sources/akka-grpc-java/            # GENERATED (not committed) — Java interface + messages
```

**Structure Decision**: A new `com.gwgs.akkaagentic.grpc` module isolates the gRPC surface (the existing
greeting HTTP endpoint stays at `com.gwgs.akkaagentic.api`). The proto's `java_package` is
`com.gwgs.akkaagentic.grpc.proto`; the generated interface is `…grpc.proto.GreeterGrpcEndpoint`; the Scala
impl is `…grpc.api.GreeterGrpcEndpointImpl` (AGENTS.md naming: `{Domain}GrpcEndpointImpl`). No `pom.xml`
change is expected — the parent profile activates on `src/main/proto` existing (confirmed in research). If a
missing runtime dependency surfaces at compile, that is itself a research finding to record, not a silent fix.

## Complexity Tracking

> No constitution violations — omitted.
