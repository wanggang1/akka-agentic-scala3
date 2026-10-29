# Quickstart: Streaming grounded answer with honest citations (capability 22, fork B5)

## What this is

`POST /sse-cited-ask` — stream a grounded answer as Server-Sent Events, then receive a terminal
`event: citations` frame that honestly cites only the sources the answer used (reported ∩ retrieved),
with the full retrieved list alongside. Clean prose; markers are a private signal, stripped before
delivery.

## Run it

```bash
# terminal 1 — local model (same as the rest of the project)
ollama serve                 # qwen3:8b pulled

# terminal 2 — the service (this repo has no main of its own; exec:java launches the Akka entry point)
mvn compile exec:java         # uses local Ollama (qwen3:8b)
```

```bash
# a grounded question (answer streams, then one citations frame)
curl -N -X POST localhost:9000/sse-cited-ask \
  -H 'content-type: application/json' \
  -d '{"question":"what makes agent work survive a restart?"}'

# an out-of-corpus question (streams the decline, cites nothing)
curl -N -X POST localhost:9000/sse-cited-ask \
  -H 'content-type: application/json' \
  -d '{"question":"what is the capital of France?"}'
```

`-N` disables curl buffering so you see frames arrive incrementally. Expect `event: data` frames of clean
prose (no `[n]` in the text), then a single `event: citations` frame.

## The one load-bearing mechanism

A streamed turn has **no structured output** (research R1), so the "which sources did you use" signal is
the model's inline `[n]` markers, parsed server-side and **stripped** from the prose. The citations are
computed from the whole turn's markers and emitted *after* the prose, without buffering the answer, by:

```
data.concatLazy( lazySource( () -> Source.single( citationsFrame(reported.get()) ) ) )
    .recover( t -> errorFrame(reasonFor(t)) )
```

- `concatLazy` (NOT plain `concat`, which eagerly materialises its second source at stream start — measured
  at T012, research R5) subscribes the citations source only after `data` completes, so the accumulated
  `reported` numbers are final by then.
- If `data` **fails**, `concatLazy` never reaches the citations source, and `.recover` turns the failure into
  a terminal `event: error` — so a failed stream carries **no** citations frame (the honest signal cap-20
  established, reused). This `.recover`-to-element is required: the SDK's `serverSentEvents` otherwise
  silently empties a failing stream (`akka-sse-helper-swallows-failure`).

## The one correctness test to get right

The stripper's boundary case. Feed `CitationMarkers` a full answer split at **every** position and assert
the stripped text and extracted numbers are identical regardless of the split — in particular a marker cut
as `"...restart [1"` + `"2] because…"` must reassemble to number `12` and leave no `[1` in the prose.

## Build / test

```bash
mvn test -Dtest='CitationMarkersTest,GroundedCitedPrepTest'        # pure units, fast
mvn verify -Dit.test='StreamingCitedDocsEndpointIntegrationTest' -Dtest='!*' -DfailIfNoTests=false  # one capability
mvn clean verify                                                    # the gate (descriptor + cross-capability)
```

`mvn clean verify` is the gate here specifically because this capability edits the **component
descriptor** (two new lines, research R8) — a `-proc:none` build does no auto-discovery, so a missing line
404s the route or breaks the `tokenStream` lookup, and that only surfaces from a clean run.

## Live measurement (capability deliverable, SC-006)

Run the small in-corpus question set live on `qwen3:8b` over `/sse-cited-ask` and compare the
end-of-stream `usedSources` with capability 21's `/cited-ask` on the same questions. Record in
`research.md` and README §24 whether the streamed self-report is consistent with the non-streamed path —
stated as evidence, including "not equivalent" if that is the finding.
