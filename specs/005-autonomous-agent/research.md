# Research: Autonomous help-desk Agent

Phase 0 findings. Every unknown from the spec / Technical Context is resolved below. **R1** is the
load-bearing finding — the *inverse* of capability 2's R1 — and **R2/R3** are the concrete interop
details that let cap-3 be idiomatic Scala. All findings are proven against **Akka Java SDK 3.6.0**
(`javap` on the SDK jar and the annotation-processor jar), not assumed.

## R1 — The Autonomous Agent API has NO method-reference wall; it can be authored in Scala (THE CRUX)

**Decision**: Implement **all of capability 3 in Scala**. This is the opposite conclusion to cap-2,
and for a concrete reason: the Autonomous Agent API never uses Java method references.

**Rationale (proven)**. cap-2's wall (R1 there) was that the Workflow API resolves steps and calls via
`SerializedLambda` (`MethodRefResolver`), which a Scala lambda's mangled `$anonfun` name can't satisfy,
and `WorkflowClient` has no `dynamicCall` escape hatch. The Autonomous Agent API has **none of that
shape**. From `javap` on SDK 3.6.0, every authoring and client entry point takes a `Class`, a
`Task`/`TaskDefinition`, a `String`, or an annotation — there is **no `akka.japi.function.Function`
(SAM/`Serializable`) parameter anywhere**:

- **Authoring** (`akka.javasdk.agent.autonomous`):
  - `AutonomousAgent.define(): AgentDefinition` (abstract `definition()` override; no handler).
  - `AgentDefinition.capability(AgentCapability)`, `.tools(Object...)`, `.instructions(String)`,
    `.modelProvider(ModelProvider)`, `.mcpTools(...)` — all declarative.
  - Tools are ordinary `@FunctionTool` methods (already proven to work in Scala — cap-1's
    `GreetingAgent` has one).
- **Tasks** (`akka.javasdk.agent.task`):
  - `Task.name(String): Task<String>`, `.description(String)`, `.instructions(String)`,
    `.resultConformsTo(Class<S>): Task<S>` — `Class`/`String` only.
  - `TaskAcceptance.of(TaskDefinition<?>, …): TaskAcceptance`, `.maxIterationsPerTask(int)`.
- **Client** (`akka.javasdk.client`):
  - `ComponentClient.forAutonomousAgent(Class<T>, String): AutonomousAgentClient`.
  - `AutonomousAgentClient.runSingleTask(Task<?>): String`, `.assignTasks(String...)`, `.getState()`.
  - `ComponentClient.forTask(String): TaskClient`; `TaskClient.create(Task<R>)`,
    `.get(TaskDefinition<R>): TaskSnapshot<R>`, `.complete(TaskDefinition<R>, R)`, `.fail(String)`.

Because the target agent is selected by `Class` and the work by a `Task` constant, a Scala caller and a
Scala agent both compile to exactly what the SDK expects — no lambda-name resolution is involved. The
`dynamicCall`-by-id shim that cap-1 needed for the *request-based* agent client isn't needed either;
`forAutonomousAgent(classOf[HelpDeskAgent], id)` is already `Class`-based.

**Consequence**: cap-3 is idiomatic Scala. The agent (`definition()` + `@FunctionTool`), the task
constants, the endpoint (`forAutonomousAgent`/`forTask`), and the tests (`withModelProvider(
classOf[HelpDeskAgent], …)`, `forTask(id).get(ANSWER)`) all sit naturally in Scala.

**Alternatives considered**:
- *Write cap-3 in Java for consistency with cap-2* — rejected: there is no interop reason to, and
  keeping it Scala is the more valuable learning result (it demonstrates the wall was Workflow-specific).

## R2 — The component descriptor key is `autonomous-agent`

**Decision**: Register `HelpDeskAgent` in the hand-maintained descriptor under a new
**`autonomous-agent`** key; register `HelpDeskEndpoint` under `http-endpoint`. Do **not** register the
`HelpDeskTasks` holder or `HelpAnswer` (they are not components).

**Rationale**: the annotation processor is disabled (`-proc:none`, cap-2's R2), so the hand-maintained
`akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` is authoritative. The exact key was
confirmed from the processor's constant pool (`ComponentAnnotationProcessor` in
`akka-javasdk-annotation-processor-3.6.0.jar`), which defines `autonomous-agent` as a distinct
component-type key alongside `agent`, `workflow`, `view`, `consumer`, `http-endpoint`, `grpc-endpoint`,
`mcp-endpoint`, `event-sourced-entity`, `key-value-entity`, and `timed-action`. Concretely:

```hocon
akka.javasdk.components {
  agent            = [ …cap-1 & cap-2 agents… ]
  workflow         = [ "com.gwgs.akkaagentic.team.application.GreetingWorkflow" ]
  autonomous-agent = [ "com.gwgs.akkaagentic.assistant.application.HelpDeskAgent" ]  # NEW
  http-endpoint    = [ …existing…, "com.gwgs.akkaagentic.assistant.api.HelpDeskEndpoint" ]  # +1
}
```

**Alternatives considered**:
- *List it under `agent`* — rejected: `AutonomousAgent` is a different component type with its own key;
  the wrong key would either fail discovery or mis-register the component type.

## R3 — The task result stays Java-shaped (two-mapper finding, carried from 003)

**Decision**: `HelpAnswer` is a **Java-shaped Scala case class** — Jackson-annotated
(`@JsonCreator`/`@JsonProperty`), non-optional fields — exactly like cap-1's `GreetingAgent.Result`. The
HTTP request/response DTOs on the endpoint remain **idiomatic** Scala (the Scala module covers HTTP
bodies).

**Rationale**: `Task.resultConformsTo(Class)` uses the result class for two internal operations — (a)
generating the JSON schema attached to the built-in `complete_task` tool, and (b) deserializing the
tool-call arguments the model returns into `R`. Both run through the SDK's **internal** serializer
(`impl.serialization.JsonSerializer`), which the public `DefaultScalaModule` registration (feature 003's
`Bootstrap`) does **not** reach. A plain Scala case class would fail deserialization with *"Cannot
construct instance of …"*, the same failure feature 003 documented for component payloads. Jackson
annotations make the type construct-able by the annotation-free internal mapper — the proven cap-1
pattern.

**Residual verification (do at the foundational step, not deferred far)**: the *schema-generation* side
(does the SDK derive a correct JSON schema from a Jackson-annotated **Scala** case class?) is not yet
exercised anywhere in this repo — cap-1 uses `responseAs` (parse only, no schema), not schema-gen. Build
`HelpAnswer` + a tiny agent-completion test **first**; if schema-gen or round-trip misbehaves for the
Scala case class, fall back to a **Java record** for `HelpAnswer` alone (a one-type, cap-2-style
concession), leaving the rest of cap-3 in Scala. This keeps the risk contained to one file.

**Alternatives considered**:
- *Idiomatic `Option` result type* — rejected: component payload, internal mapper, would fail at runtime
  (A-004).
- *Make `HelpAnswer` a Java record up front* — deferred, not chosen: try Scala first so the interop
  boundary is exercised and documented (the point of this roadmap); switch only if it actually breaks.

## R4 — Gemini tools-vs-JSON does NOT bite the Autonomous Agent (contrast with 002/004)

**Decision**: No special structured-output workaround is needed. The agent exposes `lookupPolicy` as a
`@FunctionTool` and declares a typed result via `resultConformsTo`; both are delivered through **function
calling**, not a JSON response mime type.

**Rationale**: cap-1/cap-2's Gemini constraint (features 002/004, R4 there) was specifically that Gemini
rejects *function calling combined with `responseMimeType: application/json`* — which bit because
`GreetingComposerAgent` used a tool **and** `responseConformsTo`/`responseAs` (JSON output mode). An
Autonomous Agent produces its typed result by calling the runtime's built-in **`complete_task` tool**
with the result as the tool arguments — i.e. more function calling — and the runtime does **not** set a
JSON `responseMimeType` on the request. So the domain tool (`lookupPolicy`) and the typed completion
coexist as two function-calling features, which Gemini supports. This means the Autonomous Agent path
may actually be *smoother* on Gemini than the cap-1/cap-2 structured agents.

**Verification**: confirm on the manual live smoke test (offline tests mock the model, so they can't
surface a provider constraint). If a live Gemini call ever rejects the combination, the fallback is to
set an explicit `modelProvider(...)` on the definition, but this is not expected.

## R5 — Async exposure and offline testing

**Decision**: `POST /help` validates the question, then `forAutonomousAgent(classOf[HelpDeskAgent],
UUID).runSingleTask(HelpDeskTasks.ANSWER.instructions(question))`, returning `202` with the **task id**
as the handle + `Location: /help/{taskId}`. `GET /help/{taskId}` calls `forTask(taskId).get(ANSWER)` and
maps the `TaskSnapshot`: `COMPLETED` → `200` `HelpAnswer`; `FAILED` → `422` with the failure reason;
anything else, or an unknown id (empty/exception) → `404` not-ready. Tests use `TestModelProvider` +
`AutonomousAgentTools.completeTask`/`failTask`, polling with Awaitility.

**Rationale**: `runSingleTask` returns the task id immediately and the task is itself the durable,
queryable record — the SDK explicitly says **no wrapping Workflow** is needed for durability or to await
the result (autonomous-agents hub + client docs). `TaskSnapshot` exposes `status()`, `result()`, and
`failureReason()`, giving three distinct terminal signals that map cleanly onto `200/422/404`. Mapping a
task **failure** to `422` (rather than `404` or `500`) keeps it distinct from both success and
not-ready, satisfying FR-008/SC-004. `TestKitSupport` starts the service so `TestModelProvider` drives
the agent deterministically offline; `completeTask(result)` scripts the built-in completion tool and
`failTask(reason)` scripts abandonment, so both the happy path (with an optional tool-consulting
iteration via `whenToolResult`) and the failure path are testable without a live model.

## R6 — The Scala-interop through-line narrows (meta-finding, context)

**Finding**: cap-3 is the **first** multi-step / orchestration-flavored capability on this roadmap that
does **not** hit a Scala-interop wall. The through-line of features 001–004 was that Lightbend's "the
SDK is easily used in Scala" holds for simple components but breaks for anything keyed on Java method
references or the internal serializer — hand-maintained descriptor (001), `dynamicCall` for the agent
client (001), the two-mapper `Bootstrap` (003), and the Workflow method-reference wall (004). cap-3
refines that story: the wall was **specific to the Workflow API's `SerializedLambda` step wiring**, not
intrinsic to durable multi-step orchestration on this SDK. The Autonomous Agent — a *more* capable
orchestration primitive — is declarative (Class/Task/annotation) and so is Scala-friendly. The only
residual friction is the familiar one (R3: component payloads stay Java-shaped), which is a
serialization concern, not an authoring one. Net: **prefer the Autonomous Agent over a Workflow when a
capability must be authored in Scala** and the model can drive the loop.
