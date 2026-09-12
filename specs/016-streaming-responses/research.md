# Research: Streaming agent responses (capability 14)

**Feature**: `016-streaming-responses` · **Date**: 2026-09-12 · **SDK**: 3.6.3

Every question below was **measured**, not read. The probes are kept in the tree as evidence
(FR-013): `StreamProbeIntegrationTest` (Java) and `ScalaTokenStreamProbeIntegrationTest` (Scala).

---

## Q-A — Can a Scala agent author a streamed reply? **YES, and it is uneventful.**

`streamEffects()` returns `Agent.StreamEffect.Builder`, which is keyed entirely on values and strings:

```text
model(ModelProvider) · systemMessage(String) · systemMessageFromTemplate(String, Object...)
userMessage(String|UserMessage) -> OnSuccessBuilder · tools(...) · mcpTools(...)
imageLoader(...) · contentLoader(...) · memory(MemoryProvider)
reply(String) · error(String|CommandException)
OnSuccessBuilder: thenReply()
```

No `akka.japi.function.*` parameter anywhere, so there is nothing for `MethodRefResolver` to choke on.
`StreamingChatAgent` compiles and runs; the only difference from capability 4's `ChatAgent` is the
return type and the builder entry point.

**The finding worth carrying: there is no `onFailure` on a stream, and that is structural.** The
builder offers `error(...)`, which refuses the interaction *before* any token is emitted, and nothing
at all for a failure after the first token. Capability 8's `.onFailure(_ => sentinel)` technique —
reused by capability 12 for guardrail blocks and extended by capability 13's follow-up for failed
turns — **cannot exist here**: a fallback value cannot replace text the caller has already read.

### Q-A(2) — What does a consumer see when the model fails before the first token? **Nothing. The stream never ends.**

Measured twice, with the scripted model failing via `failWith`:

| Wait | Observed |
|---|---|
| 30 s | no tokens, no completion, no failure — the consumer's own `TimeoutException` |
| **240 s** | **identical** — `java.util.concurrent.TimeoutException` from our `get`, after 240 019 ms |

240 s is well past the provider's own budget (`response-timeout = 1m` × 3 attempts ≈ 3 min), so this is
not retry latency. **A failed model call does not terminate the token stream at all.**

**Design consequence (FR-006).** The SDK's stream cannot satisfy "a failure before delivery reaches the
caller as a single clear failure" on its own — a caller would hang on an open connection indefinitely.
The endpoint must impose termination itself. `akka.stream.javadsl.Source` provides exactly the needed
operators: `initialTimeout(Duration)` (fail if no *first* element arrives in time — precisely this
hang), plus `idleTimeout`, `completionTimeout`, `keepAlive`, `recover`/`recoverWith`, `takeWithin` and
`watchTermination`.

**Honest scope of the measurement**: observed under the TestKit with a scripted failure. Whether a real
provider failure behaves the same is unverified and belongs in the live smoke test.

### Q-A(3) — what the caller sees *with* the guard: a clean, empty `200` (measured at T015)

The guard was expected to make a pre-token failure observable. It makes it **terminate**; it does not
make it **distinguishable**. Measured end to end with `first-token-timeout = 1s`:

```text
status = 200 OK, after 1041 ms, body = completed with 0 chunk(s)
```

The status line is already on the wire when the stream fails — the handler returned its
`HttpResponse` long before any token existed — so Akka HTTP **ends the already-committed chunked body
normally** rather than aborting it. At the HTTP level, "the model failed before saying anything" and
"the assistant had nothing to say" are the same response.

So the asymmetry FR-005/FR-006 actually get is:

| Failure | Caller observes |
|---|---|
| **before** the first fragment | `200`, body completes normally, **zero chunks** |
| **after** fragments were sent | body **aborts** — the stream fails, so the body is truncated (pinned on a synthetic source, since the model cannot produce a mid-stream gap offline) |

**What this costs the contract.** FR-006 asked for "a single clear failure". What exists is "a request
that ends promptly and empties", which a caller can only interpret by **treating an empty body as
failure**. That is now documented in `contracts/stream-chat-endpoint.md` rather than claimed away, and
pinned by T015 so a change in the shape surfaces as a finding.

**The available fix is a wire-format change, not a code fix.** Distinguishing the two cases needs a
framing with somewhere to put an error *after* the body has begun — which is what SSE exists for
(`HttpResponses.serverSentEvents`, with an explicit `event: error`). That would replace plain text with
an event stream and change every client, so it is recorded as a fork for the user to decide rather than
taken unilaterally (research D4 chose `streamText`; this is the first evidence against that choice).

---

## Q-B — Can a **Scala** caller consume a token stream? **NO. This is the headline.**

Four attempts, all recorded. Two failed at compile time and are not kept as dead code; their exact
diagnostics are here.

| # | Attempt | Result |
|---|---|---|
| 1 | `tokenStream[StreamingChatAgent, String]((a, m) => a.stream(m)).source(msg)` | **compiles**, then fails at run time (below) |
| 2 | `tokenStream[StreamingChatAgent, String](_.stream(_))` | same — compiles |
| 3 | `dynamicCall[String, String]("streaming-chat-agent").source(msg)` | **compile error**: `value source is not a member of akka.javasdk.client.DynamicMethodRef[String, String]` |
| 4 | `tokenStream("streaming-chat-agent")` | **compile error**: `None of the overloaded alternatives of method tokenStream in trait AgentClientInSession` takes a `String` |

Attempt 1 at run time:

```text
java.lang.IllegalArgumentException: class com.gwgs.akkaagentic.streaming.probe.
  ScalaTokenStreamProbeIntegrationTest is not a subclass of class akka.javasdk.agent.Agent
```

The same misdirecting diagnostic capability 13 met on `.method(Evaluator::evaluate)`, now reproduced on
the streaming path: `MethodRefResolver` reads the `SerializedLambda`'s `implClass`, which for a Scala
lambda is the **enclosing** class — here the test itself. It never mentions lambdas, Scala, or
`tokenStream`.

**The Java path works** — `StreamProbeIntegrationTest` consumes the same stream via
`tokenStream(StreamingChatAgent::stream).source(msg)` and receives its tokens. So attempt 1's failure
is a statement about Scala, not about our usage. That control is what makes the finding safe to publish.

### Why this matters more than the feature

A jar-wide sweep of `akka.javasdk.client` gives the complete inventory of streaming entry points:

| Client | Streaming member | Keyed on |
|---|---|---|
| `AgentClientInSession` | `tokenStream(Function \| Function2)` | **method reference** |
| `EventSourcedEntityClient` / `KeyValueEntityClient` / `WorkflowClient` | `notificationStream(Function)` | **method reference** |
| `AutonomousAgentClient` | `notificationStream()` | *zero-arg* — Scala-clean |
| `TaskClient` | `notificationStream()` | *zero-arg* — Scala-clean |
| `DynamicMethodRef` | — | no streaming member at all |

So the project's central claim needs its last amendment. The agent client has been the one client on
the friendly side of the wall since capability 1, because of `dynamicCall(String)`. **That escape hatch
covers request/response only.** The precise statement is now: *the wall is a property of which client
**and which method on it** — `dynamicCall` rescues the agent client's request/response calls and
nothing else, so the agent client is on **both** sides of the wall at once.* Streams are not the
deciding axis either: two notification streams in the same package are Scala-clean because they take no
argument.

**Decision (FR-013, and the user's 2C):** the Scala path was attempted first and is kept as evidence.
Consumption is confined to **one Java class** — the endpoint that holds the method reference — exactly
capability 11's shape, where the View stayed Scala and only its caller was Java. The agent, the domain
and every test that does not hold a method reference stay Scala.

---

## Q-C — Is a streamed reply scriptable offline? **YES — better than expected.**

The SDK's agent-testing documentation never mentions streaming, so this was genuinely open.
`TestModelProvider.fixedResponse(String)` is enough: the provider implements
`createStreamingChatModel()` and tokenizes the scripted reply itself.

Measured, for a two-sentence scripted answer:

- **57 fragments**, first three `[The, " ", runtime]` — word-and-space granularity.
- `tokens.mkString == scriptedAnswer` — **exactly**, nothing added or lost.

So both SC-001 (incrementality: more than one fragment) and SC-002 (parity: concatenation reproduces
the answer) are provable **offline with no model**. FR-010's first branch applies; no live-only caveat
is needed for the streaming mechanism itself. This is the positive counterpart to capability 7's
un-mockable delegation, and it also means FR-007's grouping can be asserted rather than eyeballed.

---

## Q-D — Is a streamed reply remembered? **YES, assembled and persisted once complete.**

After one streamed turn on a fresh session id, `SessionMemoryEntity` holds **2 messages** — 1 user,
1 AI — and the AI message's text equals the **entire** streamed answer, not a fragment. So the runtime
assembles the tokens and writes the complete reply, and FR-008/SC-005 stand: the streaming surface can
be multi-turn, with the same `MemoryProvider.limitedWindow()` shape capability 4 uses.

Reading that entity is Java, as capability 4 established (the `EventSourcedEntity` client is
method-reference-only), so this check lives in the Java probe. That is precedent, not a new concession —
and note it lands in the *same* class the wall already forces to be Java, so the quarantine does not
grow.

---

## Consolidated decisions

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | The agent is **Scala**; the consuming endpoint is **Java**, one class | Q-B: measured runtime failure for every Scala form; Java control works | Scala-only (no reachable consumption path); Java agent too (the wall does not reach it) |
| D2 | The endpoint guards the stream with `initialTimeout` | Q-A(2): a pre-token failure never terminates the stream | Rely on the SDK (hangs); rely on the provider's timeout (also never terminated) |
| D3 | Group fragments with `groupedWithin(n, duration)` | FR-007; the SDK's own doc recommends it | One event per token (wasteful, and 57 events for two sentences) |
| D4 | Respond with `HttpResponses.streamText(Source)` | The token stream is plain text; the SDK's example uses it; readable with plain `curl` | `serverSentEvents(...)` — adds event framing the caller must parse for no gain on a text reply |
| D5 | Memory: `MemoryProvider.limitedWindow()`, no `readLast(N)` | Q-D shows memory works; `readLast` orphans tool-call pairs (capability 6's live bug) | `readLast(N)` (known-broken); `MemoryProvider.none()` (would delete FR-008) |
| D6 | Parity is asserted as "concatenated fragments == scripted reply" | Q-C makes it exact and offline | Comparing against capability 4's surface (would couple two capabilities and change nothing) |

## What remains unverified (for the live smoke test, not offline)

1. Whether a **real** provider failure before the first token behaves as the scripted one did (stream
   never terminates) — the guard in D2 is designed to make the question moot for callers either way.
2. Whether a failure **mid-stream** is reachable at all. It is not scriptable offline: the test
   provider produces the whole reply and tokenizes afterwards, so an injected failure always lands
   before the first token. FR-005 is therefore designed against the guard, and the mid-stream case is
   an explicit live-only unknown rather than a silent assumption.

---

## Q-E — How does a **Java** caller read the Scala domain API? **Cleanly, measured at T009.**

Left open deliberately in `StreamQuestion`'s scaladoc rather than pre-empted: the endpoint is Java by
force (Q-B), so it is the first consumer in this project whose language the **SDK** chose rather than
we did — which is the case README §8's language-of-consumer guidance never covered.

Measured by compiling it, from clean:

```java
Either<String, StreamQuestion> validated =
    StreamQuestion.validate(Option.apply(request == null ? null : request.message()));
if (validated.isLeft()) return HttpResponses.badRequest(((Left<String, StreamQuestion>) validated).value());
var question = ((Right<String, StreamQuestion>) validated).value().question();
```

- `StreamQuestion.validate(...)` resolves **without** `StreamQuestion$.MODULE$` — scalac emits a static
  forwarder on the class for its companion's method, and javac finds it.
- `scala.Option.apply(x)` converts a nullable Java value at the boundary, so the domain never sees
  `null` (CLAUDE.md's Scala-idioms rule) even though the caller is Java.
- The only friction is **two casts**: `Either` has `isLeft()` but no Java-friendly accessor, so reading
  the value needs `((Left<..>) e).value()` / `((Right<..>) e).value()`.

**Verdict**: an idiomatic Scala `Option`/`Either` domain API is usable from Java at the cost of two
casts — not enough friction to justify a Java-shaped façade, and far less than the alternative of
moving the rule into Java, which would have grown the quarantine the wall forced (FR-013). So the
language-of-consumer guidance holds with one clarification: **when the SDK forces the consumer's
language, keep the domain idiomatic and pay the cast at the boundary.**

---

## Fork recorded (user decision, 2026-09-12): keep plain text, do not adopt SSE

Q-A(3) produced the first evidence against research D4's choice of `streamText`: a pre-token failure
arrives as a normally-completed empty `200`, and only a framing with room for a post-body error —
server-sent events with an explicit `event: error` — could make it self-describing.

**Decision: keep `HttpResponses.streamText` and record SSE as a fork.** Reasons, in the order they
mattered:

1. The weakness is *documentable and bounded*: "treat an empty body as failure" is a one-line contract
   rule, and the request does terminate promptly, which was the dangerous half.
2. Switching framing changes the wire format for **every** client, for a failure path, in a capability
   whose purpose is an interop finding rather than a production surface.
3. `serverSentEvents` is available on `HttpResponses` whenever the tradeoff changes, and the endpoint's
   stream composition would be reused as-is — only the final response builder differs.

Recorded in the contract, in `docs/streaming-vs-request-response.md`, in README §16 and in
`docs/sdk-3.6.0-limitations.md` §6b, so a future reader meets the limitation and the available fix
together rather than rediscovering both.

