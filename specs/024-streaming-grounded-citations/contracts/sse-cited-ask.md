# Contract: `POST /sse-cited-ask`

Capability 24 (fork B5). A **streaming, grounded, honestly-cited** question-answering surface. It is the
join of capability 20's SSE framing and capability 21's honesty floor. Capabilities 8 (`/ask`),
14 (`/stream-chat`), 20 (`/sse-chat`), and 21 (`/cited-ask`) are **untouched** siblings.

## Request

```
POST /sse-cited-ask
Content-Type: application/json

{ "question": "what makes agent work survive a restart?" }
```

- `question` (string, required). Absent/blank/null → `400 Bad Request` with a validation message, **before**
  any retrieval or model call (spec FR-012). No session id in the path: each question is answered
  independently (spec FR-013).

## Response — `200 OK`, `Content-Type: text/event-stream`

A sequence of Server-Sent Events. Each frame carries a JSON payload (serialized by the SDK's internal
mapper) and an SSE `event:` label. The answer is streamed as it is produced (no server-side buffering).

### Frame types

```
event: data
data: {"text":"Agent work survives a restart "}

event: data
data: {"text":"because its events are persisted and replayed."}

event: citations
data: {"usedSources":["akka-persistence"],"retrievedSources":["akka-persistence","event-sourcing","snapshots"]}
```

- **`event: data`** — one fragment of the **clean** answer. The concatenation of all decoded `text`
  fields reconstructs the grounded answer **with inline `[n]` markers removed** (spec FR-004). The caller
  never sees a citation marker in the prose.
- **`event: citations`** — exactly one, after all `data` frames, before the stream closes.
  - `usedSources`: the usage-accurate citations — the sources the model reported using (via inline `[n]`
    markers, parsed server-side) **intersected with** what was retrieved, resolved to labels, de-duplicated
    (spec FR-006). **Empty** on a decline (FR-007) or when nothing verifiable was reported (FR-008).
  - `retrievedSources`: the ground-truth retrieved set (what capability 8's `/ask` would cite), kept
    alongside so the used-vs-retrieved divergence is visible from the stream alone.

### Decline

An out-of-corpus question streams the decline sentinel as `data` and a `citations` frame that cites
nothing:

```
event: data
data: {"text":"I don't know"}

event: citations
data: {"usedSources":[],"retrievedSources":["akka-persistence","event-sourcing","snapshots"]}
```

### Failure

If the agent fails — before the first fragment (including a retrieval failure) or after some `data`
frames — the stream ends with a terminal error frame and **no** `citations` frame:

```
event: data
data: {"text":"Agent work survives a restart "}

event: error
data: {"reason":"the assistant is temporarily unavailable"}
```

- `reason` is stable and client-safe; it never leaks internal detail (stack traces, class/exception
  names) — spec FR-010, reused from capability 20.
- A failure is always distinguishable from a successful (including successfully empty) answer: a success
  ends with a `citations` frame, a failure with an `error` frame (spec FR-005/FR-009).

## Guarantees

| # | Guarantee | Spec |
|---|---|---|
| 1 | Concatenated `data` text = grounded answer with `[n]` markers removed (clean prose). | FR-004 |
| 2 | Exactly one terminal frame: `citations` on success, `error` on failure; never both, never neither. | FR-005/FR-009 |
| 3 | Every label in `usedSources` was among the retrieved passages for that request (honesty floor). | FR-006/SC-002 |
| 4 | A decline cites nothing, with no fabricated source. | FR-007/SC-003 |
| 5 | A non-decline answer with no verifiable reported source → empty `usedSources`, retrieved list still shown. | FR-008 |
| 6 | Answer streamed incrementally; not buffered whole before sending. | FR-003 |
| 7 | Capabilities 8/14/20/21 unchanged. | SC-005 |

## Not in this contract

- **No visible citation markers in the prose.** `[n]` is a private server-side signal; it is stripped
  (spec FR-004/FR-005).
- **No second model call.** `usedSources` describes the exact answer that was streamed (research R4).
- **No reconnection / `Last-Event-ID` / resumable stream.** A one-shot answer; a constant empty SSE `id:`
  is emitted (cap-20 precedent). An interrupted stream (no terminal frame) must be treated by the client
  as failure.
- **No cross-question memory, no guardrails/verdicts.** `MemoryProvider.none()`; the new agent is
  ungoverned (capability 12 guards `docs-agent` only), as capability 21's agent is.
