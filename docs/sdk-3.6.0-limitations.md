# SDK 3.6.x limitations — revisit on upgrade

This project pins **`akka-javasdk-parent` 3.6.3** (bumped from 3.6.0 on 2026-08-28). A few capabilities
hit **version-specific** SDK bugs or limitations — not design choices. Each was worked around and
documented in its feature spec; this page is the **single consolidated "re-check on upgrade" list**.

> **Re-tested on 3.6.3 (2026-08-28): all three below are STILL PRESENT — none fixed.** Confirmed by
> targeted spikes (#1 `Optional cannot be cast to Integer` on a supplied value; #2
> `Could not deserialize [json.akka.io/object]`; #3 `MemoryHistoryUtils.trimToLastN` bytecode is still a
> naive `subList(size−N, size)`). They almost certainly need **3.7+ / 3.15**, which is **gated behind a
> paid Akka plan** — see the version-ceiling note below.
>
> **Version ceiling on a FREE Akka subscription = 3.6.3.** The secure Akka artifact repo
> (`repo.akka.io/<token>/secure`) serves only **3.6.0–3.6.3** to a free-tier token; **3.6.4+ and all
> 3.7+/3.15 return a clean 404** (entitlement, not a broken release — the parent *metadata* is public on
> Central, so `versions:display-parent-updates` misleadingly advertises 3.15.19). So these items cannot be
> revisited on this SDK line without a **paid subscription**. `langchain4j` is BOM-managed to **1.11.6** by
> the 3.15.x parent (vs our pinned 1.15.0) — a conflict to reconcile *if/when* a paid plan unlocks 3.7+.

**When bumping the SDK** (only possible past 3.6.3 with a paid plan), do it on its **own branch** with a
full `mvn verify` across all capabilities plus live spot-checks — the bump touches every capability. For
each item below, check whether the newer SDK fixes it and, if so, **restore the fuller behavior and its
tests**, then remove that entry here. When the list is empty, delete this file.

---

## 1. MCP `@McpTool` — no tunable optional numeric parameter (cap-9, feature 011)

**Symptom.** An optional numeric tool argument (cap-9's `maxResults`) cannot be expressed on 3.6.0. All
three shapes fail — proven against the TestKit in feature 011 T006:

| Shape | Result |
|-------|--------|
| `Optional[Integer]` bare param *(the doc-recommended non-required shape)* | A **supplied** value throws `"class java.util.Optional cannot be cast to class java.lang.Integer"`. The cast is Optional→element, so it fails for **every** element type. Omitted works; any value fails. |
| plain `Integer` bare param | Supplied values bind, but the SDK marks a non-`Optional` object param **required** → omitting it fails with `"Missing required tool parameter [maxResults]"`. The exact inverse. |
| manual `inputSchema` over two bare params | Breaks argument binding entirely (`"argument type mismatch"`) — the manual-schema path is tied to a single wrapper-record param, incompatible with bare multi-params. |

This is an **SDK bug/limitation, not a Scala-interop wall** — the reflective bare-param path itself works
(single `question` param is fine).

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** A temporary `maxResults: Optional[Integer]` spike
supplying `maxResults=2` threw the identical `java.util.Optional cannot be cast to java.lang.Integer`
(omitted still returns 3). Unchanged.

**Current workaround.** `KnowledgeMcpEndpoint.retrieve` takes only `question` and returns a **fixed
top-K of 3**, exactly mirroring cap-8's `DocsEndpoint`. A faithful cap-8 mirror, not a loss of retrieval
quality.

**Restore on upgrade.** Re-add the optional `maxResults` (try `Optional[Integer]` first — verify the cast
bug is gone), and restore the clamping tests: omitted → 3, `1` → 1, `0`/negative → floor 1, `9999` →
whole corpus. Deviation notes live in `specs/011-mcp-knowledge-server/` (spec SC-006/FR-006, contract §3,
data-model banner, research R2) and in the `KnowledgeMcpEndpoint` scaladoc.

---

## 2. Request-based delegation not faithfully mockable offline (cap-7, feature 009)

**Symptom.** The 3.6.0 testkit's `AutonomousAgentTools.delegateTo(Class, String)` (the request-based
worker form) delivers an untyped `json.akka.io/object` payload the worker cannot deserialize, so a
delegation mock is a silent **false-green** (the WARN-level failure doesn't fail the test). The 3-arg
**autonomous**-worker form round-trips fine; only the 2-arg request-based form is affected. **Live
delegation is unaffected** — the real runtime tags the payload with the worker's type.

**Current workaround.** cap-7's offline test uses a **direct** completion (no delegation mock); delegation
itself is proven **live** (an unknown location returns `WeatherData`'s un-hallucinatable canned default).

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** A spike scripting `delegateTo(WeatherSpecialist, …)`
produced the identical `Could not deserialize message of type [json.akka.io/object] to type
[java.lang.String]` in `WeatherSpecialist.report`, and `DelegationOrchestrator` logged
`request-based delegation failed`. Unchanged.

**Restore on upgrade.** Re-add a delegation mock to `ActivityCoordinatorIntegrationTest` (coordinator
`delegateTo(...)` both specialists → each `fixedResponse` → coordinator `completeTask` on the "Continue
working" turn) and confirm the workers deserialize (no `json.akka.io/object` WARN). Detail:
`specs/009-autonomous-delegation/` research D9.

> **Companion (not version-limited):** cap-7's `consultedSpecialists` is model self-report and flaky on
> small models. The durable fix is asserting on the runtime's **notification** stream for ground-truth
> delegation records (see `akka-context/sdk/autonomous-agents/notifications.html.md`) — independent of the
> SDK version, but a good thing to tackle alongside this item.

---

## 3. `MemoryProvider.readLast(N)` orphans tool-call pairs (cap-6, feature 008)

**Symptom.** SDK 3.6.0 `MemoryHistoryUtils.trimToLastN` is a naive `subList(size-N, size)` that ignores
tool-call/response pairing. Once a tool-using session exceeds N messages the window head becomes an
**orphaned `ToolCallResponse`**, an invalid chat sequence the runtime rejects during request assembly —
surfaced misleadingly as `argument "content" is null`. Proven live in cap-6 by an A/B (removing `readLast`
fixes it).

**Current workaround.** `PersonalAssistantAgent` uses **full session history**
(`MemoryProvider.limitedWindow()`, no `readLast`), accepting unbounded token growth on long sessions.

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** Disassembling 3.6.3's
`akka.javasdk.impl.agent.MemoryHistoryUtils.trimToLastN` shows it is still `subList(size−N, size)` with no
tool-call/response pairing logic (no `ToolCall` references, no `dropWhile`/guard). Byte-for-byte the same
as 3.6.0.

**Restore on upgrade.** Check whether the SDK's trim became pair-aware; if so, `readLast(N)` can return as
a bounded window. The proper general bound is **compaction** (summarize old turns without slicing pairs),
which is worth doing regardless of the SDK — a separate future-work item. Detail: cap-6 "Live caveat" in
README §8 and `specs/008-*/`.

---

## 4. Guardrails: documentation diverges from the jar in six places (cap-12, feature 014)

Capability 12's design was derived from the SDK jars and then **run**. Six statements in the published
documentation or the SDK's own `reference.conf` did not survive contact with the running system. None
of them is Scala-specific — a Java agent hits every one of them identically.

| # | Documented / implied | Actually |
|---|---|---|
| 1 | The result type is `TextGuardrail.Result` | **That type does not exist.** It is `Guardrail.Result` — a record `(boolean passed, String explanation)` with a static `OK`. |
| 2 | A guardrail optionally takes a `GuardrailContext` constructor parameter | True, and there is a **second, undocumented** path: a **zero-arg** constructor, tried if the first match fails. |
| 3 | `SimilarityGuard` is a guardrail implementation | It is a **config holder**. Its `evaluate` throws `IllegalStateException("Not expected to be called")`; the runtime special-cases the type and evaluates it itself. |
| 4 | A block is *"aborted by throwing `Guardrail.GuardrailException`"*, implying an application can catch it | The exception is real and is handed to the agent's `onFailure` — but **rethrowing it does not reach the caller**. The SDK catches it as a *"Failure mapping error"* (`AK-01203`) and the caller receives an opaque `kalix.runtime.CorrelatedRuntimeException`; the type is erased crossing the component client. |
| 5 | The result is *"tracked in logs, metrics and traces"* — implying the rule's identity is recoverable from logs | The composed audit line (`… guardrail blocked, category [X], name [Y]: …`) lives on an **SPI-internal** `AgentException` and reaches **traces and metrics only**. `kalix.runtime.agent.AgentGuardrailInteractions` contains **no logger at all**. The public `GuardrailException` carries the **bare explanation** — no name, no category, no cause. |
| 6 | The class "must … be **public** and have a **public** constructor" | **Neither is enforced.** Akka's `ReflectiveDynamicAccess` does `getDeclaredConstructor → setAccessible(true) → newInstance`, so a `private` constructor loads fine — which is why a Scala `object` works despite the prediction that it could not. |

**Workarounds in this project.**

- #4 → a block travels the **reply channel** behind `DocsAgent.BlockedPrefix`, the same sentinel technique
  cap-8 uses for its decline sentinel, shared as a constant so the agent and endpoint cannot drift.
- #5 → rules we author **name themselves inside their own explanation**
  (`docs/domain/GuardrailAudit`: `[name/CATEGORY] …`), so their blocks are fully identified in the `422`.
  The SDK's own `SimilarityGuard` cannot, and is reported as `rule: "unknown"` — an asymmetry the tests
  assert explicitly, so that if a later SDK starts supplying the identity the suite fails and this note
  gets revisited rather than quietly rotting.
- #6 → guardrails are still authored as **top-level classes**. The `object` form works, but by a scalac
  implementation detail (object fields compile to `static` fields, so the runtime's *fresh* instance
  shares all state with `MODULE$`), not by anything the SDK promises.

**Not a limitation, recorded because it is easy to fear:** a misspelled `class` value fails the service
**at startup** — construction is eager, so there is no window in which an agent is silently unguarded.

**Re-test on upgrade.** #1 and #2 are documentation fixes; #4 and #5 are the ones that would change this
project's design if the SDK ever surfaced a typed, identified block to application code. Detail:
`specs/014-agent-guardrails/research.md` (R1-FINAL, R3-RESOLVED, R-AUDIT) and README §14.

---

## 5. Evaluators: a misdirecting error from Scala, and telemetry that cannot be observed offline (cap-13, feature 015)

Two items, one of which is the worst *diagnostic* this project has met on the method-reference wall.

### 5a. The documented call form fails from Scala with an error naming the wrong class

`akka-context/sdk/agents/llm_eval.html.md` calls a built-in judge with a Java method reference:

```java
componentClient.forAgent().inSession(taskId).method(ToxicityEvaluator::evaluate).invoke(...)
```

Scala has no `::` method reference; the nearest equivalent is a lambda adapted to
`akka.japi.function.Function2`. **It compiles.** At invocation it fails with:

```
java.lang.IllegalArgumentException:
  class com.gwgs.akkaagentic.eval.application.BuiltInJudgeIntegrationTest
  is not a subclass of class akka.javasdk.agent.Agent
```

`MethodRefResolver` reads the `SerializedLambda`'s **`implClass`**, which for a Scala lambda is the
*enclosing* class — the caller — not the agent the lambda mentions. So the SDK reports that **the
developer's own class is not an `Agent`**.

**Why this is worse than every previous encounter with the wall.** The others at least pointed at the
right area (a workflow step that would not wire, an entity client with no `dynamicCall`). This one
names a class that is not the problem, never mentions lambdas or Scala, and sends a reader off to make
their endpoint extend `Agent` — which it must not do. A Scala developer following the documentation
gets a program that compiles and an error that misdirects.

**Workaround** — one line: use `dynamicCall(componentId)`. The three built-in ids are
`hallucination-evaluator`, `toxicity-evaluator`, `summarization-evaluator`, and they resolve because
`ComponentLocator$` registers the evaluators as **provided components** of every service, so they are
in `agentClassById` alongside your own agents.

**Not a limitation, recorded because it is the good news:** this means `dynamicCall` reaches components
the SDK owns, not only your own — which is why capability 13 contains no Java at all, where
capabilities 4, 6 and 11 each had to quarantine some when they needed a runtime-owned component.

### 5b. Verdicts cannot be observed in the TestKit — no spans, no metrics reader

`TestKitSupport.telemetryReader` exposes `getAgents(sessionId)`, which should show the agents that ran
under a trace. It returns an **empty list**, and neither of these enabled tracing through
`TestKit.Settings.withAdditionalConfig`:

```
akka.runtime.telemetry.tracing.enabled = true
akka.runtime.telemetry.tracing.override-setup =
  "kalix.runtime.telemetry.tracing.TracingSetup$DevModeInMemoryTracingSetup"
```

`TestKit.getInMemorySpanExporter` does **not** throw — an exporter exists, there are simply no spans in
it. Metrics have no TestKit reader at all.

**Consequence.** The SDK's promise that an evaluator's verdicts reach metrics and traces is verified in
this project **by mechanism, not by observation**: `Reflect$.isEvaluatorAgent` marks an agent from its
handler's return type and `Sdk` folds that flag into the `AgentDescriptor`. Both halves are pinned by
`EvaluatorDescriptorTest`, but nothing here watches a verdict actually arrive anywhere. This is
capability 13's one gap in an otherwise fully-offline capability, and it belongs to the TestKit rather
than to the design.

**Re-test on upgrade.** 5a is a documentation fix (the docs should show `dynamicCall` for non-Java
callers) plus, ideally, a clearer resolver message naming the *lambda* rather than its enclosing class.
5b would let FR-011 be asserted rather than reasoned about. Detail:
`specs/015-llm-judge-evaluation/research.md` (R1, T004, R5/FR-011) and README §15.

---

## 6. Streaming (capability 14, measured on 3.6.3)

Three behaviours worth re-testing on any SDK upgrade. None is Scala-specific — a Java service meets all
three identically.

### 6a. A token stream can hang — under a scripted failure; a real provider error ends it

With the model scripted to fail, a consumer of
`componentClient.forAgent().inSession(id).tokenStream(Agent::method).source(arg)` observes **no tokens,
no completion and no failure**. Measured twice: silent at 30 s, and still silent at **240 019 ms** —
well past the provider's own budget (`response-timeout = 1m` × `max-retries = 2`). So this is not retry
latency; the stream simply never ends.

**Consequence**: an endpoint returning such a stream *must* bound it (`initialTimeout`, `idleTimeout`)
or a caller holds an open connection indefinitely. Ours does.

**CORRECTED by the live run (2026-09-12).** This is a **test-provider artifact**, not general SDK
behaviour. Against a real provider error (`OLLAMA_MODEL=no-such-model-xyz`) the stream fails in
**~178 ms** — the runtime logs `AK-01202 … Model call failed` and
`Response stream … failed with 'Model call failed'. Aborting connection.`, via
`AgentSource.publishErrorAndFailStage`.

| Injection | Stream ends? | When |
|---|---|---|
| real provider error | yes, by the runtime | ~178 ms |
| `TestModelProvider.failWith` | no | silent past 240 s |

**So what the guard is for is narrower than it first appeared**: not provider errors (the runtime
handles those faster than any timeout), but a model that never answers *and* never errors — a hang.
Worth keeping for that, and worth knowing it is not the common case.

### 6b. A guard delivers termination, not a failure signal

With `initialTimeout = 1s`, the same pre-token failure reaches an HTTP caller as:

```text
200 OK, after ~1s, body completes normally with ZERO chunks
```

The status line is written before any token exists, so Akka HTTP ends the already-committed chunked
body rather than aborting it. **A pre-token failure is therefore indistinguishable from "nothing to
say"** unless the caller treats an empty body as failure. A failure *after* fragments were sent does
abort the body, so that case is visibly truncated.

**Confirmed live, including the part the first run missed.** With a real provider error the caller sees:

```text
HTTP/1.1 200 OK · Transfer-Encoding: chunked · BYTES=0 · curl_exit=0 · Connection left intact
```

The runtime logs "Aborting connection", but it is rendered as a normal terminating zero-length chunk —
**curl reports success**. So the abort is undetectable at the client, for a scripted *and* a real
failure alike.

**Workaround, not taken**: server-sent events (`HttpResponses.serverSentEvents`) have somewhere to put
an error after the body has begun. It changes the wire format for every client, so capability 14 keeps
plain text and records SSE as a fork.

### 6c. `StreamEffect` has no `onFailure`

`Agent.StreamEffect.Builder` offers `error(String | CommandException)` — a refusal decided *before* any
token — and no failure hook thereafter. Every other agent surface in this project degrades a failed turn
to a sentinel value (`DontKnow`, `BlockedPrefix`, `FailedPrefix`); a stream structurally cannot, because
no value can replace text the caller has already read.

**Consequence**: fallback behaviour for a streamed agent has to live in the *stream* (guards, and how a
client interprets a short body), not in the effect. Worth re-checking if a later SDK adds a stream-level
failure hook.


---

## 7. Timed actions (capability 15, measured on 3.6.3)

Three behaviours to re-test on any SDK upgrade. **None is Scala-specific** — a Java service meets all three
identically. (The Scala-specific finding, that *scheduling* is method-reference-only while *cancelling* is
not, is structural and lives in [`FINDINGS.md`](../FINDINGS.md), not here.)

### 7a. A pending timer does not survive a restart in local dev mode

| Step | Time |
|---|---|
| scheduled a 60 s timer, `-Dakka.javasdk.dev-mode.persistence.enabled=true` | 09:52:17 |
| killed the service 10 s later, `db.mv.db` present | 09:52:27 |
| restarted on the same store, healthy | 09:52:35 |
| watched 100 s, past the 09:53:17 due time | **never fired** |

A first attempt that killed the service in the same second as scheduling was **discarded**, because "not
durable" could then have meant "never persisted". Capabilities 3 and 5 observed *tasks* surviving under the
same flag, so this is specifically about **timers**.

**Scope, stated rather than glossed:** local dev mode with the H2 store only. A deployed service has a real
datastore and may behave differently; that is **untested here and claimed neither way**.

**What depends on it**: capability 15 keeps reminder state in process *because* of this — durable state over
a volatile timer would read `pending` for ever. If an upgrade (or a deployed test) shows timers surviving,
revisit that decision: the state would then have to survive too.

### 7b. The three-argument `createSingleTimer` retries indefinitely

`createSingleTimer(name, delay, deferred)` on work that always fails, sampled every 5 s:

```text
5s=2  10s=3  15s=3  20s=3  25s=4  30s=4   — still climbing, gaps widening
```

A 6-second observation had read "2 attempts, bounded" — **wrongly**; the gaps simply widen. The
four-argument `createSingleTimer(name, delay, maxRetries, deferred)` with `maxRetries = 2` stopped at 2
invocations. AGENTS.md's warning ("handle errors in Timed Actions to avoid infinite rescheduling") is
therefore not theoretical.

**Consequence**: capability 15 uses the four-argument form everywhere, and `NoUnboundedTimerTest` fails the
build on any three-argument call under the capability. Re-test the default on upgrade; if it becomes
bounded, the test can relax — but the explicit bound should stay.

### 7c. An exhausted timer is silent, and an action is not told its attempt number

Two gaps that combine badly:

1. When a timer exhausts its `maxRetries`, **nothing is recorded or signalled** — the work simply stops
   being attempted.
2. A timed action has **no way to know which attempt it is on**: `TimedAction.commandContext()` returns a
   `CommandContext` carrying only `tracing()` and metadata; nothing attempt- or retry-shaped exists in
   `akka.javasdk.timedaction` or `akka.javasdk.timer`.

So "bounded" is not enough on its own: bounded *and silent* leaves any state the work was meant to update
exactly where it was — for capability 15, a reminder reading `pending` for ever. **Workaround**: the action
counts its own attempts (in the store) and, on the last permitted one, records the failure and returns
`effects().done()` instead of throwing; the timer's `maxRetries` stays as a backstop.

The SDK does not document how `maxRetries` counts (whether `2` means two attempts or two *re*-tries).
Measured: `maxRetries = 2` produced **two** invocations. The workaround does not depend on that — it sets
the timer's bound at or above its own.

**Re-test on upgrade**: whether `CommandContext` gains an attempt/retry count, or whether exhaustion
becomes observable. Either would let the action stop counting for itself.

### 7d. An endpoint constructor that throws `IllegalArgumentException` is reported as `400`

Not timer-specific — found while walking capability 15's quickstart live, and it applies to any endpoint.
`ReminderSchedulingEndpoint` reads `reminders.max-retries` in its constructor. With the value validated by
Scala's `require` (which throws `IllegalArgumentException`) and set out of range, a `POST` answered:

```text
HTTP/1.1 400 Bad Request
requirement failed: reminders.max-retries must be between 1 and 10, was 0
```

and the log said `ERROR … Failed to create instance of HTTP Endpoint [...ReminderSchedulingEndpoint]`.
So a **server misconfiguration was reported to the caller as the caller's mistake**, with an internal
setting's name in the body. Throwing `com.typesafe.config.ConfigException.BadValue` instead produced
`500 Internal Server Error` with a correlation id (the detail appears only in dev mode).

| Exception from the endpoint constructor | Caller sees |
|---|---|
| `IllegalArgumentException` (e.g. `require`) | **`400`**, message in the body |
| `ConfigException.BadValue` | `500`, correlation id |

**Rule of thumb**: validate *configuration* with a config exception, never with `require` — `require` is
right for arguments a caller controls, which is exactly the meaning the SDK gives it. **Re-test on
upgrade**: whether construction failures stop being mapped through the request-error path. Endpoints are
constructed per request, so this surfaces on the first request, not at startup; startup validation would
need `ServiceSetup`, which is shared across capabilities.

---

## 8. Consumers (capability 16, measured on 3.6.3)

Four behaviours to re-test on any SDK upgrade. **None is Scala-specific** — a Java service meets all four.

### 8a. A failing handler is redelivered without limit, and it blocks every other entity

Measured on the real projection (not the TestKit): a handler that throws for one entity was redelivered with
exponential backoff and no ceiling —

```text
redelivery schedule of one failing message (ms): 0, 277, 789, 1719, 3419, 6985, 13976, 27611 …
```

— and for the whole of that time, a change written for a **different** entity never arrived. It was delivered
only once the failing message stopped failing. A handler that counts its own attempts and returns
`effects().done()` released the stream **within 1 ms**.

**Consequence**: a consumer must bound its own attempts, or one poison message silently stops the capability
for every user. The SDK offers nothing to count with — `MessageContext` has no attempt or delivery number,
and `ce-id` changes on every redelivery — so the count must be keyed on the message's **content**. And a
give-up is not final: the failing stream restarts, so the same message can be replayed afterwards with the
count starting from zero.

**Re-test on upgrade**: whether `MessageContext` gains a delivery count, and whether blocking is confined to
a slice rather than the whole consumer. (Scope: measured in local dev mode. A deployed service partitions a
projection into slices, which may confine the blocking; claimed neither way.)

### 8b. The TestKit's key-value mock does not model redelivery — failure tests there are false greens

With `withKeyValueEntityIncomingMessages(...)`, a failing message is **never redelivered**. The real
projection does the opposite: it redelivers with backoff and holds the others back until it succeeds.

(An early run also saw messages published *during* a failure disappear; a later run delivered them. That
half is timing-dependent and is claimed in neither direction — the absence of redelivery is what
reproduces, and it is enough to make a failure test there meaningless.)

| Behaviour | Mocked incoming | Real projection |
|---|---|---|
| delivery, input type, delete handler, metadata | faithful | faithful |
| redelivery of a failing message | **none** | exponential, unbounded |
| messages behind a failure | unreliable (seen both ways) | held, then delivered |

**Consequence**: assert failure behaviour by writing the real entity. If the entity's client is
method-reference-only, that test must be Java — a *test*, which does not compromise a Scala production claim.

### 8c. One `@Produce.ToTopic` stops the whole service starting when no topic support is configured

Default is `eventing.support = "none"`. One consumer declaring a destination then fails startup for the
entire service — every unrelated capability included:

```text
AK-00406 Component [...] has declared a message destination topic [...], but no topic support is configured.
kalix.runtime.InvalidServiceException
```

The suite never shows it, because the TestKit mocks topics. `akka.javasdk.dev-mode.eventing.support =
"logging"` makes local runs work: each produced message is logged and dropped.

**But `logging` prints nothing by default, which is worth knowing before concluding it did nothing.** The
sink logs at INFO under a logger named `<class>.<topic>` —
`kalix.runtime.eventing.LoggingEventingSupport.todo-activity` — and the dev-mode logback config
(`logback-runtime-dev-mode.xml`, inside `akka-runtime-dev`) silences the entire `kalix` tree at `WARN`. So
the messages are emitted and never printed, and a live walk that greps the service log for them finds
nothing. Dev-mode user loggers are applied **last** and may override runtime loggers, so one line in
`include-dev-loggers.xml` restores it:

```xml
<logger name="kalix.runtime.eventing.LoggingEventingSupport" level="INFO"/>
```

Measured with it in place:

```text
15:11:25.489 INFO  k.r.e.L.todo-activity - DestinationEvent(CloudEvent(61459a8a-…,todo-activity-consumer,1.0,
  …TodoActivityConsumer$TodoActivityMessage,application/json,None,Some(walk-bob),Some(2026-09-25T19:11:25Z),
  Some(<ByteString@7afc059c size=135 contents="{\"username\":\"walk-bob\",\"changes\":[{\"kind\":\"comp...">),…))
```

Note the payload is a **truncated `ByteString` preview**, not the whole JSON — enough to confirm a message
was published and see its head, not a substitute for a subscriber.

**`logging` is not a broker, and it is asymmetric.** Producing is tolerated; a consumer reading *from* a
topic gets the runtime's own warning — *"has a source […] but no message broker is configured (eventing
support is [logging]); it will not receive any events"*. The runtime carries the symmetric startup message
for sources (`has declared a message source …`), though only the destination case was run here. A deployed
service still needs a real broker configured at the Akka project level.

### 8d. A produced payload is Scala-aware, but `None` is written as `null`

A produced message goes through the **Scala-aware** mapper — an idiomatic case class with `Option` fields
serialises, unlike a component payload (README §3). But `None` is emitted as `null`, so a message carried
`{"kind":"baseline","itemId":null,"description":null}` until the payload type was given
`@JsonInclude(NON_ABSENT)`. Worth checking on upgrade, and worth annotating either way: a subscriber should
not have to interpret nulls.
