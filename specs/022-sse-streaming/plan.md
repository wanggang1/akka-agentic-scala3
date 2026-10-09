# Implementation Plan: SSE Framing for the Streaming Agent Surface (capability 20)

**Branch**: `022-sse-streaming` | **Date**: 2026-10-09 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/022-sse-streaming/spec.md`

## Summary

Add a new streaming surface `POST /sse-chat/{sessionId}` that delivers the **same** agent's answer as
capability 14, but framed as **Server-Sent Events** (`text/event-stream`) with explicit
`event: data` / `event: error` frames. Capability 14's raw-chunked `/stream-chat/{sessionId}` stays
untouched as the baseline. The technical core (research Q-C) is that the SDK's
`HttpResponses.serverSentEvents` **silently empties the stream on a source failure**, so the agent
token source must convert its own failure into a **final `error`-typed element** (Akka Streams
`recover`) before being handed to the helper — otherwise the capability buys nothing over cap-14.

## Technical Context

**Language/Version**: Scala 3.3.8 on the Java-first Akka SDK; one forced-Java class (JDK 21).
**Primary Dependencies**: `akka-javasdk` 3.6.3 — `HttpResponses.serverSentEvents`, agent
`tokenStream(...)`, Akka Streams `Source` (`recover`, `initialTimeout`, `idleTimeout`,
`groupedWithin`). No new dependency (constitution I satisfied: first-class SDK SSE support exists —
research Q-A).
**Storage**: N/A — a response framing, no persistence, no component state.
**Testing**: ScalaTest/JUnit via `TestKitSupport`; SDK testkit `SseRouteTester`
(`TestKit#getSelfSseRouteTester`) for frame assertions; `TestModelProvider` to script/fail the agent.
**Target Platform**: Akka service, local dev + platform.
**Project Type**: Akka SDK service (single project; mixed Scala/Java).
**Performance Goals**: time-to-first-byte < total generation time (incremental delivery preserved,
SC-005); no server-side buffering of the whole answer (FR-008).
**Constraints**: failure must always be an in-band `event: error` frame, never a silent empty success
(SC-002); error frames carry no internal detail (FR-006); cap-14 untouched & green (FR-010/SC-004).
**Scale/Scope**: one new endpoint, one new Java wire-type (event envelope), a pure error-reason mapping,
and tests. No new agent, prompt, or component family.

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after Phase 1.*

| Principle | Status | Note |
|---|---|---|
| **I. Akka SDK First** | ✅ PASS | Uses `HttpResponses.serverSentEvents` (first-class SSE). No new external dependency; the only non-SDK construct is Akka Streams `recover`, which is part of the SDK's own stream API (research Q-A). |
| **II. Design Principles** | ✅ PASS | Domain rule (`StreamQuestion`) reused unchanged and framework-free; endpoint owns its `ChatRequest` + `SseChatEvent` types (API isolation); single responsibility (one surface); descriptive names (`SseChatEndpoint`, `SseChatEvent`, not `Event`/`Handler`). |
| **III. Test Coverage** | ✅ PASS | Pure `reasonFor(Throwable)` unit-tested; three integration scenarios (happy / fail@0 / fail@N) via `SseRouteTester`; cap-14 regression suite run unchanged. |
| **IV. Simplicity** | ✅ PASS | No reconnection/id/resume (research Q-E, YAGNI); no new component; reuses cap-14's agent, guards, and grouping. The envelope is a flat 2-case sealed record. |

**Post-Phase-1 re-check**: still PASS. The design added no abstraction beyond the `SseChatEvent`
envelope that the wire format inherently requires, and the one Java class is forced by the method-ref
wall, not by choice (research Q-F) — no complexity-tracking entry needed.

**Complexity Tracking**: *(empty — no violations to justify.)*

## Project Structure

### Documentation (this feature)

```text
specs/022-sse-streaming/
├── plan.md              # this file
├── spec.md              # feature spec
├── research.md          # Phase 0 — Q-A..Q-G (resolved)
├── data-model.md        # Phase 1 — SseChatEvent envelope + stream lifecycle
├── quickstart.md        # Phase 1 — run + the load-bearing recover
├── contracts/
│   └── sse-chat.md       # Phase 1 — POST /sse-chat/{sessionId} wire contract
├── checklists/
│   └── requirements.md   # spec quality checklist (all pass)
└── tasks.md             # Phase 2 — created by /akka.tasks, NOT here
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/streaming/
├── application/StreamingChatAgent.scala     # REUSED, unchanged (agent)
└── domain/StreamQuestion.scala              # REUSED, unchanged (validation rule)
    # (optional) domain/SseErrorReason.scala — pure reasonFor(Throwable): String, if placed in Scala

src/main/java/com/gwgs/akkaagentic/streaming/api/
├── StreamingChatEndpoint.java               # REUSED, unchanged (cap-14 baseline, /stream-chat)
├── SseChatEndpoint.java                      # NEW — POST /sse-chat/{sessionId}; the one forced-Java class
└── SseChatEvent.java                         # NEW — sealed envelope: Data(text) | ErrorEvent(reason)

src/test/scala/com/gwgs/akkaagentic/streaming/
├── api/SseChatEndpointIntegrationTest.scala  # NEW — happy / fail@0 / fail@N via SseRouteTester
└── domain/SseErrorReasonTest.scala           # NEW — reasonFor mapping (if helper is Scala)
    # cap-14 tests under this tree run UNCHANGED (US3 regression)
```

**Structure Decision**: Single Akka project, existing `streaming` package. The capability is additive:
the only new production files are the Java endpoint `SseChatEndpoint.java` and the Java envelope
`SseChatEvent.java` (both forced Java — see below). A `JavaQuarantineTest` already pins which files are
Java in this capability's tree; it is updated to expect exactly these two, so quarantine growth is a
recorded finding, not silent drift.

## Why Java is required, and bounded to two files (research Q-D, Q-F)

- **`SseChatEndpoint.java`** — consuming `tokenStream(StreamingChatAgent::stream)` is only expressible
  as a Java method reference (the wall; specs/016 Q-B, README §16). The whole SSE pipeline
  (`recover` → envelope → `serverSentEvents`) lives in this one class, adding no Java beyond it.
- **`SseChatEvent.java`** — the envelope is JSON-serialized by the SDK's **internal** mapper, which has
  no Scala module, so the wire type is Java-shaped (project rule `scala-jackson-module-followup`).
- Everything else — the agent, the validation rule, the (pure) error-reason mapping, and all tests —
  is Scala. This is capability 11's shape: the wall claims exactly these surfaces and travels no
  further.

## Descriptor note

`SseChatEndpoint` is an HTTP endpoint (no `@Component`), so — like cap-14 — it needs **no** entry in the
hand-maintained Scala component descriptor (`scala-akka-component-descriptor`). Confirm during
implementation that no descriptor change is required (endpoints are discovered differently from
components). This is a `mvn clean verify`-class check (CLAUDE.md test-selection table: touching the
endpoint surface → run this capability's integration tests; the final gate is full `clean verify`).

## Phase sequencing (for /akka.tasks)

Follows CLAUDE.md's incremental, gate-committed workflow — one component + its test per approved gate:

1. **Envelope + error-reason mapping** (`SseChatEvent.java`, `reasonFor`) + unit test for the mapping.
2. **Endpoint** (`SseChatEndpoint.java`): validate → tokenStream → guards → group → map to `Data` →
   `recover` to `ErrorEvent` → `serverSentEvents`.
3. **Integration tests**: happy / fail-before-first-token / fail-after-N via `SseRouteTester` +
   `TestModelProvider`; assert SC-001 parity and SC-002/SC-003 distinct error framing. Drive the
   pre-first-token failure with a materialized provider failure (research "Testing"), not an empty
   scripted reply.
4. **Baseline regression**: cap-14 tests unchanged & green; update `JavaQuarantineTest`.
5. **Docs** (own final commit): README new §20 recording the Q-C finding and the Q-G heartbeat
   correction; ROADMAP "Where we are" flip + table row (capability 20, fork B2); FINDINGS; memory.

## Risks / watch-items

- **The `recover` must sit upstream of `serverSentEvents`** and the `ErrorEvent` must be trivially
  JSON-serializable, or the SDK's inner `recoverWith` re-swallows it (research Q-C/Q-D.4). This is the
  one subtle correctness point.
- **TestModelProvider failure semantics**: a *scripted* token stream may not materialize a Throwable
  (specs/016 Q-A); use `whenMessage(...).failWith` so `.recover` actually fires. If offline cannot
  materialize a pre-first-token stream failure at all, record that as a measured test-harness limit
  (consistent with `akka-testing-slow-and-failing-calls`) and verify the happy + fail@N paths offline,
  noting fail@0 as live-only if needed.
- **3-arg overload needs both id and type functions** (research Q-E): supply a no-op/constant id and the
  real type function; confirm the emitted frames carry `event:` and no misleading `id:`.
```
