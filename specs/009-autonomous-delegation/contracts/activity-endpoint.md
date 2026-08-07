# HTTP Contract: ActivityEndpoint

**Feature**: 009-autonomous-delegation · **Date**: 2026-08-07
**Base path**: `/activities` · **ACL**: `@Acl(allow = @Acl.Matcher(principal = INTERNET))`
**Style**: start-then-poll (async), validation-first — mirrors cap-3's `/help`.

## POST /activities — start a coordination

Starts the coordinator on a `SUGGEST` task and returns a handle immediately.

**Request body** (`StartRequest`, idiomatic Scala):
```json
{ "location": "Boston", "preferences": "outdoorsy, with kids" }
```
- `location` (required, non-blank). `preferences` (optional).

**Responses**:
| Status | When | Body |
|---|---|---|
| `202 Accepted` | valid request; task started | `{ "taskId": "…" }` + `Location: /activities/{taskId}` |
| `400 Bad Request` | blank/absent `location`, or malformed JSON | plain error message; **no task started** |

Unknown extra JSON properties are tolerated (ignored).

## GET /activities/{taskId} — poll for the result

**Responses**:
| Status | When | Body |
|---|---|---|
| `200 OK` | task COMPLETED | `SuggestionReply` (below) |
| `404 Not Found` | task not terminal yet, OR unknown/never-issued id | plain "not ready"/"not found" |
| `422 Unprocessable Content` | coordinator reported it cannot complete the task | plain reason |

**`SuggestionReply`** (200 body, idiomatic Scala; empty optionals omitted):
```json
{
  "suggestion": "With clear skies around 20°C, try the Boston Common playground then a harbor walk.",
  "weatherConsidered": "Clear, ~20°C",
  "consultedSpecialists": ["weather-specialist", "activity-specialist"]
}
```
- `consultedSpecialists` reflects which delegates the coordinator chose (US2/SC-002); it may contain one or
  both ids depending on the request.

## Contract test scenarios (→ integration tests)

1. **Happy path (P1/SC-001)**: POST valid → 202 + `taskId`; poll until 200; body's `suggestion` reflects the
   location's simulated weather + preferences; `consultedSpecialists` non-empty.
2. **Dynamic delegation (P2/SC-002)**: two differently-shaped requests produce results whose
   `consultedSpecialists` differ in ≥1 case (coordinator chose delegates, not a fixed set).
3. **Synthesis (SC-003)**: for a request warranting both, the suggestion reflects both weather and activity
   input (not a verbatim relay of one specialist).
4. **Validation (P3/FR-007)**: POST blank/absent `location` → 400, no task; malformed JSON → 400; unknown
   extra property → tolerated (202).
5. **Poll semantics (FR-006/008)**: unknown `taskId` → 404; in-progress → 404 until terminal.
6. **Cannot-complete (FR-009)**: a task the coordinator fails/can't complete → 422 (distinct from 404/200).

All offline via `TestModelProvider` (per-agent), except recall-of-live-delegation which is a live smoke if
offline delegation mocking proves infeasible (research D9).
