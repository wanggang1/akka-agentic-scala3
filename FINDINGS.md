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

**Any client keyed solely on a Java method reference is unreachable from Scala.** That is the whole
story; everything below is a corollary. Crucially, the wall is a property of the *client*, not of
the *component kind* or of durable orchestration in general — the Autonomous Agent (cap 3) is a
*more* capable durable primitive than the Workflow, yet is fully Scala-friendly.

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

## The two crosscutting constraints (orthogonal to the wall)

1. **Two Jackson mappers** (cap 1 / feature 003). The public `JsonSupport` hook — where
   `DefaultScalaModule` registers — governs **HTTP endpoint bodies only**. Component-to-component
   payloads (agent `Request`/`Result`, entity events, workflow state, view rows, task results) go
   through a *separate internal* mapper the public hook can't reach. So HTTP DTOs can be idiomatic
   `Option` case classes, but **anything component-serialized stays Java-shaped** (Jackson-annotated,
   nullable). Trying to make a component payload an annotation-free `Option` type fails at runtime
   with *"Cannot construct instance of `scala.Option`"*.

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

Mixing the cap-2 Java sources into this Scala module needed three `pom.xml` settings: annotation
processor off (`-proc:none`, so it can't overwrite the hand-maintained descriptor), `-parameters`
restored (HTTP path binding), and `scala-maven-plugin` `sendJavaToScalac=false`.

## The practical rubric this leaves you

- **Reach for `AutonomousAgent` over `Workflow`** when a Scala capability needs the model to drive a
  durable loop — it's *the* Scala-friendly durable-orchestration primitive.
- **Expect Java only when you must:** (a) author or invoke a Workflow, or (b) query an entity
  directly. Everything else — agents, autonomous agents, HTTP endpoints, domain, validation — stays
  idiomatic Scala.
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
