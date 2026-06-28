# Research: Structured, context-aware greeting

Phase 0 decisions. APIs below were verified against `akka-javasdk` 3.6.0 bytecode. This feature
builds on 001's interop research (Scala component descriptor, `dynamicCall`, Jackson wire types) —
see [001 research](../001-greeting-agent/research.md); only the deltas are recorded here.

## R1 — Structured agent response via `responseConformsTo`

**Decision**: The agent returns `Agent.Effect[Result]` where `Result` is a Jackson-annotated Scala
`case class`. Build it with:

```scala
effects()
  .systemMessage(SystemMessage)
  .userMessage(userMsg)
  .responseConformsTo(classOf[GreetingAgent.Result])
  .thenReply()
```

**Rationale**: Verified `Agent$Effect$OnSuccessBuilder.responseConformsTo(Class<T>)` returns a
`MappingResponseBuilder<T>` whose `.thenReply()` yields `Agent.Effect[T]`. `responseConformsTo` is
preferred over `responseAs` (AGENTS.md): the SDK injects the JSON schema/instructions for the model
instead of us hand-writing them into the prompt.

**Alternatives considered**: `responseAs(Class)` — rejected, requires manual JSON instructions in
the system message. Returning a `String` and parsing in the endpoint — rejected, defeats the point
and duplicates deserialization.

## R2 — Graceful fallback with `.onFailure`

**Decision**: For robustness (FR-008 at the model boundary), the structured builder exposes
`onFailure(Function<Throwable, Result>)` (verified on `MappingResponseBuilder`). We keep the happy
path simple (let a malformed structured response surface as a server error per the spec edge case)
and reserve `onFailure` as the documented hook; the **time-of-day fallback** (invalid/again zone) is
handled deterministically in the domain function (R4), not at the model boundary.

**Rationale**: Time-of-day robustness is pure logic and belongs in the domain where it is unit
tested; model-call failure is a separate, coarser concern. Keeping them separate honors Simplicity.

## R3 — `@FunctionTool` on a Scala agent method

**Decision**: Add the tool as a method on `GreetingAgent` annotated
`@FunctionTool(description = "...")`. Self-declared tool methods are auto-registered — `.tools()` is
only needed for *external* tool objects. The method takes a `timezone` string (annotate with
`@Description`) and returns the time-of-day label by delegating to the domain function.

```scala
@FunctionTool(description = "Current coarse time of day (morning/afternoon/evening/night) for an IANA timezone; pass an empty string for the default zone.")
def currentTimeOfDay(@Description("IANA timezone id, or empty for default") timezone: String): String =
  TimeOfDay.now(timezone)   // domain function, never throws
```

**Rationale**: `@FunctionTool(name, description)` and `@Description` both exist in
`akka.javasdk.annotations`. Declaring the tool on the agent keeps it cohesive and needs **no
component-descriptor change** — a tool is a method, not a discovered component.

**Open implementation check**: Java samples mark `@FunctionTool` methods `private`; Scala `private`
compiles to private bytecode. The SDK reflects over the agent's methods and sets them accessible, so
private should work — but the implementation step will confirm and fall back to package-private/public
if reflection misses it.

**Alternatives considered**: A separate tool class passed to `.tools(new TimeTools())` — rejected as
unnecessary indirection for a single, agent-specific tool (YAGNI).

## R4 — Time-of-day as a pure domain function (real, testable logic)

**Decision**: Put the computation in `domain` as a pure, total function:

```scala
object TimeOfDay:
  // morning 5–11, afternoon 12–16, evening 17–20, night 21–4
  def of(instant: Instant, zone: ZoneId): String = ...
  def now(timezone: String): String =            // resolves zone, never throws
    of(Instant.now(), resolveZone(timezone))
  private def resolveZone(tz: String): ZoneId =   // blank/invalid -> default (UTC)
    ...
```

**Rationale**: This gives **genuine deterministic test coverage** for the time logic (hour
boundaries, invalid-zone fallback) without a live model — the agent test mocks the model and never
exercises the real clock. Domain independence (Constitution II): no Akka imports. Total function
(never throws) satisfies the invalid-timezone and lookup-failure edge cases by returning the default
zone / a safe label.

**Boundaries (documented default)**: morning 05–11, afternoon 12–16, evening 17–20, night 21–04.
**Default timezone**: `UTC` (matches spec Assumptions). Invalid/blank timezone → default.

**Alternatives considered**: Computing inside the agent method — rejected, couples logic to the
framework and is harder to unit test. A fixed enum type for the label — deferred; a `String` label
keeps the model's structured output simple and matches the free-form tone field.

## R5 — Testing structured output + tools with `TestModelProvider`

**Decision**:
- Agent test: `agentModel.fixedResponse(JsonSupport.encodeToString(result))` where `result` is a
  `GreetingAgent.Result`; the mock returns the structured JSON and **short-circuits tool calls**, so
  the real clock/tool is not invoked (US2 determinism comes from the mock + the separate domain
  unit test).
- Intent variants (tone): `agentModel.whenMessage(predicate).reply(encodedResult)` to return
  different structured results for question-style vs casual messages.
- Endpoint test: assert the structured `200` body fields; the existing `400` cases (blank user/text,
  malformed JSON) are unchanged and continue to use `invoke().status()` without `responseBodyAs`
  (see [[akka-httpclient-failure-status-testing]]).
- Domain test: real assertions on `TimeOfDay.of` boundaries and invalid-zone fallback.

**Rationale**: Consistent with 001's mocked-model approach (SC-004: offline, deterministic). Splits
"does the structured value flow through" (mocked) from "is the time logic correct" (pure unit test).

**Alternatives considered**: Driving a live model to exercise the tool — rejected, violates the
no-network test constraint and is non-deterministic.

## R6 — API isolation for the structured response

**Decision**: The agent's `Result` (application layer) is mapped to the endpoint's own `GreetReply`
(api layer) with fields `greeting`, `tone`, `timeOfDay`. The endpoint never returns the agent's type
directly.

**Rationale**: Constitution II (API isolation) and AGENTS.md (endpoints define their own
request/response types). Both are Jackson-annotated Scala records (research R3 from 001) for
deterministic round-tripping.
