# Contract: Help Desk API (capability 3)

Async, autonomous-agent-backed help answers. Two endpoints on `HelpDeskEndpoint`, base path `/help`.
Capabilities 1 (`POST /greet`) and 2 (`/greetings`) are separate, **unchanged** endpoints — this
contract does not touch them.

The pinned assertions (C1–C7) are what the endpoint integration test enforces (model mocked with
`TestModelProvider` + `AutonomousAgentTools`).

## C1 — Start a help request → 202 Accepted + Location

```
POST /help
Content-Type: application/json
{ "question": "How do I reset my password?" }
```

Response:
```
202 Accepted
Location: /help/<taskId>
{ "taskId": "<taskId>" }
```

- A single task is started via `runSingleTask` with a fresh agent instance id (UUID); the response
  returns immediately — **no** answer yet. `<taskId>` is the handle to poll.

## C2 — Retrieve before completion → not ready (no fabricated answer)

```
GET /help/<taskId>      (immediately after C1, task still running)
```
Response: `404 Not Found` (body: a short "not ready" message). Distinct from success; never a partial
or invented answer.

## C3 — Retrieve after completion → 200 with the structured answer

```
GET /help/<taskId>      (after the agent completes the task)
```
Response:
```
200 OK
{ "answer": "To reset your password, …", "category": "account",
  "citedTopics": ["password-reset"], "confidence": 90 }
```
- All four fields present. When the mocked model consults the knowledge base before answering,
  `citedTopics` is populated (C5); when it answers directly, `citedTopics` may be empty (C6).

## C4 — Unknown handle → not ready / not found

```
GET /help/does-not-exist
```
Response: `404 Not Found`. An id that was never started never yields an answer.

## C5 — Model consults the knowledge base, then completes (cited topics populated)

The mocked model first calls the `lookupPolicy` tool (scripted with `whenMessage`), then — reacting to
the tool result (`whenToolResult`) — calls `complete_task` with a `HelpAnswer` whose `citedTopics`
includes the looked-up topic. Enforces FR-004/FR-005 and SC-002: the agent iterated and the answer
reflects the consultation.

## C6 — Model answers directly, no lookup (cited topics empty)

The mocked model calls `complete_task` immediately with a `HelpAnswer` and empty `citedTopics`. Enforces
that consulting the knowledge base is the model's choice, not a fixed step (acceptance scenario US1-4).

## C7 — Agent abandons the task → 422 (distinct from success and not-ready)

The mocked model calls `fail_task("…reason…")`. Retrieval:
```
GET /help/<taskId>      (after the task reaches FAILED)
```
Response: `422 Unprocessable Content` (body: the failure reason). Distinct from `200` (success) and
`404` (not ready / unknown); never a fabricated answer. Enforces FR-008/SC-004.

## C8 — Blank question → 400, no task started

```
POST /help   { "question": "" }
POST /help   { "question": "   " }
POST /help   { }                       (question absent → None)
```
Response: `400 Bad Request` (validation message). No task is created; the agent/model is never invoked.

## C9 — Malformed body → 400; unknown JSON properties tolerated

```
POST /help   { "question":            (truncated / invalid JSON)   → 400 Bad Request
POST /help   { "question": "hi", "extra": "ignored" }              → 202 (extra ignored)
```
The SDK rejects an unparseable body before the handler; an unknown property is ignored, consistent with
capabilities 1–2.

## Status → HTTP mapping (read side)

`GET /help/{taskId}` reads `componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)` and maps the
`TaskSnapshot`:

| Task status                         | HTTP response                         |
|-------------------------------------|---------------------------------------|
| `COMPLETED`                         | `200 OK` + `HelpReply` (mapped result)|
| `FAILED` (incl. iteration-limit)    | `422 Unprocessable Content` + reason  |
| anything else / unknown / exception | `404 Not Found` ("not ready")         |

## Notes on status choice

`202 Accepted` (not `201`) because the answer resource is **not** ready at POST time — it is accepted for
asynchronous processing, and `Location` points at the eventual result. `404` covers both "still running"
and "unknown id"; callers poll until `200` (or `422`). Task **failure** maps to `422` — deliberately
distinct from `404`, so a caller can tell "the agent tried and could not answer" from "not ready yet".
