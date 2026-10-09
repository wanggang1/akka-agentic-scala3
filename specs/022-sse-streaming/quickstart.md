# Quickstart: SSE Streaming Agent Surface (capability 20)

**Feature**: `022-sse-streaming` · **SDK**: 3.6.3

## What this adds

A second streaming chat surface that uses **Server-Sent Events** so a failure is a self-describing
`event: error` frame instead of cap-14's silent empty `200`. Capability 14's `/stream-chat` is kept as
the raw-chunked baseline.

## Run it

```bash
mvn compile                 # fast check while building
mvn test -Dtest='SseChat*'  # the new tests, once they exist
```

Local service + live call (uses local Ollama by default — no API key, no network):

```bash
# 1. Start Ollama and pull the default model (once). Ollama serves on :11434.
#    macOS: `brew install ollama` then `ollama serve` (or launch the Ollama app).
ollama serve &                 # if not already running as a service
ollama pull qwen3:8b           # the model application.conf defaults to (OLLAMA_MODEL overrides)

# verify Ollama is up
curl -s http://localhost:11434/api/tags >/dev/null && echo "ollama up"

# 2. Start the service (port 9000); it talks to local Ollama out of the box.
mvn compile exec:java

# 3. Happy path — watch frames arrive incrementally.
curl --no-buffer -N -X POST http://localhost:9000/sse-chat/demo-1 \
  -H 'Content-Type: application/json' \
  -d '{"message":"Explain durable execution in two sentences."}'
```

To use Gemini instead of Ollama: `GOOGLE_AI_GEMINI_API_KEY=… MODEL_PROVIDER=googleai-gemini mvn compile exec:java`.

Expect `event: data` frames whose `data: {"text":...}` payloads concatenate to the answer, then a clean
end-of-stream.

## The one thing that makes this work

`HttpResponses.serverSentEvents` **silently empties the stream if the source fails** (its own code says
"no natural way to convey stream errors to client with SSE"). So the token source must turn its own
failure into a *final element* before handing it over:

```
tokens (Source<String,?> from tokenStream(StreamingChatAgent::stream))
  → initialTimeout / idleTimeout            // guards; they throw INTO the stream
  → groupedWithin(...).map(join)            // fragments
  → map(Data::new)                          // -> SseChatEvent.Data
  → recover { case t => ErrorEvent(reasonFor(t)) }   // <-- failure becomes a FINAL element
  → serverSentEvents(source, idFn, typeFn)  // typeFn: Data->"data", ErrorEvent->"error"
```

Without that `recover`, switching to SSE buys nothing — the silent-failure defect survives the format
change. With it, every failure path ends in exactly one `event: error` frame.

## Verify the payoff

```bash
# drive a pre-first-token failure (in tests via TestModelProvider .failWith);
# against the SSE surface you get:
event: error
data: {"reason":"..."}

# against cap-14's /stream-chat you get: 200 OK, chunked, zero bytes, curl exit 0 (no signal).
```

## Interop one-liner (for the write-up)

The SDK's SSE helper gives you typed frames (`extractEventType`) but **cannot frame a failure it
receives as a Throwable** — you must convert failure to an element upstream. The surface is still
**one Java class** (the `tokenStream(Agent::method)` wall); agent, domain rule, and this endpoint's
test stay Scala. The event envelope is a **Java record** (internal Jackson mapper), so `data` frames
carry JSON, not raw text.

## Gate before "done"

```bash
mvn clean verify     # the gate (~4-5 min); cap-14's tests MUST still pass (US3 / SC-004)
```
