# Contract: `POST /stream-chat/{sessionId}`

**Feature**: `016-streaming-responses` · **Date**: 2026-09-12

Answers a question in an ongoing conversation, delivering the answer **as it is generated**.
Capability 4's `POST /chat/{sessionId}` is a separate surface and is unchanged.

## Request

```http
POST /stream-chat/{sessionId}
Content-Type: application/json

{ "message": "why does agent work survive a restart?" }
```

| Part | Rule |
|---|---|
| `sessionId` (path) | the conversation. Reusing it continues the conversation; a new one starts a fresh one. |
| `message` (body) | required, non-blank. Unknown properties are tolerated. |

## Responses

### 200 — the answer, streamed

`Content-Type: text/plain(; charset=UTF-8)`, delivered in **fragments** as generated. Each fragment is
a group of model tokens joined into one string (FR-007); a client appends them in order.

```text
The runtime persists the task and the agent's process state
 as the loop runs. That is why work survives a restart
 without any persistence code of your own.
```

Guarantees:

- **Incremental** — the first fragment is sent long before the answer is complete (SC-001).
- **Faithful** — fragments in order, concatenated, are exactly the answer, with nothing added,
  reordered or lost (SC-002, measured exactly offline).
- **Remembered** — once complete, the whole answer becomes part of the conversation (SC-005, Q-D).
- **Complete body = complete answer** — a normally ended body is the only "finished" signal, and it is
  sufficient (FR-004).

### 400 — invalid request

Returned **before** any model call, as an ordinary non-streamed response:

```http
400 Bad Request

question must not be blank
```

Also `400` for a malformed JSON body (the SDK rejects it) and for a blank-looking `sessionId`.

### Abnormal termination — there is no error status once streaming has begun

| When | What the caller observes | Measured |
|---|---|---|
| Failure **before** the first fragment | **`200` with an empty body that completes normally.** The guard ends the request promptly (~1 s) instead of hanging — without it the SDK's stream emits nothing and never ends, silent past **240 s** — but the status line was already sent, so there is no distinct failure signal. **A caller must treat an empty body as failure.** | T015, research Q-A(2)/(3) |
| Failure **after** fragments were sent | the body **aborts**: fragments already read stay valid and the truncation is visible as an incomplete response rather than a clean end (FR-005). | pinned on a synthetic source — the model cannot produce a mid-stream gap offline |

> **Known weakness, not a to-do.** FR-006 asked for "a single clear failure"; the pre-token case
> delivers "ends promptly and empties". Making the two cases distinguishable requires a framing with
> somewhere to put an error after the body has begun — server-sent events with an explicit
> `event: error` — which changes this contract from plain text to an event stream and changes every
> client. Recorded as a fork rather than taken silently.

**Why no error JSON**: the status line and headers are sent with the first fragment, so a later failure
cannot change the status. And unlike every other agent surface in this project, there is **no fallback
value available** — `StreamEffect` has no `onFailure`, and a sentinel cannot replace text the caller
has already read (research Q-A). A caller that needs a machine-readable failure must treat an
incomplete body as failure; that is a property of streaming, stated rather than papered over.

## Not in this contract

- **No citations and no grounding.** This is a conversation, not the RAG surface. A streaming *grounded*
  answer is a documented fork (spec Assumptions): a tool call inside a stream is undocumented on this
  SDK, and citations cannot honestly be appended after the text has gone out.
- **No guardrails or verdicts.** Capability 12's rules guard `docs-agent` and capability 13's judges
  judge it; neither applies to this agent, and this capability configures nothing.
- **No structured or typed streamed result.** Text only.
