# Data Model: Multi-agent greeting Workflow

All cap-2 types are **Java** (`com.gwgs.akkaagentic.team.*`). Component payloads (agent request/result,
workflow state, workflow reply) are Java records — **Java-shaped by construction**, so they serialize
cleanly through the SDK's internal component serializer with no annotations and no two-mapper concern
(research R3). HTTP DTOs are also Java records, deserialized by the public HTTP mapper.

## Domain (pure Java, no Akka deps) — `team/domain`

### `TimeOfDay`
A copy of cap-1's time-of-day logic (deliberate duplication, research R3 / plan Complexity Tracking).

- `static String of(Instant instant, ZoneId zone)` — label by local hour: morning 05–11, afternoon
  12–16, evening 17–20, night otherwise. Total; never throws.
- `static String now(String timezone)` — current label for an IANA id; **null/blank/invalid → UTC**
  (`ZoneId.of` wrapped so it never throws). Java takes a nullable `String` at this boundary (the Scala
  version takes `Option`; here plain null-handling is idiomatic Java).

### `Tone`
- `static final String NEUTRAL = "neutral"`
- `static String normalize(String raw)` — trim; if null/blank → `NEUTRAL`; else the cleaned label.
  Parse-don't-validate for the tone label so downstream never re-checks.

## Component payloads (Java records) — `team/application`

### `StartGreeting` (workflow command)
`record StartGreeting(String user, String text, String timezone)` — the validated inputs handed to the
workflow. `user`/`text` are guaranteed non-blank (endpoint validated first); `timezone` nullable.

### `ComposeRequest` (composer agent input)
`record ComposeRequest(String user, String text, String tone, String timezone)` — carries the
pre-detected `tone` so the composer does not re-classify.

### `GreetingResult` (composer agent + workflow reply)
`record GreetingResult(String greeting, String tone, String timeOfDay)` — the structured result the
composer parses from the model, stored in workflow state, and returned by `getResult`. Same shape the
endpoint maps to its `GreetReply`.

*(`ToneAgent` takes a plain `String text` and replies a plain `String` label — no wrapper record.)*

## Workflow state — `GreetingWorkflow.State` (Java record)

`record State(String user, String text, String timezone, String tone, GreetingResult result, String status)`

| Field | Meaning |
|-------|---------|
| `user`, `text`, `timezone` | the request (timezone nullable) |
| `tone` | filled after the tone step (or `Tone.NEUTRAL` on failover); null before |
| `result` | filled after the compose step; null before |
| `status` | progress marker (see below) |

Helper methods (immutable `with*`):
- `withTone(String tone)` → status `TONE_DETECTED`
- `withResult(GreetingResult r)` → status `COMPLETED` (carries `r.tone()` as the final tone)
- `failed()` → status `FAILED`
- `boolean isComplete()` → `status == COMPLETED`

`status` is a plain `String` constant set — `STARTED → TONE_DETECTED → COMPLETED`, or `FAILED` — not a
Scala `enum` (the internal serializer has no Scala module; a Java enum would also work, but a String
keeps the record trivially serializable and matches the durable-state style).

### State machine

```text
            start(StartGreeting)
                 │  updateState(STARTED)
                 ▼
        ┌───────────────┐   agent: ToneAgent::detect (shared session = workflowId)
        │   toneStep    │──────────────────────────────► tone label → Tone.normalize
        └───────┬───────┘   updateState(TONE_DETECTED)
                │                         ▲
   (retry ≤2, then failover)             │ neutral tone
                ▼                         │
        ┌───────────────┐        ┌────────┴────────┐
        │ toneFallback  │───────►│   composeStep   │  agent: GreetingComposerAgent::compose
        └───────────────┘        └────────┬────────┘  (has @FunctionTool time; responseAs + onFailure)
                                          │  updateState(COMPLETED, result)
                                          ▼
                                        thenEnd

  default step recovery: maxRetries(1) → failedStep → updateState(FAILED) → thenEnd
  getResult(): COMPLETED → reply(result); otherwise → error("not ready")  [endpoint maps to 404]
```

Steps wired with native Java method references (`GreetingWorkflow::toneStep`, …) — the whole reason
this module is Java (research R1). `sessionId()` = `commandContext().workflowId()` so both agents share
one session (FR-007).

## HTTP DTOs — `GreetingTeamEndpoint` (Java records)

### `StartRequest` (inbound)
`record StartRequest(String user, String text, String timezone)`, annotated
`@JsonIgnoreProperties(ignoreUnknown = true)`. Validated in the endpoint before starting the workflow:
`user`/`text` must be non-null and non-blank → else `400`; `timezone` optional (null/blank/invalid →
UTC downstream, never a validation error).

### `GreetReply` (outbound)
`record GreetReply(String greeting, String tone, String timeOfDay)` — API-owned type mirroring
`GreetingResult`, kept distinct so the wire contract is independent of the workflow/agent types
(Constitution II, API isolation).

### `StartAccepted` (outbound, POST)
`record StartAccepted(String id)` — the workflow id, returned with the `Location` header so the caller
can poll `GET /greetings/{id}`.

## Reuse vs. duplication

| Concern | cap-1 (Scala) | cap-2 (Java) |
|---------|---------------|--------------|
| time-of-day | `domain.TimeOfDay` (`Option`) | `team.domain.TimeOfDay` (nullable) — **duplicated** (R3) |
| input validation | `GreetingRequest.validate: Either` | inline non-blank check in the endpoint |
| tone | detected inline by the single agent | `ToneAgent` + `Tone.normalize` |
| wire shaping | Scala `@JsonCreator` case classes (agent) / idiomatic `Option` (HTTP) | Java records throughout |
