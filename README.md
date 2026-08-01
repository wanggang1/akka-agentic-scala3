# Akka Agentic Scala3

A baseline **Scala 3** agentic service built on the **Akka Java SDK**. It exposes a greeting
agent that accepts a typed JSON payload (`user`, `text`, and an optional `timezone`) over HTTP
and returns a personalized, **structured** greeting `{greeting, tone, timeOfDay}` using the
Akka Agentic Platform Effects API. The agent detects the message's tone/intent and calls a
`@FunctionTool` to report the caller's current time of day.

The Scala 3 sources subclass the Java SDK component types directly (`Agent`, HTTP endpoints) and
are compiled alongside the SDK via the `scala-maven-plugin`. See
[Development Process](https://doc.akka.io/concepts/development-process.html) and
[Developing services](https://doc.akka.io/sdk/index.html) for the underlying Akka concepts.

## Project layout

```text
# Capability 1 — Scala (single agent: structured output + tool)
src/main/scala/com/gwgs/akkaagentic/domain/         # GreetingRequest (+ validation), TimeOfDay
src/main/scala/com/gwgs/akkaagentic/application/     # GreetingAgent (structured Result + @FunctionTool)
src/main/scala/com/gwgs/akkaagentic/api/             # GreetingEndpoint (POST /greet)

# Capability 2 — Java (multi-agent Workflow; see "Scala interop notes" §4 for why Java)
src/main/java/com/gwgs/akkaagentic/team/domain/      # TimeOfDay, Tone (Java copies)
src/main/java/com/gwgs/akkaagentic/team/application/ # ToneAgent, GreetingComposerAgent, GreetingWorkflow
src/main/java/com/gwgs/akkaagentic/team/api/         # GreetingTeamEndpoint (POST /greetings, GET /greetings/{id})

# Capability 3 — Scala (Autonomous Agent; back to Scala — see "Scala interop notes" §5)
src/main/scala/com/gwgs/akkaagentic/assistant/domain/      # KnowledgeBase (canned), HelpQuestion (validation)
src/main/scala/com/gwgs/akkaagentic/assistant/application/ # HelpDeskAgent (AutonomousAgent + @FunctionTool), HelpDeskTasks, HelpAnswer (Java-shaped result)
src/main/scala/com/gwgs/akkaagentic/assistant/api/         # HelpDeskEndpoint (POST /help, GET /help/{taskId})

# Capability 4 — Scala (session memory: multi-turn chat; see "Scala interop notes" §6)
src/main/scala/com/gwgs/akkaagentic/chat/domain/      # ChatMessage (validation)
src/main/scala/com/gwgs/akkaagentic/chat/application/ # ChatAgent (request-based Agent + session memory)
src/main/scala/com/gwgs/akkaagentic/chat/api/         # ChatEndpoint (POST /chat/{sessionId})
# Note: session memory is backed by the SDK-internal SessionMemoryEntity (runtime-registered — NOT in
# our descriptor). Cap-4's memory test is JAVA (src/test/java/.../chat) because the EventSourcedEntity
# client is method-ref-only (no dynamicCall) so Scala can't query SessionMemoryEntity — see §6.

# Capability 5 — Scala (human-in-the-loop approval gate; see "Scala interop notes" §7)
src/main/scala/com/gwgs/akkaagentic/approvals/domain/      # ApprovalQuestion (validation), TaskOutcome + ApprovalCase (pure FSM)
src/main/scala/com/gwgs/akkaagentic/approvals/application/ # DraftAgent, PublishAgent (AutonomousAgents), ApprovalTasks + 3 Java-shaped task results
src/main/scala/com/gwgs/akkaagentic/approvals/api/         # ApprovalEndpoint (POST /approvals, GET /approvals/{id}, POST .../approve|reject)
# Note: the durable record is the three-task chain (draft -> unassigned gate -> publish) keyed by a
# derived caseId — NO Entity, NO Workflow. The human decision is a plain TaskClient call (no method-ref
# wall), so the whole capability incl. tests is Scala. The task entities are runtime-owned (NOT in our
# descriptor), like cap-4's SessionMemoryEntity — see §7.

# Capability 6 — Scala agent + Java entity (agent-to-agent delegation; see "Scala interop notes" §8)
src/main/scala/com/gwgs/akkaagentic/a2a/domain/       # AssistantRequest (validation)
src/main/java/com/gwgs/akkaagentic/a2a/domain/         # Todo, TodoList (pure data/logic — Java, consumed by TodoEntity)
src/main/scala/com/gwgs/akkaagentic/a2a/application/   # PersonalAssistantAgent (per-username), ForwardTool (Scala delegation via dynamicCall)
src/main/java/com/gwgs/akkaagentic/a2a/application/     # TodoEntity (KeyValueEntity), TodoTools (Java tool object — reaches the entity past the method-ref wall)
src/main/scala/com/gwgs/akkaagentic/a2a/api/           # PersonalAssistantEndpoint (POST /request/{username}, synchronous)
# Note: one assistant per username. Chat history via session memory (username = session id, runtime-owned
# SessionMemoryEntity — NOT in our descriptor); to-dos via the Java TodoEntity, reached only through the
# Java TodoTools tool object (the entity client is method-ref-only — the wall); delegation to another
# user's assistant via agent dynamicCall (Scala-clean). ForwardTool/TodoTools are NOT components. This is
# the SDK-discouraged "agent chaining" path, taken deliberately in Scala — see §8, and specs/008 R7.

src/main/resources/application.conf                 # default model-provider config
src/test/{scala,java}/com/gwgs/akkaagentic/...       # tests (TestModelProvider, no live model)
```

- **groupId**: `com.gwgs` · **base package**: `com.gwgs.akkaagentic` · **service**: `akka-agentic-scala3`

## Scala interop notes

The Akka SDK is Java-first. Two of its build/runtime mechanisms assume Java sources, so
writing components in Scala needs explicit workarounds:

1. **Component discovery — hand-maintained descriptor.** The SDK locates components from
   `META-INF/akka-javasdk-components_<groupId>_<artifactId>.conf`, normally generated by the
   `akka-javasdk-annotation-processor` (a `javac` annotation processor). That processor only
   scans **Java** sources, so our **Scala** components are never discovered and the runtime
   fails with *"No component descriptor files found."* We therefore hand-maintain
   [`src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`](src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf),
   listing each component by type:

   ```hocon
   akka.javasdk.components {
     agent = ["com.gwgs.akkaagentic.application.GreetingAgent"]
   }
   ```

   **Add every new Scala component to this file** (matching type key, e.g. `http-endpoint`,
   `event-sourced-entity`, `view`, …) or the runtime won't find it.

2. **Calling components — use `dynamicCall`, not method references.** The Java
   `componentClient.forAgent()…method(GreetingAgent::greet)` form resolves the target by
   inspecting a Java method reference (`SerializedLambda`). A Scala lambda compiles to a
   synthetic `$anonfun` and fails that resolution. Use the component id instead:

   ```scala
   componentClient.forAgent()
     .inSession(UUID.randomUUID().toString)
     .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent") // arg = @Component id
     .invoke(GreetingAgent.Request("Ada", "hello there")) // optional timezone omitted -> UTC
   ```

   This works the same from tests and from an endpoint's injected `ComponentClient`.

3. **Idiomatic wire types — only for HTTP endpoint bodies.** By default the SDK's Jackson
   `ObjectMapper` doesn't understand Scala, forcing wire types to be Java-shaped
   (`@JsonCreator`/`@JsonProperty`, nullable `String`). We register `DefaultScalaModule` at
   startup via an `@Setup` `ServiceSetup` (see [`Bootstrap`](src/main/scala/com/gwgs/akkaagentic/application/Bootstrap.scala)),
   discovered through a **top-level** descriptor entry (a single FQCN, not under `components`):

   ```hocon
   akka.javasdk.service-setup = "com.gwgs.akkaagentic.application.Bootstrap"
   ```

   This lets **HTTP endpoint request/response bodies** be plain, annotation-free Scala case
   classes with `Option` fields — see `GreetingEndpoint.GreetRequest` / `GreetReply`.

   **Boundary (important):** the SDK uses *two* Jackson mappers. `JsonSupport.getObjectMapper()`
   — the one the module registers on, and the only one the public API exposes — governs **HTTP
   endpoint bodies**. But **component-to-component payloads** (agent `Request`/`Result` over
   `componentClient`, and by extension entity events/state, workflow state, view rows) are
   serialized by a *separate internal* mapper (`impl.serialization.JsonSerializer`) that the
   public hook does **not** reach. Those types must stay **Java-shaped** — hence
   `GreetingAgent.Request`/`Result` keep their Jackson annotations while the endpoint DTOs are
   idiomatic. Trying to make a component payload an annotation-free `Option` type fails at
   runtime with *"Cannot construct instance of `scala.Option`"*.

4. **The workflow method-reference wall — Workflows can't be authored in Scala.** The entire
   Akka `Workflow` API is keyed on Java **method references** resolved from `SerializedLambda`
   via `impl.client.MethodRefResolver`: step wiring (`transitionTo`, `thenTransitionTo`,
   `stepTimeout`, `stepRecovery`, `RecoverStrategy.failoverTo`) **and** the caller's
   `componentClient.forWorkflow(id).method(Workflow::start)`. The resolver requires a
   `Serializable` lambda whose `implMethodName` equals the target method name; a Scala lambda
   compiles to a synthetic `$anonfun$N` and never resolves. Crucially, unlike agents there is
   **no `dynamicCall` overload on `WorkflowClient`** and no string/step-name API anywhere — so a
   Scala workflow can neither wire its own steps nor be invoked. This is the workflow analogue
   of the two-mapper finding in §3: the least-friction path is to write the whole capability in
   **Java**. Capability 2 (the multi-agent greeting workflow, `com.gwgs.akkaagentic.team.*`) is
   therefore Java, fully decoupled from the Scala capability 1 — which stays put and unchanged.
   Mixing Java into this Scala module needs three `pom.xml` settings (annotation processor off
   via `-proc:none` so it can't overwrite the hand-maintained descriptor; `-parameters` restored
   for HTTP path binding; `scala-maven-plugin` `sendJavaToScalac=false` so scalac doesn't
   joint-compile the Java without `-parameters`).

5. **The Autonomous Agent has *no* method-reference wall — back to Scala.** Capability 3 (the
   autonomous help-desk agent, `com.gwgs.akkaagentic.assistant.*`) returns to **Scala**, because the
   entire `AutonomousAgent` API is keyed on `Class` references, `Task` constants, and annotations —
   **not** the Java `SerializedLambda` method references that make Workflows Java-only (§4). Verified
   against the SDK 3.6.0 bytecode: `forAutonomousAgent(Class, id)`, `runSingleTask(Task)`,
   `forTask(id).get(Task)`, `Task.name(...).resultConformsTo(Class)`, `AgentDefinition.capability(...)`
   — no `Function` parameter anywhere. So a Scala agent and a Scala caller compile to exactly what the
   SDK expects, with none of the workflow friction. The wall was **Workflow-specific**, not intrinsic
   to durable orchestration on this SDK. Three things to know:

   - **Descriptor key is `autonomous-agent`** (a distinct key from `agent`, confirmed from the
     annotation processor's constant pool). Add `HelpDeskAgent` under it, the endpoint under
     `http-endpoint`; the `HelpDeskTasks` holder and `HelpAnswer` are **not** components.
   - **The task result stays Java-shaped** (§3 again): `Task.resultConformsTo(classOf[HelpAnswer])`
     drives the built-in `complete_task` tool's schema *and* deserializes the model's completion
     through the SDK's *internal* mapper, so `HelpAnswer` is a Jackson-annotated Scala case class with
     a `java.util.List` (like cap-1's `GreetingAgent.Result`). The HTTP DTOs stay idiomatic.
   - **Gemini's tools-vs-JSON limit (§Gemini note) does *not* apply here**: the typed result is
     delivered by the `complete_task` *tool* (function calling), not a JSON response mime type, so the
     domain tool (`lookupPolicy`) and the typed completion coexist as two function-calling features.

   No `pom.xml` change was needed — the mixed build already compiles Scala cap-3, and (a pleasant
   surprise) the Scala `@Get("/help/{taskId}")` path binding works **without** scalac `-parameters`.

6. **Session memory is Scala-friendly to *build*, but has two *testing* limits.** Capability 4 (the
   multi-turn chat, `com.gwgs.akkaagentic.chat.*`) is **Scala**, and *building* on session memory adds
   **no** new interop friction: it is keyed by the session-id string already passed to `.inSession(id)`;
   the `MemoryProvider`/`MemoryFilter` API is builder-based (`String`/`int`/`Class` args — no method-ref
   wall); the backing `SessionMemoryEntity` is **registered by the runtime**, so it is deliberately
   **absent** from the hand-maintained descriptor (only `ChatAgent` under `agent` and `ChatEndpoint`
   under `http-endpoint` are added); and the agent payload is a **bare `String`** in/out, so — unlike
   cap-1's `Result` and cap-3's `HelpAnswer` — there is **no Java-shaped wire type** crossing the
   internal mapper (the least-interop capability in the project). *Testing* it, however, surfaced two
   real limits (feature 006 research R6):

   - **The mocked model never sees replayed history.** With `TestModelProvider`, each model call
     receives only the **current turn's** user message (verified: a size-1 message list across two
     turns; a 2s gap changes nothing — not a race). The assembled session history is not surfaced into
     a *test* model provider's input. So multi-turn **recall** (the model *using* prior turns) is not
     observable offline through the mock, and is proven by the **live smoke test** instead.
   - **Retention/isolation are provable offline — but only from Java.** Session memory *is* written and
     readable in tests: reading `SessionMemoryEntity` for the session id after two turns returns the
     4 stored messages (2 user + 2 AI), and a different id is empty — proving retention and isolation.
     But that read must be **Java**: the `EventSourcedEntity` client is **method-reference-only**
     (`SessionMemoryEntity::getHistory`) with **no `dynamicCall`** — the same wall as cap-2's
     `WorkflowClient` (§4). A Scala caller cannot query it, so cap-4's memory test
     ([`SessionMemoryIntegrationTest`](src/test/java/com/gwgs/akkaagentic/chat/application/SessionMemoryIntegrationTest.java))
     is Java — matching the test language to a Java SDK entity Scala can't reach. Everything else
     (agent wiring, HTTP contract, validation) stays Scala.

   No `pom.xml` change was needed — the mixed build already compiles Scala cap-4 and its one Java test.
   Takeaway: **the method-ref wall is not just a Workflow story — it recurs wherever the SDK client is
   keyed on a Java method reference with no `dynamicCall` escape hatch (Workflow *and* EventSourcedEntity
   clients).** The Agent and AutonomousAgent clients have `dynamicCall`; the entity/workflow clients do
   not.

7. **Human-in-the-loop is idiomatic Scala end-to-end — `TaskClient` has no method-ref wall.** Capability
   5 (the approval gate, `com.gwgs.akkaagentic.approvals.*`) is **Scala, tests included**, and is the
   clean counter-example to cap-2's Workflow wall (§4) and cap-4's forced Java entity test (§6). A
   `DraftAgent` produces a candidate reply; an **approval task with no agent assigned** gates it; a
   `PublishAgent` runs only once a human releases the gate. The mechanism is the Autonomous Agent
   **external-input** pattern — a three-task dependency chain (`draft → gate → publish`), **not** an Akka
   Workflow. Four things make this the least-friction orchestration capability yet:

   - **The human decision is a plain `TaskClient` call — no method reference.** `forTask(id).assign(label)`
     then `.complete(def, result)` (approve) or `.fail(reason)` (reject); `.get(def)`/`.result(def)` read
     the snapshot. Every method is keyed on value objects and strings (verified against SDK 3.6.0
     bytecode: `create`/`get`/`result`/`assign`/`complete`/`fail`), so there is **nothing for
     `MethodRefResolver` to choke on**. Contrast a Workflow `pause`/`resume` gate, which *is*
     method-ref-only and would force the whole capability into Java (§4). `TaskClient` sits on the
     Scala-friendly side of the wall, alongside the Agent/AutonomousAgent clients' `dynamicCall`.
   - **No Entity, no Workflow, no state of our own.** The three task ids are **derived** from one
     `caseId` (`{caseId}-draft`/`-approval`/`-publish`), so the endpoint reconstructs the whole chain
     from the URL and stores nothing; the tasks *are* the durable record (they survive restarts). This
     is a **correctness requirement, not tidiness**: a `KeyValueEntity` mapping store would have
     reintroduced the method-ref wall (entity clients are `.method(E::cmd)`-only, §6) and forced Java —
     the very thing cap-5 exists to disprove.
   - **The gate is enforced by the runtime, not our code.** The publish task `dependsOn` the approval
     task, and the runtime never starts a task with unmet dependencies — so `PublishAgent`, though
     *assigned* at submit time, cannot run until a person completes the gate; rejecting *fails* the gate,
     which auto-cancels the dependent publish task, so no reply is ever published. Nothing in
     [`ApprovalEndpoint`](src/main/scala/com/gwgs/akkaagentic/approvals/api/ApprovalEndpoint.scala) orders
     the steps.
   - **Task results stay Java-shaped; HTTP DTOs stay idiomatic** (the §3 two-mapper boundary again):
     `Draft`/`ApprovalDecision`/`PublishedReply` are Jackson-annotated (they cross the internal mapper via
     `resultConformsTo`), while the endpoint's request/response bodies are `Option`-typed Scala. The case
     **state machine** and the **decision guard** live in pure-Scala domain
     ([`ApprovalCase`](src/main/scala/com/gwgs/akkaagentic/approvals/domain/ApprovalCase.scala) /
     `TaskOutcome`), unit-tested with no runtime — so the integration test only has to prove the wiring
     and the dependency gate.

   No `pom.xml` change was needed. Takeaway: **the method-ref wall is client-specific, and `TaskClient`
   is on the right side of it** — a durable, human-gated, multi-step flow stays idiomatic Scala,
   verification included. See specs/007 research R1/R2 and
   [`docs/http-endpoint-sdk-boundary.md`](docs/http-endpoint-sdk-boundary.md) for the endpoint-layer
   boundary decision.

8. **Agent-to-agent delegation is Scala-clean — but the entity behind it forces a Java quarantine, and a
   mixed-language module needs a language-of-consumer rule.** Capability 6 (personal assistants,
   `com.gwgs.akkaagentic.a2a.*`) is a request-based `PersonalAssistantAgent`, one per username, that manages
   its own to-do list *and* can **delegate** to another user's assistant. It combines the two sides of the
   method-ref wall in a single capability, so it is the clearest illustration of where the wall does and
   doesn't bite:

   - **Delegation itself is idiomatic Scala (the positive result, research R1).** One assistant invokes
     another through the **agent** `ComponentClient`'s `dynamicCall` — exactly the escape hatch cap-1 uses
     (§2) — so [`ForwardTool`](src/main/scala/com/gwgs/akkaagentic/a2a/application/ForwardTool.scala) is a
     plain Scala tool object that calls `forAgent().inSession(targetUsername).dynamicCall(...)`. No wall.
     (`ForwardTool` is **not** a component, and crucially **not** an `Agent` handed to `.tools()` — the SDK
     forbids passing an `Agent` class as a tool; we only ever call the agent *through* the component client.)
   - **The to-do store re-hits the wall, and is quarantined in Java (research R2).** A per-user to-do list
     is durable state → a `KeyValueEntity`. But the **entity** client is method-reference-only
     (`.method(TodoEntity::add)`, no `dynamicCall`) — the same wall as cap-2's `WorkflowClient` (§4) and
     cap-4's `SessionMemoryEntity` (§6). So the entity **and its caller** are **Java**: the Scala agent
     reaches the entity only through a Java tool object,
     [`TodoTools`](src/main/java/com/gwgs/akkaagentic/a2a/application/TodoTools.java), which owns the
     `.method(TodoEntity::…)` calls. `TodoEntity` is listed in the descriptor under a **new
     `key-value-entity` key**; `TodoTools` is not a component. A consequence (FR-009): there is **no direct
     to-do HTTP endpoint** — to-dos are reached only *through* the assistant, because a Scala endpoint
     couldn't call the entity client anyway.
   - **Memory adds no friction (as in §6).** Chat history is the SDK's session memory keyed by `username`
     (`.inSession(username)` + `.memory(MemoryProvider.limitedWindow().readLast(10))`), backed by the
     runtime-owned `SessionMemoryEntity` — **not** in our descriptor. As with cap-4, offline tests prove
     *retention/isolation* (distinct usernames never bleed) but **recall is live-only** — the mock model
     sees only the current turn (research R6). Delegated replies and `listTodos` output both count against
     the `readLast(N)` window (research R5).
   - **Mixed Scala/Java in one package needs the language-of-consumer rule (research R8).** With Scala and
     Java types now side by side, the guideline is: **put each shared type in the language of its
     consumer(s), and only ever depend Scala→Java, never Java→Scala.** `Todo`/`TodoList` are pure
     data/logic but live in **Java** because their consumer is the Java `TodoEntity` (a Java class reading a
     Scala `case class` is noisy — `MODULE$`, `Option` interop); the Scala agent reading a Java record is
     clean. Verified empirically that Scala→Java compiles cleanly in this mixed build. The two-mapper
     boundary (§3) still holds: the agent's `Request` is Java-shaped (crosses the internal mapper), the HTTP
     body is idiomatic `Option`-typed Scala.
   - **This is the SDK-"discouraged" agent-chaining path, taken deliberately (research R7).** The docs steer
     multi-agent flows toward Workflows — but a Workflow would force the whole capability into Java (§4) and
     couldn't do **dynamic by-username targeting** (the target is a runtime string, not a compile-time
     method reference). An `AutonomousAgent` with a `Delegation` capability is the "blessed" alternative and
     is **pinned as the next capability (cap-7)** — cap-6 explores the lighter request-based chaining first,
     precisely to feel where it strains.

   No `pom.xml` change was needed (the mixed build from §4 already compiles Scala+Java here). Takeaway:
   **`dynamicCall` makes agent→agent delegation Scala-clean, but any *durable* per-actor state drags the
   entity — and its immediate caller — back to Java; keep that quarantine small and behind a tool object.**
   See specs/008 research R1/R2/R7/R8.

## Build

```shell
mvn compile
```

The build compiles Scala 3 via the `scala-maven-plugin` configured in `pom.xml`.

## Test

```shell
mvn verify
```

Tests register a `TestModelProvider`, so **no API key or network is required** — results are
deterministic.

## Run locally

By default this service runs against **local open-source models via [Ollama](https://ollama.com)**
— no API key, no network. `application.conf` sets `model-provider = ollama` (model `qwen3:8b`).
Pull the model once, then launch:

```shell
ollama pull qwen3:8b                          # once
mvn compile exec:java                          # uses local Ollama (qwen3:8b)
OLLAMA_MODEL=qwen3:14b mvn compile exec:java   # override the model
```

This repo has no `main` of its own: `exec:java` launches the Akka runtime entry point
(`kalix.runtime.AkkaRuntimeMain`, configured by the `akka-javasdk-parent` POM), which loads the
component descriptor and serves every component listed in it.

The service listens on `http://localhost:9000`.

#### Using Google Gemini instead

The provider is selected by `akka.javasdk.agent.model-provider`, made env-overridable via
`MODEL_PROVIDER` — so switching is a launch-time flag, no recompile and no code change. To use
Gemini (`gemini-2.5-flash`), supply a `GOOGLE_AI_GEMINI_API_KEY` (the JVM does **not** read `.env`
automatically) and flip the provider:

```shell
cp .env.example .env          # then set GOOGLE_AI_GEMINI_API_KEY and MODEL_PROVIDER=googleai-gemini
set -a && source .env && set +a && mvn compile exec:java
# or inline, one-off:
MODEL_PROVIDER=googleai-gemini GOOGLE_AI_GEMINI_API_KEY=… mvn compile exec:java
```

#### Choosing an Ollama model

**Pick a model that does reliable tool/function calling.** Four of the five capabilities deliver
structured output through function-calling tools — cap-1's `@FunctionTool`, and the
`AutonomousAgent` `complete_task` tool behind cap-3/cap-5's typed tasks — so the model must call
tools well, not just chat. On an M1 / 32 GB Mac, good choices (tested class, not exhaustive):

| Model | Notes |
|---|---|
| `qwen3:8b` *(default)* | Strong, reliable tool calling + JSON; fast. Start here. |
| `qwen2.5:14b` / `qwen3:14b` | Better structured-output reliability; still comfortable in 32 GB. |
| `llama3.1:8b` | Solid non-Qwen alternative with well-tested tool calling. |
| `qwen3:4b` | Fine for quick smoke tests; weakest at multi-step tool use — expect the occasional malformed completion on cap-3/cap-5 (caught by each agent's `onFailure` fallback). |

The Gemini-specific "tools vs. JSON" limitation (see the Gemini note below) does **not** apply to
Ollama; the agents already use the model-agnostic `responseAs` + `onFailure` path. Tests are
unaffected either way — they run on `TestModelProvider`, so no model server is needed for `mvn verify`.

> **Gemini note — tools vs. native structured output.** `GreetingAgent` both exposes a
> `@FunctionTool` (`currentTimeOfDay`) and returns a structured `Result`. Google Gemini
> **cannot combine function calling with a JSON response mime type** — a live call using the
> native-schema mode (`responseConformsTo`) fails with `500 INVALID_ARGUMENT: "Function calling
> with a response mime type: 'application/json' is unsupported"`. The agent therefore uses
> `responseAs` (the model is instructed to emit JSON in the system prompt, and the SDK parses
> the reply text) plus an `onFailure` fallback for the occasional non-JSON reply. OpenAI models
> support both together, so this constraint is Gemini-specific.

**Valid request** — returns a **structured** greeting `{greeting, tone, timeOfDay}` that names
the user, adapts to the message's intent, and reflects the caller's time of day. The optional
`timezone` (an IANA id) drives `timeOfDay`; a question or help request is acknowledged warmly,
a casual hello gets a casual reply:

```shell
# Structured success — note tone + timeOfDay in the response
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"How do I reset my password?","timezone":"America/New_York"}'
# 200 OK
# {"greeting":"Good evening, Ada — happy to help...","tone":"question","timeOfDay":"evening"}
```

The `timezone` is optional — a blank, invalid, or absent zone falls back to UTC (it is never a
validation error):

```shell
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hey there"}'
# 200 OK — {"greeting":"...","tone":"casual","timeOfDay":"..."}
```

**Invalid request** — blank `user`/`text` or a malformed JSON body is rejected with `400`
and the model is never called:

```shell
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"","text":"hi"}'
# 400 Bad Request
# user must not be blank
```

### Capability 2 — multi-agent workflow (`POST /greetings`, async)

Capability 2 orchestrates **two** agents through an Akka `Workflow`: a `ToneAgent` classifies the
message's tone, then a `GreetingComposerAgent` composes the greeting *given* that tone (and calls a
`@FunctionTool` for the time of day). Because a workflow runs its steps asynchronously, the HTTP
surface is **start-then-poll**: `POST /greetings` returns `202 Accepted` immediately with a handle
and a `Location`, then you poll `GET /greetings/{id}` until it returns `200`. (This capability is
implemented in **Java** — see "Scala interop notes" §4 for why.)

```shell
# 1. Start — returns 202 + Location + {"id": "..."}; the greeting is not composed yet
curl -i -X POST http://localhost:9000/greetings \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"How do I reset my password?","timezone":"America/New_York"}'
# 202 Accepted
# Location: /greetings/6c9ff9cc-...
# {"id":"6c9ff9cc-..."}
```

```shell
# 2. Poll — 404 while still in progress, 200 with the structured greeting once ready
curl -i http://localhost:9000/greetings/6c9ff9cc-...
# 404 Not Found        (while the workflow is still running)
# ...then...
# 200 OK
# {"greeting":"Good evening, Ada — happy to help.","tone":"question","timeOfDay":"evening"}
```

Same validation-first contract as capability 1: a blank `user`/`text` or malformed JSON body is
rejected with `400` before any workflow starts or model is called; an unknown/never-started id
polls as `404`.

### Capability 3 — autonomous help-desk agent (`POST /help`, async)

Capability 3 is a single **Autonomous Agent** (`HelpDeskAgent`) that answers a question through a
**model-driven** loop: given a question, the model decides on its own whether to consult a
knowledge-base `@FunctionTool` (`lookupPolicy`), then completes a **typed task** carrying
`{answer, category, citedTopics, confidence}`. Unlike capability 2's fixed workflow sequence, no code
orders the steps — the runtime drives the model until it completes (or fails) the task. The task is a
durable, queryable record, so the HTTP surface is again **start-then-poll**. (Back to **Scala** — see
"Scala interop notes" §5 for why the Autonomous Agent, unlike the Workflow, needs no Java.)

```shell
# 1. Start — returns 202 + Location + {"taskId": "..."}; the answer is not ready yet
curl -i -X POST http://localhost:9000/help \
  -H "Content-Type: application/json" \
  -d '{"question":"How do I reset my password?"}'
# 202 Accepted
# Location: /help/2f1c...
# {"taskId":"2f1c..."}
```

```shell
# 2. Poll — 404 while the agent iterates, 200 with the typed answer once COMPLETED
curl -i http://localhost:9000/help/2f1c...
# 404 Not Found        (while the agent is still working)
# ...then...
# 200 OK
# {"answer":"Use \"Forgot password\" on the sign-in page ...","category":"account",
#  "citedTopics":["password-reset"],"confidence":90}
```

`citedTopics` is populated when the model chose to consult the knowledge base, and empty when it
answered directly — the decision is the model's, not a fixed step. A task the agent reports it cannot
answer polls as `422 Unprocessable Content` (distinct from `404` not-ready and `200` success); a blank
question or malformed body is rejected `400` before any task starts; an unknown/never-started id polls
as `404`.

> **Why start-then-poll, and how the task survives — durability is in the runtime, not the agent
> code.** A model-driven loop is long-running (several LLM round-trips), so `POST /help` starts the
> work and returns a handle instead of blocking the HTTP request; the caller polls (or, in code, could
> `componentClient.forTask(taskId).result(ANSWER)` to block until terminal). What makes that safe is
> that the **task is durable** — and notice that *nothing in [`HelpDeskAgent`](src/main/scala/com/gwgs/akkaagentic/assistant/application/HelpDeskAgent.scala)
> opts into persistence*: no `persist(...)`, no state field, no annotation. Durability is intrinsic to
> the `AutonomousAgent`/`Task` primitives. The runtime persists two things as the loop progresses —
> the **task** (its id, status, and typed result) and the **agent process state** (its queue and
> iteration bookkeeping) — to the service's durable store, and recovers them automatically after a
> crash or restart (Akka docs: *"Agent and task state is persisted along the way, so work survives
> crashes and restarts"*). A task that was mid-flight resumes from its last persisted point; a
> `COMPLETED` task's result stays queryable by its `taskId`. This is exactly why **no wrapping
> `Workflow` is needed** for durability — the task already *is* the durable record. (Contrast an
> `EventSourcedEntity`, where *you* write `effects().persist(event)` yourself.)
>
> **Local caveat — persistence is opt-in when running with `exec:java`.** By default the local dev
> runtime uses an **in-memory** store, so a restart loses all state. To observe durability across a
> local restart, enable the on-disk (H2) store:
>
> ```shell
> set -a && source .env && set +a && \
>   mvn compile exec:java -Dakka.javasdk.dev-mode.persistence.enabled=true
> ```
>
> This writes a `db.mv.db` file; start → poll to `200`, restart the same command, and `GET
> /help/{taskId}` still returns the answer from disk. A **deployed** service always has its backing
> datastore on, so this flag is a local-development concern only. See Akka's *Running locally* docs
> (`akka-context/sdk/running-locally.html.md`, "Running a service with persistence enabled").
>
> *Verified locally:* with the flag on, a `COMPLETED` task's typed result survived a full JVM restart
> — a fresh process (new PID), started after the first was confirmed down, reconstructed the same
> `{answer, category, citedTopics, confidence}` from the on-disk store.

### Capability 4 — multi-turn chat (`POST /chat/{sessionId}`, synchronous)

Capability 4 is a single request-based **`ChatAgent`** that holds a **multi-turn conversation**. There
is no per-turn state in the agent: the runtime's **session memory**, keyed by the `sessionId` in the
path, stores each turn and replays earlier turns as context on the next call with the same id. Unlike
capabilities 2 and 3, this is **synchronous** — `POST /chat/{sessionId}` returns the reply directly, no
polling. Reusing the same `sessionId` across requests is the whole feature. (Back in **Scala** — see
"Scala interop notes" §6.)

```shell
# 1. State a fact on a conversation id you choose
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"my name is Ada"}'
# 200 OK — {"sessionId":"c-123","reply":"Nice to meet you, Ada!"}
```

```shell
# 2. Ask about it on the SAME id — the reply recalls turn 1
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK — {"sessionId":"c-123","reply":"Your name is Ada."}
```

A **different** id is a separate conversation with no shared context:

```shell
curl -i -X POST http://localhost:9000/chat/c-999 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK — the reply does not know "Ada"
```

Same validation-first contract as the other capabilities: a blank/absent `message` or a malformed JSON
body is rejected with `400` before the assistant is engaged; an unknown extra property is tolerated.

```shell
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — message must not be blank
```

> **Where memory lives, and why the offline tests are split across two languages.** Nothing in
> [`ChatAgent`](src/main/scala/com/gwgs/akkaagentic/chat/application/ChatAgent.scala) persists anything —
> it just sets `.memory(MemoryProvider.limitedWindow())`. The conversation history is written to the
> SDK-internal `SessionMemoryEntity` (an event-sourced entity keyed by `sessionId`) and replayed
> automatically; durability is intrinsic, exactly as with cap-3's task. When testing this offline we
> found (feature 006 research R6) that a mocked model is fed **only the current turn**, so *recall* is
> verified by a **live** smoke test, while *retention* and *isolation* are proven offline by reading
> `SessionMemoryEntity` directly — a **Java** test, because the entity client is method-ref-only (no
> `dynamicCall`) and Scala can't call it. See "Scala interop notes" §6.
>
> *Verified live:* against Gemini, `POST /chat/c-123 {"message":"my name is Ada"}` then
> `POST /chat/c-123 {"message":"what is my name?"}` replied *"Your name is Ada!"* — recall across two
> separate requests on the same `sessionId`. The same question on a different id
> (`POST /chat/c-999`) replied *"I don't know your name yet! You haven't told me."* — confirming memory
> replay **and** per-session isolation end-to-end.

### Capability 5 — human-in-the-loop approval gate (`POST /approvals`, async + a human decision)

Capability 5 puts a **person in the loop**. A `DraftAgent` writes a candidate customer reply; the work
then **pauses at a gate only a human can release**; on approval a `PublishAgent` produces the published
reply, on rejection the chain ends. The mechanism is the Autonomous Agent **external-input** pattern — a
three-task chain (`draft → unassigned gate → publish`) wired by task dependencies, **not** an Akka
Workflow. Because the draft is produced asynchronously and the gate then waits for a person, the surface
is **start → poll → decide → poll**. (Back in **Scala**, tests included — see "Scala interop notes" §7:
the human decision is a plain `TaskClient` call with no method-ref wall.)

```shell
# 1. Submit a question — 202 + a case handle; the draft is being produced
curl -i -X POST http://localhost:9000/approvals \
  -H "Content-Type: application/json" \
  -d '{"question":"How do I get a refund?"}'
# 202 Accepted
# Location: /approvals/2f1c...
# {"caseId":"2f1c..."}
```

```shell
# 2. Poll — "drafting" until the agent finishes, then "awaiting-approval" WITH the draft and NO reply
curl -s http://localhost:9000/approvals/2f1c...
# {"state":"drafting"}
# ...then...
# {"state":"awaiting-approval","draft":"You can request a refund within 30 days…"}
```

```shell
# 3a. APPROVE — releases the publish step; poll to "published" with the final reply
curl -i -X POST http://localhost:9000/approvals/2f1c.../approve \
  -H "Content-Type: application/json" -d '{"note":"Looks good."}'
# 200 OK — approved
curl -s http://localhost:9000/approvals/2f1c...
# {"state":"published","reply":"You can request a refund within 30 days…"}
```

```shell
# 3b. REJECT (on a fresh case) — fails the gate; the publish task is auto-cancelled, so no reply is
#     ever published. The note is retained.
curl -i -X POST http://localhost:9000/approvals/<id>/reject \
  -H "Content-Type: application/json" -d '{"note":"Tone is too casual; please revise."}'
# 200 OK — rejected
curl -s http://localhost:9000/approvals/<id>
# {"state":"rejected","note":"Tone is too casual; please revise."}
```

Same validation-first contract as the other capabilities, plus decision integrity: a blank question or
malformed body is `400` before any case starts; an unknown handle is `404`; a decision before the draft
is ready, or a **second** decision after a gate is decided, is `409` (never a double-publish). Empty
optional fields are omitted from the JSON (idiomatic Scala).

> **Where durability lives — the task chain, not an Entity of ours.** Nothing in `ApprovalEndpoint`
> persists anything: the three task ids are derived from one `caseId`, and the **tasks themselves** are
> the durable record (the runtime persists task status + typed result, exactly as with cap-3). So the
> pending gate survives a restart — drive a case to `awaiting-approval`, restart, then approve, and it
> still publishes. As with cap-3, observing this across a **local** restart needs the on-disk store:
>
> ```shell
> set -a && source .env && set +a && \
>   mvn compile exec:java -Dakka.javasdk.dev-mode.persistence.enabled=true
> ```
>
> *Verified live* (against Gemini, `gemini-2.5-flash`): both paths end-to-end.
> **Approve** — `POST /approvals {"question":"How do I get a refund?"}` → poll returned
> `{"state":"awaiting-approval","draft":"To request a refund, please refer to our refund policy…"}`
> with **no `reply` field**; then `POST …/approve {"note":"Looks good."}` → `200 approved`, and the next
> poll returned `{"state":"published","reply":"To request a refund, please refer to our refund policy…"}`
> — the published reply **identical to the approved draft**, and present **only after** approval.
> **Reject** — a fresh case reached `awaiting-approval` with its own draft; `POST …/reject {"note":"Tone
> is too casual; please revise."}` → `200 rejected`, and polling returned
> `{"state":"rejected","note":"Tone is too casual; please revise."}` with **no `reply`, ever** (stable
> across repeated polls). Confirms the gate genuinely holds publishing until a human approves (SC-002,
> SC-003), rejection stops it for good (FR-006), and — as noted in the Gemini caveat — the typed task
> results round-trip through the `complete_task` tool with **no** tools-vs-JSON conflict.

### Capability 6 — agent-to-agent delegation (`POST /request/{username}`, synchronous)

Capability 6 gives each **username** its own `PersonalAssistantAgent`: it remembers the conversation
(session memory keyed by the username), keeps a personal **to-do list**, and — the headline — can
**delegate** to *another* user's assistant, relaying that assistant's reply. Everything runs through one
synchronous endpoint, `POST /request/{username}`, and the model decides (from the message) whether to
manage the caller's own to-dos or to forward to someone else's assistant. (Back in **Scala**, tests
included — the agent→agent call goes through `dynamicCall`; only the durable to-do entity is quarantined
in Java. See "Scala interop notes" §8.)

```shell
# Manage your OWN to-do list in natural language — the model calls the to-do tools
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to buy milk"}'
# {"username":"alice","reply":"Added \"buy milk\" as item 1."}
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"what is on my list?"}'
# {"username":"alice","reply":"1. buy milk (open)"}
```

**Delegation** — ask your assistant to get something done by *another* user's assistant. The effect lands
under the **target** user; your reply relays their assistant's confirmation:

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" \
  -d '{"message":"ask bob'\''s assistant to add a to-do: prepare slides"}'
# {"username":"alice","reply":"bob's assistant: Added \"prepare slides\" as item 1."}

curl -s -X POST http://localhost:9000/request/bob \
  -H "Content-Type: application/json" -d '{"message":"what is on my list?"}'
# {"username":"bob","reply":"1. prepare slides (open)"}   <- landed under bob, not alice
```

**Memory & isolation** — the same username is one continuous conversation; a different username is a
separate one with its own memory *and* its own to-do list:

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"my name is Alice"}'
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# recalls "Alice" (same username = same conversation)
curl -s -X POST http://localhost:9000/request/carol \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# does NOT know "Alice" (different username = isolated)
```

Same validation-first contract as the other capabilities: a blank/absent `message` or malformed JSON body
is `400` before the assistant is engaged; an unknown extra property is tolerated. A **one-hop loop guard**
bounds delegation: a request that arrived *as a delegate* is offered no forward tool, so an A→B→A chain
cannot form.

```shell
curl -i -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — message must not be blank   (no model call)
```

> **Where durability lives — session memory + a to-do entity, none of it in the agent.** The assistant is
> stateless and short-lived; each turn the runtime replays the last N messages from the runtime-owned
> `SessionMemoryEntity` (keyed by username) and the to-dos live in a `TodoEntity` (`KeyValueEntity`, keyed
> by username). Neither is persisted by our code. To observe history/to-dos surviving a **local** restart,
> enable the on-disk store (as with cap-3/cap-5):
>
> ```shell
> mvn compile exec:java -Dakka.javasdk.dev-mode.persistence.enabled=true
> ```
>
> **Two interop findings in one capability** (see §8): delegation is idiomatic Scala via the agent
> client's `dynamicCall` (no wall), but the to-do `KeyValueEntity` client is method-reference-only, so the
> entity **and its caller** are quarantined in Java behind a `TodoTools` tool object — the same wall as
> cap-2/cap-4. As with cap-4, offline tests prove *retention/isolation* while **recall is verified live**
> (the mock model sees only the current turn).
>
> *Verified live* (against local Ollama, `qwen3:8b`), all four scenarios end-to-end:
> **Own to-dos** — `add buy milk` / `add call the dentist` / `what is on my list?` returned the two items
> (`1. buy milk (open)`, `2. call the dentist (open)`). **Delegation** — `emma` asking *"ask frank's
> assistant to add a to-do: prepare slides"* replied *"Frank's assistant added the to-do 'prepare slides'
> with ID 1"*; `frank`'s list then showed `1. prepare slides (open)` while `emma`'s stayed empty — the
> effect landed under the **target** and stayed isolated. **Recall** — on `iris`, *"my name is Iris"* then
> *"what is my name?"* replied *"Your name is Iris."*, while `jack` (a different username) *"don't have
> access to your name"* — recall across turns **and** per-user isolation. **Validation** — a blank
> `message` returned `400`.
>
> > **Live caveat — qwen3:8b can emit a null-content tool-call turn, and it durably poisons that session.**
> > On one delegation attempt the model returned an assistant message with `content: null` on a tool-call
> > turn (runtime `AK-01202 … argument "content" is null`); that request failed **and** every *subsequent*
> > turn for the same username kept failing, because the bad message was persisted into session memory and
> > re-thrown on replay. It is **intermittent** (a fresh `emma → frank` delegation succeeded on the first
> > try) and **model-specific** (a known Ollama/qwen tool-calling quirk, not a wiring defect — the 131
> > offline tests on `TestModelProvider` are green). Mitigations if it bites in practice: prefer a
> > stronger tool-calling model (`qwen2.5:14b` / `qwen3:14b`, or a hosted model), and/or add an
> > `.onFailure(...)` fallback so a null-content reply doesn't propagate raw. Left as an honest limitation
> > of running a small local model, not patched over.

You can use the [Akka Console](https://console.akka.io) to create a project and see the status of
your service.

## Deploy

Build the container image:

```shell
mvn clean install -DskipTests
```

Install the `akka` CLI as documented in
[Install Akka CLI](https://doc.akka.io/reference/cli/index.html), then deploy using the image tag
from the `mvn install` above:

```shell
akka service deploy akka-agentic-scala3 akka-agentic-scala3:tag-name --push
```

Refer to [Deploy and manage services](https://doc.akka.io/operations/services/deploy-service.html)
for more information.
