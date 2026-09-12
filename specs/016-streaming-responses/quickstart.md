# Quickstart: Streaming agent responses (cap-14)

**Feature**: `016-streaming-responses` · **Date**: 2026-09-12

Capability 14 adds `POST /stream-chat/{sessionId}`: a conversation whose reply arrives **as it is
written**. Capability 4's `POST /chat/{sessionId}` is untouched and still answers in one piece.

## Verify offline (no model, no API key, no network)

```shell
mvn clean verify
```

Streaming is **fully** provable offline, which was not a given — the SDK's agent-testing documentation
never mentions it. `TestModelProvider` tokenizes a scripted reply on its own: a two-sentence answer
arrives as **57 fragments**, and concatenating them reproduces the scripted text exactly. So both
"it really is incremental" and "nothing is lost" are asserted, not eyeballed (research Q-C).

Two things are *not* provable offline, and are live-only by nature: whether a **real** provider failure
before the first token behaves like the scripted one, and whether a failure **mid-stream** is reachable
at all (the test provider always fails before the first token).

## Run it

```shell
ollama pull qwen3:8b            # once
mvn compile exec:java
```

Watch the answer arrive — `--no-buffer` is what makes curl print fragments as they land:

```shell
curl --no-buffer -N -X POST http://localhost:9000/stream-chat/c-1 \
  -H "Content-Type: application/json" \
  -d '{"message":"why does agent work survive a restart, in a few sentences?"}'
```

### It is a conversation

```shell
curl --no-buffer -N -X POST http://localhost:9000/stream-chat/c-1 \
  -H "Content-Type: application/json" -d '{"message":"my name is Ada"}'

curl --no-buffer -N -X POST http://localhost:9000/stream-chat/c-1 \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# ...streams back an answer that knows "Ada" — the streamed turn was remembered
```

A different `sessionId` is a different conversation and knows nothing of it.

### Validation still runs first

```shell
curl -i -X POST http://localhost:9000/stream-chat/c-1 \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — question must not be blank   (no model call, and nothing streamed)
```

## What this capability costs, and what it cannot do

- **No fallback reply.** Every other agent surface here degrades a failed turn to a sentinel. A stream
  cannot: `StreamEffect` has no `onFailure`, and no value can replace text the caller has already read.
- **A failure before the first token would hang forever** if the endpoint did not guard the stream.
  Measured: no tokens, no completion and no failure after **240 seconds**, well past the provider's own
  ~3-minute budget. The endpoint therefore imposes `initialTimeout`, and `idleTimeout` for a stall
  mid-answer. Without them a caller keeps an open connection indefinitely.
- **Once bytes are flowing there is no error status.** An interrupted answer is an incomplete body, not
  a `5xx` — a property of streaming, not of this service.

## Where the interop line falls

Authoring a streamed reply is **Scala-clean**: `streamEffects()` takes only values and strings.
**Consuming one is not.** Every Scala form was measured failing — `dynamicCall(...).source(...)` and a
string-keyed `tokenStream("id")` do not compile, and the documented lambda form compiles then fails at
run time with `IllegalArgumentException: class <your own class> is not a subclass of class
akka.javasdk.agent.Agent`. So the endpoint is **one Java class**, capability 11's shape, and everything
else — agent, domain, and the endpoint's own test — stays Scala.

That is the amendment this capability makes to the project's oldest finding: `dynamicCall` rescues the
agent client's **request/response** calls and nothing else, so the agent client sits on *both* sides of
the method-reference wall. See README §16 and [`FINDINGS.md`](../../FINDINGS.md).
