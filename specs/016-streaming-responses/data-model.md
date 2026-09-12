# Data Model: Streaming agent responses (capability 14)

**Feature**: `016-streaming-responses` · **Date**: 2026-09-12

This capability stores nothing of its own. The only durable record is the runtime's session memory,
which research Q-D measured receiving the **assembled** streamed reply.

## Domain (pure Scala, no Akka import — Constitution II)

### `StreamQuestion`

The validated input. Parse, don't validate: the endpoint receives a nullable wire value and turns it
into a type whose field is proven present, so nothing downstream re-checks it.

| Field | Type | Rule |
|---|---|---|
| `question` | `String` | non-blank after trimming |

```text
StreamQuestion.validate(question: Option[String]): Either[String, StreamQuestion]
  None | Some(blank) -> Left("question must not be blank")
  Some(text)         -> Right(StreamQuestion(text.trim))
```

The session id is **not** part of this type: it arrives as a path segment, and an empty path segment
cannot reach the handler. A blank-looking id (`%20%20`) is rejected by the same validation call, which
returns the message the contract documents.

*Why not reuse capability 8's `AskQuestion`?* It lives in `docs.domain` and belongs to the RAG
capability. Reusing it would couple two capabilities for one non-blank check and would put a
capability-14 change one edit away from capability 8 — against FR-009. The duplication is four lines
and is pinned by its own unit test.

## Wire types (API-owned, in the Java endpoint)

### Request

| Field | Type | Notes |
|---|---|---|
| `message` | `String` | nullable on the wire; converted immediately |

A Java record in the endpoint, so it crosses the SDK's endpoint mapper as a plain Java shape. The
two-mapper boundary (README §3) does not bite here: **nothing this capability sends crosses the
internal component mapper**. The agent's command parameter is a bare `String` and its reply is a
stream of bare `String`s — the least-serialization capability in the project, as capability 4 was.

### Response

Not a type. The response body is a **sequence of text fragments**, each a group of model tokens joined
into one string, delivered as they are produced. There is no envelope, no JSON, and no trailer:
completion is the end of the body, and abnormal termination is a broken body. That is the whole reason
FR-004 to FR-006 need the stream guard rather than a status field — once bytes are flowing, the status
code is already sent.

## Runtime-owned state (not ours, not in the descriptor)

| Entity | Owner | What capability 14 relies on |
|---|---|---|
| `SessionMemoryEntity` | the Akka runtime | Keyed by the path's `sessionId`. Receives 1 user + 1 AI message per streamed turn, the AI message carrying the **complete** reply (measured, Q-D). Absent from the hand-maintained descriptor, like capabilities 4 and 6. |

## Stream outcome (behaviour, not a stored shape)

The three outcomes FR-004 to FR-006 must keep distinct, and how each is observable:

| Outcome | What the caller sees | Mechanism |
|---|---|---|
| Completed | fragments, then a normally ended body | the stream completes |
| Interrupted after delivery began | fragments, then an abnormally ended body | `idleTimeout` / upstream failure fails the stream mid-body |
| Failed before delivery began | a single failure, no fragments | `initialTimeout` fails the stream before the first element — **required**, because the SDK's stream produces no event at all in this case (measured at 240 s) |
