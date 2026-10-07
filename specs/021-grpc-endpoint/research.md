# Research: gRPC endpoint fronting an agent (capability 19, A4)

This file records what was **measured** during planning (by reading the SDK parent POM and existing code)
and what is **predicted** and must be **confirmed empirically** during implementation. The project's rule:
a claim you could not verify is recorded as unverified. Each prediction below names how it gets confirmed.

Legend: **[MEASURED]** = established now, from a file in the repo / local `.m2`. **[PREDICTED]** = reasoned
from the mechanism, confirmed in implementation. **[TO CONFIRM]** = an open risk with a named check.

---

## Q-A — How does this project build a `.proto` into something the Scala endpoint can implement? **[MEASURED]**

**Decision / finding:** The SDK parent `akka-javasdk-parent:3.6.3` carries a profile
`generate-protobuf-endpoints`, **auto-activated by `<file><exists>src/main/proto</exists></file>`**. Merely
creating `src/main/proto` turns on gRPC codegen — **no `pom.xml` edit is needed** to add the plugin.

The profile wires two plugins, in order (both relevant to `generate-sources`):

1. `maven-dependency-plugin` `unpack-proto-dependencies` @ **`generate-sources`** — unpacks the core
   `google/protobuf/*.proto` types into `target/proto` (so `includeStdTypes` works).
2. `com.lightbend.akka.grpc:akka-grpc-maven-plugin:2.5.10`, goal `generate`, **tied to `generate-sources`**
   (its default phase). `protoPaths = [src/main/proto, target/proto]`. Output (by akka-grpc convention):
   `target/generated-sources/akka-grpc-java`.

`generatorSettings` on the plugin: `blockingApis=true`, `includeStdTypes=true`,
`generateScalaHandlerFactory=true`.

Separately, in the parent's **base** build (always active, not inside the profile),
`build-helper-maven-plugin` `add-java-source` @ **`generate-sources`** adds
`target/generated-sources/akka-grpc-java` as a **compile source root**.

**Rationale:** `blockingApis=true` is why the generated service interface is the blocking Java form the docs
show (`GreetReply greet(GreetRequest in)`), not a `CompletionStage` form — and confirms the codegen is
**Java-flavor**. This settles the spec's assumption: a Scala protobuf generator (ScalaPB) is not used or
usable; the SDK's `@GrpcEndpoint` contract is the generated **Java** interface.

**Evidence:** `~/.m2/repository/io/akka/akka-javasdk-parent/3.6.3/akka-javasdk-parent-3.6.3.pom`, lines
~263–272 (add-source), ~518–592 (the profile).

---

## Q-B — Does the codegen run *before* this project's Scala compile? **[MEASURED] — CONFIRMED from clean**

> **RESULT (T005, 2026-10-07): CONFIRMED.** `mvn clean verify` from a clean tree is **green** (224 tests, 0
> failures, 5:32) with a stub Scala endpoint implementing the generated Java interface. `mvn clean
> generate-sources` placed the interface + messages under `target/generated-sources/akka-grpc-java` (Q-A), and
> the started runtime logged `gRPC endpoint component [com.gwgs.akkaagentic.grpc.api.GreeterGrpcEndpointImpl],
> gRPC service name [com.gwgs.akkaagentic.grpc.GreeterGrpcEndpoint]`. The prediction below held exactly: the
> generated Java is on scalac's source path by `process-resources`, and a Scala class compiles against a Java
> interface that did not exist until mid-build. **No `pom.xml` change was needed.** The build-ordering axis —
> the only genuine A4 risk — is resolved favorably.



**The build-ordering crux of A4.** Maven lifecycle order is:
`validate → initialize → generate-sources → process-sources → generate-resources → process-resources →
compile → test-compile → …`

- gRPC `generate` and build-helper `add-source` run at **`generate-sources`**. **[MEASURED]**
- This project's `scala-maven-plugin:compile` is bound to **`process-resources`** (pom.xml lines 78–84),
  which is **later**. **[MEASURED]**
- `scala-maven-plugin` has `sendJavaToScalac=true` (pom.xml line 64), so scalac **joint-compiles the `.java`
  sources on the compile source roots** — which now include `generated-sources/akka-grpc-java`. **[MEASURED]**

**Therefore [PREDICTED]:** on a clean build, by the time the Scala compile runs, (a) the generated Java
service interface + message classes exist on disk and (b) their directory is a registered compile source
root, so scalac compiles them **together with** the Scala endpoint class that implements the interface. The
later `compile` phase (`maven-compiler-plugin`, `-proc:none`) re-compiles the `.java` (javac wins for Java
`.class`; it never touches `.scala`), exactly the same double-compile already in effect for `src/main/java`
(cap-2/cap-13) — benign here because gRPC messages do not depend on `-parameters` name binding (that hazard
is HTTP-path-specific, §13 R3).

**Confirmed by:** building a **thin proto + stub endpoint** (empty `greet` returning a hardcoded reply)
with `mvn clean verify` **before** adding any logic — the first implementation task. If clean is green, the
ordering holds; if not, the failure mode (and which phase produced it) is the finding.

**Why clean specifically:** §13 R3 is the precedent where an incremental build passed and `clean` failed.
The build-ordering claim is only honored if proven from clean.

---

## Q-C — Can a Scala class implement the generated Java interface, and call the agent from it? **[PREDICTED]**

**Decision:** The endpoint is **Scala**, implementing `…grpc.proto.GreeterGrpcEndpoint` with
`override def greet(in: GreetRequest): GreetReply`, annotated `@GrpcEndpoint`. It reaches the greeting agent
with the agent client's `dynamicCall`, **identically to the existing Scala HTTP `GreetingEndpoint`**
(`src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala:69–77`):

```scala
componentClient.forAgent()
  .inSession(UUID.randomUUID().toString)
  .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
  .invoke(GreetingAgent.Request(user, text, timezone /* nullable */))
```

**Rationale:** Scala implementing a Java interface is routine; the method-ref wall does not bite because the
**agent client** reaches a component by id via `dynamicCall` (project notes: dynamicCall reaches SDK-owned
components; the agent client is on the right side of the wall). An entity front would have forced Java
(cap-18); fronting the agent is what keeps the endpoint Scala — which is the point of choosing an agent.

**Confirmed by:** the Scala endpoint compiling against the generated interface (part of Q-B's build) and the
integration test returning the agent's mocked reply.

**residual risks** (resolved in T003–T006):

1. **Exception/`Status` imports.** **RESOLVED (T011):** validation throws
   `new akka.grpc.GrpcServiceException(io.grpc.Status.INVALID_ARGUMENT.augmentDescription(message))`. Both
   classes are on the classpath via `akka-grpc-runtime_2.13` 2.5.10. Client-side the blocking
   `GreeterGrpcEndpointClient.greet` surfaces it as an exception whose message carries both `INVALID_ARGUMENT`
   and the augmented description (the domain's `"user must not be blank"` / `"text must not be blank"`),
   asserted in the IT. Chosen over bare `IllegalArgumentException` for an explicit status + message. Scala uses
   `new` on `GrpcServiceException` (a Java class, not a case class) — no companion `apply`.
2. **`generateScalaHandlerFactory=true`.** **RESOLVED (T005): it generates JAVA, not Scala.** The generated
   file is `…/proto/GreeterGrpcEndpointScalaHandlerFactory.**java**` — despite the name, a `.java` file. No
   `.scala` escapes the single added source root; nothing extra to compile. (This is why plain-Java SDK
   projects with no scalac build fine: the "Scala handler factory" is a historical akka-grpc name, emitted as
   Java.)
3. **akka-grpc runtime on the compile classpath.** **RESOLVED (T005): present.** The stub compiled and the
   service started; `akka-grpc-runtime_2.13-2.5.10.jar` is in `.m2` via the `akka-javasdk` dependency tree. No
   hand-added dependency was needed — the profile adds only `protobuf-java`, and the runtime comes transitively.

---

## Q-D — What is the component descriptor key for a gRPC endpoint? **[MEASURED] — `grpc-endpoint` CONFIRMED**

> **RESULT (T004/T005): `grpc-endpoint` is correct.** With
> `grpc-endpoint = ["com.gwgs.akkaagentic.grpc.api.GreeterGrpcEndpointImpl"]` in the hand-maintained
> descriptor, the started runtime registered and named the service (log line in Q-B). A wrong key would have
> left it undiscovered; it was discovered. The key sits beside `http-endpoint` / `mcp-endpoint`, as expected.


**Decision:** Register the impl under a new top-level key **`grpc-endpoint`** in the hand-maintained
descriptor (`src/main/resources/META-INF/akka-javasdk-components_*.conf`), alongside the existing
`http-endpoint`, `mcp-endpoint`, etc. The javac annotation processor is disabled (`-proc:none`), so Scala
components never auto-register — this file is authoritative.

**Rationale:** `@GrpcEndpoint` is an endpoint, like `@HttpEndpoint`/`@McpEndpoint`; those use `http-endpoint`
/ `mcp-endpoint`. The AGENTS.md import table and the SDK's `ComponentType` naming both point to
`grpc-endpoint`.

**[TO CONFIRM]:** verify the exact key string from the SDK's `ComponentType` constant pool (the method prior
capabilities used to confirm `view`, `mcp-endpoint`, `event-sourced-entity`), rather than trusting the
spelling. If the key is wrong, the endpoint silently does not register and the integration test fails to find
it at startup — the check is "the gRPC test connects to a registered endpoint."

---

## Q-E — How is a gRPC endpoint tested here? **[MEASURED] from docs/testkit**

**Decision:** An integration test extending `TestKitSupport`, obtaining the generated client via
`getGrpcEndpointClient(GreeterGrpcEndpointClient.class)` and registering a `TestModelProvider` for
`GreetingAgent` in `testKitSettings()`. The model is mocked with a fixed structured `Result`; the test
asserts the three reply fields (US1), and a second case asserts an empty `user` fails with the
invalid-argument status without the model being called (US2).

**Rationale:** Matches the SDK gRPC testing doc (`grpc-endpoints.html.md` §Testing the Endpoint) and the
project's agent-testing pattern (`TestModelProvider.fixedResponse(JsonSupport.encodeToString(result))`).
US3 (build/startup) needs no separate test — the integration test running at all against a started runtime
*is* the startup check.

**[MEASURED] (T005): the generated test client is `com.gwgs.akkaagentic.grpc.proto.GreeterGrpcEndpointClient`**
(alongside a `GreeterGrpcEndpointClientPowerApi`). Confirmed from the generated sources. Scala test authoring
is expected clean (no method reference; the client is a generated type with ordinary methods) — exercised in
US1/T007.

---

## Summary of interop verdict (build probe done; US1/US2 still to add logic)

- **CONFIRMED (T005):** the gRPC family is **build-ordering-clean** in this project. The parent's
  auto-activated profile generates Java at `generate-sources`, which precedes this project's
  `process-resources` Scala compile, so a **Scala endpoint implements the generated Java interface** — proven
  from a clean build, with **no pom change**. The A4 bet (build ordering, the one part of the build that had
  broken twice before) resolved favorably on the first clean run.
- The endpoint stays **Scala** because it fronts an **agent** (dynamicCall), not an entity. The method-ref
  wall is not the A4 story. *(Q-C — confirmed for the stub; the dynamicCall itself is exercised in US1/T008.)*
- The asymmetry is the honest result: **Scala-authored endpoint over Java-generated stubs** — the wire types
  are Java-shaped by generation, the same boundary exception carried since cap-3, now at a code-generated
  surface. Not "gRPC in Scala end to end." The `generateScalaHandlerFactory` is a red herring: emitted as
  Java.
- New descriptor key **`grpc-endpoint`** — **CONFIRMED (T004/T005)** by the runtime registering the service.
- **Still open, for US1/US2:** the live `dynamicCall` to `greeting-agent` returning the mocked reply (T007/T008)
  and the `GrpcServiceException`/`Status` choice for validation (T011).

Anything above that implementation **disproves** gets corrected here, in place, with what was wrong stated —
not quietly edited (CLAUDE.md cold-start rule).
