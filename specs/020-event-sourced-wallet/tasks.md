# Tasks: Event-sourced wallet (Event Sourced Entity)

**Input**: Design documents from `specs/020-event-sourced-wallet/`
**Prerequisites**: plan.md, spec.md, research.md (all measured), data-model.md, contracts/wallet-endpoints.md

**Tests**: INCLUDED — the spec requests them (SC-005 pure-domain unit tests; US1–US3 are verified by
integration tests). Follow the project's test-selection rule (CLAUDE.md): domain → unit by name; entity/
endpoint → that capability's integration tests; descriptor/`application.conf`/`pom.xml` → `mvn clean verify`.

**Organization**: by user story (spec.md). Note these stories are facets of **one** entity, so US2/US3
build on US1's entity + endpoint rather than being fully independent slices; dependencies are called out.

**Phase 0 is already done** (committed): the probe under `com.gwgs.akkaagentic.wallet.probe` measured every
interop question (research.md). Much of US1 is *promoting* that proven code into production locations and
splitting the rules into a pure domain model.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no incomplete dependency)
- Paths are repo-relative; `scala/` and `java/` trees as in plan.md "Project Structure".

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: production package layout; the `event-sourced-entity` descriptor key already exists (probe).

- [ ] T001 Create production package dirs: `src/main/scala/com/gwgs/akkaagentic/wallet/{domain,application}`, `src/main/java/com/gwgs/akkaagentic/wallet/{domain,api}`, and matching test dirs under `src/test/{scala,java}/com/gwgs/akkaagentic/wallet/{domain,application,api}`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the event hierarchy and the pure domain model — prerequisites for the entity in every story.

**⚠️ CRITICAL**: no entity/endpoint work begins until this phase is complete.

- [ ] T002 [P] Promote the event hierarchy to `src/main/java/com/gwgs/akkaagentic/wallet/domain/WalletEvent.java` — a Java `sealed interface` with `@TypeName` records `Opened`/`Deposited`/`Withdrawn`/`Closed` (research D1); move it out of `wallet/probe`
- [ ] T003 [US-shared] Create the pure domain model `src/main/scala/com/gwgs/akkaagentic/wallet/domain/Wallet.scala` — `case class Wallet(balance: Long, open: Boolean)` + SDK-free rules returning `Either[String, WalletEvent]` for open/deposit/withdraw/close (parse-don't-validate; data-model.md "Pure rules"); references the Java `WalletEvent`
- [ ] T004 [P] Pure-domain unit tests `src/test/scala/com/gwgs/akkaagentic/wallet/domain/WalletTest.scala` — construct **no** SDK types (SC-005): fold correctness, each rejection (overdraw/non-positive/closed/re-open), exact-balance withdrawal allowed, close-with-balance decision; run `mvn test -Dtest=WalletTest`

**Checkpoint**: events + domain rules proven in isolation; the entity can now be built on them.

---

## Phase 3: User Story 1 — A wallet records its history and reports its balance (P1) 🎯 MVP

**Goal**: open/deposit/withdraw/close persist events; balance is the fold; readable over HTTP.

**Independent Test**: open, deposit twice, withdraw once → `GET` returns the signed sum; each accepted op
produced exactly one event.

### Implementation for User Story 1

- [ ] T005 [US1] Create `src/main/scala/com/gwgs/akkaagentic/wallet/application/WalletEntity.scala` — `EventSourcedEntity[Wallet, WalletEvent]`, `@Component(id = "wallet")`, `emptyState()`, thin command handlers delegating legality to `Wallet` (T003) and persisting on success, total `applyEvent` (data-model.md "Entity")
- [ ] T006 [US1] Register the production entity in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` under `event-sourced-entity` (replace the probe class `wallet.probe.WalletEntity` with `wallet.application.WalletEntity`); this touches the descriptor → gate is `mvn clean verify`
- [ ] T007 [US1] Create `src/main/java/com/gwgs/akkaagentic/wallet/api/WalletEndpoint.java` (Java — entity client is method-ref-only, research D4): `POST /wallets/{id}/{open,deposit,withdraw,close}`, `GET /wallets/{id}`; endpoint-local `OpenRequest`/`AmountRequest`/`WalletView` records + private `toApi(Wallet)`; read `getBalance` reply via `((Number) reply).longValue()` (research D6); `@Acl` as other endpoints; register under `http-endpoint`
- [ ] T008 [P] [US1] Endpoint integration test `src/test/java/com/gwgs/akkaagentic/wallet/api/WalletEndpointIntegrationTest.java` (`TestKitSupport` + `httpClient`): open→deposit×2→withdraw→`GET` equals the signed sum; `GET` of an untouched id returns empty state `{balance:0,open:false}`, not 404 (contracts + spec Edge Cases)

**Checkpoint**: MVP — a wallet works end to end over HTTP. Gate: `mvn clean verify` (T006 touched the descriptor).

---

## Phase 4: User Story 2 — Refuses what the rules forbid, records nothing (P1)

**Goal**: forbidden ops are rejected with the balance unchanged and **no event** persisted.

**Independent Test**: against an open wallet, each forbidden op → 400, balance unchanged, event count not
advanced.

**Depends on**: US1 (same entity + endpoint).

### Implementation for User Story 2

- [ ] T009 [US2] Verify each handler maps a domain rejection to `HttpResponses.badRequest(...)` and never throws for a validation failure (AGENTS.md HTTP rule); adjust `WalletEndpoint.java` / `WalletEntity.scala` if any path throws
- [ ] T010 [P] [US2] Add integration tests to `WalletEndpointIntegrationTest.java`: overdraw, zero/negative amount, any op on a closed wallet, re-open → each returns 400; after each, `GET` shows the unchanged balance. Assert **no event advanced** by reading the entity's event count via a Java `EventSourcedTestKit` unit test `src/test/java/com/gwgs/akkaagentic/wallet/application/WalletEntityTest.java` (Java method ref — research D5/Q-E) covering `getAllEvents().size()` before/after a rejected command

**Checkpoint**: US1 + US2 both hold; rejected commands provably persist nothing (SC-002/SC-003).

---

## Phase 5: User Story 3 — Snapshot-reconstituted wallet equals a full replay (P2)

**Goal**: a wallet past the snapshot threshold reloads identically.

**Independent Test**: drive past `snapshot-every`, confirm the folded balance stays correct; state
(de)serialization proven directly.

**Depends on**: US1.

### Implementation for User Story 3

- [ ] T011 [US3] Set `akka.javasdk.event-sourced-entity.snapshot-every` in `src/main/resources/application.conf` (or per-test config) low enough to exercise snapshots; state the chosen value in README §20. Descriptor/config touched → gate `mvn clean verify`
- [ ] T012 [P] [US3] State round-trip test (promote from the probe) `src/test/scala/com/gwgs/akkaagentic/wallet/application/WalletStateSerializationTest.scala` — `Wallet` through the internal `JsonSerializer` both directions (Q-C); plus an integration test driving > threshold events and asserting the fold stays correct across a snapshot WRITE
- [ ] T013 [US3] Record the honest limit in the test and README: TestKitSupport exposes no passivation hook, so reload-from-snapshot is proven by the serializer round-trip + snapshot-write, not by a forced reload (research "what remains unverified" #1)

**Checkpoint**: snapshot behaviour exercised and its boundary stated, not glossed.

---

## Phase 6: User Story 4 — The interop question is answered in public (P3)

**Goal**: publish the measured verdict.

**Independent Test**: a reader learns the `enum`/sealed-trait/Java outcome, the snapshot result, and the
Java boundary from docs alone, without reading source.

- [ ] T014 [P] [US4] Add README "Scala interop notes" §20 — the two-gate finding (enum unreadable; sealed trait fails startup validation), the Java-events/Scala-entity/Scala-state/Java-endpoint shape, Q-C/Q-D/Q-E, and the build-ordering data point
- [ ] T015 [P] [US4] Update `ROADMAP.md` "Where we are" (cap-18 / A3 done; A4 the last untouched family) and the capability table row
- [ ] T016 [P] [US4] Update `FINDINGS.md` with the event-sourced-entity verdict
- [ ] T017 [P] [US4] Write/refresh memory: new `akka-scala-event-sourced-entity` note (the two-gate finding) and update the roadmap/index notes; link `[[akka-persistence-two-models]]`, `[[scala-jackson-module-followup]]`, `[[scala-akka-component-descriptor]]`

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T018 Retire/trim the Phase-0 probe (`src/main/{scala,java}/com/gwgs/akkaagentic/wallet/probe/`, tests): keep only the tests that still earn their place as evidence (the Q-E wall-pinning `WalletProbeTest`; the enum/sealed-trait failure is documented in research, not kept as dead code), drop coverage now duplicated by production tests; remove the probe entity's descriptor line if fully replaced (FR-014)
- [ ] T019 Final gate: `mvn clean verify` green; change set shows no existing capability's sources/tests modified (SC-006, FR-011)
- [ ] T020 Documentation commit (README/ROADMAP/FINDINGS) as its own final commit (CLAUDE.md); open/refresh the PR with the compare URL + body

---

## Dependencies & Execution Order

- **Setup (P1)** → **Foundational (P2: events + domain + domain tests)** → **US1 (entity + endpoint + IT)**.
- **US2** and **US3** both depend on US1 (same entity/endpoint); they are independent of each other.
- **US4 (docs)** depends on US1–US3 being settled (it reports their results).
- **Polish** last.

### Within each story

- Domain rules (pure) before the entity; entity before the endpoint; endpoint before its integration test.
- Descriptor/config changes (T006, T011) force `mvn clean verify`, not a selective run (CLAUDE.md).

### Parallel opportunities

- T002 (Java events) ∥ T004 (domain tests, once T003 exists).
- T014–T017 (docs) are all [P] — different files.

---

## Implementation Strategy

- **MVP = Phases 1–3 (US1)**: a working, HTTP-exercisable event-sourced wallet. Stop and validate.
- **Increment**: add US2 (rejection contract), then US3 (snapshots), each its own approved gate + commit.
- **US4 + Polish**: publish the verdict and retire the probe; docs are the final, separate commit.

## Notes

- Commit at each approved gate with a scoped message (CLAUDE.md); do not batch the capability into one commit.
- The one forced-Java file is `WalletEvent.java`; everything else the SDK does not force is Scala.
- Keep endpoints thin; domain rules stay SDK-free and are the unit-test surface (SC-005, FR-009).
