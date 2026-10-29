# Data Model: Streaming grounded answer with honest citations (capability 22, fork B5)

Phase 1. The types this capability adds or reuses, the SSE stream lifecycle, and the stripper state
machine. No persistence — every type is either a wire envelope, a transient request/reply, or a pure
in-memory value.

## Wire envelope — `GroundedSseEvent` (Java, `docs/api`)

JSON-serialized by the SDK's internal mapper (Java-shaped, research R7). A sealed interface with three
record cases; the SSE `event:` label is derived from the case in the endpoint, not carried on the record
(cap-20's shape).

| Case | Fields | SSE `event:` | Meaning |
|---|---|---|---|
| `Data` | `String text` | `data` | A fragment of the **clean** (marker-stripped) grounded answer. |
| `Citations` | `List<String> usedSources`, `List<String> retrievedSources` | `citations` | The single terminal honest-citation frame: `usedSources` = reported ∩ retrieved, resolved to labels, de-duplicated (empty on decline / unverifiable report); `retrievedSources` = ground-truth retrieved set. |
| `ErrorEvent` | `String reason` | `error` | A client-safe failure reason (`SseErrorReason.reasonFor`); terminates the stream. No `Citations` frame accompanies it. |

**Invariants**: a successful stream is `Data*` then exactly one `Citations`; a failed stream is `Data*`
then exactly one `ErrorEvent`; the two terminals are mutually exclusive (spec FR-005/FR-009).

## Request / reply types

- **`StreamingCitedDocsEndpoint.ChatRequest`** (Java record, endpoint-owned): `{ String question }`,
  `@JsonIgnoreProperties(ignoreUnknown=true)`; absent/null `question` → rejected by `AskQuestion.validate`
  (400), never a 500 (feature-003 Option boundary). Path carries no session (each question independent).
- **`DocsAgent.Request`** (reused, Java-shaped): `{ String question, List<Passage> passages }`; `Passage`
  = `{ String source, String text }`. Built up-front from retrieval, handed to the streaming agent.

## Reused domain values (unchanged)

- **`KnowledgeStore.Retrieved`**: `{ String source, String text, double score }` — one retrieved passage.
  `retrieve(question, k): List[Retrieved]`.
- **`RetrievedSources`** (opaque newtypes `SourceIndex`/`Label`): the honesty floor. `of(labels)` builds
  it in presented order (no dedup); `cite(reportedIndexes, declined): List[Label]` returns reported ∩
  retrieved in retrieval order, de-duplicated, empty on decline. **This is the structural floor — a
  fabricated/out-of-range number resolves to nothing.** Reused verbatim from capability 21.
- **`AskQuestion.validate(Option[String]): Either[String, AskQuestion]`** — blank/absent → `Left`.
- **`DocsAgent.DontKnow`** = `"I don't know"` — the decline sentinel.
- **`SseErrorReason.reasonFor(Throwable): String`** — client-safe reason mapping (reused from cap-20).

## New pure value — `CitationMarkers` (Scala, `docs/domain`)

The incremental stripper. Framework-free, immutable, driven from the stream pipeline (research R3).

- **State**: an opaque carry value (conceptually the pending text tail that might begin a `[n]` marker).
  `empty: State`.
- **`step(state, chunk): StepResult`** → `{ String emit, State carry, List[Int] numbers }`. Appends
  `chunk` to the carry, extracts every **complete** `[n]` marker (recording its number in `numbers` and
  removing it from the text), emits the safe prefix as `emit`, and retains in `carry` any trailing text
  from the last unmatched `[` onward (which might complete into a marker in the next chunk).
- **`finish(state): FinishResult`** → `{ String emit, List[Int] numbers }`. Flushes the carry: any
  remaining complete marker is extracted; a dangling `[` that never completed is emitted as literal text
  (it was not a marker).

**Marker grammar**: `[` + one or more ASCII digits + `]`. Non-numeric brackets (e.g. `[note]`) are left as
literal text — only numeric markers are the citation signal. (Settled here so the stripper and the agent
prompt agree.)

**Boundary property (the one that matters)**: for any way a full answer string is split into chunks, the
concatenation of all `emit`s (steps then finish) equals the answer with every numeric `[n]` removed, and
the concatenation of all `numbers` equals the markers' numbers in order. This is the offline test oracle.

## New Scala façade — `GroundedCitedPrep` (`docs/application`)

Java-callable bridge so the forced-Java endpoint never touches Scala `List` or the opaque newtypes
(research R7).

- **`prepare(store: KnowledgeStore, question: String, topK: Int): Prepared`** — retrieves, builds the
  `DocsAgent.Request` and the `RetrievedSources`, and the Java `List[String]` of retrieved labels (distinct,
  for the frame). `Prepared` is a Java-shaped carrier: `{ DocsAgent.Request request, RetrievedSources
  sources, java.util.List[String] retrievedLabels }`.
- **`citationsEvent(sources: RetrievedSources, reportedNumbers: java.util.List[Integer], declined:
  Boolean, retrievedLabels: java.util.List[String]): GroundedSseEvent.Citations`** — applies the floor via
  `sources.cite(...)` and packages the terminal frame. Keeps the opaque-newtype conversions on the Scala
  side.

## New Scala component — `StreamingCitedDocsAgent` (`docs/application`)

- `@Component(id = "streaming-cited-docs-agent")`, `extends Agent`.
- `def stream(request: DocsAgent.Request): Agent.StreamEffect` — `streamEffects().systemMessage(…)
  .memory(MemoryProvider.none()).userMessage(numberedSources(request)).thenReply()`.
- **System message** (adapted from cap-8/21): answer **only** from the numbered sources; write inline
  `[n]` markers naming the sources used (here the markers are *wanted* — they are the only signal, R1/R6);
  if the sources do not answer, reply EXACTLY `DontKnow`. No structured-output instruction (there is no
  structured channel on a stream).

## Stream lifecycle (happy path)

```
retrieve up-front → Prepared(request, sources, retrievedLabels)
   → tokenStream(agent).source(request)              # Source<String>, clean of structure, has [n] in text
   → timeouts → groupedWithin → statefulMap(strip)    # emit Data(cleanChunk); accumulate numbers
   → concat( lazySource( Citations(cite(sources, numbers, declined), retrievedLabels) ) )
   → recover(ErrorEvent)
   → serverSentEvents(events, id="", type=data|citations|error)
```

Failure at any point before the token source completes ⇒ `concat` skips the citations source ⇒ `recover`
emits one `ErrorEvent` ⇒ terminal `event: error`, **no** `citations` frame (spec FR-009).
