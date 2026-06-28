# Data Model: Structured, context-aware greeting

All types are immutable Scala records. Wire types (crossing HTTP or the model boundary) carry
explicit Jackson annotations (`@JsonCreator` / `@JsonProperty`, and `@JsonIgnoreProperties` on
inbound) per 001 research R3. Layers follow the constitution: `domain` has no Akka imports; the
endpoint never exposes `domain`/`application` types.

## Domain layer (`com.gwgs.akkaagentic.domain`)

### GreetingRequest (existing, extended)
Reused from 001. Fields: `user: String`, `text: String`. Optional `timezone` is **not** added to
the domain validation type — timezone is contextual, not validated input; it rides on the API/agent
types. `validate(): Either[String, GreetingRequest]` unchanged (rejects blank `user`/`text`).

### TimeOfDay (new — pure function, no state)
A total, side-effect-free helper. No Akka imports.

| Member | Signature | Behavior |
|--------|-----------|----------|
| `of` | `of(instant: Instant, zone: ZoneId): String` | Coarse label from the local hour |
| `now` | `now(timezone: String): String` | `of(Instant.now(), resolveZone(timezone))` |
| `resolveZone` (private) | `(tz: String): ZoneId` | Blank/invalid → `Default` (UTC) |
| `Default` | `ZoneId` | `ZoneOffset.UTC` |

**Labels & boundaries** (local hour in the zone):
- `morning` 05–11, `afternoon` 12–16, `evening` 17–20, `night` 21–04.
- Never throws; an unresolvable timezone falls back to `Default` (so the result is always one of the
  four labels). The string `"unknown"` is reserved as a documented fallback if a future caller path
  cannot determine a time at all — not produced by `now`/`of` today.

**Validation rules**: none (total function). **State transitions**: none.

## Application layer (`com.gwgs.akkaagentic.application`)

### GreetingAgent.Request (existing, extended)
Inbound to the agent. Adds optional `timezone`.

| Field | Type | Notes |
|-------|------|-------|
| `user` | `String` | Jackson `@JsonProperty("user")` |
| `text` | `String` | Jackson `@JsonProperty("text")` |
| `timezone` | `String` | Jackson `@JsonProperty("timezone")`; empty string = default zone |

### GreetingAgent.Result (new — structured model output)
The type the model conforms to via `responseConformsTo`. Jackson-annotated.

| Field | Type | Meaning |
|-------|------|---------|
| `greeting` | `String` | The personalized greeting text, names the user |
| `tone` | `String` | Detected intent/tone label, free-form (e.g. `casual`, `question`) |
| `timeOfDay` | `String` | The time-of-day label the tool provided |

## API layer (`com.gwgs.akkaagentic.api`)

### GreetingEndpoint.GreetRequest (existing, extended)
Inbound HTTP body. `@JsonIgnoreProperties(ignoreUnknown = true)`. Adds optional `timezone`.

| Field | Type | Required | Constraint |
|-------|------|----------|------------|
| `user` | `String` | Yes | non-blank (domain `validate`) |
| `text` | `String` | Yes | non-blank (domain `validate`) |
| `timezone` | `String` | No | IANA id; blank/invalid → default zone |

### GreetingEndpoint.GreetReply (existing, extended)
Outbound HTTP body — the endpoint's own type (API isolation). Mapped from `GreetingAgent.Result`.

| Field | Type | Source |
|-------|------|--------|
| `greeting` | `String` | `Result.greeting` |
| `tone` | `String` | `Result.tone` |
| `timeOfDay` | `String` | `Result.timeOfDay` |

## Flow

```text
POST /greet {user, text, timezone?}
  → GreetRequest                       (api)
  → GreetingRequest.validate           (domain)  ── Left ─→ 400, no model call
        │ Right
  → GreetingAgent.Request(user, text, timezone)   (application, via dynamicCall)
        │  agent: systemMessage + userMessage
        │         + @FunctionTool currentTimeOfDay(timezone) → TimeOfDay.now (domain)
        │         + responseConformsTo(Result)
  → GreetingAgent.Result {greeting, tone, timeOfDay}
  → GreetReply {greeting, tone, timeOfDay}         (api)  ── 200
```
