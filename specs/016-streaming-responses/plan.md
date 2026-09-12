# Implementation Plan: Streaming agent responses

**Branch**: `016-streaming-responses` | **Date**: 2026-09-12 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/016-streaming-responses/spec.md`

## Summary

Add `POST /stream-chat/{sessionId}`: a conversation whose reply is delivered **as it is generated**.
A Scala `StreamingChatAgent` returns `StreamEffect` instead of `Effect[String]`; the endpoint consumes
its token stream, groups the fragments, guards the stream against never terminating, and returns them
as a streaming text response. Capability 4's chat surface is untouched.

Phase 0 measured all four open questions (see [research.md](research.md)). Two shape the plan:

- **Authoring is Scala-clean; consuming is not.** Every Scala form of `tokenStream` either fails to
  compile or fails at run time with `IllegalArgumentException: class <the caller's own class> is not a
  subclass of class akka.javasdk.agent.Agent`. The Java form works. So the endpoint is **Java, one
  class** — capability 11's shape — and everything else is Scala.
- **A failed model call never terminates the stream** (measured: no event after 240 s). The endpoint
  must impose termination with `initialTimeout`, or a caller hangs on an open connection.

## Technical Context

**Language/Version**: Scala 3.3.8 (LTS) on Java 21 (Temurin); one Java 21 class by measurement
**Primary Dependencies**: `akka-javasdk` 3.6.3 only — **no new dependency**. `akka.stream.javadsl`
arrives with the SDK and is already on the compile classpath.
**Storage**: none of ours. Conversation history is the runtime's `SessionMemoryEntity`, keyed by the
`sessionId` in the path (measured to receive the assembled streamed reply — research Q-D).
**Testing**: JUnit 5 + AssertJ; `TestModelProvider` for the model (tokenizes a scripted reply — 57
fragments, measured), so the whole capability is offline. Streams are run in tests with
`testKit.getMaterializer()`.
**Target Platform**: the existing service; new HTTP path only.
**Project Type**: single Akka service (this repository).
**Performance Goals**: the first fragment reaches the caller in a small fraction of the total answer
time (SC-001), and fragments are grouped so a client handles far fewer events than tokens (FR-007).
**Constraints**: offline-first (no API key, no network in tests); no existing capability's sources or
tests modified (FR-009); `domain` free of Akka imports; a streaming response must terminate even when
the model never produces a token (FR-006, from research Q-A(2)).
**Scale/Scope**: one agent, one endpoint, one small domain validation type, ~6 test classes.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — still passing.*

| Principle | Status | Evidence |
|---|---|---|
| **I. Akka SDK First** (non-negotiable) | **PASS** | The feature is an SDK `Agent` returning the SDK's `StreamEffect`, consumed through the SDK's `ComponentClient` and returned by the SDK's `HttpResponses.streamText`. **No new dependency**: `akka.stream.javadsl` is inside the SDK's own tree. |
| **II. Design Principles** | **PASS** | *Domain independence*: question/session validation is a pure Scala type with no Akka import. *API isolation*: the endpoint owns its request type and emits text fragments, never a domain or agent type. *Single responsibility*: the agent generates, the endpoint shapes and delivers. *Descriptive naming*: `StreamingChatAgent`, `StreamingChatEndpoint`, `StreamQuestion`. |
| **III. Test Coverage** | **PASS** | Unit tests for the pure validation; integration tests for incrementality, parity, grouping, the termination guard, multi-turn memory, and validation-first. The two Phase 0 probes are kept as permanent evidence (FR-013). |
| **IV. Simplicity** | **PASS** | One agent, one endpoint, one validation type, no state of our own. The stream guard is three operators on the source, not a new abstraction. Grouping uses the SDK's recommended `groupedWithin` rather than hand-rolled batching. |

**Deviation requiring justification**: one **Java** class in an otherwise-Scala capability. See
Complexity Tracking — it is forced by a measured SDK property, and it is the smallest unit that works.

## Project Structure

### Documentation (this feature)

```text
specs/016-streaming-responses/
├── spec.md              # /akka.specify output
├── plan.md              # this file
├── research.md           # Phase 0 — four measured answers
├── data-model.md         # Phase 1
├── quickstart.md         # Phase 1
├── contracts/
│   └── stream-chat-endpoint.md
├── checklists/
│   └── requirements.md
└── tasks.md              # /akka.tasks output — NOT created here
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/streaming/domain/
└── StreamQuestion.scala              # pure validation; NO Akka import (Constitution II)

src/main/scala/com/gwgs/akkaagentic/streaming/application/
└── StreamingChatAgent.scala          # EXISTS (Phase 0 probe subject): StreamEffect + session memory

src/main/java/com/gwgs/akkaagentic/streaming/api/
└── StreamingChatEndpoint.java        # the ONLY Java class: holds the tokenStream method reference,
                                      # groups fragments, guards termination, returns streamText

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
                                      # + StreamingChatAgent under `agent` (DONE), + the endpoint
                                      # under `http-endpoint` (Phase 2)

src/test/scala/com/gwgs/akkaagentic/streaming/domain/
└── StreamQuestionTest.scala          # validation, no runtime

src/test/scala/com/gwgs/akkaagentic/streaming/api/
└── StreamingChatEndpointIntegrationTest.scala   # SCALA: httpClient only, no method ref

src/test/scala/com/gwgs/akkaagentic/streaming/probe/
└── ScalaTokenStreamProbeIntegrationTest.scala   # EXISTS: the wall, recorded (FR-013)

src/test/java/com/gwgs/akkaagentic/streaming/application/
└── StreamProbeIntegrationTest.java   # EXISTS: Java control + token count + memory (Q-A/C/D)
```

**Structure Decision**: the capability's own package, mirroring capability 13's separation, so FR-009
is provable by `git diff` rather than by argument. The Java/Scala split follows the measurement, not
taste: **only** the class holding a `tokenStream` method reference is Java. Note where the line does
*not* fall — the endpoint's own integration test stays **Scala**, because `httpClient` holds no method
reference, exactly as capability 11's endpoint test did.

## Key design decisions

1. **The endpoint is Java, and nothing else is.** Measured in research Q-B. The agent, the domain, the
   descriptor entry, and the endpoint's test are Scala.
2. **`initialTimeout` is load-bearing, not defensive.** Research Q-A(2) showed a pre-token model
   failure produces no event ever. The endpoint applies `initialTimeout` so FR-006 is satisfiable at
   all, and `idleTimeout` so a stream that stalls mid-answer also terminates (FR-005). Both surface as
   stream failure, which the streaming response turns into a terminated connection — the only signal
   available once bytes are already flowing.
3. **Grouping with `groupedWithin(20, 100.millis)`**, the SDK's own recommendation, then joined into
   one string per group. FR-007, and cheap to assert offline because token counts are deterministic.
4. **`streamText`, not `serverSentEvents`.** The reply is plain text; SSE would add framing the caller
   must parse. Recorded as a fork in research D4.
5. **No fallback value, because none can exist.** `StreamEffect` has no `onFailure` (research Q-A) and
   a fallback cannot replace text already read. This is the first capability in the project that
   *cannot* use the sentinel technique of capabilities 8, 12 and 13 — stated in the docs rather than
   worked around.
6. **Session memory is the same shape as capability 4's** (`limitedWindow()`, no `readLast`), justified
   by Q-D and by capability 6's live `readLast` bug.

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| One **Java** class (`StreamingChatEndpoint`) in a Scala capability | Consuming a token stream requires a Java method reference; every Scala form was measured failing (compile-time for `dynamicCall`/string-keyed, run-time for the lambda). The Java control works. | *Scala-only endpoint*: there is no reachable Scala consumption path — the capability would have no working surface. *Whole capability in Java*: the wall reaches only the class holding the method reference, proven by capability 11 and re-proven here — a Java agent would concede more than measured. |
| A stream **guard** (`initialTimeout` + `idleTimeout`) rather than plain pass-through | A failed model call never terminates the stream (measured at 240 s), so without a guard FR-006 is unsatisfiable and callers hang. | *Trusting the SDK/provider timeouts*: measured — neither terminated the stream. |

## Phase 2 preview (owned by `/akka.tasks`, not created here)

Foundational: the domain validation type and its unit test. US1: the endpoint plus incrementality and
parity tests. US2: the multi-turn memory test. US3: the termination-guard tests. US4: README §16,
FINDINGS, ROADMAP, `docs/sdk-3.6.0-limitations.md` for the never-terminating stream. Finally a live
smoke test covering the two unverified items in research.
