# Contract: SSE Streaming Chat Endpoint (capability 20)

**Feature**: `022-sse-streaming` · **SDK**: 3.6.3

A new HTTP surface, parallel to capability 14's `POST /stream-chat/{sessionId}`. Same agent, same
question, **SSE framing** instead of raw chunked `text/plain`.

## Endpoint

```
POST /sse-chat/{sessionId}
```

- **Path param** `sessionId` — the agent session id (same semantics as cap-14).
- **Request body** (`application/json`): `{ "message": string }` — unknown properties ignored.
- **ACL**: `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))` — matches cap-14 (FR-011).

> **Why POST, not GET.** Browsers' `EventSource` only issues GET for SSE (doc note). This surface is for
> programmatic clients (`curl --no-buffer`, server-to-server); a browser `EventSource` front-end is out
> of scope (spec Out-of-Scope), so POST with a JSON body is kept for parity with cap-14. A GET variant
> is a possible future addition, not part of this capability.

## Responses

### 400 Bad Request — blank/invalid question (non-streamed)

Validation happens before any model call. Body is the plain validation reason. This is an ordinary
non-SSE response (no model cost), identical to cap-14.

### 200 OK — SSE stream (`Content-Type: text/event-stream`)

Set by `HttpResponses.serverSentEvents`. Also carries `Cache-Control: no-cache`,
`Connection: keep-alive`. The `200` and headers are committed **before** the first token exists (same as
any streaming surface) — which is exactly why failures are reported *in-band* as frames, not as a
status code.

Frame kinds:

| `event:` | `data:` payload (JSON) | Meaning |
|---|---|---|
| `data` | `{"text":"<fragment>"}` | one grouped fragment of the answer |
| `error` | `{"reason":"<client-safe>"}` | the request failed (before or during generation) |
| *(none)* | `:` heartbeat comment | SDK keepalive, emitted on ~10 s idle (research Q-G) |

**Frame ordering guarantees**:
- Happy path: one or more `event: data` frames, then a clean end-of-stream. Concatenating the `text`
  fields in order yields exactly the agent's answer (SC-001 parity with cap-14).
- Failure before first token: a single `event: error` frame, then end-of-stream. **Never** an empty
  success (SC-002).
- Failure after N fragments: the N `event: data` frames already produced, then one `event: error`,
  then end-of-stream (SC-003) — a partial answer is never presented as complete.
- Successful empty answer: clean end-of-stream with **no** `event: error` frame (edge case).

## Example

```bash
curl --no-buffer -N -X POST http://localhost:9000/sse-chat/session-123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"Explain durable execution in two sentences."}'
```

Happy path (illustrative):

```
event: data
data: {"text":"The runtime persists the task "}

event: data
data: {"text":"and the agent's process state as the loop runs. "}

event: data
data: {"text":"That is why work survives a restart."}

```

Failure before the first token:

```
event: error
data: {"reason":"timed out waiting for a response"}

```

> Contrast with cap-14 (`/stream-chat`) on the same failure: `200 OK`, `Transfer-Encoding: chunked`,
> **zero bytes**, `curl` exits 0 — indistinguishable from a successful empty answer. That contrast is
> the capability (research Q-C).

## Non-goals (contract-level)

- No `id:` / `retry:` / last-event-id resume (research Q-E).
- No change to `/stream-chat/{sessionId}` — it remains the raw-chunked baseline (FR-010).
- `data` payloads are JSON objects, not raw text lines (research Q-D) — clients decode `.text`.
