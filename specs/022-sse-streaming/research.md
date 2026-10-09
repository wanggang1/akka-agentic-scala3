# Research: SSE Framing for the Streaming Agent Surface (capability 20)

**Feature**: `022-sse-streaming` · **Date**: 2026-10-09 · **SDK**: 3.6.3

All findings below were measured against the SDK on disk
(`~/.m2/.../akka-javasdk/3.6.3`), not inferred from documentation. Where the documentation and the
bytecode/source disagree, the source wins and the disagreement is recorded as a candidate correction.

---

## Q-A — Does the SDK expose a first-class SSE / `text/event-stream` construct, or must frames be hand-built?

**Decision**: Use the SDK's `akka.javasdk.http.HttpResponses.serverSentEvents(...)`. SSE is
first-class; no hand-framing over a raw `Source[String, ?]` is needed.

**Evidence** — three public overloads exist (`javap akka.javasdk.http.HttpResponses`):

```
serverSentEvents(Source<T,?>)
serverSentEvents(Source<T,?>, Function<T,String> extractEventId)
serverSentEvents(Source<T,?>, Function<T,String> extractEventId, Function<T,String> extractEventType)
```

The private implementation maps each element to
`ServerSentEvent.create(jsonPayload, eventType, eventId, OptionalInt.empty())`, sets
`Content-Type: text/event-stream`, adds `Cache-Control: no-cache` + `Connection: keep-alive`, and
emits a heartbeat on idle.

**Rationale**: the project constitution is "Akka SDK first"; a first-class helper exists and hand-rolling
SSE encoding would be an unjustified deviation. This **resolves the spec's central open interop
question** (spec Assumptions): there *is* an SDK SSE helper.

**Alternatives considered**: hand-building `ServerSentEvent.encode` over a `Source` and returning a
chunked `text/event-stream` entity ourselves (as cap-14 does for `text/plain`). Rejected — it
re-implements exactly what `serverSentEvents` already does, for no gain.

---

## Q-B — Can we emit a real `event: error` label, i.e. distinguish frame kinds? (FR-002, FR-004)

**Decision**: Yes — the 3-arg overload's second function is `extractEventType`, which sets the SSE
`event:` field per element. Model the stream as a **sealed envelope** with a `data` variant and an
`error` variant; `extractEventType` returns `"data"` / `"error"`.

**Evidence** — the source's own javadoc:

> `@param extractEventType A function extracting an event type for the event, making it easier for the
> SSE client to distinguish between a set of different kinds of events emitted.`

and the impl passes it straight into `ServerSentEvent.create(payload, eventType, …)`.

**Rationale**: this is precisely the "slot for an error after the body starts" the capability exists to
exploit. The event *id* overload (middle arg) is for reconnection offsets and is **not** needed here
(see Q-E).

---

## Q-C — THE HEADLINE FINDING: the SDK's SSE helper swallows a stream *failure* into a silent empty completion — reproducing cap-14's defect one level up.

**Decision**: The agent token source MUST convert its own failure into a **final `error`-typed
element** (via Akka Streams `recover`) *before* it is handed to `serverSentEvents`. A `Throwable` that
reaches `serverSentEvents` is lost, not framed.

**Evidence** — the private impl ends with:

```java
.recoverWith(Match.match(Throwable.class, (ex) -> {
    // Note: no natural way to convey stream errors to client with SSE - the
    //       HTTP response with status is already sent to client
    //       so we recover/complete stream and log error
    SdkRunner.userServiceLog().error("HTTP endpoint SSE stream failed with error", ex);
    return Source.<ByteString>empty();   // <-- silent completion, no error frame
}).build());
```

So if the token `Source` **fails** (model error, or a timeout guard firing — both materialize as a
`Throwable` in the stream), the SDK logs it server-side and completes the response as an **empty,
successful-looking** SSE stream. **This is byte-for-byte the cap-14 defect**: a failure the client
cannot distinguish from "no content". The `event: error` capability is therefore *not* automatic from
`serverSentEvents` — the helper cannot frame a failure it receives as a Throwable, exactly as its own
comment admits.

**The fix that makes the capability real**: upstream of `serverSentEvents`, apply
`.recover { case ex => ErrorEnvelope(reasonFor(ex)) }` to the token source. `recover` consumes the
Throwable and substitutes a **final normal element**, then completes. That error element flows through
as an ordinary `event: error` frame; the SDK's inner `recoverWith` never sees a Throwable.

**Rationale / why this is the whole capability**: without this move, "switch to SSE" buys *nothing* over
cap-14 — the silent-empty-`200` failure survives the format change. The capability's value (SC-002:
pre-first-token failure is always a distinct signal) lives entirely in catching the failure as an
element before the SDK can erase it.

**Consequence for the timeout guards**: cap-14's `initialTimeout` / `idleTimeout` throw
`TimeoutException` *into* the stream. Under `serverSentEvents` that would be swallowed too, so the same
`.recover` must catch them and emit an `event: error` (FR-009) rather than let them vanish.

**Alternatives considered**:
- *Rely on `serverSentEvents` to surface the error* — rejected; measured impossible (it empties).
- *Keep cap-14's `streamText` and bolt an error onto it* — rejected; raw chunked text has no frame
  slot at all, which is the original defect.

---

## Q-D — What does a `data` frame actually carry? Raw text or JSON?

**Decision**: JSON. Each element is rendered with `JsonSupport.getObjectMapper()` (the SDK's **internal**
mapper), so a `data` frame carries a JSON object, e.g. `data: {"text":"hello "}`, not `data: hello `.

**Evidence**: impl line `JsonSupport.getObjectMapper().writeValueAsString(extractValue.apply(elem))`.

**Consequences**:
1. **The envelope is a wire type → Java-shaped.** Per the project's standing rule
   (`scala-jackson-module-followup`: component/internal payloads use the internal mapper and must be
   Java-shaped), the SSE event envelope is a **Java record**, defined in the endpoint's `api` package
   (constitution II: endpoints own their response types). It is *not* a Scala case class.
2. **Content parity (FR-003, SC-001) is defined on the decoded field**, not the raw `data` line: the
   client concatenates the `text` fields of the `data` frames, and that must equal cap-14's reassembled
   answer. The two surfaces differ in framing *and* in data encoding (JSON vs raw) — both recorded.
3. You cannot have *both* raw-text `data` payloads *and* a typed `error` frame from this helper, because
   `extractValue` JSON-serializes every element uniformly. JSON-enveloped `data` is the price of typed
   errors — itself a finding.
4. **The error element must be trivially serializable** (a flat record of strings): if JSON
   serialization of the error element itself throws, the SDK's inner `recoverWith` swallows *that* into
   an empty stream — re-opening the very hole we closed. Keep the error envelope dumb.

---

## Q-E — Reconnection / last-seen-event-id: in scope?

**Decision**: Out of scope. Do not supply an `extractEventId`; use the 1-arg data path plus a 3-arg call
only to set event *type* (pass an id function that is unused only if required). SSE `id:`/resume
semantics (`RequestContext.lastSeenSseEventId`) are for resumable streams; a one-shot agent answer is
not resumable (you cannot re-ask half a question), and the spec's Out-of-Scope excludes it.

**Note**: the 3-arg overload requires *both* an id function and a type function. We pass a constant/no-op
id (or omit id semantics) and the real `extractEventType`. Exact call shape is a plan detail, not a
risk — both functions are plain `Function<T,String>` lambdas in the (Java) endpoint.

---

## Q-F — Method-ref wall: how much Java does this cost? (spec Assumption)

**Decision**: Exactly **one Java class** — the endpoint — unchanged from cap-14's verdict. Confirmed, not
merely expected.

**Evidence**: cap-14's `StreamingChatEndpoint.java` consumes the token stream via
`componentClient.forAgent().inSession(id).tokenStream(StreamingChatAgent::stream).source(question)`.
README §16 / specs/016 Q-B measured that this is the *only* expressible form: `dynamicCall(id).source`
does not compile, `tokenStream("agent-id")` does not compile, and the same lambda in Scala compiles then
fails at runtime (`MethodRefResolver` reads the `SerializedLambda.implClass`, which for a Scala lambda is
the enclosing class → "not a subclass of Agent"). The SSE endpoint consumes the same token stream, so it
inherits the same single-Java-class cost. The agent (`StreamingChatAgent.scala`), the domain rule
(`StreamQuestion.scala`), and the new endpoint's own integration test stay Scala.

**Rationale**: the `.recover` → envelope → `serverSentEvents` pipeline all lives in that same forced-Java
endpoint, so it adds **no new** Java beyond the one class the wall already requires.

---

## Q-G — Candidate doc corrections surfaced while researching

1. **Heartbeat interval.** `http-endpoints.html.md` says "a heartbeat is emitted every 5 seconds"; the
   3.6.3 source uses `.keepAlive(Duration.ofSeconds(10), ServerSentEvent::heartbeat)` — **10 seconds**,
   and the method javadoc itself says 10. The prose doc is stale. Record in FINDINGS; do not "fix" by
   code.
2. **"Any Source … can be turned into an SSE endpoint method."** True only for the *happy path*. The doc
   omits that a *failing* source is silently emptied (Q-C). This omission is the capability's whole
   reason to exist and will be stated plainly in the write-up.

---

## Testing approach (resolved)

- **Unit**: the error-reason mapping (`Throwable → client-safe reason`) and the envelope are pure and
  Scala-testable where they live in the domain; the small Java glue is covered by integration.
- **Integration**: use the SDK testkit's **`SseRouteTester`** (`TestKit#getSelfSseRouteTester`,
  `akka.javasdk.testkit.SseRouteTester`) to assert framing, plus a `TestModelProvider` to script the
  agent. Three scenarios map to US1/US2: happy multi-frame answer (parse frames, concat `data.text` =
  expected), failure before first token (assert a single `event: error` frame, no empty-200
  ambiguity), failure after N fragments (assert N `data` frames then `event: error`).
- **Known test-provider caveat** (specs/016 Q-A, `akka-testing-slow-and-failing-calls`): under a
  *scripted* token stream a failure may produce silence rather than a Throwable, so the "fails before
  first token" path must be driven with a provider error (`whenMessage(...).failWith`) that actually
  materializes a stream failure, not merely an empty scripted reply. Verify the `.recover` fires on a
  real materialized failure.
- **Baseline regression** (US3): run cap-14's existing streaming tests unchanged; they must stay green.

---

## Q-H — Live validation (T016), against real Ollama `qwen3:8b`

Run after implementation, to confirm the offline findings and close the offline-only gaps. Both paths
verified end to end through the live SSE surface.

**Happy path** — `POST /sse-chat/demo-1`, "Say hello in one short sentence.":

```
data:{"text":"Hello! How can"}
event:data
id:

data:{"text":" I assist you today?"}
event:data
id:

data:{"text":" 😊"}
event:data
id:
```

Confirms: `event: data` frames, JSON `data:` payloads (Q-D), incremental arrival, and a multibyte emoji
surviving the framing byte-for-byte (FR-007, beyond the offline `\n` case). Also observed, and worth
recording: Akka HTTP emits the SSE fields in the order **`data:` then `event:` then `id:`**, and the
no-op id function (Q-E) renders as a bare **`id:`** line on every frame — harmless but real noise, the
price of the 3-arg overload being the only one that sets event type.

**Real provider error** — service restarted with `OLLAMA_MODEL=does-not-exist-99b`, same request:

```
data:{"reason":"the request failed"}
event:error
id:
```

**This closes the gap the offline tests flagged as live-only.** Offline, a `failWith` turn goes silent,
so `initialTimeout` fires and the reason is `TimedOut`. Live, a genuine provider error materialises as a
stream failure that `.recover` catches as a **non-`TimeoutException`**, mapping to **`Failed`** ("the
request failed") — the generic branch of `SseErrorReason` that no offline test could reach. So both
branches of the reason mapping are now measured, and the capability's whole point — a failure is a
self-describing `event: error` frame, not a silent empty `200` — is confirmed against a real model.

---

## Summary of decisions feeding Phase 1

| # | Decision |
|---|---|
| Q-A | Use `HttpResponses.serverSentEvents` (first-class SSE; no hand-framing). |
| Q-B | 3-arg overload's `extractEventType` → `event: data` / `event: error` via a sealed envelope. |
| Q-C | **Agent source must `.recover` its failure into a final `error` element** before `serverSentEvents`, or the failure is silently erased. This is the capability. |
| Q-D | Envelope is a **Java record** (internal mapper); `data` frames carry JSON; parity is on the decoded `text` field; keep the error envelope trivially serializable. |
| Q-E | No reconnection / event-id; one-shot answers are not resumable. |
| Q-F | Exactly one Java class (the endpoint) — same wall as cap-14, no new Java. |
| Q-G | Heartbeat is 10 s not 5 s (doc stale); the "any Source" claim hides the silent-empty-on-failure behaviour. |
