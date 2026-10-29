# Research: Streaming grounded answer with honest citations (capability 22, fork B5)

Phase 0 of `/akka.plan`. Each item is a decision with its rationale and the alternatives weighed. The
first is the load-bearing one; the rest follow from it.

## R1 — A streamed turn has no structured output; the used-sources signal must ride in the text *(the core finding)*

**Decision**: Obtain the model's used-sources from the inline `[n]` markers it writes in its answer,
parsed server-side from the accumulated streamed text. Do **not** attempt a structured `usedSources`
field over the stream.

**Why**: Capability 21 (`CitingDocsAgent`) gets its used-sources from `responseConformsTo(CitedAnswer)` —
a single structured object. The streaming builder is a different API: `streamEffects()` offers only
`systemMessage` / `userMessage` / `memory` / `error` / `thenReply`, and the consumer side,
`tokenStream(Agent::method).source(x)`, returns `Source<String, NotUsed>` — "the tokens are always text
strings" (akka-context/sdk/agents/streaming.html.md:71). There is **no** slot for a structured value
delivered alongside or after the token stream on the HTTP surface. So capability 21's mechanism is
unavailable in principle, not merely unused. The only signal of *which source was used* that can travel
inside streamed text is the inline `[n]` marker — which capability 21's R6 finding already established the
model emits **reliably** on `qwen3:8b` (it was the structured *mirroring* of that number that was
unreliable, not the number itself).

**Alternatives considered**:
- *Structured output over the stream* — not supported (above).
- *A second, non-streamed structured call* (B4's `CitingDocsAgent`) run alongside the streamed prose call
  to get `usedSources` — **rejected on honesty grounds (R4)**: two independent model turns can diverge, so
  the citations would describe a *different* answer than the one actually streamed. Also doubles model
  latency/cost.
- *`publishTokenStream`'s `finalAnswer`* (streaming.html.md:156) returns the full concatenated text, but
  it is the Workflow→notification bridge, not the HTTP token-source path, and it still yields only text —
  it does not add a structured field. It confirms, though, that the full answer text is recoverable at
  stream end, which R2 relies on.

## R2 — Clean prose by stripping the markers; the terminal `citations` frame is the single source of truth *(user decision, 2026-10-29)*

**Decision**: The model writes `[n]` markers; the server reads them as a **private** signal and **strips
them from the prose** before forwarding each `data` frame, so the caller reads clean prose and sees
citations only in a terminal `event: citations` frame. The frame alone applies the honesty floor, maps
numbers to labels, and carries the retrieved list.

**Why**: The inline marker is the model's *raw, pre-floor* claim — a bare number with no label and no
verification. The frame is the *verified, post-floor, labeled, machine-readable* result. If the raw
markers were left visible in the prose, an out-of-range or non-retrieved marker (`[5]` when 3 were
retrieved) would appear in the text while the floored frame correctly omitted it — the prose would
silently contradict the citations. Stripping removes that contradiction and makes the frame authoritative.
This is the user's explicit choice (discussed 2026-10-29) of clean-prose-plus-frame over visible markers.

**Alternatives considered**:
- *Visible markers + frame* — simplest and most robust (no boundary buffering), but the prose shows raw
  unverified `[n]` that the frame can contradict (above). Rejected for the honesty wrinkle.
- *Strip only the markers that fail the floor* — still needs the full stripping machinery plus a
  consistency pass; no simpler than full strip, and leaves some markers visible. Rejected.

## R3 — The stripper is a pure Scala function; the boundary-split marker is its one hard case

**Decision**: Implement marker stripping as a pure, framework-free Scala function in `docs/domain`
(`CitationMarkers`), driven incrementally from the Java stream pipeline. It threads a small **carry**
string across chunks: `step(carry, chunk) -> (emit, carry', numbers)` and `finish(carry) -> (emit,
numbers)`. A `[` near a chunk's end that could begin a marker spanning into the next chunk is held back in
`carry` rather than emitted, so a marker split as `"...restart [1"` + `"2] because…"` is reassembled and
stripped cleanly, never leaking a partial `[1` to the caller (spec FR-004/edge case).

**Why**: Streamed text arrives in groups (`groupedWithin`, cap-20's 20-token/100 ms grouping, reused), so
a regex over each group independently would miss a marker straddling a group boundary. A carry buffer is
the minimal correct mechanism. Keeping it pure (no `var`, immutable state value threaded through —
`scala-idioms-no-mutable-state`) makes the boundary cases **offline-unit-testable** by feeding crafted
chunk sequences, which is where the real risk lives.

**Alternatives considered**:
- *Buffer the whole answer, then strip once* — trivial and exact, but buffers the entire answer
  server-side before sending, violating incremental delivery (spec FR-003, cap-20 FR-008). Rejected.
- *A custom `GraphStage`* — more power than needed; a pure `step/finish` driven by `statefulMap` is
  simpler and testable without a materializer (constitution IV). 

## R4 — One streamed turn, not two calls; the terminal frame is built from the same turn's markers

**Decision**: A single streaming agent call produces the answer. The reported numbers accumulated from
*that* turn's markers feed the terminal frame. No second model call.

**Why**: B5's whole premise is to cite **the answer that was streamed**. A separate structured call would
be a different turn with its own (possibly different) answer and sources — the citations could then
describe text the caller never saw. One turn keeps the citation honest about the delivered prose.

## R5 — Emitting a terminal frame computed from the whole stream, without buffering the answer

**Decision**: Build the SSE source as `dataFrames.concatLazy(citationsSource).recover(errorElement)`, where
`dataFrames` emits stripped `Data` frames incrementally (flushing the carry on completion via
`statefulMap`'s completion hook) and writes the extracted numbers into an `AtomicReference[StripTotals]`
held in the endpoint; `citationsSource` is a `lazySource` that reads that reference.

**CORRECTION (measured at T012, implementation).** The original decision wrote `concat`, on the stated
assumption that "`concat` only starts the second source after the first completes." That is **wrong**, and
the happy-path IT proved it: plain `concat` **eagerly materialises** its second source at stream start, so
`lazySource`'s factory ran before any token flowed and the citations frame read the `AtomicReference` while
it still held its initial empty value — the stream produced correct, marker-stripped prose but an empty
`usedSources`. The fix is **`concatLazy`**, which is precisely the operator that defers materialising the
second source until the first completes (its existence as a distinct method is the tell that `concat` is
eager). `lazySource` is still needed to defer the `reported.get()` read (a `Source.single(...)` argument is
evaluated eagerly at construction); `concatLazy` + `lazySource` together read the accumulated numbers at the
right moment. The failure-skips-citations property (below) holds for `concatLazy` as well.

**Why**: The citations frame depends on the *whole* turn's markers, but we must not buffer the prose. A
lazily-evaluated concatenated source reads the fully-accumulated numbers at exactly the right moment.
Crucially, if the token stream **fails**, `concat` propagates the failure and never subscribes to
`citationsSource`, so **no citations frame is emitted for an answer that did not complete** (spec FR-009);
the downstream `.recover` then turns the failure into the terminal `event: error` element — exactly
cap-20's load-bearing move (`akka-sse-helper-swallows-failure`), reused unchanged. The `AtomicReference`
holds an **immutable** value and is written by a single linear stream, so it respects the project's
no-mutable-state rule; it is stream plumbing in the (forced-Java) endpoint, not domain state.

**Alternatives considered**:
- *`Source.fold` to a final value* — buffers the whole answer (R3 rejection). 
- *Thread numbers out of `statefulMap` as a final emitted element, then map it to the frame* — possible,
  but the element would interleave with the Data type awkwardly; the `concat` + reference reads cleaner and
  keeps the failure-skips-citations property automatic.

## R6 — The streaming grounded agent is a new Scala component; grounding/marker prompt adapted from cap-8/21

**Decision**: A new `StreamingCitedDocsAgent` (`@Component(id = "streaming-cited-docs-agent")`) in
`docs/application`, returning `Agent.StreamEffect`, taking the reused `DocsAgent.Request` (question +
`List[Passage]`). Its system message instructs grounding-only answering, the `DontKnow` decline sentinel,
and — unlike capability 21's **merged** contract, which *suppressed* inline markers — it **asks for**
inline `[n]` markers, because here they are the only signal (R1). `MemoryProvider.none()` (each question
independent, spec FR-013).

**Why**: An `Agent` has one command handler and the existing agents return other shapes, so a streamed
grounded answer is a separate component — leaving capabilities 8/14/20/21 untouched (spec FR-001). Authoring
a `StreamEffect` carries no method-ref wall (specs/016 Q-A; `StreamingChatAgent`), so the agent is Scala.

**Note / interop**: capability 21's follow-up deliberately kept markers *out* of the prose. B5 re-admits
them **into the model output** but strips them before delivery, so the caller still never sees them. The
two are consistent: markers are suppressed from what the *caller* reads in both; B5 just needs them in the
*raw* output as its signal.

## R7 — What is forced to Java, and bounded (the §16 wall)

**Decision**: Exactly two Java files, as cap-20:
- `StreamingCitedDocsEndpoint.java` — consuming `tokenStream(StreamingCitedDocsAgent::stream)` is only
  expressible as a Java method reference (README §16; specs/016 Q-B). The whole SSE pipeline (strip →
  concat citations → recover → `serverSentEvents`) lives in this one class.
- `GroundedSseEvent.java` — the wire envelope is JSON-serialized by the SDK's internal (non-Scala) mapper,
  so it is Java-shaped (`scala-jackson-module-followup`). A 3-case sealed interface: `Data(text)` |
  `Citations(usedSources, retrievedSources)` | `ErrorEvent(reason)`.

Everything else is Scala and reached from the endpoint via static forwarders / DI, as cap-20 reaches
`StreamQuestion` and `SseErrorReason`:
- `CitationMarkers` (pure stripper, R3) — `docs/domain`.
- Retrieval + request/sources preparation and the floor computation — Scala. Because `RetrievedSources`
  uses opaque newtypes that Java cannot drive, and `KnowledgeStore.retrieve` returns a Scala `List`, a
  thin Scala façade `GroundedCitedPrep` exposes **Java-shaped** helpers: `prepare(store, question)` →
  `Prepared(request: DocsAgent.Request, retrievedLabels: java.util.List[String], sources)` and
  `cite(sources, reportedNumbers: java.util.List[Integer], declined)` → `java.util.List[String]`. The
  floor logic itself stays in `RetrievedSources` (reused unchanged).
- `AskQuestion.validate` (reused) and `SseErrorReason.reasonFor` (reused) — `docs/domain` /
  `streaming/domain`.

**Why**: This is capability 11/20's shape — the wall claims only the endpoint and the wire type and
travels no further. Keeping the floor and the stripper in Scala keeps them provable in isolation
(constitution II/III) and keeps `RetrievedSources`' opaque-newtype guarantee intact.

## R8 — Descriptor registration (the `-proc:none` consequence)

**Decision**: Register the new Java endpoint `com.gwgs.akkaagentic.docs.api.StreamingCitedDocsEndpoint`
and the new Scala agent `com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent` by hand in
`src/main/resources/META-INF/akka-javasdk-components_…conf`. The data types (`GroundedSseEvent`,
`CitationMarkers`, `GroundedCitedPrep`, `Prepared`) are not components and need no entry.

**Why**: The mixed build runs the javac annotation processor OFF (`-proc:none`,
`akka-mixed-java-scala-descriptor-proc-none`), so there is no auto-discovery for anything; every component
is hand-listed (cap-20 T002/T008b). Omitting the endpoint 404s the route at runtime; omitting the agent
fails the `tokenStream` lookup. This is a `mvn clean verify`-class check (CLAUDE.md descriptor row).

## R9 — What can be proven offline, and what is live-only

**Decision**:
- **Offline-deterministic** (`TestModelProvider`): the marker stripper incl. boundary splits
  (`CitationMarkers` unit test), the floor/decline/empty-report logic (`RetrievedSources`/`GroundedCitedPrep`
  unit test, reusing cap-21's cases), frame **ordering** and **content** (scripted token stream →
  `data`…`data` then one `citations` frame), the decline path (scripted `DontKnow` → empty citations), and
  the failure framing (`whenMessage(...).failWith` → `event: error`, **no** citations frame) — the last via
  the `SseRouteTester`, as cap-20.
- **Live-only** (Ollama `qwen3:8b`): SC-006 — whether the model's *end-of-stream* `[n]` self-report is
  consistent with capability 21's non-streamed path on the same question set. This mirrors how cap-21
  measured faithfulness; the offline tests use scripted markers and cannot measure the real model's
  reporting.

**Why**: Correctness is deterministic and must not depend on a live model; faithfulness is a property of
the model and can only be measured live (same split as cap-21, `akka-testing-slow-and-failing-calls`).

**Watch-item** (carried to the plan): whether a *pre-first-token* failure can be materialized offline on a
streamed source (cap-20 recorded this as possibly live-only — specs/022 risks). If it cannot, the happy
and fail-after-N paths are proven offline and the pre-first-token failure is noted as live-verified,
consistent with cap-20's resolution.

**RESOLVED (measured at T015/T016, implementation).**
- *A model failure is not directly materializable offline on a streamed agent.* Confirmed cap-16's probe:
  `whenMessage(...).failWith` on a streamed agent **hangs** (no failure surfaces) rather than failing the
  token stream. So neither "fail-after-N-tokens" nor a mid-stream model error can be scripted with
  `TestModelProvider`.
- *But the framing IS proven offline, via the endpoint's `initialTimeout` guard.* A hanging model with a
  short `streaming.first-token-timeout` (3s in the test) raises a `TimeoutException` **into** the stream →
  `.recover` → a terminal `event: error` with the client-safe reason, and **no** `citations` frame. This
  exercises the real failure path end-to-end over HTTP (FR-009/FR-010). The failure *kind* is a timeout
  rather than a model error, but the pipeline behaviour asserted (terminal error, no citations, no leaked
  internals) is identical.
- *The two load-bearing operator properties were measured directly* on the real operators (a probe running
  `CitationMarkers` → `concatLazy` → `.recover` over an upstream that holds a partial-marker carry then
  fails): (1) `statefulMap`'s completion hook does **NOT** flush the held carry on a **failure** — no
  partial-marker leak, so the plan's "move the tail flush" contingency is **not needed**; (2) `concatLazy`
  does **NOT** subscribe the citations source when the data source failed — no citations frame. A caveat
  recorded honestly: a synthetic `single.concat(failed)` fails synchronously with no network flush, so the
  already-processed clean prefix is dropped in-memory in that probe; over real SSE the earlier `data` frames
  are network-flushed before a later failure (the HTTP-level happy/decline tests exercise that delivery),
  and whether a real `qwen3:8b` mid-answer failure delivers its partial answer-then-error is the one thing
  left to the live run (T017).
