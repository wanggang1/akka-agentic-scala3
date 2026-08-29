# Findings — Scala 3 on the Java-first Akka SDK

What building **four agentic capabilities** in Scala 3 on the Java-first Akka SDK taught us,
consolidated into one page. The per-capability design detail lives in [`specs/`](specs/); the
day-to-day interop workarounds live in [`README.md`](README.md) "Scala interop notes" §1–6; the
status table lives in [`ROADMAP.md`](ROADMAP.md). **This page is the synthesis** — the single
finding that explains all four outcomes, and the rubric it yields.

## The one finding that explains everything: the `dynamicCall` escape hatch

The Akka SDK is Java-first, and its component **clients** come in two shapes. That single
distinction predicted the language of every capability.

A Scala lambda compiles to a synthetic `$anonfun$N`. The SDK's `impl.client.MethodRefResolver`
needs a `Serializable` lambda whose `implMethodName` **equals the target method name**, so a Scala
lambda never resolves. The only workaround is a **string-keyed `dynamicCall(id)`** overload — and
only two of the four clients have one.

| Client | Resolves target by | Scala-callable? |
|--------|--------------------|-----------------|
| `AgentClient` | `dynamicCall(id)` **or** method-ref | ✅ yes |
| `AutonomousAgentClient` | `Class` + `Task` constants | ✅ yes |
| `WorkflowClient` | method-ref **only** | ❌ no |
| `EventSourcedEntityClient` | method-ref **only** | ❌ no |
| `KeyValueEntityClient` | method-ref **only** | ❌ no |
| `ViewClient` (cap-11) | method-ref **only** | ❌ no |
| `DependencyProvider` (custom DI, cap-8) | `Class` key (`getDependency[T](Class[T])`) | ✅ yes |
| `RemoteMcpTools` (MCP client, cap-10) | URL **string** (`fromService`/`fromServer`) | ✅ yes |

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
- **Expect Java only when you must:** (a) author or invoke a Workflow, (b) query an entity directly, or
  (c) query a View. Everything else — agents, autonomous agents, HTTP endpoints, MCP endpoints, domain,
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

---

## Not on this page: version-specific SDK bugs

The findings above are **structural** — they follow from the SDK's design and hold across versions. A
separate, smaller category is **version-specific SDK bugs** we hit on **3.6.0** (e.g. MCP tool optional
params, request-based delegation offline-mocking, `readLast` history trimming). Those are worked around
and tracked in [`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md) to re-check on an SDK
upgrade — they are debt to clear, not lessons about the language boundary.
