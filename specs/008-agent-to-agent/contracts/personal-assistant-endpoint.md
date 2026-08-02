# Contract: PersonalAssistantEndpoint

HTTP surface for capability 6. One synchronous endpoint. Base package
`com.gwgs.akkaagentic.a2a.api`. Secured with `@Acl(allow = @Acl.Matcher(principal = INTERNET))` like the
other capabilities' public endpoints.

## `POST /request/{username}`

Send a message to `{username}`'s personal assistant and get the reply synchronously. The assistant may,
on its own, use to-do tools or delegate to another user's assistant before replying.

### Path parameters

| Name | Type | Rules |
|---|---|---|
| `username` | string | non-blank (trimmed). Blank → `400`. Any string is a valid assistant handle; an unseen username starts fresh. |

### Request body

```json
{ "message": "add a to-do to buy milk" }
```

| Field | Type | Rules |
|---|---|---|
| `message` | string | required, non-blank (trimmed). Absent/blank → `400`. |

`delegated` is **not** part of this contract — it is an internal, agent-to-agent concern (FR-005).
Unknown extra properties are tolerated.

### Responses

| Status | When | Body |
|---|---|---|
| `200 OK` | assistant replied | `{ "username": "alice", "reply": "..." }` |
| `400 Bad Request` | blank username, blank/absent `message`, or malformed JSON | plain-text reason, e.g. `message must not be blank`; **no model call** |

### Semantics

- **Synchronous**: the reply is returned in the response; there is no polling handle.
- **Delegation is transparent**: if the message asks to involve another user's assistant, the reply
  contains that assistant's answer (relayed verbatim with a brief attribution). The effect (e.g. a to-do
  added) lands under the **target** username.
- **One hop**: a request that arrives *as a delegate* cannot delegate onward (structural guard).
- **Isolation**: `{username}` scopes both the chat history and the to-do list; different usernames never
  share state.
- **Durability**: chat history and to-dos survive a restart; an in-flight request is not resumed — the
  caller retries (sync + retry, research.md R3).

### Examples

```shell
# Own to-do — add
curl -i -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" \
  -d '{"message":"add a to-do to buy milk"}'
# 200 OK — {"username":"alice","reply":"Added \"buy milk\" as item 1."}

# Own to-do — list
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" \
  -d '{"message":"what is on my list?"}'
# {"username":"alice","reply":"1. buy milk (open)"}

# Delegation — alice asks bob's assistant to add a to-do (lands under BOB)
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" \
  -d '{"message":"ask bob'\''s assistant to add a to-do: prepare slides"}'
# {"username":"alice","reply":"Bob's assistant: Added \"prepare slides\" as item 1."}

curl -s -X POST http://localhost:9000/request/bob \
  -H "Content-Type: application/json" \
  -d '{"message":"what is on my list?"}'
# {"username":"bob","reply":"1. prepare slides (open)"}   <- landed under bob, not alice

# Validation — blank message, no model call
curl -i -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — message must not be blank
```
