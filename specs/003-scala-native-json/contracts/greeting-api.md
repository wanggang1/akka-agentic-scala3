# Contract: `POST /greet` (pinned — MUST be unchanged by feature 003)

This feature is a serialization refactor. The externally observable HTTP contract carries over
from feature 002 **verbatim**; it is restated here as the regression oracle (FR-006, SC-003). If
any item below changes, the feature has failed.

## Request

`POST /greet`, `Content-Type: application/json`

```json
{ "user": "Ada", "text": "How do I reset my password?", "timezone": "America/New_York" }
```

- `user` (string, required, non-blank)
- `text` (string, required, non-blank)
- `timezone` (string, optional; IANA id; blank/invalid/absent → UTC — never a validation error)
- Unknown properties are ignored (accepted, not rejected).
- Absent or explicit-`null` `user`/`text`/`timezone` are accepted at the JSON layer (become
  "absent" internally); blankness of `user`/`text` is then rejected by validation.

## Responses

### 200 OK — structured reply

```json
{ "greeting": "Good morning, Ada! …", "tone": "question", "timeOfDay": "morning" }
```

- All three fields present and non-empty.
- `timeOfDay` reflects the caller's `timezone` when supplied, else UTC.

### 400 Bad Request — validation failure, **no model call**

Triggered by blank/absent `user` or `text`, or a malformed JSON body. Body is a plain message,
e.g. `user must not be blank`. The model provider is never invoked.

## Invariants the refactor must preserve

| # | Invariant | How verified |
|---|-----------|--------------|
| C1 | Valid request (with `timezone`) → `200` + all 3 fields | endpoint integration test + live smoke |
| C2 | Valid request (without `timezone`) → `200`, `timeOfDay` via UTC | endpoint integration test |
| C3 | Blank/absent `user` → `400`, no model call | existing test `emptyUserIsRejected` |
| C4 | Blank `text` → `400`, no model call | existing test `blankTextIsRejected` |
| C5 | Malformed JSON → `400`, no model call | existing test `malformedJsonIsRejected` |
| C6 | Unknown JSON field tolerated | `@JsonIgnoreProperties(ignoreUnknown = true)` retained |
| C7 | `GET /health` → `200 {"status":"ok"}` (Java-shaped type still works) | existing `HealthEndpointIntegrationTest` |

## Notes

- The reply fields are all required (no `Option`), so `None`-rendering semantics of the Scala
  module affect only request **deserialization** (absent/null → `None`), which is the intended
  behavior — not the response contract.
- C7 is the US3 coexistence check: `HealthEndpoint.Health` stays annotated on purpose.
