# Implementation Plan: Usage-accurate citations

**Branch**: `023-usage-accurate-citations` | **Date**: 2026-10-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/023-usage-accurate-citations/spec.md`

## Summary

Add a **new, parallel** RAG surface, `POST /cited-ask`, whose citations reflect the sources the model
reports **using** rather than everything that was **retrieved**. A new agent (`citing-docs-agent`) answers
with structured output `{ answer, usedSources }`; the endpoint intersects `usedSources` with the actually
retrieved labels (the honesty floor) and returns both the usage-accurate `citedSources` and the
ground-truth `retrievedSources`. Cap-8's `/ask`, its agent and its contract are untouched. The capability's
real deliverable is a **measurement** of how faithfully qwen3:8b self-reports its used sources — the
deliberate reopening of cap-7's D6 self-report tension, stated from evidence (research R1/R6).

## Technical Context

**Language/Version**: Scala 3.3.x on the Java-first Akka SDK 3.6.3 (free-tier ceiling)
**Primary Dependencies**: Akka SDK (`agent`, `http`, `client`); reused in-process RAG stack from cap-8
(langchain4j + all-minilm ONNX, in-jar) — **no new dependencies** (constitution §I)
**Storage**: N/A — reuses cap-8's in-memory `KnowledgeStore` vector index
**Testing**: ScalaTest/JUnit via `TestKitSupport`; `EventSourcedTestKit` n/a (no entity);
`TestModelProvider` for deterministic model mocking; Awaitility n/a (synchronous endpoint)
**Target Platform**: Local JVM (dev mode) / Akka runtime
**Project Type**: Single project (web service) — `src/main/scala`, `src/test/scala`
**Performance Goals**: N/A — correctness/honesty capability, not a throughput one
**Constraints**: Citations must satisfy `citedSources ⊆ retrievedSources` (SC-002); offline-deterministic
correctness tests + one live faithfulness measurement (SC-005)
**Scale/Scope**: 1 new agent, 1 new endpoint, 1 pure domain object, 2 test classes, 2 descriptor lines,
README section + ROADMAP/FINDINGS/memory updates

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Note |
|-----------|--------|------|
| I. Akka SDK First | ✅ PASS | Built only on SDK primitives (Agent, HttpEndpoint, ComponentClient). **No new dependencies** — reuses cap-8's RAG stack. |
| II. Design Principles | ✅ PASS | Domain-independent selection logic (`UsageCitations`, no framework deps); API-owned request/response records (never exposes agent/domain types); single-responsibility new agent + endpoint; descriptive domain names. |
| III. Test Coverage | ✅ PASS | Pure logic → unit tests (`UsageCitationsTest`); endpoint → integration test (`CitedDocsEndpointIntegrationTest`); live faithfulness recorded. No existing coverage decreases (cap-8 untouched). |
| IV. Simplicity | ✅ PASS | New agent only because one handler per Agent forces it (R2); reuses cap-8 wire/retrieval types; failed→decline collapsed (no `/evaluate` consumer, R3); guardrails **not** re-wired (R5, YAGNI). |

**Post-Phase-1 re-check:** ✅ still PASS — design added no abstraction beyond the one pure function and the
two SDK components the feature inherently needs. No entries in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/023-usage-accurate-citations/
├── plan.md              # this file
├── research.md          # Phase 0 ✅
├── data-model.md        # Phase 1 ✅
├── quickstart.md        # Phase 1 ✅
├── contracts/
│   └── cited-ask.md     # Phase 1 ✅
├── checklists/
│   └── requirements.md  # from /akka.specify ✅
└── tasks.md             # Phase 2 (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/docs/
├── domain/
│   └── UsageCitations.scala          # NEW — pure select(reportedUsed, retrievedLabels, declined)
├── application/
│   ├── DocsAgent.scala               # UNCHANGED (cap-8) — Request/Passage/DontKnow reused read-only
│   ├── KnowledgeStore.scala          # UNCHANGED (cap-8) — retrieve() reused
│   └── CitingDocsAgent.scala         # NEW — id "citing-docs-agent", responseConformsTo(CitedAnswer)
└── api/
    ├── DocsEndpoint.scala            # UNCHANGED (cap-8) — /ask left intact (FR-001)
    └── CitedDocsEndpoint.scala       # NEW — POST /cited-ask

src/test/scala/com/gwgs/akkaagentic/docs/
├── domain/
│   └── UsageCitationsTest.scala      # NEW — unit: subset, decline, drop-non-retrieved, empty→empty
└── api/
    └── CitedDocsEndpointIntegrationTest.scala  # NEW — integration, model mocked

src/main/resources/META-INF/
└── akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf  # +2 lines (agent, http-endpoint)
```

**Structure Decision**: Existing single Scala project; all new code under the `docs` feature package in
its three layers (domain/application/api), matching cap-8's own layout.

## Phase 0 — Research ✅

See [research.md](./research.md). All NEEDS CLARIFICATION resolved (the one spec clarification, FR-007, was
resolved by the user to **cite nothing**). Open items are explicitly UNVERIFIED and are precisely what the
live measurement (R6/SC-005) exists to settle — no confident prose stands in for them.

Key decisions: R1 `responseConformsTo` (no-tools, qwen3-proven) · R2 new agent + descriptor lines ·
R3 decline sentinel reused, failed→decline collapsed · R4 pure `UsageCitations.select` + intersection
floor · R5 **guardrails not re-wired** (flagged for user) · R6 offline correctness + live faithfulness ·
R7 separate endpoint class, `dynamicCall`.

## Phase 1 — Design & Contracts ✅

[data-model.md](./data-model.md), [contracts/cited-ask.md](./contracts/cited-ask.md),
[quickstart.md](./quickstart.md) written. Agent context is CLI-managed; no manual update.

## Phase 2 — Task planning approach (NOT executed here)

`/akka.tasks` will generate `tasks.md`. Expected shape, following the CLAUDE.md incremental gates
(domain → agent → tests → endpoint → integration tests → docs), one commit per approved gate:

1. **Domain**: `UsageCitations.select` + `UsageCitationsTest` (decline, subset/order, drop-non-retrieved,
   empty-intersection→empty). Pure, fast.
2. **Application**: `CitingDocsAgent` (structured output, `onFailure`→decline fallback) + descriptor line.
3. **API**: `CitedDocsEndpoint` (`POST /cited-ask`, validate→retrieve→agent→select) + descriptor line.
4. **Integration**: `CitedDocsEndpointIntegrationTest` with `TestModelProvider` — answered/subset,
   decline/empty, non-retrieved-label-dropped, FR-007 empty, 400; plus a cap-8 `/ask` regression assertion
   (SC-004).
5. **Gate**: `mvn clean verify`.
6. **Live measurement + docs**: run in-corpus questions against qwen3:8b; record faithfulness; README new
   §, ROADMAP B4 flip, FINDINGS, memory. (Docs are their own final commit.)

## Open decision for the plan gate

- **Guardrail parity (R5):** the new surface is planned **ungoverned** (cap-12 rules attach to `docs-agent`
  only). If you want cap-12's jailbreak guardrail on `/cited-ask` too, it is a one-line config add per rule
  — say so and it moves into Phase 2; otherwise it stays a recorded scope boundary.

## Complexity Tracking

No constitution violations — table intentionally empty.
