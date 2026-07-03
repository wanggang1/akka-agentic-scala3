# Tasks: Scala-native JSON for wire types

**Input**: Design documents from `/specs/003-scala-native-json/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/greeting-api.md

**Tests**: INCLUDED — the constitution requires tests for behavioral change, the spec requires
offline + live validation (FR-008), and the passing suite is a success criterion (SC-004).

**Organization**: Tasks are grouped by user story so each story is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3
- All paths are relative to the repository root.

## Path Conventions

Single Akka service (Scala 3.3.4 on the Akka Java SDK). Sources under
`src/main/scala/com/gwgs/akkaagentic/{domain,application,api}`, tests under
`src/test/scala/...`, descriptor at
`src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`. Run Maven
with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish a clean regression baseline before changing serialization. No new
dependency or build config is needed — `jackson-module-scala` is already on the classpath
(research R2).

- [ ] T001 Confirm baseline: run `mvn verify` on branch `003-scala-native-json` and confirm the existing suite is green, so any later failure is attributable to this feature

**Checkpoint**: Baseline green; safe to start changes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Register the Scala module at startup. Until the module is registered, no
annotation-free `Option` wire type can (de)serialize, so this blocks every user story.

**⚠️ CRITICAL**: Blocks US1, US2, US3.

- [ ] T002 Create `Bootstrap` in `src/main/scala/com/gwgs/akkaagentic/application/Bootstrap.scala`: an `@Setup` class implementing `akka.javasdk.ServiceSetup` whose `onStartup` registers `com.fasterxml.jackson.module.scala.DefaultScalaModule` on `akka.javasdk.JsonSupport.getObjectMapper()`; stateless, no fields — per data-model.md and research.md R1/R2
- [ ] T003 Register the setup class in the descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`: add the top-level entry `akka.javasdk.service-setup = "com.gwgs.akkaagentic.application.Bootstrap"` as a sibling of `akka.javasdk.components` (single string, not a list) — per research.md R1. Verify with `mvn -o compile`

**Checkpoint**: The service registers the Scala module on startup (in both runtime and TestKit,
since both locate the setup class via this descriptor entry).

---

## Phase 3: User Story 1 - Register the Scala module so idiomatic types serialize (Priority: P1) 🎯 MVP

**Goal**: Prove the registered module works end to end — one annotation-free `Option`-bearing Scala
case class round-trips, and the first converted wire type (`GreetRequest`) drives a real `200`.

**Independent Test**: Offline suite green (incl. the new round-trip test) and a live `POST /greet`
returns `200`, proving the module is active.

### Tests for User Story 1

> Write first; the round-trip test FAILS until the module is registered (T002/T003).

- [ ] T004 [P] [US1] Serialization round-trip test in `src/test/scala/com/gwgs/akkaagentic/application/BootstrapSerializationTest.scala` (extends `TestKitSupport` so `onStartup` has run): serialize and deserialize an annotation-free Scala case class with `Option` fields via `JsonSupport.getObjectMapper()`, asserting present → `Some`, and absent-or-explicit-null → `None`, and round-trip equality — per research.md R3/R4 (SC-001)

### Implementation for User Story 1

- [ ] T005 [US1] Convert `GreetingEndpoint.GreetRequest` in `src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala` to an annotation-free case class `(user: Option[String], text: Option[String], timezone: Option[String])` (drop `@JsonCreator`/`@JsonProperty`, keep `@JsonIgnoreProperties(ignoreUnknown = true)`); remove the `Option(request.user)`/`Option(request.text)` boundary wraps in `greet` (pass the fields straight into `GreetingRequest`); pass timezone to the still-`String` `GreetingAgent.Request` via `request.timezone.orNull` (interim glue, removed in US2/T008) — per data-model.md
- [ ] T006 [US1] Update `src/test/scala/com/gwgs/akkaagentic/api/GreetingEndpointIntegrationTest.scala` where it constructs `GreetRequest`: use the idiomatic `Option` form (or post raw JSON), keeping the same assertions — valid → `200` with all three fields; verify `mvn verify` is green

**Checkpoint**: MVP — module registered and proven; `GreetRequest` is idiomatic; offline suite green
and a live `POST /greet` returns `200`. (Live smoke deferred to T012; do it now if convenient.)

---

## Phase 4: User Story 2 - Convert the greeting wire types to idiomatic Scala (Priority: P2)

**Goal**: Convert the remaining wire types and delete all remaining `null → None` boundary code, so
the greeting service's boundary reads idiomatically.

**Independent Test**: Full offline suite plus a live `POST /greet` behave identically (same `200`
structured body, same `400` rejections); no `@JsonCreator`/`@JsonProperty` or `Option(...)` boundary
conversions remain on the converted types.

### Implementation for User Story 2

- [ ] T007 [US2] Convert `GreetingEndpoint.GreetReply` in `src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala` to an annotation-free case class `(greeting: String, tone: String, timeOfDay: String)` (drop `@JsonCreator`/`@JsonProperty`; all fields required, no `Option`); keep `toApi` — per data-model.md
- [ ] T008 [US2] Convert `GreetingAgent.Request` and `GreetingAgent.Result` in `src/main/scala/com/gwgs/akkaagentic/application/GreetingAgent.scala`: `Request(user: String, text: String, timezone: Option[String])` and `Result(greeting: String, tone: String, timeOfDay: String)`, both annotation-free (drop `@JsonCreator`/`@JsonProperty`; remove the now-unused `@Description` on `Result` — `responseAs` uses no schema); update `timezoneLine` to consume `Option[String]` directly (drop the `Option(request.timezone)` wrap); and in `GreetingEndpoint.greet` remove the interim `request.timezone.orNull`, passing `request.timezone` (now `Option[String]`) straight through — per data-model.md and research.md R5
- [ ] T009 [US2] Update `src/test/scala/com/gwgs/akkaagentic/application/GreetingAgentTest.scala` to construct the idiomatic `GreetingAgent.Request` (`Option` timezone) and `Result`; keep the mocked-model assertions (structured shape, tone-per-intent, timeOfDay carried, timezone-reaches-prompt) — per research.md R5
- [ ] T010 [US2] Grep-verify zero `@JsonCreator`/`@JsonProperty` and zero `Option(...)` boundary conversions remain on the converted greeting types (`GreetRequest`, `GreetReply`, `GreetingAgent.Request`, `GreetingAgent.Result`); run `mvn verify` green (SC-002, SC-003)

**Checkpoint**: US1 and US2 both green; the greeting boundary is fully idiomatic; `POST /greet`
behavior unchanged.

---

## Phase 5: User Story 3 - Coexistence with types that stay Java-shaped (Priority: P3)

**Goal**: Prove that an intentionally Java-shaped type still works after registration — safe,
incremental adoption.

**Independent Test**: `HealthEndpoint.Health` (kept `@JsonCreator`/`@JsonProperty`) still
round-trips and `GET /health` returns `200 {"status":"ok"}`.

### Implementation for User Story 3

- [ ] T011 [US3] Confirm `src/main/scala/com/gwgs/akkaagentic/api/HealthEndpoint.scala` remains Java-shaped (unchanged) and `src/test/scala/com/gwgs/akkaagentic/api/HealthEndpointIntegrationTest.scala` still passes after module registration — the in-repo coexistence witness (SC-006, contract C7). Add a one-line comment on `Health` noting it is the deliberate Java-shaped coexistence example; no functional change

**Checkpoint**: All three stories pass; converted and annotated wire types coexist.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T012 Live smoke test (needs a real Gemini key): `set -a && source .env && set +a && mvn compile exec:java`, then run the four `curl`s from quickstart.md (with-timezone `200`, without-timezone `200`, invalid `400`, `GET /health` `200`) and confirm behavior matches the pinned contract (FR-008, SC-001, SC-003)
- [ ] T013 [P] Update `README.md` interop notes: document that the service registers `DefaultScalaModule` via the `@Setup` `Bootstrap` discovered through the `akka.javasdk.service-setup` descriptor entry, why (idiomatic `Option` wire types, no per-type annotations), and correct any prior guidance that Java-style annotations are mandatory for wire types (note the deliberate `HealthEndpoint` exception) — per FR-009, SC-005
- [ ] T014 [P] Verify no secrets are committed (env-driven key only; `.env` git-ignored) and the descriptor change is limited to the added `service-setup` line
- [ ] T015 Run `mvn clean verify` from a clean checkout and confirm the full suite passes offline with no API key (SC-004); validate quickstart.md steps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately.
- **Foundational (Phase 2)**: T002 → T003 (the class must exist before the descriptor names it). BLOCKS all user stories — no annotation-free `Option` type serializes until the module is registered.
- **User Stories (Phase 3+)**: depend on Foundational.
  - US1 (P1) → US2 (P2) → US3 (P3) in priority order. US1 and US2 edit the same endpoint/agent files, so sequence them if one developer.
- **Polish (Phase 6)**: after the desired user stories are complete.

### User Story Dependencies

- **US1 (P1)**: Needs T002/T003. MVP. T005 (endpoint) before T006 (its test).
- **US2 (P2)**: Builds on US1's endpoint edits (T007/T008 touch the same files as T005); removes US1's interim `orNull` glue in T008.
- **US3 (P3)**: Independent — a confirmation that the untouched Java-shaped type still works; can be checked any time after Foundational.

### Within Each User Story

- The round-trip test (T004) is written first and fails until T002/T003 register the module.
- Foundational (Bootstrap → descriptor) before any conversion; endpoint/agent conversions before their test updates.

### Parallel Opportunities

- T004 is `[P]` (new file, independent of the endpoint edits).
- Polish T013 and T014 are `[P]` (different files/concerns).
- US3 (T011) can run in parallel with US1/US2 once Foundational is done.

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup (T001) → Phase 2 Foundational (T002–T003) → Phase 3 US1 (T004–T006).
2. **STOP and VALIDATE**: `mvn verify` green + a live `POST /greet` returns `200` — the module is
   proven and the first wire type is idiomatic.

### Incremental Delivery

1. Setup + Foundational → module registered.
2. US1 → test → demo (module proven, `GreetRequest` idiomatic — MVP).
3. US2 → test → demo (all greeting wire types idiomatic, boundary code deleted).
4. US3 → confirm (Java-shaped coexistence preserved).
5. Polish → live smoke, README, clean verify.

---

## Notes

- [P] = different files, no dependencies.
- Offline tests use `TestModelProvider` — no live model, no network, deterministic (SC-004).
- `Bootstrap.onStartup` runs in the TestKit too (same descriptor-based discovery), so the module is
  registered for the offline suite, not only live.
- The agent stays on `responseAs` (feature 002 Gemini constraint); do NOT switch to
  `responseConformsTo` (out of scope).
- The `POST /greet` contract is pinned in `contracts/greeting-api.md` (C1–C7) — treat any deviation
  as a regression.
- Serialization quirks are provider-specific → the live smoke test (T012) is required, not optional.
- Commit after each task or logical group; validate at each checkpoint.
