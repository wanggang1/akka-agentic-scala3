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

# Capability 7 — Scala (AutonomousAgent Delegation; the SDK-recommended dynamic delegation; see §9)
src/main/scala/com/gwgs/akkaagentic/activities/domain/      # SuggestionQuestion (validation), WeatherData (canned, offline)
src/main/scala/com/gwgs/akkaagentic/activities/application/ # ActivityCoordinator (AutonomousAgent + Delegation.to), WeatherSpecialist + ActivitySpecialist (request-based delegates), ActivityTasks, ActivitySuggestion (Java-shaped result)
src/main/scala/com/gwgs/akkaagentic/activities/api/         # ActivityEndpoint (POST /activities, GET /activities/{taskId})
# Note: a coordinator (AutonomousAgent) delegates to two request-based specialist Agents via the built-in
# Delegation capability — the "recommended" dynamic-delegation primitive, contrasting cap-6's hand-rolled
# ForwardTool chaining and cap-2's fixed Java Workflow. All Scala (no method-ref wall). The Task is the
# durable record (no Entity/Workflow). ActivityTasks/ActivitySuggestion are NOT components. See docs/
# multi-agent-delegation-patterns.md.

# Capability 8 — Scala (RAG: retrieval-augmented, grounded Q&A; see "Scala interop notes" §10)
src/main/scala/com/gwgs/akkaagentic/docs/domain/      # Passage, KnowledgeCorpus (canned corpus), AskQuestion (validation)
src/main/scala/com/gwgs/akkaagentic/docs/application/ # KnowledgeStore (in-process embeddings + in-memory vector store; NOT a component), DocsAgent (grounded/declines; Java-shaped Request, bare-String reply)
src/main/scala/com/gwgs/akkaagentic/docs/api/         # DocsEndpoint (POST /ask, synchronous)
# Note: real semantic retrieval on in-process embeddings — langchain4j all-minilm-l6-v2-q (ONNX model
# packaged in-jar) + InMemoryEmbeddingStore, ALREADY in the SDK 3.6.0 dep tree (only a runtime->compile
# scope bump). The endpoint retrieves top-k and hands passages to DocsAgent; citations are computed
# endpoint-side from what was retrieved (ground truth, not model self-report). KnowledgeStore is a custom
# dependency injected via Bootstrap's DependencyProvider (the project's first non-ComponentClient
# injection — Scala-clean). Retrieval is deterministic → offline-testable; the answer is mocked. See §10.

# Capability 9 — Scala (MCP server: expose cap-8 retrieval over the Model Context Protocol; see §11)
src/main/scala/com/gwgs/akkaagentic/mcp/api/          # KnowledgeMcpEndpoint (@McpEndpoint at /mcp: `retrieve` @McpTool + corpus-sources @McpResource)
# Note: an @McpEndpoint is an ENDPOINT (no @Component), invoked reflectively from JSON-RPC — no
# ComponentClient method-ref, so it's Scala-clean (the wall is a *client* property; §11). New descriptor
# key `mcp-endpoint`. Reuses cap-8's KnowledgeStore (same DI); a @McpTool MUST return String (result
# rendered to JSON, errors via throw→isError); bare params work (scalac emits names). Fully offline-tested
# over JSON-RPC (no MCP testkit). SDK-3.6.0 limit: no tunable maxResults → fixed top-K 3 (see
# docs/sdk-3.6.0-limitations.md). Server-side only; the client (.mcpTools()) is cap-10.

# Capability 10 — Scala (MCP client: agentic RAG via a remote MCP tool; see "Scala interop notes" §12)
src/main/scala/com/gwgs/akkaagentic/mcpclient/application/ # McpClientAgent (Agent + .mcpTools(RemoteMcpTools.fromService) → cap-9's /mcp; Config-injected service name)
src/main/scala/com/gwgs/akkaagentic/mcpclient/api/         # McpClientEndpoint (POST /grounded-ask, synchronous)
# Note: a request-based agent that grounds by calling the REMOTE `retrieve` MCP tool of THIS service's own
# cap-9 /mcp — the model decides whether/when to retrieve (agentic RAG), contrasting cap-8's endpoint
# pre-retrieval. Closes the loop in-process (agent → MCP client → our MCP server → KnowledgeStore), fully
# offline. Consuming a remote MCP server is Scala-clean: RemoteMcpTools is a URL-string builder, NO
# method-ref wall (R1, bytecode-verified) — the wall is a *client* property and the MCP client isn't a
# ComponentClient. No new domain (reuses cap-8's AskQuestion/KnowledgeStore); no citations (the model owns
# retrieval — the cap-8-fork tradeoff). The tool loop IS faithfully offline-testable (TestModelProvider
# scripts a real `retrieve` round-trip) — a positive contrast to cap-7's D9. Reuses cap-9's server; no
# ACL edit needed (a same-service /mcp call passes the INTERNET-only ACL).

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
   - **Memory adds no friction to *build* — but `readLast(N)` has a tool-call sharp edge.** Chat history is
     the SDK's session memory keyed by `username` (`.inSession(username)` + `.memory(MemoryProvider.limitedWindow()...)`),
     backed by the runtime-owned `SessionMemoryEntity` — **not** in our descriptor. As with cap-4, offline
     tests prove *retention/isolation* (distinct usernames never bleed) but **recall is live-only** — the
     mock model sees only the current turn (research R6). **We deliberately do NOT use `.readLast(N)`:** its
     naive last-N trim orphans a tool-call/response pair once a tool-using session exceeds N messages, which
     the model provider rejects as an invalid sequence (surfaced misleadingly as `argument "content" is
     null`). This was cap-6's real live failure — proven by removing `readLast` — so we keep **full history**
     and accept the token-growth tradeoff (compaction is the proper bound; future work). See the cap-6 "Live
     caveat".
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

9. **AutonomousAgent `Delegation` is Scala-clean and the "recommended" counterpart to cap-6 — but
   request-based delegation isn't faithfully *mockable* offline in this SDK.** Capability 7 (the activity
   coordinator, `com.gwgs.akkaagentic.activities.*`) is an `AutonomousAgent` that **delegates** to two
   request-based specialist `Agent`s via the built-in `Delegation.to(...)` capability and **synthesizes** a
   typed result. It is the SDK-**recommended** dynamic-delegation primitive (docs steer multi-agent flows
   here), the blessed counterpart to cap-6's discouraged hand-rolled `ForwardTool` chaining, and a third
   point against cap-2's fixed Java Workflow. Findings:

   - **Delegation is Scala-clean — no method-ref wall (research D1/D2).** `Delegation.to(Class...)`,
     `TaskAcceptance.of(Task)`, `runSingleTask`, `forTask(id).get(Task)` are all keyed on `Class`/`Task`,
     not Java method references (cap-3/cap-5 §5). The specialists are called *by the framework* (delegation
     tools), never by our code, so there is no client method-ref to trip on. Whole capability is Scala.
   - **`Delegation.to(...)` accepts request-based *and* autonomous workers (bytecode-verified).** Its
     signature is `Delegation.to(Class<? extends AgentDelegationWorker>...)`, and **both** `Agent` and
     `AutonomousAgent` implement `AgentDelegationWorker`. We chose **request-based specialists** (Simplicity;
     cleanest cap-6 contrast). **Verified live** that a request-based worker really is delegated to (an
     unknown location returned `WeatherData`'s canned default — un-hallucinatable — proving the weather
     specialist actually ran).
   - **The two-mapper boundary holds (§3).** The task result `ActivitySuggestion` is Java-shaped (it crosses
     the internal mapper via `resultConformsTo`/`complete_task`), while the HTTP DTOs are idiomatic
     `Option`-typed Scala. The Task is the durable record — no Entity, no Workflow (like cap-3/cap-5).
   - **Offline testing limit (research D9) — request-based delegation is NOT faithfully mockable in the SDK
     3.6.0 testkit.** `AutonomousAgentTools.delegateTo(Class, String)` delivers a generic `json.akka.io/object`
     the worker can't deserialize (proven with a `String` param *and* a record). A delegation mock is a
     silent **false-green** (the WARN-level delegation failure doesn't fail the test). So the offline tests
     assert the coordinator→task→typed-result→endpoint path with a **direct** completion, and **delegation +
     synthesis is proven live**. `consultedSpecialists` is model-**self-reported** (research D6) and
     unreliable on small models (qwen3 under-reports which specialists it consulted), so SC-002 (dynamic
     choice) is a live/best-effort criterion. Both are logged TODOs (revisit on a newer SDK; use runtime
     notifications for ground-truth delegation records). See specs/009 research D1/D6/D9 and
     [`docs/multi-agent-delegation-patterns.md`](docs/multi-agent-delegation-patterns.md).

10. **RAG is Scala-clean, the retrieval stack ships with the SDK, and — unlike delegation — retrieval is
    fully offline-testable.** Capability 8 (the grounded Q&A agent, `com.gwgs.akkaagentic.docs.*`) does
    real **retrieval-augmented generation**: it embeds a small canned corpus into an in-memory vector
    store, retrieves the passages most **semantically** similar to a question, and has a `DocsAgent`
    answer grounded **only** in them (or honestly decline). It is **Scala end-to-end, tests included**,
    and surfaces four findings:

    - **The in-process RAG stack is ALREADY in the SDK 3.6.0 dependency tree (research R1).**
      `akka-javasdk` pulls langchain4j `1.15.0` (carrying `InMemoryEmbeddingStore` + the retrieval core)
      and `langchain4j-embeddings-all-minilm-l6-v2-q:1.15.0-beta25` (the quantized all-MiniLM-L6-v2 ONNX
      model). The **22 MB ONNX model is packaged in-jar** (`unzip -l` shows `all-minilm-l6-v2-q.onnx`), so
      embedding is **fully offline — no network, no API key**, matching this sandbox's ethos. The only
      `pom.xml` change is promoting two already-present artifacts `runtime → compile` at the **SDK-aligned
      versions** (zero new version, zero conflict) so Scala source may reference them — the constitution's
      dependency-justification gate satisfied by *reuse*, not a new stack. `KnowledgeStore` does the flat
      `embed` + `store.search` directly (no `RetrievalAugmentor` plumbing — Simplicity).
    - **Custom-dependency injection is Scala-clean — a new positive interop result (research R5).**
      `KnowledgeStore` is a plain class (NOT a component), provided as a singleton via `Bootstrap`'s
      `override def createDependencyProvider(): DependencyProvider` and **constructor-injected** into
      `DocsEndpoint`. This is the project's **first injection of a dependency other than the SDK's
      `ComponentClient`**, and it *just works* from Scala: `DependencyProvider` is `Class`-keyed
      (`getDependency[T](cls: Class[T]): T`), so — like the Agent/AutonomousAgent/Task clients (§5, §7) and
      unlike the Workflow/entity clients (§4, §6) — there is **no method-ref wall**. The store is built
      **once, eagerly**, at startup (seeding embeddings; the ~22 MB model load is a one-time cost paid at
      service/TestKit start, not per request). *Production would index via a Workflow* — Java-only (§4) — so
      we **seed at bootstrap** to keep the capability Scala.
    - **The two-mapper boundary holds, and citations are ground truth, not self-report (research R3/R4).**
      Retrieval happens in the **endpoint**, once; it hands the passages to the agent as a **Java-shaped
      `DocsAgent.Request`** (crosses the internal mapper, §3), and the agent replies with a **bare `String`**
      (no Java-shaped result needed — the cap-4 shape). Citations are computed **endpoint-side from what was
      actually retrieved** — so they are *ground truth*, directly sidestepping cap-7's D6 unreliability (the
      model never authors the citation list). The model's only citation-affecting signal is a **decline
      sentinel** (`DocsAgent.DontKnow`): on a decline the endpoint cites nothing (FR-005).
    - **Retrieval is the clean counter-example to cap-7's D9 — it IS faithfully offline-testable
      (research R6).** Because the in-process embeddings are **deterministic**, *which passages ground an
      answer* is verified **offline with no model** (`KnowledgeStoreTest`: a paraphrased in-corpus query —
      wording unlike the source — returns the right passage by meaning; two distinct queries return
      different top passages). Only the **generative** half needs a model, mocked via `TestModelProvider`;
      end-to-end grounding is proven **live**. So RAG splits cleanly: **retrieval half offline-provable,
      generative half live/mocked** — the opposite of cap-7's un-mockable delegation.

    A teaching detail the corpus surfaced: a *vague* "how does the assistant remember a conversation?"
    slightly favors cap-6's passage (which also says "remembers the conversation") over cap-4's session
    memory (0.778 vs 0.756) — naming the "session id" discriminates cleanly (0.828). That *is* semantic
    retrieval: closely-worded passages compete, and specificity wins. No `pom.xml`/build change beyond the
    two dependency pins. See specs/010 research R1/R3/R4/R5/R6 and
    [`docs/http-endpoint-sdk-boundary.md`](docs/http-endpoint-sdk-boundary.md) (the endpoint-layer boundary).

11. **An MCP server is Scala-clean — the wall is a *client* property, and MCP has no client to author.**
    Capability 9 (the knowledge MCP server, `com.gwgs.akkaagentic.mcp.*`) exposes cap-8's semantic
    retrieval over the **Model Context Protocol** — a `retrieve` **tool** and a corpus-sources **resource**,
    served as JSON-RPC at `/mcp`. It is **Scala end-to-end, tests included**, and confirms the interop
    verdict the whole series predicts:

    - **`@McpEndpoint` is an *endpoint*, not a `@Component` — and it's Scala-clean (research R1).** Like an
      HTTP endpoint it carries no `@Component`; a remote MCP client calls its `@McpTool`/`@McpResource`
      methods over JSON-RPC and the SDK dispatches to them **reflectively from the request**. There is **no
      `ComponentClient` method reference** for us to author, so none of the Workflow/entity method-ref wall
      (§4, §6) applies — the wall is a *client* property, and an MCP endpoint has no client. New descriptor
      key **`mcp-endpoint`** (confirmed from the SDK's `ComponentType` constant pool); `KnowledgeMcpEndpoint`
      is added under it. **No `pom.xml` change** — the mixed build (§4) already compiles it.
    - **Bare tool params work in Scala — a positive finding (research R2).** The SDK reflects each tool
      *parameter* into a top-level JSON-Schema property **by its name**, and scalac emits parameter names in
      this build (the mixed-build `-parameters` flag, §4). So `retrieve(question)` needs **no wrapper record
      and no manual `inputSchema`** — the SDK's own tool shape. (A wrapper-record + manual-schema attempt
      *failed* first, which is how this was pinned; see specs/011 R2's empirical correction.)
    - **A `@McpTool` MUST return `String`, and throwing is its error channel (research R3).** The SDK rejects
      any other tool return type at startup (*"MCP tool method must return String"*), so there is no
      `Effect`/`Either`/typed-result return; the result is **rendered to a JSON string**. And a tool has no
      typed error return either — **throwing** is the mechanism: the runtime converts a thrown exception into
      a well-formed `{content:[{type:text,text:<msg>}], isError:true}` result the calling model sees. So the
      domain stays pure — `AskQuestion.validate` returns `Either`, and the endpoint adapter throws on `Left`,
      mirroring `DocsEndpoint`'s `Left → HttpResponses.badRequest`.
    - **A zero-arg `@McpResource` is the simplest surface — Scala-clean, no friction (research R5).** The
      corpus-sources resource is a zero-parameter method returning a JSON `String`: **no input schema, no
      param-name/mapper concern**, so it carries none of the tool's parameter friction. `resources/list` +
      `resources/read` round-trip in Scala with no snag.
    - **Retrieval is reused, injected, and offline-testable (R4/R6).** `KnowledgeStore` is the **same**
      cap-8 dependency, obtained by constructor injection via `Bootstrap`'s `DependencyProvider` (cap-8 R5,
      Scala-clean) — no new retrieval logic. Because the embeddings are deterministic, the whole capability
      is verified **offline over real JSON-RPC** (testkit `httpClient` + hand-crafted payloads — there is no
      MCP-specific testkit): 8 tests incl. **SC-004 parity** (MCP results equal a direct
      `KnowledgeStore.retrieve` for the same question) and the resource labels equal
      `KnowledgeCorpus.passages.map(_.source)`. No model needed.
    - **One SDK-3.6.0 limitation, not an interop wall — no tunable `maxResults`.** An optional numeric tool
      arg cannot be expressed on 3.6.0: the doc-recommended `Optional[Integer]` bare param throws
      *"Optional cannot be cast to Integer"* on a *supplied* value; a plain `Integer` is forced *required*; a
      manual `inputSchema` on bare params breaks binding. So `retrieve` takes only `question` and returns a
      **fixed top-K of 3**, exactly mirroring cap-8's `DocsEndpoint` — a faithful mirror, not a retrieval
      loss. This is a version bug (revisit on upgrade), tracked in
      [`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md); see specs/011 R2 (the three dead ends).
    - **Server-side only; the client is cap-10.** This capability is the MCP *server*. Consuming a remote MCP
      server from an agent (`.mcpTools(url)`) is a separate capability — deliberately split out (the cap-8
      "retrieval-as-a-tool" fork anticipated it) so the server/client interop questions stay one-per-feature.

    Takeaway: **MCP extends the "wall is a client property" through-line — an endpoint invoked reflectively
    has no client, so it's Scala-clean like HTTP endpoints.** The only friction was a version-specific SDK
    bug, not the language boundary. See specs/011 research R1–R6 and README's cap-9 usage section below.

12. **Consuming a remote MCP server is Scala-clean too — the "wall is a client property" through-line, now
    from the *outbound* side.** Capability 10 (the MCP client, `com.gwgs.akkaagentic.mcpclient.*`) is the
    consuming counterpart to cap-9: a request-based `McpClientAgent` grounds its answers by calling the
    **remote `retrieve` MCP tool** of this service's own cap-9 `/mcp` server, and the model decides
    whether/when to retrieve — the "retrieval-as-a-tool / agentic RAG" fork cap-8 flagged, contrasting
    cap-8's endpoint pre-retrieval. It closes the loop entirely in-process (agent → MCP client → our MCP
    server → cap-8's `KnowledgeStore`), fully offline. **Scala end-to-end, tests included**, and it settles
    the last MCP interop question:

    - **`.mcpTools(RemoteMcpTools.fromService/fromServer(...))` is Scala-clean (R1, bytecode-verified).**
      `RemoteMcpTools` is a **URL-string/config builder** — `fromServer(String)`/`fromService(String)` plus
      `Predicate`/`Set`/`HttpHeader`/`Duration`/interceptor methods — with **no Java method reference
      anywhere**. So the *consuming* side hits none of the Workflow/entity method-ref wall (§4/§6): the wall
      is a `ComponentClient`-method-ref property, and the MCP client is **not** a `ComponentClient` (same
      reason `.tools()` and the Agent/AutonomousAgent/Task/DI clients are Scala-clean). Cap-9 proved a *server*
      endpoint has no client to author; cap-10 proves the one place there *is* an outbound call is configured
      by a URL string, not a method ref. No `pom.xml` change (the mixed build already compiles it).
    - **The self-call topology just works — no ACL edit, cap-9 untouched (R2/S1).**
      `RemoteMcpTools.fromService(<service name>)` builds `http://<name>/mcp` (the `/mcp` path is **hardcoded**
      in the factory — R2b) and routes to **this** service's own in-process `/mcp`; a service can address
      itself by name. Cap-9's INTERNET-only `@Acl` **permits the self-service call unchanged** (a
      same-service MCP call is not denied), so the anticipated ACL broadening proved unnecessary. The service
      name is **config-overridable with an artifactId fallback** (`McpClientAgent` injects
      `com.typesafe.config.Config`; precedence: `mcp-client.knowledge-service-name` → the SDK's resolved
      `dev-mode.service-name` → the artifactId `akka-agentic-scala3`), so the self-call stays correct across
      TestKit, local `exec:java`, and a differently-named deploy. Note the three easily-conflated names:
      the **Akka service name** (`akka-agentic-scala3`, what `fromService` routes on) ≠ the **MCP protocol
      `serverName`** (`akka-agentic-knowledge-mcp`, a handshake id) ≠ the **path** (`/mcp`).
    - **The tool loop IS faithfully offline-testable — a positive contrast to cap-7's D9 (R3/S2).** Unlike
      request-based *delegation* (un-mockable in 3.6.0 — cap-7 D9), a remote **MCP tool** is a normal
      function-tool call returning a typed `String`, so `TestModelProvider` can script it end-to-end:
      `whenUserMessage(...).reply(ToolInvocationRequest("retrieve", …))` then `whenToolResult(...).thenReply(…)`.
      The SDK performs the **real** round-trip to the in-process `/mcp`, and the received `ToolResult` equals a
      direct `KnowledgeStore.retrieve` (source labels + order) — so **SC-005 parity is proven offline**, no
      live model. The 8-test `McpClientEndpointIntegrationTest` covers grounded answer + real round-trip
      (SC-003), honest decline (SC-002), validation-first (SC-004), and the shared-store parity (SC-005).
    - **Tool transport is invisible to the model — `@FunctionTool` and `@McpTool` are interchangeable at the
      model layer (R6).** Every tool (local `@FunctionTool`, `.tools()` object, remote `@McpTool`) is presented
      to the model as the same `{name, description, inputSchema}` in one flat namespace; the model calls by
      **name** and the SDK hides whether dispatch is an in-process method or a JSON-RPC round-trip (our mock
      drives the MCP tool with a `ToolInvocationRequest("retrieve", …)` *identically* to a `@FunctionTool`).
      Below the model layer they differ — notably the tool's description the model sees comes from the
      **server's** advertised `tools/list` (cap-9's `@McpTool`), not the client — so the agent's system prompt
      needn't (and shouldn't) name the transport. **Tool-name uniqueness** is therefore scoped **per-agent-per-
      request** (the tools one agent registers together must not collide), *not* application-global — two
      different MCP servers may each expose a `retrieve` (`withAllowedToolNames`/`withToolNameFilter` filter
      collisions). **Live-verified** on Ollama `qwen3:8b`: the in-corpus answer reconstructed the corpus's
      method-ref-wall/two-mapper findings (un-hallucinatable → the tool genuinely ran), the out-of-corpus reply
      declined citing *"the retrieved passages"*, and a blank question returned `400` with no call.

    Takeaway: **the MCP through-line completes — server (cap-9) and client (cap-10) are both Scala-clean, for
    the same reason: neither authors a `ComponentClient` method reference.** See specs/012 research R1–R6 and
    README's cap-10 usage section below.

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
> > **Live caveat — a session that stops working after a few tool-using turns, surfaced as
> > `argument "content" is null`.** Symptom: `add` / `add` / `complete` succeed, then `what is on my list?`
> > (and every later turn) fails with the fallback reply. The runtime reports
> > `AK-01202 … Model error: argument "content" is null`, which *looks* like a null-content model quirk but
> > is actually misleading.
> >
> > **Actual cause (proven live) — the SDK's `readLast(N)` orphans tool-call pairs.** Each tool-using turn
> > persists **4** messages (`User`, `AiMessage`+tool-call, `ToolCallResponse`, `AiMessage`-final), so 3 turns
> > = 12 messages. The SDK's last-N trim ([`MemoryHistoryUtils.trimToLastN`] in SDK 3.6.0) is a **naive
> > `subList(size-N, size)`** that ignores tool-call/response pairing — with `readLast(10)` the window head
> > becomes an **orphaned `ToolCallResponse`** (its preceding `AiMessage`+tool-call was dropped). That invalid
> > chat sequence is rejected during request assembly (before the call even reaches Ollama) and the SDK
> > surfaces it as the confusing `argument "content" is null`. **Proven by experiment:** the same failing flow
> > works once `.readLast(N)` is removed — hence this capability uses **full session history**
> > (`MemoryProvider.limitedWindow()`, no `readLast`). *Correction:* an earlier version of this note blamed
> > qwen3 null-content persistence; a live A/B (remove `readLast` → fixed) showed the window trim was the real
> > cause. Tradeoff of full history: **unbounded token growth** on long sessions — **compaction** (summarize
> > old turns without slicing pairs) is the proper bound, left as future work.
> >
> > **Two defensive layers kept alongside** ([`PersonalAssistantAgent`](src/main/scala/com/gwgs/akkaagentic/a2a/application/PersonalAssistantAgent.scala)),
> > for related but distinct failure modes:
> > 1. **`NullSafeAiContentInterceptor`** — a `SessionMemoryInterceptor` (`.withInterceptor(...)`) rewriting a
> >    null AI `text` to `""` **on the write path** (preserving `toolCallRequests`). A `content:null`+`tool_calls`
> >    turn *is* legal and langchain4j accepts it, so the runtime persists it as null — proven offline by a Java
> >    `getHistory` test ([`NullContentPersistenceIntegrationTest`](src/test/java/com/gwgs/akkaagentic/a2a/application/NullContentPersistenceIntegrationTest.java)):
> >    stored `text` is `""` with the interceptor, **`null` without it** (so langchain4j does not normalize;
> >    the interceptor is load-bearing). Honest scope: this null-content *persistence* is real, but it was
> >    **not** the cause of the live failure above — the `readLast` trim was. Kept as defense-in-depth.
> > 2. **`.onFailure(_ => FailureReply)` + a `logger.warn`** — degrades any failed turn to a clean `200`
> >    instead of a raw `500`, and (crucially) **logs the real exception** — the silent fallback was why this
> >    bug was initially invisible in the logs. Closes the AGENTS.md agent-checklist gap. Offline `failWith` test.
> >
> > Belt-and-suspenders: also prefer a stronger tool-calling model (`qwen2.5:14b` / `qwen3:14b`, or a hosted
> > model) if small-model tool quirks bite in practice.

### Capability 7 — activity coordinator via AutonomousAgent delegation (`POST /activities`, async)

Capability 7 is an **Autonomous Agent** (`ActivityCoordinator`) that suggests activities by **delegating** to
two request-based specialists — a `WeatherSpecialist` and an `ActivitySpecialist` — through the SDK's built-in
**`Delegation`** capability, then **synthesizing** their input into one typed result. Unlike cap-2 (a fixed
Java `Workflow`) the model chooses which specialist(s) to consult at runtime; unlike cap-6 (hand-rolled
`ForwardTool` chaining by username) this is the SDK-**recommended** dynamic-delegation primitive, dynamic
**by class**. Because coordination is multi-round-trip, the surface is **start-then-poll** (like cap-3). (Back
in **Scala** — the delegation API has no method-ref wall; see "Scala interop notes" §9.)

```shell
# 1. Start — 202 + a task handle; the coordinator is delegating to specialists
curl -i -X POST http://localhost:9000/activities \
  -H "Content-Type: application/json" \
  -d '{"location":"Boston","preferences":"outdoorsy, with kids"}'
# 202 Accepted
# Location: /activities/9671183c-...
# {"taskId":"9671183c-..."}

# 2. Poll — 404 while coordinating, 200 with the synthesized suggestion once COMPLETED
curl -s http://localhost:9000/activities/9671183c-...
# 404 Not Found        (while the coordinator delegates + synthesizes)
# ...then...
# 200 OK
# {"suggestion":"Enjoy a day at the park with swings, slides, and a picnic under the clear skies...
#   fly kites in the gentle breeze—both activities thrive in 20°C weather!",
#  "weatherConsidered":"clear skies, 20°C",
#  "consultedSpecialists":["weather-specialist","activity-specialist"]}
```

`weatherConsidered` comes from the (canned, offline) `WeatherData`; `consultedSpecialists` reflects which
delegates the coordinator's model consulted. Same validation-first, poll contract as the other async
capabilities: a blank/absent `location` or malformed body is `400` before any task starts; an unknown/
never-started id polls `404`; a task the coordinator reports it cannot complete polls `422`.

```shell
curl -i -X POST http://localhost:9000/activities \
  -H "Content-Type: application/json" -d '{"location":"  "}'
# 400 Bad Request — location must not be blank   (no task started)
```

> **Where durability lives, and two testing caveats.** As with cap-3, nothing in `ActivityCoordinator`
> persists anything — the **Task is the durable record** (no Entity, no Workflow); observing it across a local
> restart needs the on-disk store (`-Dakka.javasdk.dev-mode.persistence.enabled=true`). Two honest caveats
> (see "Scala interop notes" §9 and specs/009 research D6/D9):
> 1. **Delegation is proven *live*, not offline.** Request-based delegation isn't faithfully mockable in the
>    SDK 3.6.0 testkit (the mock delivers an untyped payload the worker rejects), so the offline tests use a
>    direct completion and the delegation itself is verified live.
> 2. **`consultedSpecialists` is model self-reported and best-effort.** Small models under-report which
>    specialists they consulted; the coordinator *does* delegate dynamically, but the reported list can be
>    incomplete. Ground-truth via runtime notifications is a logged follow-up.
>
> *Verified live* (Ollama `qwen3:8b`): `POST /activities {Boston, outdoorsy/with kids}` → after polling,
> `200` with a coherent kid-friendly park/kites suggestion, `weatherConsidered:"clear skies, 20°C"` (the
> canned Boston conditions), and `consultedSpecialists:["weather-specialist","activity-specialist"]`. A blank
> `location` returned `400`; an unknown task id `404`. An **unknown** location (e.g. "Atlantis") returned
> `WeatherData`'s canned default — un-hallucinatable — confirming the weather specialist is genuinely
> delegated to, not guessed.

### Capability 8 — RAG-grounded Q&A (`POST /ask`, synchronous)

Capability 8 answers a question using **retrieval-augmented generation**: it embeds a small local corpus
into an in-memory vector store, retrieves the passages most **semantically** similar to the question, and
has a `DocsAgent` answer grounded **only** in those passages — or honestly say *"I don't know"* when the
corpus doesn't cover it. Everything runs **offline** (in-process ONNX embeddings, no API key), and the
surface is **synchronous** like cap-4. The reply carries the answer plus the **cited source labels** of the
passages that grounded it (computed from what was retrieved, not model self-report). (Scala end-to-end —
see "Scala interop notes" §10: the RAG stack ships with the SDK, and custom-dependency DI is Scala-clean.)

```shell
# In-corpus question (paraphrased — no shared keywords) → grounded answer + correct citation
curl -s -X POST http://localhost:9000/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"what makes agent work survive a restart without me writing persistence code?"}'
# {"answer":"An autonomous agent's task is durable — the runtime persists the task and the agent's
#  process state as the loop runs and recovers them after a restart, so no wrapping workflow is needed.",
#  "citedSources":["durability-tasks"]}
```

**Honest decline** — a question the corpus does not cover returns an explicit "I don't know" and cites
nothing (no fabrication, no misleading citation):

```shell
curl -s -X POST http://localhost:9000/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"what is the capital of France?"}'
# {"answer":"I don't know","citedSources":[]}
```

**Semantic discrimination** — two differently-worded questions retrieve and cite *different* passages:

```shell
curl -s -X POST http://localhost:9000/ask -H "Content-Type: application/json" \
  -d '{"question":"how does the coordinator pick which specialist to consult?"}'
# {"answer":"...","citedSources":["cap-7-activity-coordinator"]}
curl -s -X POST http://localhost:9000/ask -H "Content-Type: application/json" \
  -d '{"question":"why can some components only be written in Java, not Scala?"}'
# {"answer":"...","citedSources":["interop-method-ref-wall"]}
```

Same validation-first contract as the other capabilities: a blank/absent `question` or malformed JSON body
is rejected with `400` **before** any retrieval or model call; an unknown extra property is tolerated.

```shell
curl -i -X POST http://localhost:9000/ask \
  -H "Content-Type: application/json" -d '{"question":"  "}'
# 400 Bad Request — question must not be blank   (no retrieval, no model call)
```

> **Where the knowledge lives, and why retrieval is offline-testable.** Nothing in `DocsAgent` persists or
> retrieves anything — the endpoint retrieves top-k passages from a `KnowledgeStore` (in-process
> all-MiniLM-L6-v2 embeddings + an `InMemoryEmbeddingStore`, seeded from a canned corpus at startup) and
> hands them to the agent as grounding context. `KnowledgeStore` is a plain class provided via `Bootstrap`'s
> `DependencyProvider` and constructor-injected — the project's first non-`ComponentClient` injection, and
> Scala-clean (§10). Because the embeddings are **deterministic**, *which passages ground an answer* is
> proven **offline with no model** ([`KnowledgeStoreTest`](src/test/scala/com/gwgs/akkaagentic/docs/application/KnowledgeStoreTest.scala));
> only the generated answer is mocked in the endpoint test. This is the clean counter-example to cap-7's
> un-mockable delegation: RAG's **retrieval half is fully offline-provable**, only the generative half needs
> a live/mocked model.
>
> The corpus here is **self-referential** — passages describing capabilities 1–9 and the interop findings —
> so in-corpus vs out-of-corpus questions are easy to construct. Swap
> [`KnowledgeCorpus`](src/main/scala/com/gwgs/akkaagentic/docs/domain/KnowledgeCorpus.scala) for any domain.

> **Known tradeoff — citations reflect *retrieval*, not *usage* (and grounding is a soft constraint).**
> `citedSources` lists the **top-K passages that were retrieved** and handed to the agent (ordered by
> descending similarity), **not** the passages the answer actually drew on. The endpoint retrieves `TopK = 3`,
> gives all three to the model as context, and cites all three — it never asks the model which it used. So a
> question like *"what makes agent work survive a restart?"* can answer purely from `durability-tasks` yet
> still cite `cap-3-help-desk` and `cap-4-session-memory` — they were the 2nd/3rd nearest passages (all three
> are semantically about "state surviving without you writing persistence code") and so got offered as
> context. This is **deliberate** (research R3/R4): retrieval-side citations are *ground truth of what was
> retrieved* — un-fakeable, computed endpoint-side — which is exactly how cap-8 sidesteps cap-7's D6 (a model
> that self-reports its sources is unreliable, especially small models). The cost is **over-citation**: we
> sometimes cite more than the answer needed.
>
> A related, more fundamental point: the model is **instructed** to answer only from the supplied passages
> (else reply with the `DontKnow` sentinel), but that is instruction-following — a **soft** constraint, not a
> runtime guarantee. An LLM can ignore the context or blend in its own parametric knowledge; we lean on the
> instruction plus the decline sentinel, we do not *prove* per-answer grounding.
>
> **Future work (if over-citation matters):**
> - **Lower `TopK`** (1–2) — simplest, but risks dropping a genuinely relevant passage.
> - **Score threshold** — cite only passages above a similarity cutoff, trimming the weak tail (the retrieved
>   `score` is already available on `KnowledgeStore.Retrieved`).
> - **Usage-accurate citations** — have the agent return *which* source labels it used (structured output).
>   More honest about usage, but reintroduces the model-self-report unreliability (cap-7 D6) this design was
>   built to avoid — a genuine tension, not a free upgrade.

> **Architecture fork — *how* retrieved passages reach the model: pre-retrieval vs retrieval-as-a-tool.**
> Cap-8 uses **pre-retrieval, endpoint-orchestrated**: the endpoint retrieves top-K *before* the model runs,
> once, and inlines the passages into the agent's (Java-shaped) `Request`. That is one of two supported shapes
> — the SDK docs (`agents/extending.html.md`, `use-cases/rag-and-knowledge.html.md`) also support the other:
>
> - **Where in the prompt (minor axis):** the passages could sit in the **system message** (`.systemMessage`)
>   instead of the user message, or be injected via langchain4j's `RetrievalAugmentor` / `ContentInjector`
>   (the SDK's own `Knowledge.java` example) rather than our hand-rolled block. Presentation only — *who*
>   retrieves doesn't change.
> - **Who retrieves & when (the real axis):** expose `KnowledgeStore.retrieve` as a `@FunctionTool` (on the
>   agent, or an external tool object via `.tools()`, or a View/Entity as a component-tool, or a remote **MCP**
>   tool via `.mcpTools()` — cap-9 territory). Then the **model** decides whether to retrieve, with what query,
>   and how many times — enabling query reformulation and multi-hop lookups ("agentic RAG").
>
> | | Pre-retrieval (cap-8) | Retrieval-as-a-tool |
> |---|---|---|
> | Who queries | endpoint, always, once | model, on demand, 0..N times |
> | Multi-hop / query rewriting | no | yes |
> | Latency / cost | 1 model call | extra round-trips per tool call |
> | Skips retrieval when irrelevant | no (always top-K) | yes |
>
> **Why cap-8 chose pre-retrieval — it's a package deal with the citation guarantee above.** Because the
> endpoint *owns* retrieval, citations are ground truth and retrieval is deterministically **offline-testable**
> (R6 — the whole selling point). Move retrieval into a tool and both weaken: the retrieval now happens inside
> the model loop, so to cite sources you must capture what each tool call returned (via runtime notifications /
> interaction logs) and reconcile it — reintroducing exactly cap-7's D6 observability problem. So the fork is:
> **pre-retrieval** = deterministic, ground-truth citations, offline-provable, but rigid (always top-K, no
> multi-hop); **retrieval-as-tool** = flexible, model-driven, multi-hop, but citations and offline-testability
> get harder. Cap-8 optimizes for the former on purpose; a tool-based variant is a natural future capability.

### Capability 9 — MCP knowledge server (`/mcp`, JSON-RPC)

Capability 9 exposes cap-8's semantic retrieval over the **Model Context Protocol** so any MCP client (an
LLM host like Claude Desktop, the MCP Inspector, or another Akka agent via `.mcpTools()`) can use it. The
server is an `@McpEndpoint` at **`/mcp`** speaking **JSON-RPC** over stateless Streamable HTTP — **no
`initialize` handshake**, requests are a plain POST, and replies are a single `application/json` object.
It offers a **`retrieve` tool** (semantic search → grounded passages) and a **corpus-sources resource**
(the discoverable list of what the corpus covers). No new retrieval logic — it reuses cap-8's
`KnowledgeStore` verbatim. (Scala end-to-end, tests included — an MCP endpoint has no client to author, so
no method-ref wall; see "Scala interop notes" §11.)

```shell
# Discover the tools — advertises `retrieve` and its input schema (required `question`)
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"retrieve","description":"Semantic search over the
#  knowledge corpus; ...","inputSchema":{"type":"object","properties":{"question":{"type":"string",...}},
#  "required":["question"]}}]}}
```

```shell
# Call `retrieve` — grounded passages (fixed top-3), score-descending, as a JSON string in the tool result
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"retrieve",
       "arguments":{"question":"why must some components be written in Java instead of Scala?"}}}'
# {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":
#  "[{\"source\":\"interop-method-ref-wall\",\"score\":0.71,\"text\":\"...\"}, ...]"}],"isError":false}}
```

A **blank question** is a well-formed tool error (`isError: true`) with cap-8's validation message, and
**no retrieval runs** — the same validation-first contract as every other capability:

```shell
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"retrieve","arguments":{"question":"   "}}}'
# {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"question must not be blank"}],"isError":true}}
```

**Discover the corpus** — `resources/list` advertises the sources resource, `resources/read` returns the
source labels of every passage (a client's "table of contents" for what `retrieve` can ground on):

```shell
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"knowledge://corpus/sources"}}'
# {"jsonrpc":"2.0","id":4,"result":{"contents":[{"uri":"knowledge://corpus/sources",
#  "mimeType":"application/json","text":"[\"cap-1-greeting\",\"cap-3-help-desk\",\"cap-4-session-memory\",
#  \"cap-5-approval-gate\",\"cap-6-delegation\",\"cap-7-activity-coordinator\",\"interop-method-ref-wall\",
#  \"interop-two-mapper\",\"durability-tasks\"]"}]}}
```

**The third MCP primitive** — MCP servers can expose **tools**, **resources**, *and* **prompts**. This
server declares no prompts, but the SDK still answers `prompts/list` (a client may probe all three during
discovery) — an empty list, not a "method not found" error:

```shell
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":7,"method":"prompts/list"}'
# {"jsonrpc":"2.0","id":7,"result":{"prompts":[]}}
```

(Akka exposes prompts via `@McpPrompt` methods; this capability declares none.)

> **Where the knowledge lives, and one SDK caveat.** The MCP endpoint holds no state — it reuses cap-8's
> `KnowledgeStore` (in-process all-MiniLM-L6-v2 embeddings, seeded at startup), injected the same way
> (`Bootstrap`'s `DependencyProvider`). Because that retrieval is deterministic, the whole surface is
> **offline-tested over real JSON-RPC** (`KnowledgeMcpEndpointIntegrationTest`, 8 tests, no model): tool
> discovery, paraphrase→correct top source, **SC-004 parity** with a direct `KnowledgeStore.retrieve`,
> out-of-corpus low-score, blank→error, and the resource labels.
>
> **SDK-3.6.0 caveat — the `retrieve` tool returns a fixed top-K (3), not a tunable count.** A designed
> optional `maxResults` argument could not be expressed on SDK 3.6.0 (its optional-parameter mechanisms all
> fail — see [`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md)), so the tool takes only
> `question` and returns 3 passages, exactly mirroring cap-8's `DocsEndpoint`. To be restored on an SDK
> upgrade.
>
> **Server-side only.** This is the MCP *server*. Consuming a remote MCP server from an agent
> (`.mcpTools(url)`) is the next capability (cap-10), kept separate so the server and client interop
> questions are explored one at a time.

### Capability 10 — MCP-client agentic RAG (`POST /grounded-ask`, synchronous)

Capability 10 is the **consuming** counterpart to cap-9: a request-based `McpClientAgent` answers a
question by calling the **remote `retrieve` MCP tool** of this service's own cap-9 `/mcp` server. The
model decides whether/when to retrieve — **agentic RAG** — contrasting cap-8's endpoint pre-retrieval.
The whole loop is in-process and offline (agent → MCP client → our MCP server → cap-8's `KnowledgeStore`),
and the surface is **synchronous** like cap-4/cap-8. The reply is just the answer — **no `citedSources`**,
because the model (not the endpoint) owns retrieval here (the deliberate cap-8-fork tradeoff). (Scala
end-to-end — consuming a remote MCP server has no method-ref wall; see "Scala interop notes" §12.)

```shell
# In-corpus question → the model calls `retrieve`, answers grounded in the returned passages
curl -s -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" \
  -d '{"question":"why must some components be written in Java instead of Scala?"}'
# {"answer":"Some components must use Java due to the \"method-reference wall,\" where clients like
#  Workflow and event-sourced-entity rely on Java method references without dynamicCall; component
#  payloads also use a non-Scala-aware mapper, requiring Java-shaped types."}
```

**Honest decline** — a question the corpus doesn't cover: the model retrieves, finds the passages
insufficient, and says so rather than fabricating (no citation to fake, either):

```shell
curl -s -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" \
  -d '{"question":"what is the capital of France?"}'
# {"answer":"The retrieved passages do not contain information about the capital of France. I don't
#  know the answer based on the provided knowledge corpus."}
```

Same validation-first contract as every other capability — a blank/absent `question` or malformed body
is rejected `400` **before** any model or tool call; an unknown extra property is tolerated:

```shell
curl -i -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" -d '{"question":"  "}'
# 400 Bad Request — question must not be blank   (no model, no retrieval)
```

> **How it points at itself, and why it's Scala-clean.** The agent uses
> `RemoteMcpTools.fromService(<service name>)`, which builds `http://<name>/mcp` — routing to **this**
> service's own `/mcp` (cap-9). The service name is resolved from config with an artifactId fallback
> (`McpClientAgent` injects `com.typesafe.config.Config`), so it's correct in TestKit, local `exec:java`,
> and a differently-named deploy alike; override it with `mcp-client.knowledge-service-name` (or the
> `KNOWLEDGE_MCP_SERVICE_NAME` env var) if ever needed. Consuming a remote MCP server is **Scala-clean** —
> `RemoteMcpTools` is a URL-string builder with no method reference — and cap-9's INTERNET-only ACL permits
> the self-service call unchanged (no cap-9 edit). Because a remote MCP tool is a normal typed-`String`
> function-tool call, the whole loop is **offline-tested** with `TestModelProvider` scripting a real
> `retrieve` round-trip (`McpClientEndpointIntegrationTest`, 8 tests, no model), including **SC-005 parity**
> against a direct `KnowledgeStore.retrieve`. *Verified live* (Ollama `qwen3:8b`): the in-corpus answer
> reconstructed the corpus's own interop findings (un-hallucinatable → the tool genuinely ran), the
> out-of-corpus reply declined citing *"the retrieved passages"*, and a blank question returned `400`.

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
