# Data Model: SSE Framing for the Streaming Agent Surface (capability 20)

**Feature**: `022-sse-streaming` · **Date**: 2026-10-09

This capability adds a response *framing*, not new persistent state. There is no entity, no event
journal, no view. The only "data" is the shape of what crosses the wire. Two reused inputs and one new
wire type.

## Reused (unchanged) — from capability 14

- **`StreamQuestion`** (`streaming/domain`, Scala) — the validated question. Its
  `validate(Option[String]): Either[String, StreamQuestion]` rule is reused verbatim so a blank message
  is a `400` before any model call.
- **`StreamingChatAgent`** (`streaming/application`, Scala) — the agent whose token stream is consumed.
  Unchanged; the SSE endpoint consumes the *same* `tokenStream(StreamingChatAgent::stream)`.
- **`ChatRequest`** (inbound body) — reused shape `{ "message": string }`, redeclared on the new
  endpoint (constitution II: endpoints own their types). Unknown properties tolerated; missing
  `message` → domain rejection, not a 500.

## New — the SSE event envelope (wire type, Java-shaped)

A **sealed envelope** of the elements the stream emits. It is JSON-serialized by the SDK's internal
mapper (research Q-D), so it is **Java-authored** and lives in the endpoint's `api` package.

```
SseChatEvent            (sealed)
├── Data(String text)     → SSE frame:  event: data   / data: {"text":"<fragment>"}
└── ErrorEvent(String reason)
                          → SSE frame:  event: error  / data: {"reason":"<client-safe>"}
```

| Field | Type | Rule |
|---|---|---|
| `Data.text` | String | one grouped fragment of the answer; never null (empty group is dropped upstream) |
| `ErrorEvent.reason` | String | stable, client-safe (research Q-D.4: trivially serializable; no exception/class detail) |

- **`extractEventType`**: `Data → "data"`, `ErrorEvent → "error"`.
- **Serializability invariant**: `ErrorEvent` is a flat record of strings. If its JSON serialization
  threw, the SDK's inner `recoverWith` would silently empty the stream (research Q-C/Q-D.4), re-opening
  the defect — so the envelope carries no nested or Scala-shaped fields.

## State transitions — the per-request stream lifecycle

The stream is linear; the only branch is success vs. failure, and the capability's point is that
**both branches are observable**:

```
validate(message)
  ├── Left(reason)                         → 400 (non-streamed, as cap-14)   [not an SSE response]
  └── Right(question)
        → tokenStream(...).source(question)
        → initialTimeout / idleTimeout guards
        → group tokens → Data(text) elements
        → .recover(Throwable => ErrorEvent(reasonFor(t)))   ← converts failure to a FINAL element
        → serverSentEvents(source, idFn, typeFn)
             ├── happy path:   event: data … event: data … [clean end-of-stream]        (US1)
             ├── fail @0:      event: error … [end]        (distinct from empty success)  (US2.1)
             ├── fail @N>0:    event: data ×N … event: error … [end]                      (US2.2)
             └── empty success: [clean end-of-stream, NO error frame]                     (edge)
```

**Invariant (SC-002)**: every failure path ends with exactly one `event: error` frame; no path ends in
a silent empty-but-successful stream when the agent actually failed. The `.recover` is what guarantees
it — see research Q-C.

## Error-reason mapping (pure)

A small total function `reasonFor(Throwable): String` turns each failure class into a stable, client-safe
string (no stack trace, no internal class name — FR-006):

| Throwable (materialized in the token stream) | `reason` |
|---|---|
| `TimeoutException` from `initialTimeout` (no first token) | e.g. `"timed out waiting for a response"` |
| `TimeoutException` from `idleTimeout` (stalled mid-stream) | e.g. `"response stalled"` |
| any other model/runtime failure | a generic client-safe `"the request failed"` |

This mapping is the one piece of real logic and is unit-testable in isolation (it takes a `Throwable`,
returns a `String`). Where it physically lives (a tiny Scala helper the Java endpoint calls, vs. inline
Java) is a plan/tasks decision; it is pure either way.
