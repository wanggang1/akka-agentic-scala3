# Implementation Plan: Event-sourced wallet (Event Sourced Entity)

**Branch**: `020-event-sourced-wallet` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/020-event-sourced-wallet/spec.md`

## Summary

Build the repo's first **event-sourced entity** — a `Wallet` ledger (open/deposit/withdraw/close, read
balance) — to answer A3: can a Scala 3 sum type be the event hierarchy the SDK handles? **Phase 0 measured
the answer (research.md): no.** A Scala `enum` serializes but cannot be deserialized; a Scala `sealed trait`
round-trips through the mapper but fails the SDK's startup validation because Scala 3.3.8 does not emit a
JVM-`sealed` interface. So the **events are a Java `sealed interface` of records** (one file), while the
**entity and the `Wallet` state stay Scala** (the state round-trips as a plain case class), and the
**endpoint is Java** (the entity client is method-reference-only). Business rules live in a pure Scala
domain model, unit-tested there; the entity is exercised by a Java integration test.

## Technical Context

**Language/Version**: Scala 3.3.8 (entity, state, domain, tests) + Java 21 (event hierarchy, endpoint,
entity integration test) on the Java-first Akka SDK 3.6.3
**Primary Dependencies**: Akka SDK (`event-sourced-entity`, HTTP endpoint, testkit) — no new dependency
**Storage**: the entity's event journal (+ periodic snapshots); no external store
**Testing**: JUnit 5 + AssertJ; `EventSourcedTestKit` (Java) for the entity, `TestKitSupport` for the
endpoint, pure Scala unit tests for the domain
**Target Platform**: local dev runtime / Akka platform
**Project Type**: single service (mixed Scala/Java), existing layout
**Performance Goals**: none specific — a sandbox capability; tests stay offline and fast
**Constraints**: no model, no network; existing capabilities' sources/tests unchanged (FR-011)
**Scale/Scope**: one entity, one endpoint, one domain model, ~one Java event file

## Constitution Check

*GATE: must pass before Phase 0 research. Re-checked after design.*

- **I. Akka SDK First** — ✅ an SDK Event Sourced Entity; no custom persistence; no new dependency.
- **II. Design Principles** — ✅ domain independence (rules in pure Scala `Wallet`, SDK-free, FR-009); API
  isolation (endpoint defines its own request/response records, never returns the domain `Wallet`); single
  responsibility (entity persists/folds, domain decides legality, endpoint adapts HTTP); descriptive naming
  (`WalletEntity`, `WalletEvent`, `WalletEndpoint`).
- **III. Test Coverage** — ✅ domain rules unit-tested in pure Scala; entity + endpoint via integration.
- **IV. Simplicity** — ✅ one entity, one endpoint; no transfers/multi-currency/overdraft (Assumptions);
  the Java footprint is the one file the SDK's validation forces, no more.

**Result: PASS.** No violations; Complexity Tracking not required.

The one deviation from "author everything in Scala" — the Java event hierarchy — is **not** a constitution
deviation but a measured SDK constraint (research Q-A/Q-B): the SDK requires a JVM `sealed interface` event
type, which Scala 3.3.8 cannot emit. It is confined to one file (FR-014).

## Project Structure

### Documentation (this feature)

```text
specs/020-event-sourced-wallet/
├── plan.md              # this file
├── research.md          # Phase 0 — all interop questions MEASURED
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── wallet-endpoints.md
├── checklists/
│   └── requirements.md
├── spec.md
└── tasks.md             # Phase 2 (/akka.tasks)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/wallet/
├── domain/
│   └── Wallet.scala              # state + pure rules (open/deposit/withdraw/close), SDK-free
└── application/
    └── WalletEntity.scala        # EventSourcedEntity[Wallet, WalletEvent]; thin handlers + applyEvent

src/main/java/com/gwgs/akkaagentic/wallet/
├── domain/
│   └── WalletEvent.java          # sealed interface + 4 @TypeName records (the one forced-Java file)
└── api/
    └── WalletEndpoint.java       # POST /wallets/{id}/{open,deposit,withdraw,close}, GET /wallets/{id}

src/test/scala/com/gwgs/akkaagentic/wallet/
├── domain/WalletTest.scala                 # pure domain rules (no SDK types)
└── application/WalletEntityTest.scala?      # OPTIONAL — only if a Java-driver form is wanted; see D5
src/test/java/com/gwgs/akkaagentic/wallet/
└── api/WalletEndpointIntegrationTest.java   # boots the service, drives over HTTP

src/main/resources/META-INF/akka-javasdk-components_*.conf   # + event-sourced-entity key (done in probe)
```

**Structure Decision**: the standard `domain`/`application`/`api` split, with the single measured twist
that `WalletEvent` sits under `domain` in **Java** (the SDK's sealed-interface requirement), while the
state and entity beside it are Scala. The Phase 0 probe under `wallet/probe/` is kept as FR-014 evidence
and is **replaced** by the production entity on the same descriptor key (the probe `@Component` id
`wallet-probe` gives way to the production id, or the probe is retired — decided in tasks).

## Phase 2 preview (owned by `/akka.tasks`)

Dependency-ordered, one component per gate (CLAUDE.md incremental workflow):
1. **Domain** — `Wallet.scala` (state + rules) + `WalletTest.scala` (pure unit tests).
2. **Events** — `WalletEvent.java` (promote the probe's file from `probe` to `domain`).
3. **Entity** — `WalletEntity.scala` + descriptor; exercised by the endpoint integration test.
4. **Endpoint** — `WalletEndpoint.java` (Java, Q-D) + request/response records + `toApi`.
5. **Integration test** — `WalletEndpointIntegrationTest.java` over HTTP.
6. **Probe retirement** — remove/relabel `wallet/probe/*` per FR-014 (keep the wall-pinning tests that
   still earn their place, drop duplicated coverage).
7. **Docs** — README §20, ROADMAP, FINDINGS, memory.

## Complexity Tracking

No constitution violations to justify.
