# Findings — Scala 3 on the Java-first Akka SDK

What building **sixteen capabilities** in Scala 3 on the Java-first Akka SDK taught us,
consolidated into one page. The per-capability design detail lives in [`specs/`](specs/); the
day-to-day interop workarounds live in [`README.md`](README.md) "Scala interop notes" §1–18; the
status table lives in [`ROADMAP.md`](ROADMAP.md). **This page is the synthesis** — the single
finding that explains every outcome, and the rubric it yields.

## The one finding that explains everything: the `dynamicCall` escape hatch

The Akka SDK is Java-first, and its component **clients** come in two shapes. That single
distinction predicted the language of every capability.

A Scala lambda compiles to a synthetic `$anonfun$N`. The SDK's `impl.client.MethodRefResolver`
needs a `Serializable` lambda whose `implMethodName` **equals the target method name**, so a Scala
lambda never resolves. The escape hatch is a **string-keyed `dynamicCall(id)`** overload — and a
jar-wide sweep (cap-11 R1) finds exactly **one** client that has it: the agent client. Every other
Scala-callable row below is friendly for a *different* reason — its API was never keyed on a method
reference in the first place (`Class` references, `Task` constants, a URL string).

| Client | Resolves target by | Scala-callable? |
|--------|--------------------|-----------------|
| `AgentClient` | `dynamicCall(id)` **or** method-ref | ✅ yes — **incl. agents the SDK owns** (cap-13) |
| `AutonomousAgentClient` | `Class` + `Task` constants | ✅ yes |
| `WorkflowClient` | method-ref **only** | ❌ no |
| `EventSourcedEntityClient` | method-ref **only** | ❌ no |
| `KeyValueEntityClient` | method-ref **only** | ❌ no |
| `ViewClient` (cap-11) | method-ref **only** | ❌ no |
| `DependencyProvider` (custom DI, cap-8) | `Class` key (`getDependency[T](Class[T])`) | ✅ yes |
| `RemoteMcpTools` (MCP client, cap-10) | URL **string** (`fromService`/`fromServer`) | ✅ yes |
| `TimedActionClient` — **scheduling** (cap-15) | method-ref **only**; `DynamicMethodRef` has no `deferred()` | ❌ no |
| `TimerScheduler.delete` — **cancelling** (cap-15) | timer name, a plain **string** | ✅ yes |
| `Consumer` — the whole family (cap-16) | an annotation carrying a `Class`; the handler by **parameter type** | ✅ yes — no client at all |
| `AgentClient.withDetailedReply` (cap-17) | method-ref **only** — `DynamicMethodRef` has `invoke`/`invokeAsync` and nothing more | ❌ no |

**Any client keyed solely on a Java method reference is unreachable from Scala.** That is the whole
story; everything below is a corollary. Crucially, the wall is a property of the *client*, not of
the *component kind* or of durable orchestration in general — the Autonomous Agent (cap 3) is a
*more* capable durable primitive than the Workflow, yet is fully Scala-friendly.

**Cap-11 sharpens this one step further.** Until then, every capability that hit the wall had its
*whole component* pulled into Java (cap-2's Workflow, cap-6's entity), so "the wall is a client
property" and "some components are Java" were indistinguishable in practice. A View separates them:
`ViewClient` is method-ref-only, yet the **View component itself is Scala** and only the querying
endpoint is Java. The precise statement is therefore:

> **The wall is a property of the client, and it travels no further than the class that holds the
> method reference.**

Everything downstream of that class (the component, its logic, its domain) and everything upstream
that doesn't hold a method ref (an `httpClient` test) stays Scala.

**Cap-13 supplies the last clause, and it is the one that matters most in practice.** Every capability
that had ever hit the wall needed a component the *runtime* owned — cap-4's `SessionMemoryEntity`,
cap-6's `TodoEntity`, cap-11's `View` — and each quarantined Java to reach it. Three for three made the
wall look like a property of **ownership**: *the SDK's own components are out of reach from Scala.*
That reading is wrong. `dynamicCall` resolves off `agentClassById`, a map the runtime populates with
**every** registered agent, its own included — so calling the SDK's built-in `hallucination-evaluator`
from Scala is a one-liner, and cap-13 contains no Java anywhere. The complete statement:

> **The wall is a property of *which* client. The agent client — alone in having
> `dynamicCall(String)` — is on the right side of it *even for components the SDK owns*. A
> runtime-owned component forces Java only when it is not an agent.**

## Per-capability: why each landed where it did

### Capability 1 — Tools + structured output · **Scala**
Agent client has `dynamicCall`, so invocation works from Scala. The only friction was
*serialization*, not calling — see the two-mapper constraint below. Also surfaced the Gemini
tools-vs-JSON limit.

### Capability 2 — Multi-agent Workflow · **Java**
The Workflow API is method-ref **end to end** — not just the caller
(`WorkflowClient.method(Wf::start)`) but the internal step wiring (`transitionTo`, `stepTimeout`,
`RecoverStrategy.failoverTo`). There is no `dynamicCall` and no string/step-name overload anywhere,
so a Scala workflow can neither be invoked nor wire its own steps. Java was the only path
(`com.gwgs.akkaagentic.team.*`), fully decoupled from the Scala capabilities.

### Capability 3 — Autonomous Agent · **Scala**
The surprise that reframed the project. A *more* capable durable-orchestration primitive than the
Workflow, yet keyed entirely on `Class` references, `Task` constants, and annotations
(`forAutonomousAgent(Class, id)`, `runSingleTask(Task)`, `forTask(id).get(Task)`,
`Task.name(...).resultConformsTo(Class)`) — **zero** method references (verified against SDK 3.6.0
bytecode). This proved **the wall is Workflow-*specific*, not intrinsic to durable orchestration.**
Bonus: the Scala `@Get("/help/{taskId}")` path binding worked **without** scalac `-parameters`.

### Capability 4 — Session memory · **Scala** (one Java test)
The least-interop capability to *build*: string-keyed by `.inSession(id)`, builder-based
`MemoryProvider`, a runtime-owned `SessionMemoryEntity` (not even in the descriptor), and a bare
`String` payload (no wire type at all). But *testing* it recurred the wall in a new place: reading
`SessionMemoryEntity` to prove retention/isolation needs the `EventSourcedEntity` client, which is
method-ref-only → that one test ([`SessionMemoryIntegrationTest`](src/test/java/com/gwgs/akkaagentic/chat/application/SessionMemoryIntegrationTest.java))
is Java. A second testing limit: with `TestModelProvider` the mock is fed **only the current turn**,
so multi-turn *recall* isn't offline-observable and is proven by a **live** smoke test instead.

### Capability 8 — RAG-grounded Q&A · **Scala**
(Caps 5–7 are covered in README interop §7–§9.) Real retrieval-augmented generation, Scala end-to-end.
Two findings extend the story: **(a)** the whole in-process RAG stack (langchain4j + the all-MiniLM ONNX
model, packaged in-jar) is **already in the SDK 3.6.0 dependency tree** — only a `runtime→compile` scope
bump, no new version, fully offline. **(b)** the project's first **custom-dependency injection** —
`KnowledgeStore` provided via `Bootstrap.createDependencyProvider()` and constructor-injected — is
**Scala-clean**: `DependencyProvider` is `Class`-keyed (see the table row), so it lands on the friendly
side of the wall alongside the Agent/AutonomousAgent/Task clients. And unlike cap-7's un-mockable
delegation, **retrieval is deterministic → fully offline-testable**; only the generative half is mocked.

### Capabilities 9 & 10 — MCP server & client · **Scala** (both)
(Caps 5–7 are covered in README interop §7–§9.) The Model Context Protocol closes the interop story from
**both** sides, for the **same** reason — neither side authors a `ComponentClient` method reference.
**(cap-9, server)** An `@McpEndpoint` is an *endpoint* (no `@Component`), invoked **reflectively** from
JSON-RPC — there is no client to author, so it's Scala-clean like an HTTP endpoint (new descriptor key
`mcp-endpoint`). **(cap-10, client)** The one place there *is* an outbound call — an agent consuming a
remote MCP server via `.mcpTools(RemoteMcpTools.fromService/fromServer(...))` — is configured by a **URL
string**, not a method ref (see the table row), so it's Scala-clean too. Cap-10 also lands a *positive*
testing result opposite cap-7's D9: a remote **MCP tool** is a normal typed-`String` function-tool call,
so `TestModelProvider` scripts a **real** `retrieve` round-trip to the in-process `/mcp` offline (SC-005
parity vs a direct `KnowledgeStore.retrieve`). And tool transport is invisible to the model —
`@FunctionTool` and `@McpTool` are interchangeable at the model layer (same flat `{name, description,
inputSchema}` namespace; the MCP tool's description comes from the *server's* `tools/list`). **Verdict:
the wall is a client-method-ref property end to end — every SDK surface that isn't one is Scala-friendly.**

### Capability 11 — Views / read-model · **Scala throughout except one Java caller**
The CQRS read side over cap-6's `TodoEntity`. `ViewClient` is method-ref-only (no `dynamicCall`), so the
querying endpoint is Java — but the **View is Scala**, making this the first capability split *across* the
component/caller boundary (see the sharpened statement above). After the build fix below, the Java part is
**a single class**: the endpoint. Everything else, view rows included, is Scala. Even the tests split along that line rather
than wholesale: the view-query test is Java (it holds the method ref), the endpoint test is **Scala**
(`httpClient` + a `Class`-keyed publisher hold none). Two findings that are *not* corollaries of the wall:

- **A second, independent hazard axis: reflected bytecode shape.** Every prior finding turned on whether an
  API was keyed on a `Class`/`String` (fine) or a Java method ref (impossible). This one turns on how Scala
  *compiles*. The SDK finds `TableUpdater`s via `Class.getDeclaredClasses()` and builds them with a
  **zero-arg** `getDeclaredConstructor()` + `newInstance()`. Scala's two nesting forms differ: an **inner**
  class (`class V { class U }`) compiles to a non-static class with only `U($outer)` — **unconstructable, a
  runtime failure**; a **companion-object** class (`object V { class U }`) compiles to a `public static`
  member of `V` with a synthesized no-arg constructor — exactly the Java `static class` shape. So the
  updater *must* live in the companion object. **Generalization: wherever the SDK reflects on a class rather
  than dispatching through a client, ask what shape it expects, not just what it is keyed on.**
- **A latent build defect, found in review — and the fix shrank the Java quarantine to ONE class.** Until cap-11,
  `maven-compiler-plugin` (parent POM) ran before `scala-maven-plugin` (ours), so **javac ran before
  scalac** and no Java class could reference a Scala one. Cap-11's Java endpoint *must* name the Scala
  View to hold its method reference, so the capability **did not build from clean** — hidden throughout
  development because incremental builds reused a `target/classes` that already held the Scala output.
  **The IDE flagged it; the build did not, because the build was never run clean.** Fix: bind
  `scala-maven-plugin` to `process-resources` / `process-test-resources` with `sendJavaToScalac=true`, so
  scalac runs first (reading Java sources for signatures) and javac compiles last against its output.
  `-parameters` **survives under this order** — it was lost before precisely because scalac ran *second*
  and overwrote javac's class files. (Plus its mirror image: scalac's joint-compiled Java classes needed
  `-parameters` too, or a *clean* build passes while an *incremental* one ships `arg0` and breaks path
  binding. Both directions of the build are now verified separately.) **Consequence:** with Java→Scala
  compiling, the view rows moved to Jackson-annotated **Scala** case classes, so the Java quarantine is
  now **exactly the one class holding the method reference** — making the through-line above literal
  rather than approximate. §8's language-of-consumer rule is **ergonomics guidance, not a mechanical
  law**. *Lesson worth more than the finding:* **run `mvn clean verify` before calling a capability
  done** — an incremental build can mask a broken one indefinitely, and "all tests green" is not the same
  claim as "this builds".

Cap-11 is also the project's **first entirely model-free capability** — no `TestModelProvider`, mocked or
live, anywhere in its tests — so its correctness is fully deterministic offline, with no live-only caveat
like cap-6's recall or cap-7's delegation.

### Capability 12 — Agent guardrails · **Scala, and the platform builds *our* classes**
The first capability where the platform constructs our code from a configuration string rather than
dispatching to it, which puts the whole weight on cap-11's second axis — **reflected bytecode shape** —
through an entirely unrelated mechanism. It **corrects** that axis rather than confirming it.

- **All three Scala class forms load, including `object` — the prediction was wrong.** Rules are named in
  `application.conf` by class-name string and built via `DynamicAccess.createInstanceFor`, which tries
  `(GuardrailContext)` then zero-arg. Both class forms load, the no-arg path being undocumented but real.
  A Scala **`object`** was predicted to fail, since its module class's only constructor is `private` —
  and it **loads**, because Akka's `ReflectiveDynamicAccess` does `getDeclaredConstructor →
  setAccessible(true) → newInstance`. The runtime then holds a **fresh instance, not `MODULE$`** (measured
  by `identityHashCode`), which is harmless *only* because a Scala 3 `object`'s fields compile to **static**
  fields and its constructor body is empty.

  **The corrected generalization** — and this supersedes the tempting reading of cap-11: on this axis what
  decides it is **whether a constructor with the required parameter types exists at all**, not whether it
  is public. Cap-11's inner `TableUpdater` has *no* zero-arg constructor (only `U($outer)`) → **fails**;
  an `object` has one, merely private → **succeeds**. Same axis, genuinely different failure reason;
  conflating them yields a rule that is wrong half the time. Still prefer a top-level `class` — the object
  form works by a scalac implementation detail, not by anything the SDK promises. (The SDK's own
  `reference.conf` demands a *public* class and constructor; the runtime enforces **neither**.)
- **Guardrails are not components — governance costs zero descriptor lines.** For a project whose first
  finding was *"Scala components are invisible to the annotation processor, so the descriptor is
  hand-maintained"*, the notable result is that three rules governing an agent left that file
  **byte-identical**. Registration is configuration; there is no `@Component`, and nothing to hand-list.
- **Two limits that are about the platform, not the language** — a Java agent hits both identically, which
  is worth saying on a page otherwise devoted to Scala-vs-Java:
  1. **A block cannot be rethrown to the caller.** Throwing from an agent's `onFailure` is caught by the
     SDK as a *"Failure mapping error"* (`AK-01203`); the caller receives an opaque
     `kalix.runtime.CorrelatedRuntimeException` with the type erased. Governance therefore travels the
     **reply channel** behind a shared sentinel constant — the same technique cap-8 uses for its decline.
  2. **A rule's identity never reaches application code.** `Guardrail.GuardrailException` carries the bare
     explanation — no name, no category, no cause. The composed audit line exists on an SPI-internal
     exception and is exported to **traces and metrics only**; the runtime's guardrail code has no logger
     at all. So rules we author **name themselves inside their own explanation**; the SDK's
     `SimilarityGuard` cannot, and is reported as `unknown`. The asymmetry is about **who owns the rule**.
- **The collision worth generalizing: a governance block can masquerade as an honest answer.** Cap-8's
  `DocsAgent` ended with `.onFailure(_ => DontKnow)` — correct while the only throwables were model
  failures, and quietly wrong the moment a rule could fail the interaction. A blocked request came back to
  the caller as *"I don't know"*: a refusal reported as a decline, unauditable and misleading. **Before
  adding a rule to any agent, read that agent's `onFailure`.** This was found by a discovery test written
  *before* any production edit, which is why it cost one test instead of a redesign.

  **It recurred, which is what makes it a class rather than an incident.** Cap-13's follow-up found the
  *same* handler reporting a **model timeout** as a decline — harmless to `/ask`, which shows a decline
  either way, but `/evaluate` then had a judge rate a decision the assistant never made. So
  `.onFailure` now sorts three outcomes, not two, and the general rule is sharper than "read
  `onFailure`": **a catch-all that maps every failure onto a legitimate answer is a lie waiting for a
  second consumer.** Cap-8 had exactly one consumer when it was written.

A modelling limit worth carrying: `TextGuardrail.evaluate` receives **text only** — no question, no
retrieved passages — so a guardrail structurally *cannot* check grounding. Cap-12 ships a documented proxy
instead of faking one, and that gap is the clearest argument for an evaluation/LLM-judge capability next.

### Capability 13 — LLM-as-judge evaluation · **Scala, and no Java anywhere**

Judges cap-8's answers with the SDK's built-in `hallucination-evaluator` and an authored
`decline-judge`. It exists because cap-8 documented a soft-grounding gap and cap-12 found a
*structural* reason it could not close it — `TextGuardrail.evaluate` sees the answer text alone, no
question and no passages. An evaluator's request carries all three.

- **`dynamicCall` reaches SDK-owned components.** The three built-in evaluators are ordinary `Agent`s
  *and* provided components (`ComponentLocator$`, same list as `SessionMemoryEntity`/`TaskEntity`),
  with real `@Component` ids — so the string-keyed escape hatch resolves them. The documented
  `.method(Evaluator::evaluate)` form is the wall. This is the clause added to the headline finding
  above, and it is why this capability needed **no Java at all** where caps 4, 6 and 11 each did.
- **An authored evaluator is an ordinary agent; the *return type* is the switch.** No `@Evaluator`
  annotation exists. `Reflect$.isEvaluatorAgent` tests whether the handler's return class implements
  `EvaluationResult`, and `Sdk` folds that into the `AgentDescriptor` — the flag that routes verdicts
  into metrics and traces. Quiet failure mode: drop the `extends` and you get a compiling, working,
  silently un-instrumented agent.
- **Read cap-12 and cap-13 together or not at all.** They both wrap an agent's behaviour and agree on
  nothing else: config-registered vs component, **0** descriptor lines vs **1**, bytecode shape
  (cap-11's axis) vs the method-ref wall (cap-2's axis), no identity reaching our code vs all of it.
  The attribution difference generalises: **a mechanism the platform invokes on your behalf tells you
  less than one you invoke yourself.** A guardrail block arrives as `rule: "unknown"`; a verdict is a
  return value, so nothing is erased.
- **Fully offline — including the SDK's own judge**, which was not the obvious outcome. `LlmAsJudge`
  sets its model *explicitly*, but `AgentImpl` reads
  `overrideModelProvider(id).getOrElse(requestModel.modelProvider)`, so the TestKit's per-agent
  override wins. Better than cap-6 (recall live-only) or cap-7 (delegation not faithfully mockable).
- **Evaluation could only ever have had its own surface, as a research result rather than as
  discipline.** There is no `Consume.From*` source for a request-based agent — the SDK's documented
  `EvaluationConsumer` consumes `TaskEntity`, which cap-8 does not have. At merge that left cap-8
  **byte-identical**, provable by `git diff`. The property did not survive PR review: a turn that
  failed *inside* cap-8's agent arrived as `"I don't know"` and was judged as a decline, so
  `DocsAgent` now replies behind a failure sentinel that `DocsEndpoint` maps back (see the guardrail
  collision above). Worth stating plainly — **byte-identical was the cheaper claim; not judging a
  timeout as a decision was the more valuable one.**

Two limits carried forward: the documented Java-method-ref form fails from Scala with an error naming
**the developer's own class** as "not a subclass of `Agent`" (the worst diagnostic this project has met
on the wall), and verdict telemetry is not observable under the TestKit at all — see
[`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md) §5.

## The two crosscutting constraints (orthogonal to the wall)

1. **Two Jackson mappers** (cap 1 / feature 003). The public `JsonSupport` hook — where
   `DefaultScalaModule` registers — governs **HTTP endpoint bodies only**. Component-to-component
   payloads (agent `Request`/`Result`, entity events, workflow state, view rows, task results) go
   through a *separate internal* mapper the public hook can't reach. So HTTP DTOs can be idiomatic
   `Option` case classes, but **anything component-serialized stays Java-shaped** (Jackson-annotated,
   nullable). Trying to make a component payload an annotation-free `Option` type fails at runtime
   with *"Cannot construct instance of `scala.Option`"*. **Java-shaped is not Java-authored**: an
   annotated Scala case class satisfies it, confirmed for agent results and task results (caps 1/3/5)
   and — as of cap-11 — for **view rows** too. Use `java.util.List`, not a Scala `List`.

2. **Gemini: tools vs. structured output** (cap 1). Gemini rejects function calling combined with a
   JSON response mime type (`500 INVALID_ARGUMENT`). Use `responseAs` + a system-prompt JSON
   instruction + an `.onFailure` fallback — **not** `responseConformsTo`. OpenAI supports both
   together; this is Gemini-specific. (Note: cap 3 sidesteps it because the typed result is delivered
   by the `complete_task` *tool*, not a JSON response mime type.)

## Descriptor discipline (every capability)

The SDK discovers components from a `META-INF/akka-javasdk-components_*.conf` file normally generated
by a `javac` annotation processor that only scans **Java** sources. Our Scala components are never
scanned, so the file is **hand-maintained** — **add every new Scala component** under its type key
(`agent`, `autonomous-agent`, `http-endpoint`, …). The exception is runtime-registered components
like `SessionMemoryEntity`: leave those **out**.

Mixing Java sources into this Scala module needs three `pom.xml` settings: annotation processor off
(`-proc:none`, so it can't overwrite the hand-maintained descriptor), `-parameters` restored (HTTP path
binding), and — **corrected in cap-11** — `scala-maven-plugin` bound to `process-resources` /
`process-test-resources` with `sendJavaToScalac=true`, so scalac runs *before* javac. The original
`sendJavaToScalac=false` setting left javac running first, which silently made Java→Scala references
uncompilable.

## The practical rubric this leaves you

- **Reach for `AutonomousAgent` over `Workflow`** when a Scala capability needs the model to drive a
  durable loop — it's *the* Scala-friendly durable-orchestration primitive.
- **Expect Java only when you must:** (a) author or invoke a Workflow, (b) query an entity directly,
  (c) query a View, (d) consume an agent's token stream (cap-14), or (e) **schedule** a timed action
  (cap-15) — though *cancelling* one, and authoring it, stay Scala. A **Consumer** (cap-16) needs none of
  it: the family holds no method reference anywhere. Everything else — agents, autonomous agents, HTTP endpoints, MCP endpoints, domain,
  validation — stays idiomatic Scala. And keep the Java part **as small as the class holding the method
  ref**: a Java caller does not imply a Java component.
- **Check the reflected *shape*, not only the key type.** When the SDK reflects over a class (nested
  updaters, and anything else built by `getDeclaredConstructor()`), Scala's inner-class form is
  unconstructable — put such classes in a **companion `object`** so they compile to `public static` with a
  no-arg constructor.
- **Run `mvn clean verify`, not just `mvn verify`, before declaring a capability done.** An incremental
  build reuses `target/classes` and can hide a genuinely broken build (cap-11 shipped one). Tests passing
  is not the same claim as the project compiling.
- **Mixed-language compile order is load-bearing.** scalac is bound to `process-resources` with
  `sendJavaToScalac=true` so it runs *before* javac; that is what makes both Scala→Java and Java→Scala
  resolve, and what lets javac (running last) write the `-parameters` metadata HTTP path binding needs.
  Changing plugin phases here breaks one direction or the other.
- **Match the test language to the code under test.** Not stylistic: the wall applies to tests too. A
  Workflow-driving or entity-querying test *must* be Java; agent (`dynamicCall`), `httpClient`, and
  pure-domain tests stay Scala.
- **Keep component wire types Java-shaped; only HTTP DTOs go idiomatic** (`Option`, no annotations).
- **Register every Scala component in the hand-maintained descriptor** (runtime-registered entities
  excepted).
- **Don't assert model-memory behavior through the mock** — assert *storage* via the entity, and
  *behavior* via a live model.

**Net:** idiomatic Scala on this SDK is very achievable, and the exact places it isn't are
**predictable from one property** — whether the component's client offers a `dynamicCall` escape
hatch.

## Platform note: Akka's two persistence models (not an interop finding)

Orthogonal to everything above — it holds identically for the Java capabilities — but it came up
often enough while reading the runtime to be worth recording. Full write-up:
[`docs/akka-persistence-models.md`](docs/akka-persistence-models.md).

Akka offers exactly **two** entity state models, chosen by base class, with **no per-entity storage
configuration**: `EventSourcedEntity<State, Event>` (persist events, derive state, full audit trail,
Views consume `onEvent`) and `KeyValueEntity<State>` (store the latest state, no history, Views
consume `onUpdate`). This repo uses the Key Value model for cap-6's `TodoEntity` and has never
authored an Event Sourced Entity of its own — the only one we touch is the runtime's
`SessionMemoryEntity`.

**The finding: both are backed by the same event journal.** A Key Value Entity is *not* implemented
on Akka Persistence's durable-state store, despite the name and the conceptual docs. The runtime's
own `reference.conf` (`akka-runtime-core_2.13-1.6.15.jar`, `akka.runtime.value-entity.cleanup`) says
so outright:

> Key Value Entity is implemented with event sourcing where each event contains the current state.
> Older events are cleaned up by a background process…

Confirmed two ways: `akka-runtime-core` contains **no** `DurableState*` classes (though
`akka-persistence-r2dbc` ships a full durable-state store the SDK just doesn't use), and no runtime
config sets a durable-state plugin — only `akka.persistence.r2dbc.journal` and `.snapshot`. Storage
is Akka Persistence R2DBC on H2 locally, PostgreSQL deployed. Corroborating detail: the
`value-entity` block has no snapshot setting while `eventsourced-entity` has `snapshot-every = 100` —
it needs none, since every event is already a complete state.

Three practical consequences: **(a)** cap-11's View over a Key Value Entity works because there
genuinely *is* an event stream underneath — state-change subscription isn't a bolted-on extra;
**(b)** that history is still not yours (24-hour retention, active cleanup), so "no history" remains
the right mental model at the API level; **(c)** every Key Value update writes the whole state, so
write cost scales with state size, not change size.

---

## Not on this page: version-specific SDK bugs

The findings above are **structural** — they follow from the SDK's design and hold across versions. A
separate, smaller category is **version-specific SDK bugs** we hit on **3.6.0** (e.g. MCP tool optional
params, request-based delegation offline-mocking, `readLast` history trimming). Those are worked around
and tracked in [`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md) to re-check on an SDK
upgrade — they are debt to clear, not lessons about the language boundary.

---

## Capability 14 — streaming: the escape hatch is narrower than "the agent client"

`dynamicCall(String)` has been the project's one exemption from the method-reference wall since
capability 1, and every capability since has been classified by asking *which client* is involved.
Streaming shows that question is one level too coarse.

**`dynamicCall` rescues the agent client's request/response calls and nothing else.** It returns a
`DynamicMethodRef`, which has `invoke`/`invokeAsync` and **no streaming member**. Consuming a token
stream is `tokenStream(SomeAgent::method)` — a Java method reference — so **the same agent client is on
both sides of the wall at once**. The precise rule is now: *the wall is a property of which client **and
which method on it**.*

Measured, all four routes:

| Attempt | Result |
|---|---|
| Scala lambda into `tokenStream` | **compiles**, fails at run time: `class <the caller's own class> is not a subclass of class akka.javasdk.agent.Agent` |
| `dynamicCall(id).source(msg)` | compile error: `value source is not a member of DynamicMethodRef` |
| `tokenStream("component-id")` | compile error: no `String` overload |
| the same call in **Java** | works — the control that makes this about Scala, not about our usage |

**Streams are not the deciding axis.** The jar-wide inventory is the useful part:

| Client | Streaming member | Keyed on | Scala? |
|---|---|---|---|
| `AgentClientInSession` | `tokenStream(Function \| Function2)` | method reference | no |
| `EventSourcedEntityClient` / `KeyValueEntityClient` / `WorkflowClient` | `notificationStream(Function)` | method reference | no |
| `AutonomousAgentClient` | `notificationStream()` | *nothing* | **yes** |
| `TaskClient` | `notificationStream()` | *nothing* | **yes** |

Two notification streams in the same package are Scala-clean because they take no argument. What
decides is what it always was: whether the API takes a Java method reference.

**The wall took one class, and that is now enforced rather than observed.** The agent, the domain rule
and even the capability's own endpoint test are Scala; only the endpoint holding the method reference is
Java, and a test asserts exactly one `.java` file exists under the capability. Capability 11 discovered
that the wall travels no further than the class holding the reference; capability 14 makes it a
regression test.

**Three platform findings that are not about Scala at all:**

1. **A stream has no `onFailure`.** The builder's `error(...)` is decided before any token; after the
   first one there is no hook. The sentinel technique capabilities 8, 12 and 13 depend on cannot exist
   here — no value can replace text already read. **Every fallback pattern in this project assumed a
   single-value reply.**
2. **A token stream can hang, but not for the reason first measured — and the correction matters.**
   Under `TestModelProvider.failWith` the stream emits nothing, completes never and fails never, still
   silent after **240 s**. Against a **real** provider error the runtime fails the stage in **~178 ms**
   (`AgentSource.publishErrorAndFailStage`). So the 240 s silence is a **test-provider artifact**, and
   a guard's real justification is the narrower case: a model that never answers *and* never errors.
   Worth carrying as a method lesson too — **a failure you injected is not necessarily the failure
   production will hand you**, and capability 6's `readLast` correction was the same shape of mistake.
3. **A pre-token failure is undetectable by the client, measured both ways.** It reaches the caller as
   `200` with a body that *completes normally and is empty*, because the status line went out before
   any token existed. Live, against a real provider error:
   `Transfer-Encoding: chunked · BYTES=0 · curl_exit=0 · Connection left intact` — the runtime logs
   "Aborting connection" and it still renders as a normal terminating zero-length chunk, so **curl
   reports success**. A caller must treat an empty body as failure; the alternative is a wire format
   with room for a post-body error (SSE), recorded as a fork. This is the first outcome in the project
   where the honest answer is "the contract cannot express it" rather than a technique that recovers it.

**And one positive interop result worth reusing.** The endpoint's language was chosen by the *SDK*, so
capability 14 is the first time §8's language-of-consumer guidance meets a consumer we did not choose.
A Java caller reads the idiomatic Scala domain (`Option`/`Either`) cleanly: the companion method
resolves through scalac's static forwarder with no `MODULE$`, `Option.apply` converts the nullable at
the boundary, and the only cost is two `Left`/`Right` casts. **When the SDK forces the consumer's
language, keep the domain idiomatic and pay the cast** — moving the rule into Java would have grown the
quarantine the wall forced.

## Capability 15 — timed actions: the wall runs through one family, by operation

Capability 14 showed one *client* with two kinds of method. Capability 15 shows something sharper: **two
different APIs govern two halves of one feature**, and they sit on opposite sides of the wall.

| Operation | API | Scala? |
|---|---|---|
| schedule | `TimedActionClient.method(japi.Function)` → `.deferred(...)` | **no** — no id-keyed form; `DynamicMethodRef` has no `deferred()` |
| cancel | `TimerScheduler.delete(String)` | **yes** — measured cancelling a *Java*-scheduled timer |
| perform the work | `TimedAction` + `effects()` | **yes** — the action itself is Scala |

**You can cancel from Scala what you could not have scheduled from Scala.** So the precise rule, after
fifteen capabilities: *the wall is a property of which client, which method on it — and, where a feature
spans two APIs, which operation.* What decides is still the one thing it always was: whether the API takes
a Java method reference.

**The diagnostic finally says what happened.** The Scala lambda compiles and fails at run time, as in
capabilities 13 and 14, but this message names the synthetic lambda outright:

```text
IllegalArgumentException: Use dedicated builder for calling Object component method
  ReminderProbeEndpoint::$anonfun$1. This builder is meant for Action component calls.
```

The earlier form — *"class <the caller's own class> is not a subclass of …"* — never mentioned lambdas.
A **Java control** scheduled the same Scala action successfully. The probe now *asserts* the failure, so a
future SDK that lifted the wall would turn a test red rather than go unnoticed.

**The wall took one class.** `POST /reminders` is the capability's only Java production class; `GET`,
`DELETE`, the timed action, the domain and the store are Scala, and so is every test but one — the
retry-bound test, which must schedule an always-failing action and therefore needs a method reference of
its own. A test is not production, so the quarantine still holds at one.

**Two platform findings that are not about Scala** — a Java service meets both identically:

1. **The retry contract has two traps.** The three-argument `createSingleTimer` retries **indefinitely**
   (still climbing at 30 s, widening gaps — and a 6 s observation had read "bounded", wrongly). And a timer
   that exhausts the four-argument form's `maxRetries` **stops silently**, while the SDK gives a timed
   action **no attempt number**. So "bounded" alone is not enough: bounded *and silent* leaves a reminder
   reading `pending` for ever. Each action counts its own attempts and, on the last one, records the failure
   and returns `done()` — AGENTS.md's "handle errors in timed actions", made concrete, in one helper
   (`BoundedAttempts`) that the production action and the test instrument share. A test reads every
   source in the capability and fails on any three-argument call, with no exemption list.
2. **A pending timer did not survive a restart in local dev mode**, under the same
   `persistence.enabled=true` flag with which capabilities 3 and 5 saw *tasks* survive. Measured once
   validly (a same-second kill was discarded as confounded): scheduled 60 s out, killed 10 s later with the
   store on disk, restarted, watched 100 s past due — never fired. The design followed the measurement:
   reminder state lives in process, so a restart yields an honest `404` rather than a `pending` that can
   no longer fire. **Deployed behaviour is untested and claimed neither way.**

**And a method result worth reusing: prove a guarantee with a witness that takes no part in it.** Once a
reminder is `failed`, the store ignores later transitions — so the store cannot testify that no retry
happened afterwards. The retry test uses a separate invocation counter that plays no role in the stop
decision, which is exactly what makes it a valid check on that decision. The same idea showed up in the
cancellation test: "still `cancelled`" would hold even if the timer had run (the store guards it), so the
run's log was checked for the action executing — it never did.

## Capability 16 — consumers: Scala-clean throughout, and the hazard moves somewhere else

Fifteen capabilities asked "which language does this force?". This one answers **neither** — and then shows
that the question had been hiding a bigger one.

**No Java in production**, the first since capability 13. A `Consumer` is declared by an annotation carrying
a `Class`, its handler is chosen by **parameter type**, and it returns effects from `effects()`; the reading
endpoint holds an in-process store rather than a `ViewClient`. There is no client to be keyed on a method
reference, so the wall never comes up.

**The sharpest comparison in the project so far**: capability 11's View consumes **the same entity**. It
needed a Java querying endpoint (`ViewClient` is method-ref-only) *and* the companion-object bytecode shape.
Two projections over one source, two different verdicts — which is what "the wall is a property of the
client" means in practice, stated as concretely as it can be.

**Handler selection is by type, and the failure modes are asymmetric** (measured): two handlers taking the
same type compile and stop the service starting; a handler taking the *wrong* type compiles, starts, and is
simply never called, with nothing logged. The only signal for the second is an integration test timing out —
worth knowing before trusting a green unit suite.

**The real hazard is not the language boundary at all.** A handler that throws is redelivered **without
limit** (measured: 0, 277, 789, 1719, 3419, 6985, 13976, 27611 ms, doubling) and **blocks every other
entity** until it stops. Giving up and returning `done()` released the stream within 1 ms. So a consumer
must bound its own attempts — and the SDK gives it nothing to count with: no attempt number, and a `ce-id`
that changes on every redelivery, so the key has to be the message's **content**. A set-aside is not a
tombstone either: a restart of the failing stream can replay the message with the count starting over.

**Two platform traps worth carrying forward.** The TestKit's key-value mock **never redelivers** a failing
message, so failure tests belong on the real projection path — a false-green hazard, not a convenience.
(It was first recorded as also losing the messages behind the failure; that half did not reproduce and is
now claimed neither way — the absence of redelivery is enough on its own.) And one consumer declaring `@Produce.ToTopic` stops the **whole service** booting when no topic
support is configured (`AK-00406`), which the suite cannot show because it mocks topics.

**A third, found by the live walk rather than the probe: the local substitute for a broker prints nothing.**
`eventing.support = "logging"` does log every produced message — at INFO, under a logger named
`kalix.runtime.eventing.LoggingEventingSupport.<topic>` — but the dev-mode logback config silences the whole
`kalix` tree at `WARN`. So a walk that greps the service log for published messages finds none and can
conclude, wrongly, that publishing never happened. One line in `include-dev-loggers.xml` restores it. The
general shape is worth keeping: **a diagnostic that is configured off by default reads exactly like a
feature that did not run**, and only the bytecode settled which it was.

**Method result worth reusing.** Capability 15 asked a guarantee to be proven with a witness that takes no
part in it; capability 16 needed the same trick for a different reason. Once a delivery is set aside its
attempt count is cleared, so the store cannot testify to how many times the runtime actually delivered — a
separate counter in the probe does, and it is what the bound is checked against.

## Capability 17 — compaction: a third method of the same client, and four corrections

The interop result is a footnote; the useful part is how much this capability had to un-write.

**The wall takes a third method of the agent client.** `dynamicCall` returns a `DynamicMethodRef` carrying
`invoke`, `invokeAsync`, `withMetadata`, `withRetry`. `withDetailedReply()` — the only route to a summary's
token usage — lives on four types, all reached by method reference. So the escape hatch that has carried
this project since capability 1 covers **plain request/response and nothing else**: `invoke` rescued,
`tokenStream` not (capability 14), `withDetailedReply` not (here).

**A Java caller cannot construct a Scala 3 `enum` case** — *"enum classes may not be instantiated"*. That
is a new Java↔Scala wrinkle, and it improved the design: the first gateway had the message mapping and the
outcome decision in Java and would not compile. Scala-side factories were available and rejected, because
the compiler was pointing at a real flaw — the quarantine held **logic**. It is now one class **and** one
responsibility, which is a stronger claim than capability 14 and 15's file counts.

**Four things this capability asserted and then had to correct**, which is the reason it is worth reading:

| Asserted | Measured |
|---|---|
| History grows unbounded | Bounded at **510 KiB**, which is also the maximum permitted |
| That bound has `readLast`'s orphan bug at a higher threshold | It is **turn-aligned and safe**; the two mechanisms differ in exactly one property — whether the cut lands on a turn boundary |
| `compactHistory` silently discards a stale sequence number | It **merges**: the number marks how much the summary stands for, and later events are replayed on top |
| A `ServiceSetup.onStartup` failure stops the service | The runtime logs it and **starts anyway** |

The third is the one to learn from. The probe passed `sequenceNumber - 2` against a four-message history,
saw four messages after, and recorded "unchanged" — when it was *compacted to two and two replayed back*.
The same count for a different reason: exactly the rule this capability itself later wrote down as **"under
concurrency, infer nothing from a count"**, made before the rule existed and never applied backwards.
A probe is not immune to the lessons it produces.

**And one operational shape worth carrying beyond Akka: when a component is reconstructed per message, a
throw in its constructor is not a failure, it is a loop.** An out-of-range setting read in the consumer
threw for every session-memory event — 40 redeliveries in one short test — while the service started, turns
succeeded, and nothing pointed at the cause. Validate where it is reported once; degrade where it would
repeat.

## Capability 18 — event-sourced entity: a sum type crosses the mapper but fails a *second* gate

The repo's first event-sourced entity (a `Wallet` ledger), and the sharpest correction to this page's own
vocabulary. A3's question was whether a Scala 3 sum type can be the event hierarchy. Measured: no — and the
reason is subtler than "the mapper can't take it."

**Two independent gates, and they disagree.** A Scala 3 `enum` **serializes** (its `@TypeName` is honoured)
but **cannot be deserialized**: Jackson reports *"Cannot reflectively create enum objects."* A Scala 3
`sealed trait` of case classes **round-trips through the internal mapper cleanly** — all four cases, both
directions, no `@JsonCreator`/`@JsonProperty` for flat primitive fields — and then **fails at startup**:
the SDK validates that *"the event type of an EventSourcedEntity is required to be a sealed interface,"* and
Scala 3.3.8 emits no JVM `sealed` attribute (`isSealed() == false`, `getPermittedSubclasses() == null`).

**This corrects the project's shorthand.** "Scala sum types can't cross the internal mapper" was too coarse:
the sealed trait *does* cross it. What forces Java is a *second*, separate gate — startup validation — that
the serializer knows nothing about. So the event hierarchy is **one Java `sealed interface`** of records,
and that is the only forced-Java file: the entity is Scala and the `Wallet` **state** is a plain Scala case
class that round-trips for snapshots (the sealed requirement is on the *event* type only). The caller is
Java for the usual reason — the `EventSourcedEntity` client is method-reference-only (cap-6/cap-11).

**The method-ref wall reaches the unit testkit.** `EventSourcedTestKit.of(...)` takes a SAM so a Scala
lambda *constructs* it, but `.method(scalaLambda)` throws `ClassCastException` in `Reflect.getReturnType`,
which reads the command's parameterized return type reflectively — a Scala lambda is a synthetic method with
an erased return type, a Java method reference is the real command method. So domain rules are unit-tested in
pure Scala and the entity through a Java testkit / integration test.

**Two service-global registries, not per-entity** — both of which shape how a *second* entity is added
later. `@TypeName` is unique service-wide (the Phase-0 probe's `opened` and production's `opened` collided at
startup, forcing the probe out the moment production registered); and `snapshot-every` is a single
service-wide knob with no per-entity form (so production keeps the SDK default and only a per-test override
exercises snapshots, to avoid changing the runtime-owned `SessionMemoryEntity`).

**And one reassurance for A4.** A3's secondary risk was build ordering; a Scala entity referencing a
same-package Java `sealed interface` compiled cleanly under `mvn clean verify`. gRPC's *generated* sources
remain the untested case.

## Capability 19 — gRPC: the first family whose finding is *not* the wall, it's the build

The repo's first gRPC endpoint, and the last untouched SDK component family — so the map is complete. A4 was
chosen knowing it carried **little interop novelty and real build risk**, and that is precisely how it
resolved.

**The method-ref wall was never the question.** gRPC's answer was already implied: a Scala class implements
the generated Java service interface (routine), and the endpoint reaches a component via the **agent
client's `dynamicCall`** — the right side of the wall (cap-15). Fronting capability 1's `greeting-agent`
rather than an entity is what keeps the endpoint Scala; an entity front would have forced Java (cap-18), off
A4's actual question.

**The real finding is build ordering, and it held.** The SDK parent carries a profile
`generate-protobuf-endpoints` **auto-activated by the mere existence of `src/main/proto`** — no `pom.xml`
edit, no `<plugin>` line. It runs `akka-grpc-maven-plugin` 2.5.10 (Java flavor, `blockingApis=true`) at
**`generate-sources`**, and `build-helper` adds `target/generated-sources/akka-grpc-java` as a compile source
root. This project's scalac is bound to **`process-resources`** (later) with `sendJavaToScalac=true`, so the
generated Java interface is on scalac's path before the Scala compile — a Scala class implements a Java
interface whose source did not exist until mid-build. `mvn clean verify` was green on the first clean run:
the §13 R3 arrangement that broke twice before did not break here.

**The honest shape is "Scala endpoint over Java-generated stubs."** `blockingApis` yields the Java interface
`GreetReply greet(GreetRequest in)`; the messages are Java (builders, proto3 non-null scalars); ScalaPB is
not usable because the SDK's `@GrpcEndpoint` contract *is* the generated Java interface. This is the §3
wire-type exception — Java-shaped types at the boundary — now at a **code-generated** surface, not "gRPC in
Scala end to end." `generateScalaHandlerFactory=true` is a red herring: it emits
`…ScalaHandlerFactory.**java**`, which is why plain-Java SDK projects (no scalac) build this profile fine.

**Everything else was Scala-clean.** Validation reuses the pure domain `GreetingRequest`; a blank field throws
`new akka.grpc.GrpcServiceException(io.grpc.Status.INVALID_ARGUMENT.augmentDescription(msg))` before the
agent is called. The integration test uses the generated blocking client `GreeterGrpcEndpointClient` from
`getGrpcEndpointClient(...)` with `TestModelProvider` — no method reference anywhere. New descriptor key
`grpc-endpoint`, confirmed by the runtime registering the service at startup; `akka-grpc-runtime` arrives
transitively via `akka-javasdk`.

## Capability 20 — SSE: the SDK's own helper reproduces the defect it was meant to fix

The first capability that is neither a new SDK component family nor a bug fix — a wire-format refinement
(fork B2). It adds a second streaming surface `POST /sse-chat/{sessionId}` beside cap-14's raw-chunked
`/stream-chat` (left untouched as the baseline), framing the **same** agent's answer as Server-Sent Events
with explicit `event: data` / `event: error`, so a failure is self-describing instead of cap-14's silent
empty `200`.

**SSE is first-class in the SDK, and the 3-arg overload is the one that matters.**
`HttpResponses.serverSentEvents(source, extractEventId, extractEventType)` renders each element to a `data:`
frame and labels it with `extractEventType` — so `event: data` / `event: error` is just a sealed envelope
(`SseChatEvent.Data` / `.ErrorEvent`) mapped to `"data"` / `"error"`. Two costs fall out of the API shape:
the `data:` payload is **JSON** via the SDK's *internal* mapper (`{"text":"…"}`, not raw text — so clients
concatenate decoded `text` fields, and the envelope is a **Java** record, the §3 boundary on an SSE surface),
and the type-setting overload **also requires an id function** with no type-only alternative, so a one-shot
answer still emits a bare `id:` line per frame.

**The headline is that `serverSentEvents` cannot convey a stream *failure* — it silently empties.** Its
implementation ends in `.recoverWith(…) → Source.empty()` with the comment *"no natural way to convey stream
errors to client with SSE — the HTTP response with status is already sent."* A `Throwable` reaching the
helper is logged server-side and the stream completes **empty and successful-looking** — cap-14's exact
defect, one level up. So switching to SSE buys nothing *by itself*. The capability's whole value is one
operator upstream of the helper: `.recover { case t => new SseChatEvent.ErrorEvent(SseErrorReason.reasonFor(t)) }`
(javadsl `PFBuilder`), which consumes the failure and substitutes a **final element**, so the helper never
sees a Throwable and the error flows through as an ordinary `event: error` frame. The guards' timeouts
(`initialTimeout`/`idleTimeout`) raise `TimeoutException` into the stream and are caught by the same
`.recover`. The error element must be trivially serialisable, or the inner `recoverWith` swallows *its*
serialisation failure too — so `ErrorEvent` is a flat single-String record.

**Still exactly one Java class per the §16 wall, plus the Java wire type.** The endpoint holds
`tokenStream(StreamingChatAgent::stream)` (Java method ref), and `SseChatEvent` is Java because the internal
mapper serialises it. The agent, the domain rule (`StreamQuestion`), the reason mapping (`SseErrorReason`)
and all the integration tests are Scala; a `JavaQuarantineTest` pins the streaming tree's Java set at exactly
those three `api` classes across both capabilities.

**Verified live (Ollama `qwen3:8b`), and the live run closed an offline gap.** Happy path streamed
`event: data` frames (a multibyte emoji survived the framing, beyond the offline `\n` round-trip). A real
provider error (`OLLAMA_MODEL=does-not-exist`) produced `event: error` / `{"reason":"the request failed"}` —
the **generic `Failed`** branch that no offline test could reach: offline, a mocked `failWith` goes silent, so
`initialTimeout` fires and the reason is `TimedOut`; a live provider error materialises as a stream failure
that `.recover` catches as a non-`TimeoutException`, mapping to `Failed`. The mid-stream "N data frames *then*
error" ordering over HTTP remains live-only — `TestModelProvider` cannot produce a gap between tokens
(cap-14's measured limit) — and is pinned instead on a synthetic source using the same `PFBuilder` recover.

## Capability 21 — usage-accurate citations: the finding is about the *model*, not interop

Fork **B4**, and the second capability (after cap-20) that is neither a new component family nor a bug fix.
It adds a second RAG surface `POST /cited-ask` beside cap-8's `/ask` (left untouched): instead of citing
every passage **retrieved** (cap-8, ground truth but over-inclusive), it asks the model which sources it
actually **used** (structured output `{answer, usedSources}`) and cites only those. That deliberately
re-opens the self-report unreliability cap-8 was built to avoid (cap-7's D6).

**Interop-wise there is nothing new — it is pointedly Scala-clean.** Structured output is
`responseConformsTo(classOf[CitedAnswer])`, the same mechanism cap-13's evaluator uses, with a Java-shaped
reply record (the §3 internal-mapper boundary). It is a *separate* agent (`citing-docs-agent`) only because
an `Agent` has exactly one command handler and cap-8's returns a bare `String` — not an interop limit, a
component rule, and it is what keeps cap-8 literally untouched. The citation decision is a pure, framework-free
domain function (`UsageCitations.select`), unit-tested with no runtime. `citing-docs-agent` is deliberately
**ungoverned** — cap-12's guardrails attach by config to `docs-agent` only, and wiring them onto a new surface
was out of scope (one config line if ever wanted). No new descriptor key; the agent and endpoint are two
ordinary lines.

**The honesty floor is what makes re-opening self-report defensible.** A self-reported label is cited only if
it was *also* genuinely retrieved (`reported ∩ retrieved`), so the model can only ever *narrow* the
ground-truth set, never invent a citation. A decline cites nothing; a non-decline answer whose reported
sources don't intersect the retrieved set also cites nothing (FR-007 — no fallback to cap-8's full set), so an
unverifiable claim stays visible rather than masked. The reply carries both `citedSources` and
`retrievedSources` so divergence is readable from one response.

**The headline is a measurement, and it inverts the expected failure.** Live on Ollama `qwen3:8b`, 6 runs:
the unreliability is **under-reporting, not hallucination.** The floor held perfectly (0 un-retrieved
citations across all runs), and when the model *did* name sources it narrowed *correctly* — 1-of-3 and 2-of-3,
dropping the irrelevant passage. But **3 of 5** grounded answers returned an **empty** `usedSources` (stable
across a re-run) despite answers plainly grounded in a retrieved passage, so `/cited-ask` cited nothing.
Precision is excellent *when it reports*; the recall of the reporting itself is poor on an 8B local model. On
the exact question cap-8's README uses to illustrate *over*-citation ("survive a restart"), B4 here cites
**nothing** — it trades over-citation for **under**-citation. Both are failure modes of the same hard problem;
B4's is at least honest about its own uncertainty. The prompt and schema are not the bottleneck (the model
*can* report — the 1-of-3 / 2-of-3 cases prove it), so a stronger model is the natural follow-up and needs one
beyond the free-tier local one. See specs/023 research R6 and README §23.

