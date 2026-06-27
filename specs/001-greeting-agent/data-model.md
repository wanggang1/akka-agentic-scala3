# Phase 1 Data Model: Greeting Agent Service Baseline

The baseline is **stateless** — there is no persisted entity, journal, or view. The "data model"
here is the set of in-memory typed messages that flow through the request, expressed as Scala 3
case classes across the three layers.

## Layer ownership

| Layer | Type | Purpose | Akka deps? |
|-------|------|---------|------------|
| `api` (`GreetingEndpoint`) | `GreetRequest`, `GreetReply` | HTTP wire types; isolate the API from domain internals | Jackson annotations only |
| `domain` (`Greeting`) | `GreetingRequest`, `GreetingResponse` | Validated domain values + business rules | None |
| `application` (`GreetingAgent`) | `GreetingAgent.Request` | Single parameter passed to the agent command handler | Inner type of an Akka component |

> API and domain types are kept separate on purpose (Constitution II — API isolation). The
> endpoint converts `GreetRequest` → domain `GreetingRequest` (validating) → `GreetingAgent.Request`,
> and converts the agent's `String` reply → `GreetReply`.

## Domain types (`com.gwgs.akkaagentic.domain`)

### `GreetingRequest`

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `user` | `String` | Yes | non-null, non-blank (FR-003) |
| `text` | `String` | Yes | non-null, non-blank (FR-004) |

- **Business logic (lives on the type, Constitution II)**:
  - `validate(): Either[String, GreetingRequest]` (or a smart `apply`/factory) returns a
    validation error message when `user` or `text` is blank, otherwise the valid request.
  - No mutation methods — the request is immutable.
- **No Akka imports.** Pure Scala; unit-testable in isolation.

### `GreetingResponse`

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `greeting` | `String` | Yes | The personalized greeting text produced by the agent |

- Immutable value wrapping the greeting string. Optional in the baseline (the agent returns a
  plain `String`), but defined so the domain has an explicit result type the endpoint can map
  from.

## API wire types (`com.gwgs.akkaagentic.api`, inner to `GreetingEndpoint`)

### `GreetRequest` (HTTP request body)

```jsonc
{ "user": "Ada", "text": "hello there" }
```

| JSON field | Type | Required |
|------------|------|----------|
| `user` | string | Yes |
| `text` | string | Yes |

- Scala 3 `case class GreetRequest @JsonCreator() (@JsonProperty("user") user: String, @JsonProperty("text") text: String)`.

### `GreetReply` (HTTP response body)

```jsonc
{ "greeting": "Hello Ada! ..." }
```

| JSON field | Type | Required |
|------------|------|----------|
| `greeting` | string | Yes |

- Scala 3 `case class GreetReply(@JsonProperty("greeting") greeting: String)` with `@JsonCreator`.

## Agent parameter (`com.gwgs.akkaagentic.application`, inner to `GreetingAgent`)

### `GreetingAgent.Request`

| Field | Type | Notes |
|-------|------|-------|
| `user` | `String` | Already validated by the time it reaches the agent |
| `text` | `String` | Already validated |

- Single wrapper parameter for the one command handler (`greet`). Annotated for Jackson because
  the agent boundary is serialized.

## Validation rules → requirement traceability

| Rule | Source | Enforced in |
|------|--------|-------------|
| `user` non-blank | FR-003, SC-002 | `GreetingRequest.validate` (called by endpoint) |
| `text` non-blank | FR-004, SC-002 | `GreetingRequest.validate` (called by endpoint) |
| Malformed payload → client error | FR-006 | Jackson deserialization + endpoint error response |
| Each request independent / stateless | FR-007 | No shared mutable state; new session id per request |

## State transitions

None. The service holds no durable state and performs no lifecycle transitions; every request is
handled independently (FR-007).

## Notes on Scala 3 enums

The clarified scope models the payload with **case classes** (`user`, `text` are free-form
strings). No closed-set field exists on the wire, so a Scala 3 `enum` is intentionally **not**
introduced for the baseline (Constitution IV — YAGNI). If future work models a greeting
*tone/intent* as a closed set, a Scala 3 `enum GreetingTone` is the natural fit and would be added
then.
