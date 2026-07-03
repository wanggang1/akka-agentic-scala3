# Data Model: Scala-native JSON for wire types

This feature changes the **shape** (not the meaning) of existing wire types and adds one startup
class. No persisted state, events, or domain types are introduced or altered.

## New: Bootstrap (service setup)

| Aspect | Detail |
|--------|--------|
| Type | `com.gwgs.akkaagentic.application.Bootstrap` |
| Role | `@Setup` class implementing `akka.javasdk.ServiceSetup` (exactly one per service) |
| Responsibility | In `onStartup`, register `DefaultScalaModule` on `JsonSupport.getObjectMapper()` — once per instance |
| State | None (stateless; no mutable fields) |
| Discovery | Hand-maintained descriptor entry `akka.javasdk.service-setup = "…Bootstrap"` (see research R1) |

Idempotency note: `onStartup` runs once per service instance; `registerModule` on the same mapper
is effectively idempotent for our purpose (re-registering the same module is harmless).

## Converted wire types (annotation-free Scala case classes)

### `GreetingEndpoint.GreetRequest` — inbound HTTP body

| Field | Before (Java-shaped) | After (idiomatic) |
|-------|----------------------|-------------------|
| user | `@JsonProperty String` (nullable) | `Option[String]` |
| text | `@JsonProperty String` (nullable) | `Option[String]` |
| timezone | `@JsonProperty String = null` | `Option[String]` |

- Drops `@JsonCreator`, `@JsonProperty`. Keeps `@JsonIgnoreProperties(ignoreUnknown = true)`
  (unknown-field tolerance is a contract behavior, not a Scala/Java concern — see contract).
- Absent or explicit-null JSON → `None`; present → `Some`. The endpoint no longer wraps fields in
  `Option(...)`.

### `GreetingEndpoint.GreetReply` — outbound HTTP body

| Field | Before | After |
|-------|--------|-------|
| greeting | `@JsonProperty String` | `String` |
| tone | `@JsonProperty String` | `String` |
| timeOfDay | `@JsonProperty String` | `String` |

- All fields always present → plain `String` (no `Option`). Drops all Jackson annotations.
- Still an API-owned type distinct from `GreetingAgent.Result` (API isolation, constitution II).

> **Amended (research R6):** the two `GreetingAgent` types below were **reverted to Java-shaped**
> and are NOT converted. They travel the component-to-component serializer (a separate internal
> mapper the Scala module can't reach), so annotation-free `Option` types fail at runtime there.
> Only the endpoint DTOs above (`GreetRequest`/`GreetReply`) are converted. The tables below record
> the *attempted* conversion; the shipped types keep `@JsonCreator`/`@JsonProperty` and a nullable
> `String` timezone.

### `GreetingAgent.Request` — agent command parameter (KEPT Java-shaped)

| Field | Before | After |
|-------|--------|-------|
| user | `@JsonProperty String` | `String` (always present — endpoint already validated) |
| text | `@JsonProperty String` | `String` |
| timezone | `@JsonProperty String = null` | `Option[String]` |

- Drops `@JsonCreator`/`@JsonProperty`. `timezoneLine` in the agent consumes `Option[String]`
  directly (no `Option(...)` wrap).

### `GreetingAgent.Result` — agent structured reply (parsed from model text via `responseAs`)

| Field | Before | After |
|-------|--------|-------|
| greeting | `@JsonProperty @Description String` | `String` (+ keep `@Description`? see note) |
| tone | `@JsonProperty @Description String` | `String` |
| timeOfDay | `@JsonProperty @Description String` | `String` |

- Drops `@JsonCreator`/`@JsonProperty`. **`@Description` is unused by `responseAs`** (it fed the
  `responseConformsTo` schema, which we do not use); remove it to avoid implying a schema. The
  JSON shape the model must emit is already spelled out in the agent's system message.

## Unchanged (deliberately)

| Type | Why kept as-is |
|------|----------------|
| `HealthEndpoint.Health` | **US3 coexistence witness** — stays `@JsonCreator`/`@JsonProperty`; its `GET /health` test proves annotated types still work after registration (SC-006). |
| `domain.GreetingRequest`, `ValidGreeting`, `TimeOfDay`, `GreetingResponse` | Already idiomatic `Option`-based domain; no wire role changed. |

## Validation & invariants (unchanged behavior)

- Blank/absent `user` or `text` → domain validation returns `Left(...)` → endpoint `400`, no model
  call. Blankness remains a **domain** rule; `Option` only removes the `null` representation.
- The `null → None` conversion previously done at the endpoint/agent boundary is now performed by
  the Scala module during deserialization, so the boundary code is deleted (FR-005, SC-002).
