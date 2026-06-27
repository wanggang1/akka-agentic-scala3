# HTTP Contract: Greeting API

Single endpoint exposed by `GreetingEndpoint` (`com.gwgs.akkaagentic.api`). Transport: HTTP/JSON
(clarified). Access: annotated with `@Acl` allowing the Internet principal for the baseline.

## POST /greet

Submit a typed greeting request; receive a personalized, LLM-composed greeting.

### Request

- **Method**: `POST`
- **Path**: `/greet`
- **Content-Type**: `application/json`
- **Body** (`GreetRequest`):

```json
{
  "user": "Ada",
  "text": "hello there"
}
```

| Field | Type | Required | Constraint |
|-------|------|----------|------------|
| `user` | string | Yes | non-blank |
| `text` | string | Yes | non-blank |

### Responses

| Status | When | Body |
|--------|------|------|
| `200 OK` | Valid request; greeting composed | `GreetReply` |
| `400 Bad Request` | `user` or `text` missing/blank (FR-003/FR-004/FR-005), or body not valid JSON for `GreetRequest` (FR-006) | error message |

**200 body** (`GreetReply`):

```json
{
  "greeting": "Hello Ada! Lovely to hear from you — \"hello there\" right back at you."
}
```

**400 body** (validation failure, e.g. empty `user`):

```text
user must not be blank
```

### Behavior notes

- The endpoint deserializes `GreetRequest`, builds a domain `GreetingRequest` and runs its
  validation. On failure it returns `400` via `HttpResponses.badRequest(...)` **without** calling
  the agent (no model invocation on invalid input).
- On success it calls `GreetingAgent` via `ComponentClient`
  (`forAgent().inSession(<uuid>).method(GreetingAgent::greet).invoke(...)`) using a fresh session
  id per request (stateless, FR-007), and wraps the returned greeting string in `GreetReply`.
- The endpoint never returns domain types directly (Constitution II — API isolation).
- Malformed JSON / wrong shape: the `400` for a body that fails `GreetRequest` deserialization is
  **assumed** to be produced by the SDK's request decoding. This must be verified during T011; if
  the SDK returns a different status, the endpoint adds explicit handling to return `400`.
- `GreetRequest` is configured to ignore unknown JSON properties, so extra/unexpected fields are
  accepted and ignored (spec.md → Edge Case Handling).

### Example

```bash
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hello there"}'
```

### Contract test mapping

| Scenario (spec) | Contract assertion | Test |
|-----------------|--------------------|------|
| US1 success | `200` + non-empty `greeting` naming the user | `GreetingEndpointIntegrationTest` (success) |
| US2 empty user | `400` + validation message, no greeting | `GreetingEndpointIntegrationTest` (failure) |
| FR-006 malformed body | `400` | `GreetingEndpointIntegrationTest` (optional malformed case) |
