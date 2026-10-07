# Quickstart: gRPC greeting endpoint (capability 19, A4)

## Build (this is also the A4 research check)

```bash
# From a CLEAN state — the point of A4 is that codegen interleaves with scalac from clean.
mvn clean verify
```

What happens, in order (see research.md Q-A/Q-B):
1. `generate-sources`: the parent's `generate-protobuf-endpoints` profile (auto-activated because
   `src/main/proto` exists) runs `akka-grpc-maven-plugin`, generating the Java interface + messages into
   `target/generated-sources/akka-grpc-java`; `build-helper` adds that dir as a compile source root.
2. `process-resources`: `scala-maven-plugin` compiles Scala **and** joint-compiles the generated Java
   (`sendJavaToScalac=true`), so `GreeterGrpcEndpointImpl` compiles against the freshly generated interface.
3. `compile`: `maven-compiler-plugin` re-compiles the Java sources.
4. `integration-test`: the gRPC integration test runs against a started runtime.

A green `clean verify` is SC-002.

## Run locally

```bash
mvn compile exec:java      # or the project's documented run command (README "Run")
```

The gRPC endpoint is served on the service's gRPC port alongside the HTTP endpoints.

## Call it

With `grpcurl` (reflection is enabled in dev mode):

```bash
grpcurl -plaintext -d '{"user":"Ada","text":"hello there","timezone":"Europe/London"}' \
  localhost:9000 com.gwgs.akkaagentic.grpc.GreeterGrpcEndpoint/Greet
```

Expected (shape; greeting text is model-generated):

```json
{
  "greeting": "Good afternoon, Ada! ...",
  "tone": "casual",
  "timeOfDay": "afternoon"
}
```

Invalid request (empty user) → gRPC `INVALID_ARGUMENT`, model not called:

```bash
grpcurl -plaintext -d '{"user":"","text":"hi"}' \
  localhost:9000 com.gwgs.akkaagentic.grpc.GreeterGrpcEndpoint/Greet
# ERROR: Code: InvalidArgument  Message: user must not be empty
```

## Test (offline, no model)

`GreeterGrpcEndpointIntegrationTest` (Scala, `TestKitSupport`):

- Registers a `TestModelProvider` for `GreetingAgent`, `fixedResponse` a known `Result`.
- `getGrpcEndpointClient(GreeterGrpcEndpointClient.class)`; calls `greet(...)`; asserts the three fields.
- Second case: empty `user` → expects the invalid-argument status and asserts the model was not invoked.

```bash
mvn verify -Dit.test='GreeterGrpcEndpointIntegrationTest' -Dtest='!*'
```
