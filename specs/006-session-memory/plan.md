# Implementation Plan: Session memory (multi-turn chat)

**Branch**: `006-session-memory` | **Date**: 2026-07-12 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/006-session-memory/spec.md`

## Summary

Add a **synchronous, multi-turn conversational agent** in **Scala** as a self-contained package
(`com.gwgs.akkaagentic.chat.*`) alongside the untouched capabilities 1–3. A `ChatAgent` (request-based
`Agent`) answers one message per request; the runtime's **session memory**, keyed by a caller-supplied
conversation id, retains each turn and replays it as context on the next call with the same id. A
`ChatEndpoint` exposes `POST /chat/{sessionId}` and returns `{sessionId, reply}`. Domain validation
(`ChatMessage`) rejects blank/absent input before the model is engaged.

The capability's headline (the Scala-on-Java-SDK through-line): **multi-turn session memory works from
Scala with zero new interop friction** — memory is keyed by the same session-id string we already pass
via `dynamicCall(...).inSession(id)`; the `MemoryProvider`/`MemoryFilter` API is builder-based (no
method-ref wall); and the backing `SessionMemoryEntity` is registered by the *runtime*, so it needs **no
entry in our hand-maintained descriptor**. This is the simplest capability yet: `String` in / `String`
out means there is **no Java-shaped wire payload at all** (contrast cap-1/cap-3's structured results).

## Technical Context

**Language/Version**: Scala 3 (on the Java-first Akka SDK), compiled by `scala-maven-plugin`.
**Primary Dependencies**: Akka SDK `akka-javasdk` (request-based `Agent`, session memory, HTTP
endpoints, `ComponentClient`). No new external dependencies.
**Storage**: Session memory, provided by the SDK's internal `SessionMemoryEntity` (event-sourced),
keyed by conversation id. This feature adds no storage of its own.
**Testing**: JUnit 5 + AssertJ + `TestKitSupport`; `TestModelProvider` mocks the model (offline, no key,
no network). Domain unit test + agent integration test + endpoint integration test.
**Target Platform**: Akka runtime (`kalix.runtime.AkkaRuntimeMain`), served on `http://localhost:9000`.
**Project Type**: Single Akka service (web service), mixed Scala/Java module — this capability is Scala.
**Performance Goals**: One model round-trip per turn; interactive latency dominated by the model. Not a
throughput feature.
**Constraints**: Offline-deterministic tests; capabilities 1–3 unchanged and green; idiomatic Scala in
domain/API layers (Option, Either, immutability) per project conventions.
**Scale/Scope**: Three new source files + three test files + two descriptor lines. One agent, one
endpoint, one domain validator.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First** — ✅ Built entirely on SDK primitives: request-based `Agent`, SDK session memory,
  HTTP endpoint, `ComponentClient`. No third-party additions; no custom persistence (memory is the SDK's).
- **II. Design Principles** — ✅
  - *Domain independence*: `ChatMessage` (validation) lives in `domain`, no Akka imports.
  - *API isolation*: `ChatEndpoint` defines its own `Option`-shaped request/response DTOs; it never
    exposes the agent's `String` payload directly (it wraps it in `ChatReply`).
  - *Single responsibility*: agent = converse; endpoint = HTTP surface; domain = validation.
  - *Descriptive naming*: `ChatAgent`, `ChatEndpoint`, `ChatMessage` — domain-aligned, suffixed.
- **III. Test Coverage** — ✅ Every new behavior has a test: domain validation (unit), multi-turn +
  isolation (agent integration), HTTP status/validation contract (endpoint integration).
- **IV. Simplicity** — ✅ YAGNI honored: no history-listing/export/delete endpoint, no server-minted ids,
  no streaming, no compaction (all explicitly Out of Scope). One flat agent, no abstraction layers.

**Result: PASS** (no violations; Complexity Tracking not required).

## Project Structure

### Documentation (this feature)

```text
specs/006-session-memory/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── chat-api.md      # Phase 1 output — HTTP contract
├── checklists/
│   └── requirements.md  # From /akka.specify
└── tasks.md             # Phase 2 output (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
# Capability 4 — Scala (session memory: multi-turn chat) — NEW, self-contained
src/main/scala/com/gwgs/akkaagentic/chat/domain/      # ChatMessage (+ validation)
src/main/scala/com/gwgs/akkaagentic/chat/application/ # ChatAgent (request-based Agent + session memory)
src/main/scala/com/gwgs/akkaagentic/chat/api/         # ChatEndpoint (POST /chat/{sessionId})

src/test/scala/com/gwgs/akkaagentic/chat/domain/      # ChatMessageTest
src/test/scala/com/gwgs/akkaagentic/chat/application/ # ChatAgentIntegrationTest (multi-turn + isolation)
src/test/scala/com/gwgs/akkaagentic/chat/api/         # ChatEndpointIntegrationTest (HTTP contract)

# Edited (additive only)
src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
  # + chat-agent under `agent`; + ChatEndpoint under `http-endpoint`. NO SessionMemoryEntity entry.

# Untouched: com.gwgs.akkaagentic.{domain,application,api} (cap-1),
#            com.gwgs.akkaagentic.team.* (cap-2, Java), com.gwgs.akkaagentic.assistant.* (cap-3)
```

**Structure Decision**: Reuse the established per-capability package split (`domain`/`application`/`api`)
under a new `chat` sub-package, exactly as cap-3 used `assistant`. Keeps capabilities decoupled and
leaves 1–3 byte-for-byte unchanged (SC-006). No `pom.xml` change — the mixed Scala/Java build already
compiles new Scala sources.

## Complexity Tracking

> No Constitution violations — section intentionally empty.
