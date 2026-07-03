# HTTP Contract: Greeting API (v2 — structured response)

Extends the 001 contract. Same endpoint (`POST /greet`, `GreetingEndpoint`,
`com.gwgs.akkaagentic.api`), Internet-allowed ACL. The **response shape changes** from a single
`greeting` string to a structured object, and the request gains an optional `timezone`.

## POST /greet

### Request

- **Method**: `POST` · **Path**: `/greet` · **Content-Type**: `application/json`
- **Body** (`GreetRequest`):

```json
{
  "user": "Ada",
  "text": "How do I reset my password?",
  "timezone": "America/New_York"
}
```

| Field | Type | Required | Constraint |
|-------|------|----------|------------|
| `user` | string | Yes | non-blank |
| `text` | string | Yes | non-blank |
| `timezone` | string | No | IANA zone id; blank/omitted/invalid → default zone (UTC) |

### Responses

| Status | When | Body |
|--------|------|------|
| `200 OK` | Valid request; greeting composed | `GreetReply` (structured) |
| `400 Bad Request` | `user`/`text` blank, or body not valid JSON for `GreetRequest` | error message |

**200 body** (`GreetReply`):

```json
{
  "greeting": "Good evening, Ada — happy to help you reset your password!",
  "tone": "question",
  "timeOfDay": "evening"
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `greeting` | string | Personalized greeting; names the user |
| `tone` | string | Detected intent/tone label (e.g. `casual`, `question`) |
| `timeOfDay` | string | Coarse time-of-day: `morning`/`afternoon`/`evening`/`night` |

**400 body** (validation failure, e.g. empty `user`):

```text
user must not be blank
```

### Behavior notes

- Validation (`GreetingRequest.validate`) runs first; on failure the endpoint returns `400` via
  `HttpResponses.badRequest(...)` **without** calling the model (unchanged from 001/US2).
- On success the endpoint calls `GreetingAgent` via `ComponentClient`
  (`dynamicCall[Request, Result]("greeting-agent")`, fresh session id per request). The agent
  invokes a `@FunctionTool` to obtain the time-of-day and returns a structured `Result`, which the
  endpoint maps to its own `GreetReply` (API isolation — never returns the agent type directly).
- `timeOfDay` is derived from the actual request-time clock in the resolved zone; an invalid or
  omitted `timezone` falls back to UTC (the request still succeeds).
- Malformed JSON / wrong shape → `400` from the SDK decode layer (verified in 001); no custom
  handling. `GreetRequest` ignores unknown properties.

### Contract test mapping

| Scenario (spec) | Contract assertion | Test |
|-----------------|--------------------|------|
| US1 structured success | `200` + `GreetReply` with all 3 fields present/non-empty, names user | `GreetingEndpointIntegrationTest` (structured success) |
| US2 time-of-day | `timeOfDay` matches controlled context; morning ≠ evening | `GreetingAgentTest` (structured + intent) and `GreetingTest` (`TimeOfDay` boundaries) |
| US2 invalid/blank timezone | request still `200`, falls back to default zone | `GreetingTest` (`TimeOfDay` invalid-zone fallback) |
| US3 empty user | `400`, no model call | `GreetingEndpointIntegrationTest.emptyUserIsRejected` |
| US3 blank text | `400`, no model call | `GreetingEndpointIntegrationTest.blankTextIsRejected` |
| US3 malformed body | `400` (SDK decode default) | `GreetingEndpointIntegrationTest.malformedJsonIsRejected` |
