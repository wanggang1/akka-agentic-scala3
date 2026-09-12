# Streaming vs request/response: when to stream an agent, and what it costs

**Written**: 2026-09-12 · **Context**: capability 14 (`specs/016-streaming-responses`) · **SDK**: 3.6.3

Capability 4 answers a question in one piece (`POST /chat/{sessionId}`). Capability 14 answers the
same kind of question as it is generated (`POST /stream-chat/{sessionId}`). Both are supported, and
picking between them is not a matter of taste — each buys something the other cannot.

Everything below was measured on this project, not inferred from documentation.

## What "streaming" actually is here

`HttpResponses.streamText(source)` does **not** run the stream. Its bytecode builds:

```text
HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, source.map(...))  ->  HttpEntity$Chunked
```

A chunked entity has no known length, so the response carries no `Content-Length` and the server
frames it as HTTP/1.1 chunked. The handler returns that value **immediately**; Akka HTTP then *pulls*
fragments from the source while writing the body, at the rate the client reads. The reply is never
assembled into a complete string on the server.

Measured on the wire for a two-sentence scripted answer:

```text
status               = 200 OK
entity.isChunked     = true
contentType          = text/plain; charset=UTF-8
contentLengthOption  = OptionalLong.empty

chunk[0]  60 bytes  "The runtime persists the task and the agent's process state "
chunk[1]  45 bytes  "as the loop runs. That is why work survives a"
chunk[2]  50 bytes  " restart without any persistence code of your own."
```

Two things that example teaches:

- **Chunk boundaries are meaningless.** `"survives a"` / `" restart"` splits mid-phrase. Only the
  concatenation has meaning, which is why *parity* — fragments in order reassembling to exactly the
  answer — is the invariant worth testing.
- **Grouping shifts shape between offline and live.** 57 model tokens became 3 chunks because
  `groupedWithin(20, 100ms)` filled on **count**: all 57 arrived inside one 100 ms window. Against a
  real model producing tokens over seconds, the **window** dominates instead, and the same code yields
  many more, smaller chunks.

## What streaming buys

| | request/response | streamed |
|---|---|---|
| First visible text | after the whole answer is generated | after the first tokens |
| Server memory per request | holds the complete reply | one fragment at a time |
| Slow reader | server buffers the whole reply | backpressure slows the pull |
| "Is it working or stuck?" | indistinguishable until it returns | visible progress |

The first row is the reason to do it. Total time is unchanged; the wait becomes legible instead of
blank. On a local model answering in a few sentences that is the difference between several seconds of
nothing and text appearing at once.

## What streaming costs — the part usually left out

1. **No fallback value exists.** `Agent.StreamEffect.Builder` has `error(...)`, decided *before* any
   token, and **no `onFailure`**. Capabilities 8, 12 and 13 all lean on `.onFailure(_ => sentinel)`;
   a stream cannot, because no value can replace text the caller has already read. This is the first
   capability in the project that cannot use that technique.
2. **No error status after the first byte, and worse than "truncated".** The `200` and headers are on
   the wire before any token exists, so a later failure cannot change the status. What a caller
   actually observes splits in two, both measured:

   | Failure | Caller observes |
   |---|---|
   | **before** the first fragment | `200`, `Transfer-Encoding: chunked`, **zero bytes**, and — measured live — `curl_exit=0` with the connection left intact. The runtime logs "Aborting connection"; it still renders as a normal terminating zero-length chunk, so there is **no client-side signal at all** |
   | **after** fragments were sent | the body **aborts**: truncated, and visibly so |

   The first row is the uncomfortable one: a guard makes the request *end* (1041 ms, versus silent
   past 240 s without it) but not *legible*. A client must treat an empty body as failure. The only
   real fix is a framing with room for an error after the body starts — server-sent events, with an
   explicit `event: error` — which is a wire-format decision, not a code change.
3. **Guards are for the hang, and the first measurement of that was misleading.** Under a *scripted*
   failure the token stream produces nothing — no tokens, no completion, no failure — still silent
   after **240 seconds**. But against a **real** provider error the runtime fails the stage in
   **~178 ms** (`AgentSource.publishErrorAndFailStage`), so that silence is a **test-provider
   artifact**. `initialTimeout`/`idleTimeout` still earn their place, for the case the runtime does
   *not* cover: a model that never answers **and** never errors. Keep them; just do not believe the
   scarier version of the story.
4. **Reassembly becomes the client's job**, and a buffering client sees results byte-identical to the
   non-streaming surface — so "we stream" is unobservable to it. (`curl` needs `--no-buffer`.)
5. **Nothing can be appended after the text.** Citations computed from retrieval cannot honestly
   follow an answer that has already streamed, which is exactly why a streaming *grounded* answer was
   forked out of capability 14's scope rather than attempted.
6. **Consuming a token stream is Java-only on this SDK** (see README §16): `tokenStream(Agent::method)`
   needs a Java method reference, and the project's `dynamicCall` escape hatch has no streaming
   counterpart. Streaming costs exactly one Java class in an otherwise-Scala capability.

## When *not* to stream

**Payload size is not a reason.** The agent's stream is text by type:
`tokenStream(...)` yields `ComponentStreamMethodRef<String>`, and `streamText` takes
`Source<String, ?>`. There is no binary agent stream, and a `StreamEffect` reply is a `String` — an
LLM does not emit a PDF or an image as a token stream, and image generation is not this effect API.

For large binary payloads the SDK points elsewhere, and notably none of it is streamed end to end:

| Need | Tool | Shape |
|---|---|---|
| Small/medium bytes | `HttpResponses.of(status, contentType, byte[])` | fully buffered |
| Packaged file | `HttpResponses.staticResource(...)` | server-managed |
| Stored object | `ObjectStorage.get` / `put` | **whole-object** `ByteString`; only *listings* stream (`listObjectsStream`) |

So the rule of thumb: **stream when output is produced incrementally and time-to-first-byte matters.**
That means text from a model. A 5 MB PDF you already have is a file response, not an agent stream. The
one case where size and streaming coincide is an agent producing very long *text*, and even then the
reason is latency, not byte count.

## How far does backpressure actually reach?

Honestly: **further than the endpoint, and not verifiably to the model.**

- The runtime's streaming path (`kalix.runtime.agent.Agent`'s streaming source, `AgentSource`) contains
  **no buffering construct** — no `OverflowStrategy`, no `BoundedSourceQueue`, no `.buffer(`, no queue.
  It composes demand-driven sources (`futureSource`, `single`, `failed`), so demand does appear to
  propagate upstream rather than stopping at a buffer.
- That is evidence, not proof. A buffer could live in the per-provider adapter, and even with demand
  reaching the provider's socket read, whether the **model** pauses generation is the model server's
  behaviour, not Akka's.

**Do not design as though a slow reader throttles the LLM.** A hosted provider generally keeps
generating (and billing) once a request is in flight; a local model bound to its HTTP write may stall.
The decisive experiment — total generation wall-clock with a slow reader versus a fast one — is listed
as live-only work in `specs/016-streaming-responses/research.md`.

## Choosing, in one line each

- **Request/response** — when the caller needs a status code it can act on, a fallback when the model
  misbehaves, or anything appended after the answer (citations, verdicts, a typed result).
- **Streaming** — when a person is waiting for text and the wait is long enough to feel.
