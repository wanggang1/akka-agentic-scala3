# Tasks: gRPC endpoint fronting an agent (capability 19, A4)

**Input**: Design documents from `specs/021-grpc-endpoint/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/greeter.proto

**Tests**: INCLUDED. The spec defines US1/US2 acceptance via a gRPC integration test (`TestKitSupport` +
`getGrpcEndpointClient` + `TestModelProvider`). No unit tests — there is no new domain logic (validation
reuses capability 1's pure `GreetingRequest`).

**Organization**: By user story. **US3 (the build-ordering probe) is the blocking prerequisite** and comes
first — nothing else can compile until the generated Java interface builds and the Scala stub implements it
from clean. This is deliberate (project probe-first culture): prove the only genuine unknown before writing
logic.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3

## Path Conventions

Mixed Scala/Java Akka service. Scala: `src/main/scala/com/gwgs/akkaagentic/grpc/…`. Proto:
`src/main/proto/…`. Tests: `src/test/scala/com/gwgs/akkaagentic/grpc/…`. Generated (not committed):
`target/generated-sources/akka-grpc-java`.

---

## Phase 1: Setup

**Purpose**: Place the proto and module skeleton. Creating `src/main/proto` is itself load-bearing — it
auto-activates the parent's `generate-protobuf-endpoints` profile (research Q-A).

- [ ] T001 [P] Create module directories: `src/main/scala/com/gwgs/akkaagentic/grpc/api/` and
  `src/test/scala/com/gwgs/akkaagentic/grpc/`.
- [ ] T002 Copy the service contract to `src/main/proto/com/gwgs/akkaagentic/grpc/greeter.proto` from
  `specs/021-grpc-endpoint/contracts/greeter.proto` (verbatim — `java_package com.gwgs.akkaagentic.grpc.proto`,
  `service GreeterGrpcEndpoint`, `rpc Greet`).

---

## Phase 2: User Story 3 - Build generates stubs & the Scala endpoint compiles from clean (Priority: P1) 🎯 BLOCKING PROBE

**Goal**: Prove A4's only real unknown — that the Java codegen interleaves with this project's
`scalac`-at-`process-resources` build **from clean**, and that a Scala class can implement the generated Java
interface — using a STUB endpoint (hardcoded reply, no agent) before any logic exists.

**Independent Test**: `mvn clean verify` is green from a clean tree; the service starts with the gRPC endpoint
registered; a trivial call to the stub returns the hardcoded reply.

- [ ] T003 [US3] Create the STUB endpoint
  `src/main/scala/com/gwgs/akkaagentic/grpc/api/GreeterGrpcEndpointImpl.scala`: `@GrpcEndpoint` + `@Acl(... INTERNET)`,
  `class GreeterGrpcEndpointImpl(componentClient: ComponentClient) extends com.gwgs.akkaagentic.grpc.proto.GreeterGrpcEndpoint`,
  `override def greet(in: GreetRequest): GreetReply` returning a **hardcoded** `GreetReply.newBuilder()…build()`.
  No agent call yet. (Depends on T002 — the interface must be generated first.)
- [ ] T004 [US3] Register the endpoint in the descriptor
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`: add a new top-level
  key `grpc-endpoint = [ "com.gwgs.akkaagentic.grpc.api.GreeterGrpcEndpointImpl" ]` with a comment noting it
  is this project's first gRPC endpoint and the key is confirmed in T006.
- [ ] T005 [US3] Run `mvn clean verify` from clean. Confirm: codegen runs at `generate-sources`, scalac
  compiles the stub against the generated interface, the build is green, and the service starts with the
  endpoint registered. If it fails, record the failing phase/message — that is the A4 finding. Write the
  outcome into `research.md` Q-B (MEASURED, replacing the PREDICTED label).
- [ ] T006 [US3] Resolve the four `[TO CONFIRM]` residuals and write each into `research.md` in place:
  (1) `GrpcServiceException` / `Status` import packages (needed by US2);
  (2) inspect `target/generated-sources/` — confirm no stray `.scala` escapes the single added source root
  (Q-C risk 2);
  (3) confirm the generated test client class name, e.g. `GreeterGrpcEndpointClient` (Q-E);
  (4) confirm the `grpc-endpoint` descriptor key actually registers (from the SDK `ComponentType` constant
  pool and from the started service) and that akka-grpc runtime classes were on the compile classpath (Q-C
  risk 3). Commit the probe gate (T003–T006).

**Checkpoint**: The build-ordering bet is resolved and committed. US1/US2 can now add logic to a compiling,
registered endpoint.

---

## Phase 3: User Story 1 - A gRPC client gets a greeting from the agent (Priority: P1) 🎯 MVP

**Goal**: The endpoint forwards to the capability-1 greeting agent via `dynamicCall` and returns its reply.

**Independent Test**: With the agent's model mocked to a fixed structured reply, a gRPC client call returns
a `GreetReply` carrying the mocked greeting, tone, and time of day; a timezone in the request is forwarded;
an absent timezone still succeeds.

- [ ] T007 [US1] Write the integration test
  `src/test/scala/com/gwgs/akkaagentic/grpc/GreeterGrpcEndpointIntegrationTest.scala`: extend `TestKitSupport`;
  register `TestModelProvider` for `GreetingAgent` in `testKitSettings()`; `fixedResponse` a known
  `GreetingAgent.Result`; obtain the client via `getGrpcEndpointClient(classOf[GreeterGrpcEndpointClient])`;
  assert the happy path returns all three reply fields (US1 scenario 1), a supplied timezone is accepted
  (scenario 2), and an absent timezone still succeeds (scenario 3). Run it — it MUST fail against the
  hardcoded stub first.
- [ ] T008 [US1] Replace the stub body in `GreeterGrpcEndpointImpl.scala`: build the agent request and call
  `componentClient.forAgent().inSession(UUID.randomUUID().toString).dynamicCall[GreetingAgent.Request,
  GreetingAgent.Result]("greeting-agent").invoke(...)`, mirroring the Scala HTTP `GreetingEndpoint`. (Depends
  on T007.)
- [ ] T009 [US1] Add the private `toApi(result: GreetingAgent.Result): GreetReply` converter and the
  timezone bridge (proto `""` → `null` into `GreetingAgent.Request`). Run T007's test green.

**Checkpoint**: US1 works — the capability exists end to end for the happy path.

---

## Phase 4: User Story 2 - A malformed request is rejected with a client error (Priority: P2)

**Goal**: Empty `user`/`text` → gRPC `INVALID_ARGUMENT` before the agent is called.

**Independent Test**: A call with empty `user` fails with the invalid-argument status naming the field, and
the model is not invoked.

- [ ] T010 [US2] Add integration-test cases to `GreeterGrpcEndpointIntegrationTest.scala`: empty `user` →
  invalid-argument status; empty `text` → invalid-argument status; assert the model was NOT invoked (the
  `TestModelProvider` received no call). Run — must fail before T011.
- [ ] T011 [US2] Add boundary validation to `GreeterGrpcEndpointImpl.greet`: build `GreetingRequest` from the
  proto fields and on `validate` → `Left(msg)` throw `GrpcServiceException(Status.INVALID_ARGUMENT…msg)` (exact
  type/imports per T006) BEFORE the `dynamicCall`. Run T010 green. Commit the US1+US2 gate.

**Checkpoint**: US1 and US2 both pass; the endpoint owns its input contract.

---

## Phase 5: Polish & Documentation

**Purpose**: Record the capability and its findings; run the full gate.

- [ ] T012 [P] Add README "Scala interop notes" §20 (gRPC): build-ordering resolved (codegen at
  generate-sources precedes process-resources scalac; profile auto-activated by src/main/proto; no pom
  change), Scala-endpoint-over-Java-generated-stubs asymmetry, descriptor key `grpc-endpoint`, the residuals
  from T006. Add a curl/grpcurl example (from quickstart.md).
- [ ] T013 [P] Flip `ROADMAP.md` "Where we are" to capability 19 and mark the A4 row done with the measured
  verdict; add a `FINDINGS.md` line if the build-ordering result warrants it.
- [ ] T014 [P] Update memory: new memory file for the gRPC interop verdict + MEMORY.md pointer; correct the
  stale `sendJavaToScalac=false` note (it is `true`); update the exploration-roadmap memory (A4 done, only
  forks B2–B5 remain).
- [ ] T015 Run the full gate `mvn clean verify` (all unit + integration tests) and confirm green with no
  regression to any existing capability; run `quickstart.md` end to end. This is the "done" gate.

---

## Dependencies & Execution Order

### Phase / story dependencies

- **Setup (Phase 1)**: no dependencies.
- **US3 (Phase 2)**: depends on Setup. **BLOCKS US1 and US2** — the endpoint must compile and register before
  any logic or test can run. (US3 is the foundational story; it is a user story in the spec, so it carries a
  label, but it also plays the Foundational role.)
- **US1 (Phase 3)**: depends on US3 (compiling, registered endpoint).
- **US2 (Phase 4)**: depends on US3; **shares files with US1** (same endpoint + same test class), so it runs
  after US1, not in parallel with it.
- **Polish (Phase 5)**: depends on US1 + US2 complete.

### Within a story

- Test before implementation (T007 before T008/T009; T010 before T011) — the test must fail first.
- US1: endpoint change (T008) before converter/bridge polish (T009), same file → sequential.

### Parallel opportunities

- T001 ∥ (nothing else in Setup is independent; T002 needs the dir but is quick).
- Polish T012 ∥ T013 ∥ T014 (different files); T015 runs last, alone.
- US1 and US2 are **not** parallel (same files) despite being different stories — noted honestly.

---

## Implementation Strategy

### MVP = US3 + US1

1. Phase 1 Setup → 2. Phase 2 US3 (prove the build from clean — the capability's risk) → 3. Phase 3 US1
(the greeting works end to end). **Stop and validate**: the gRPC client gets a mocked greeting and
`mvn clean verify` is green.

### Incremental delivery

US3 (build green, stub) → commit · US1 (agent call) → commit · US2 (validation) → commit · Polish/docs →
final commit. Commit at each checkpoint (project rule: commit at each approved gate).

---

## Notes

- The one real risk lives entirely in US3; US1/US2 are ordinary endpoint work once it compiles.
- No `pom.xml` change is expected. If US3 reveals one is needed (e.g. a missing akka-grpc runtime dep), that
  is a finding to record in research.md, and the build-config touch means the final gate is `mvn clean verify`
  (per CLAUDE.md's test-selection table).
- Do not narrow the final gate (T015) to one capability — a descriptor/`pom.xml`/build-order edit has a
  measured precedent for breaking every capability at startup.
