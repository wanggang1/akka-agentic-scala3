# HTTP Contract: Chat API (capability 4)

Base: `http://localhost:9000`. One synchronous endpoint — no start-then-poll (contrast cap-2/cap-3).
The conversation id is **caller-supplied in the path**; reusing it across requests is the feature.

## `POST /chat/{sessionId}`

Send one message on conversation `{sessionId}` and receive the assistant's reply. The runtime records
this turn (user + reply) in the session's memory and replays prior turns as context.

**Path parameter**

| Name | Type | Notes |
|------|------|-------|
| `sessionId` | string | Opaque, caller-chosen. A never-seen id starts a new, empty conversation (FR-005). |

**Request body**

```json
{ "message": "my name is Ada" }
```

- `message` — required, non-blank. Absent/null/blank → `400` (FR-006). Unknown extra fields are
  ignored (FR-008).

**Responses**

| Status | When | Body |
|--------|------|------|
| `200 OK` | message accepted and answered | `ChatReply` (below) |
| `400 Bad Request` | blank/absent `message`, or malformed JSON body | plain-text reason (e.g. `message must not be blank`) |

`ChatReply`:

```json
{ "sessionId": "c-123", "reply": "Nice to meet you, Ada!" }
```

- `sessionId` echoes the path id so the caller can correlate and continue (FR-010).
- `reply` is the assistant's answer for this turn.

**Notes**

- **Synchronous**: `200` carries the reply directly; there is no polling.
- **Memory is implicit**: nothing in the request references history — the runtime supplies it from the
  session keyed by `{sessionId}`.

## Worked example — multi-turn recall (US1)

```shell
# Turn 1 — state a fact on conversation c-123
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"my name is Ada"}'
# 200 OK
# {"sessionId":"c-123","reply":"Nice to meet you, Ada!"}

# Turn 2 — ask about it on the SAME id; the reply recalls turn 1
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK
# {"sessionId":"c-123","reply":"Your name is Ada."}
```

## Worked example — isolation (US2)

```shell
# A different id has no knowledge of c-123's conversation
curl -i -X POST http://localhost:9000/chat/c-999 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK
# {"sessionId":"c-999","reply":"I don't think you've told me your name yet."}
```

## Worked example — validation (US3)

```shell
# Blank message — rejected before the assistant is engaged
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"  "}'
# 400 Bad Request
# message must not be blank

# Malformed body — rejected at the boundary
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":'
# 400 Bad Request

# Extra unknown field alongside a valid message — tolerated
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"hi","debug":true}'
# 200 OK
```

## Contract test checklist (maps to endpoint integration tests)

| ID | Scenario | Expect | Story |
|----|----------|--------|-------|
| C1 | Two turns, same id: state then ask | turn-2 `200`, reply reflects turn-1 fact | US1 |
| C2 | Ask on a fresh id after a fact stated elsewhere | `200`, reply does not know the fact | US2 |
| C3 | Blank/whitespace `message` | `400`, no model call | US3 |
| C4 | Absent `message` field | `400`, no model call | US3 |
| C5 | Malformed JSON body | `400` | US3 |
| C6 | Valid `message` + unknown extra field | `200` | US3 |
| C7 | Reply echoes the path `sessionId` | `200`, `sessionId` matches path | US1 |
