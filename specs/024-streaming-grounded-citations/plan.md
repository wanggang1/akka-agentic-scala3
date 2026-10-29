# Implementation Plan: Streaming grounded answer with honest citations (capability 22, fork B5)

**Branch**: `024-streaming-grounded-citations` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/024-streaming-grounded-citations/spec.md`

## Summary

Add a new surface `POST /sse-cited-ask` that **streams a grounded answer** (capability 20's SSE framing)
**and cites it honestly** (capability 21's honesty floor). Retrieval happens **up-front** (sidestepping
the undocumented in-stream tool call); the grounded prose streams as `event: data` frames; and a single
terminal `event: citations` frame carries the usage-accurate citations (reported ∩ retrieved, labeled)
plus the ground-truth retrieved list. The core finding (research R1) is that a streamed turn has **no
structured output**, so the used-sources signal is the model's inline `[n]` markers, parsed server-side;
the markers are **stripped** from the prose (research R2, user decision) so the caller reads clean prose
and the terminal frame is the single authoritative citation source. Capabilities 8, 14, 20, 21 are
untouched baselines.

## Technical Context

**Language/Version**: Scala 3.3.8 on the Java-first Akka SDK; two forced-Java files (JDK 21).
**Primary Dependencies**: `akka-javasdk` 3.6.3 — `tokenStream(...).source(...)`,
`HttpResponses.serverSentEvents`, Akka Streams `Source` (`groupedWithin`, `statefulMap`, `concat`,
`lazySource`, `recover`, `initialTimeout`, `idleTimeout`); reused in-process RAG stack (langchain4j +
all-minilm ONNX) via `KnowledgeStore`. **No new dependency** (constitution I: all SDK/existing).
**Storage**: N/A — a response framing over an in-memory corpus; no component state, no persistence.
**Testing**: ScalaTest/JUnit via `TestKitSupport`; `SseRouteTester` for frame assertions;
`TestModelProvider` to script/fail the agent and to script marker-bearing answers.
**Target Platform**: Akka service, local dev + platform.
**Project Type**: Akka SDK service (single project; mixed Scala/Java).
**Performance Goals**: incremental delivery preserved — time-to-first-`data`-frame < full generation
time; no server-side buffering of the whole answer (spec FR-003).
**Constraints**: clean prose (markers never delivered, FR-004/FR-005); failure is always a terminal
`event: error` with no `citations` frame (FR-009); a marker split across a chunk boundary must still strip
cleanly (FR-004 edge case); capabilities 8/14/20/21 untouched & green (SC-005).
**Scale/Scope**: one new endpoint (Java), one new wire envelope (Java), one new Scala streaming agent, one
pure Scala stripper, one thin Scala prep/cite façade, reusing `KnowledgeStore` / `RetrievedSources` /
`DocsAgent.Request`/`Passage`/`DontKnow` / `AskQuestion` / `SseErrorReason`, plus tests.

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after Phase 1.*

| Principle | Status | Note |
|---|---|---|
| **I. Akka SDK First** | ✅ PASS | Uses first-class SDK streaming (`tokenStream`, `serverSentEvents`) and Akka Streams operators that are part of the SDK's own stream API. No new external dependency; reuses the existing RAG stack. |
| **II. Design Principles** | ✅ PASS | Domain logic is framework-free and reused/added in `docs/domain` (`CitationMarkers`, `RetrievedSources`); the endpoint owns its request/response + `GroundedSseEvent` wire types (API isolation); single responsibility (one new surface); descriptive names (`StreamingCitedDocsAgent`, `GroundedSseEvent`, not `Event`/`Handler`). |
| **III. Test Coverage** | ✅ PASS | Pure `CitationMarkers` (incl. boundary splits) and the floor/cite façade unit-tested; integration scenarios (happy / decline / fail@0 / fail@N) via `SseRouteTester` + `TestModelProvider`; cap-8/14/20/21 regression suites run unchanged. |
| **IV. Simplicity** | ✅ PASS | No reconnection/resume/id semantics (reuses cap-20's constant-id); no second model call (research R4); the stripper is a pure `step/finish` pair, not a custom GraphStage (R3). The only new abstraction is the 3-case wire envelope the format inherently requires. |

**Post-Phase-1 re-check**: still PASS. The design adds no abstraction beyond `GroundedSseEvent` and the
pure `CitationMarkers`; the two Java files are forced by the method-ref wall and the internal mapper, not
by choice (research R7). No complexity-tracking entry needed.

**Complexity Tracking**: *(empty — no violations to justify.)*

## Project Structure

### Documentation (this feature)

```text
specs/024-streaming-grounded-citations/
├── plan.md              # this file
├── spec.md              # feature spec
├── research.md          # Phase 0 — R1..R9 (resolved)
├── data-model.md        # Phase 1 — entities, stream lifecycle, strip state machine
├── quickstart.md        # Phase 1 — run + curl + the load-bearing concat/recover
├── contracts/
│   └── sse-cited-ask.md  # Phase 1 — POST /sse-cited-ask wire contract
├── checklists/
│   └── requirements.md   # spec quality checklist (all pass)
└── tasks.md             # Phase 2 — created by /akka.tasks, NOT here
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/docs/
├── application/StreamingCitedDocsAgent.scala   # NEW — StreamEffect grounded agent, asks for [n] markers
├── application/GroundedCitedPrep.scala         # NEW — Java-shaped façade: prepare(retrieve+Request) / cite(floor)
├── application/KnowledgeStore.scala            # REUSED, unchanged (retrieval)
├── application/DocsAgent.scala                 # REUSED, unchanged (Request/Passage/DontKnow)
├── application/CitingDocsAgent.scala           # REUSED, unchanged (cap-21 — untouched)
├── domain/CitationMarkers.scala                # NEW — pure incremental stripper: step/finish + boundary carry
├── domain/RetrievedSources.scala               # REUSED, unchanged (the honesty floor + opaque newtypes)
└── domain/AskQuestion.scala                    # REUSED, unchanged (validation)

src/main/scala/com/gwgs/akkaagentic/streaming/domain/
└── SseErrorReason.scala                        # REUSED, unchanged (client-safe reasonFor)

src/main/java/com/gwgs/akkaagentic/docs/api/
├── StreamingCitedDocsEndpoint.java             # NEW — POST /sse-cited-ask; forced-Java (tokenStream wall)
├── GroundedSseEvent.java                        # NEW — sealed: Data | Citations | ErrorEvent (wire envelope)
├── CitedDocsEndpoint.scala                      # REUSED, unchanged (cap-21 /cited-ask)
└── DocsEndpoint.scala                           # REUSED, unchanged (cap-8 /ask)

src/test/scala/com/gwgs/akkaagentic/docs/
├── domain/CitationMarkersTest.scala             # NEW — strip incl. split-across-boundary, no-marker, decline
├── application/GroundedCitedPrepTest.scala      # NEW — floor/decline/empty-report (reuse cap-21 cases)
└── api/StreamingCitedDocsEndpointIntegrationTest.scala  # NEW — happy / decline / fail@0 / fail@N via SseRouteTester
    # cap-8/14/20/21 tests under these trees run UNCHANGED (SC-005 regression)
```

**Structure Decision**: Single Akka project, existing `docs` package (this is a grounded surface). Additive
only: the new production files are the two Java files plus three Scala files; everything else is reused
unchanged. A `JavaQuarantineTest` (if present for this tree) is updated to expect exactly these two Java
files, so quarantine growth is a recorded finding, not silent drift.

## Why Java is required, and bounded to two files (research R7)

- **`StreamingCitedDocsEndpoint.java`** — `tokenStream(StreamingCitedDocsAgent::stream)` is a Java method
  reference (the §16 wall). The whole SSE pipeline lives here: validate → prepare (Scala façade) →
  tokenStream → timeouts → group → **strip** (`CitationMarkers`, threaded via `statefulMap`, numbers
  accumulated into an `AtomicReference`) → `concat` a lazy `citations` source → `recover` to `ErrorEvent`
  → `serverSentEvents`.
- **`GroundedSseEvent.java`** — JSON-serialized by the internal (non-Scala) mapper, so Java-shaped.
- Everything else — agent, stripper, prep/cite façade, floor, validation, error-reason, all tests — is
  Scala, reached via DI and static forwarders. The wall claims exactly these two surfaces.

## The pipeline, precisely (the one subtle part — research R3/R5)

```
Source<String> tokens = client.forAgent().inSession(randomUUID)
    .tokenStream(StreamingCitedDocsAgent::stream).source(request);   // request = prepared DocsAgent.Request

AtomicReference<List<Integer>> reported = new AtomicReference<>(List.of());

Source<GroundedSseEvent> data = tokens
    .initialTimeout(firstTokenTimeout).idleTimeout(idleTimeout)      // reuse cap-20 guards → TimeoutException
    .groupedWithin(GROUP_SIZE, GROUP_WINDOW).map(join)               // reuse cap-20 grouping
    .statefulMap(() -> CitationMarkers.empty(),                      // carry state (immutable)
        (carry, chunk) -> { var r = CitationMarkers.step(carry, chunk);
                            reported.updateAndGet(acc -> concat(acc, r.numbers()));
                            return Pair(r.carry(), r.emit()); },     // emit stripped text
        carry -> { var f = CitationMarkers.finish(carry);
                   reported.updateAndGet(acc -> concat(acc, f.numbers()));
                   return Optional.of(f.emit()); })                  // flush clean tail on completion
    .filter(s -> !s.isEmpty()).map(s -> new GroundedSseEvent.Data(s));

Source<GroundedSseEvent> citations = Source.lazySource(() ->        // read reported AFTER data completes
    Source.single(GroundedCitedPrep.citationsEvent(sources, reported.get(), declinedFlag)));

Source<GroundedSseEvent> events = data.concat(citations)            // failure in `data` ⇒ citations skipped
    .recover(matchAny(t -> new GroundedSseEvent.ErrorEvent(SseErrorReason.reasonFor(t))));

return HttpResponses.serverSentEvents(events, _ -> "", EVENT_TYPE); // EVENT_TYPE: data|citations|error
```

Two correctness points, both load-bearing:
1. **`concat` after a lazy source** reads the fully-accumulated `reported` numbers at the right moment and
   makes "failure ⇒ no citations frame" automatic (the second source is never subscribed if the first
   fails). This reuses cap-20's `.recover`-to-element move for the error path (research R5).
2. **The carry in `CitationMarkers`** is what makes a boundary-split marker strip cleanly; it is the one
   thing the unit test must hammer (research R3).

*Determining `declinedFlag`*: the decline sentinel is detected on the **stripped full answer**. Since the
prose is not buffered, track it incrementally (e.g. the finish step reports whether the accumulated clean
text, trimmed, starts with `DontKnow`), or re-use the same normalized check cap-21's endpoint uses on the
assembled clean text held only as a short prefix. Settled in Phase 2; the floor already makes a declined
answer cite nothing regardless, so this governs only the FR-007-vs-decline labeling, not safety.

## Descriptor note (research R8)

⚠️ `-proc:none` means **no auto-discovery**. Add **two** lines to
`src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`:
`com.gwgs.akkaagentic.docs.api.StreamingCitedDocsEndpoint` (Java endpoint — else the route 404s) and
`com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent` (Scala agent — else `tokenStream` lookup
fails). The data types need no entry. This is a `mvn clean verify`-class check.

## Setup record (T002) — measured before any code

Baseline `mvn test -Pquick` green (274 tests) on branch `024-streaming-grounded-citations` (T001).

**The two descriptor lines this capability adds** (research R8), to
`src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`:
- `com.gwgs.akkaagentic.docs.api.StreamingCitedDocsEndpoint` — Java endpoint (T011); omit → route 404s.
- `com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent` — Scala agent (T009); omit → `tokenStream` lookup fails.
The data types (`GroundedSseEvent`, `CitationMarkers`, `GroundedCitedPrep`) are not components — no entry.

**JavaQuarantine expectation (measured, resolving the "if present" hedge):** the `docs` tree
(`src/main/java/com/gwgs/akkaagentic/docs`) currently holds **zero** Java files — `DocsAgent`,
`CitingDocsAgent`, `DocsEndpoint`, `CitedDocsEndpoint` are all Scala. The existing `JavaQuarantineTest`s
cover only the `reminders` and `streaming` trees; there is **no** `docs` one. So cap-22 introduces the
**first two** Java files into the docs tree — `docs/api/StreamingCitedDocsEndpoint.java` and
`docs/api/GroundedSseEvent.java` — and T020 **creates** a new `docs` quarantine test asserting exactly
those two (modeled on `streaming/api/JavaQuarantineTest.scala`), rather than updating an existing one.

## Phase sequencing (for /akka.tasks)

Follows CLAUDE.md's incremental, gate-committed workflow — one component + its test per approved gate:

1. **Stripper** (`CitationMarkers.scala`, pure) + unit test: no-marker passthrough, single/multiple
   markers, **marker split across chunk boundary**, trailing `[` held then flushed, decline text.
2. **Prep/cite façade** (`GroundedCitedPrep.scala`) + unit test: `prepare` builds the `Request` and
   `RetrievedSources` from a stub corpus; `citationsEvent` applies the floor (reuse cap-21 cases:
   subset, out-of-range dropped, decline → empty, non-decline-empty-report → empty + retrieved shown).
3. **Streaming agent** (`StreamingCitedDocsAgent.scala`) — grounding + marker + `DontKnow` prompt;
   `MemoryProvider.none()`. (Behaviour proven via the endpoint test in step 4, as streamed agents are.)
4. **Endpoint** (`StreamingCitedDocsEndpoint.java` + `GroundedSseEvent.java`) + integration tests via
   `SseRouteTester` + `TestModelProvider`: happy (data… then one citations frame; clean prose; subset
   citations), decline (empty citations), fail@N (`event: error`, **no** citations frame), fail@0 (per
   research R9 watch-item — offline if materializable, else live-noted). Add the **two** descriptor lines.
5. **Baseline regression**: cap-8/14/20/21 tests unchanged & green; update `JavaQuarantineTest` if present.
6. **Live measurement + Docs** (own final commit): run the small in-corpus question set live on `qwen3:8b`
   over the new surface; record SC-006 (end-of-stream self-report vs cap-21's non-streamed path) in
   research.md and the new README §24; ROADMAP "Where we are" flip + B5 fork row; FINDINGS; memory.

## Risks / watch-items

- **The carry/strip is the real risk.** A partial marker leaking into prose, or a number double-counted
  across the boundary, are the failure modes — unit-test boundary splits exhaustively (step 1).
- **`statefulMap` completion hook semantics** (flushing the carry tail as a final element) must actually
  fire on normal completion and must **not** fire on failure (else a citations frame could follow a
  failing stream). Verify the SDK's `statefulMap(create, f, onComplete)` behaves so; if `onComplete` fires
  on failure too, move the tail flush into the stage's normal path and rely on `concat`/`recover` ordering.
- **`lazySource` + `AtomicReference` timing**: confirm `concat` subscribes the citations source strictly
  after `data` completes (it does by contract) so `reported.get()` is complete; add an assertion in the
  integration test that the citations frame's `usedSources` matches the scripted markers.
- **Pre-first-token failure offline** — may be live-only (cap-20 precedent); if so, record it as a measured
  test-harness limit and verify happy + fail@N offline (research R9).
- **TestModelProvider marker scripting**: the scripted streamed answer must actually contain `[n]` tokens
  so the stripper and floor exercise real input; a decline script must emit exactly `DontKnow` with no
  markers.
