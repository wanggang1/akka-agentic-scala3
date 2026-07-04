# Contract: Greeting Team API (capability 2)

Async, workflow-backed greeting. Two endpoints on `GreetingTeamEndpoint`, base path `/greetings`.
Capability 1's `POST /greet` is a **separate, unchanged** endpoint — this contract does not touch it.

The pinned assertions (C1–C9) are what the endpoint integration test enforces.

## C1 — Start a greeting → 202 Accepted + Location

```
POST /greetings
Content-Type: application/json
{ "user": "Ada", "text": "How do I reset my password?", "timezone": "America/New_York" }
```

Response:
```
202 Accepted
Location: /greetings/<workflowId>
{ "id": "<workflowId>" }
```

- The workflow is started with a fresh id (UUID); the response returns immediately — **no** greeting
  yet. `timezone` is optional.

## C2 — Retrieve before completion → not ready (no fabricated greeting)

```
GET /greetings/<workflowId>      (immediately after C1, still running)
```
Response: `404 Not Found` (body: a short "not ready" message). Distinct from success; never a partial
or invented greeting.

## C3 — Retrieve after completion → 200 with the structured greeting

```
GET /greetings/<workflowId>      (after both steps complete)
```
Response:
```
200 OK
{ "greeting": "Good evening, Ada — happy to help…", "tone": "question", "timeOfDay": "evening" }
```
- All three fields present. `tone` reflects the label from the **tone step**; `timeOfDay` reflects the
  supplied timezone (here evening in `America/New_York`).

## C4 — Unknown handle → not ready / not found

```
GET /greetings/does-not-exist
```
Response: `404 Not Found`. An id that was never started never yields a greeting.

## C5 — Tone reflects intent (question)

A question/help message (as in C1) yields a question-ish `tone` and a warm, help-acknowledging
greeting — not a fixed template. Enforced with a mocked tone model returning `"question"`.

## C6 — Tone reflects intent (casual)

```
POST /greetings   { "user": "Ada", "text": "hey there" }        (no timezone → UTC)
```
After completion, `tone` is casual and the greeting is casual/upbeat. `timeOfDay` computed for UTC.

## C7 — Blank user or text → 400, no workflow started

```
POST /greetings   { "user": "", "text": "hi" }
POST /greetings   { "user": "Ada", "text": "   " }
```
Response: `400 Bad Request` (validation message). No workflow instance is created; the model is never
called.

## C8 — Malformed body → 400

```
POST /greetings   { "user":                 (truncated / invalid JSON)
```
Response: `400 Bad Request` (SDK rejects the unparseable body before the handler).

## C9 — Unknown JSON properties tolerated

```
POST /greetings   { "user": "Ada", "text": "hi", "nickname": "Countess" }
```
The extra property is ignored (`@JsonIgnoreProperties(ignoreUnknown = true)`); the greeting proceeds
normally.

## Durability (not a status-code contract, exercised by the workflow test)

- If the **tone step** fails terminally (after bounded retries), the workflow fails over to a neutral
  tone and still completes — `GET` eventually returns `200` with `tone: "neutral"`.
- If the **compose step**'s model returns non-JSON, the composer's `onFailure` yields a safe greeting
  (names the user, carries the detected tone, time-of-day computed directly) — still `200`, never a
  `500`.

## Notes on status choice

`202 Accepted` (not `201`) because the greeting resource is **not** ready at POST time — it is accepted
for asynchronous processing, and the `Location` points at the eventual result. `404` is used for both
"still running" and "unknown id"; callers poll until `200`. (A `200 {status:"pending"}` shape was
considered but rejected as heavier than needed for this learning feature.)
