# Phase 0 Research: Agent-to-agent delegation (personal assistants)

All unknowns for capability 6 resolved before design. Sources: the Akka docs under
`akka-context/sdk/agents/` (`calling.html.md`, `memory.html.md`, `extending.html.md`,
`structured.html.md`), `akka-context/sdk/key-value-entities.html.md`, and the carried-over findings from
features 002 (`dynamicCall` escape hatch), 003 (two-mapper boundary), 004 (Workflow method-ref wall),
and 006 (the wall recurs on entity/workflow clients; session-memory testing limits). This capability is
the first where **one agent calls another agent**, so the central question is which side of the
"method-ref wall" the agent client sits on — and what happens when the same request needs *mutable*
per-user state.

The headline is a **two-sided** result: delegation is Scala-clean (R1), but per-user mutable structured
state re-hits the wall (R2). R3–R6 are the consequent design decisions.

---

## R1 — Agent-to-agent delegation is idiomatic Scala (the POSITIVE finding)

**Decision**: Implement delegation as a **Scala** tool object (`ForwardTool`) on a **Scala** agent
(`PersonalAssistantAgent`), invoking the other user's assistant through the agent `ComponentClient`'s
**`dynamicCall`**:

```scala
componentClient
  .forAgent()
  .inSession(targetUsername)                      // the delegate runs as that user
  .dynamicCall[Request, String]("personal-assistant-agent") // component id, NOT a method ref
  .invoke(Request(targetUsername, question, delegated = true))
```

**Rationale**: The agent client exposes `dynamicCall(componentId)` (feature-002), which resolves the
target by **string id**, not by inspecting a Java `SerializedLambda`. A Scala lambda compiles to a
synthetic `$anonfun$N` that the SDK's `impl.client.MethodRefResolver` can never resolve — but
`dynamicCall` never asks it to. So a Scala agent calling another Scala agent is friction-free, exactly as
a Scala endpoint calling an agent already is (cap-1). Delegation is just an agent invoking an agent from
inside a tool; nothing new breaks.

**Alternatives considered**: (a) `.forAgent().method(PersonalAssistantAgent::request)` — rejected: the
method-ref form fails on a Scala target (the wall). (b) Make the delegate an `AutonomousAgent` and use
delegation capabilities — rejected as heavier than needed; a single synchronous forward-and-relay is one
model call on the delegate, for which a request-based agent is the lighter default (AGENTS.md "Choosing
between Agent and Autonomous Agent").

---

## R2 — A per-user mutable to-do list re-hits the entity wall (the NEGATIVE finding)

**Decision**: Model the to-do list as a **Java** `KeyValueEntity` (`TodoEntity`, keyed by username) and
put the four to-do `@FunctionTool`s in a **Java** tool object (`TodoTools`) that calls the entity by
method reference. Hand that tool object to the **Scala** agent via `.tools(new TodoTools(componentClient))`.

**Rationale**: A to-do list is mutable, structured domain state that must be queried and updated by
command → the SDK primitive is a `KeyValueEntity`. But the entity `ComponentClient` is
**method-reference-only** — `componentClient.forKeyValueEntity(id).method(TodoEntity::addTodo)` — with
**no `dynamicCall` overload** (feature-006 R6; the wall recurs on entity and workflow clients, unlike the
agent/autonomous-agent clients). A Scala agent therefore cannot call the entity directly. The **tool-object
seam** resolves this: `.tools(obj)` reflects `@FunctionTool` methods off any object, so a *Java* `TodoTools`
holds the method-ref calls while the agent, forward tool, and endpoint remain Scala. The two Java domain
records (`Todo`, `TodoList`) are Java anyway, because the entity state crosses the SDK's **internal**
mapper (R4 / feature-003) and must be Java-shaped.

**Consequence (FR-009)**: **No direct to-do read endpoint.** The endpoint is Scala; a Scala endpoint
reading `TodoEntity` would re-hit the very same wall. So to-dos are surfaced **only through the assistant**
(ask it "what's on my list?"), which routes through the Java `TodoTools`. This is not a limitation of the
feature — it is the wall dictating the API shape.

**Alternatives considered**: (a) *All-Scala, no entity — keep to-dos in session memory* — rejected:
session memory is an opaque append-only transcript, not queryable/mutable structured state; you cannot
"delete item 3" from it. (b) *Write the whole agent in Java* — rejected: it throws away the R1 Scala
delegation result, which is the point of the capability. (c) *A Scala endpoint that reads the entity for a
todo-list route* — rejected: re-hits the wall (hence FR-009). (d) *Event-sourced entity instead of
key-value* — rejected by Simplicity (IV): to-dos need current state, not an audit journal.

---

## R3 — Sync + retry, not durable-async (the durability fork)

**Decision**: Keep `POST /request/{username}` **synchronous** (returns the reply directly). Accept that an
in-flight request — including a delegation waiting on another assistant — is **not resumed** after a
restart; the caller retries. Persisted chat history and to-dos **do** survive.

**Rationale**: A request-based `Agent` call is synchronous and **not durable mid-flight**: only *completed*
turns are persisted (to session memory), and a synchronous HTTP reply has **nowhere to land** after a
restart. This is fundamental to a request/response surface, not an Akka quirk. For a personal-assistant
chat UX, synchronous is the natural shape, and the honest statement of the limit *is* a teaching point of
the capability.

**Alternatives considered**: `AutonomousAgent` task + **start-then-poll** (like cap-3/cap-5). This *would*
make an in-flight delegation durable and resumable (the task is the durable record), and it stays Scala
(`TaskClient` has no wall — feature-007). **Rejected for this capability** because it changes the UX from
"POST and get your answer" to "POST a handle, then poll", and the sync-vs-durable trade is exactly what we
want to document, not hide. (Noted as the natural follow-up if a "resume mid-delegation" requirement ever
appears.)

---

## R4 — Two-layer request + a structural one-hop loop guard

**Decision**: Two request types. The **HTTP body** is an idiomatic Scala DTO `{message}` (username comes
from the path). The **internal agent wire** type is Java-shaped:

```scala
// application layer — Jackson-annotated, crosses the internal mapper
object PersonalAssistantAgent:
  final case class Request(username: String, message: String, delegated: Boolean = false)
```

`delegated` defaults `false`; it is set `true` **only** by `ForwardTool`. The agent command handler picks
tools by request:

```scala
val tools = if request.delegated then Seq(todoTools) else Seq(todoTools, forwardTool)
```

so a **delegated call has no forward tool** and cannot delegate onward — a **structural** one-hop guard
(A→B→A is impossible by construction), not a prompt request the model might ignore.

**Rationale**: The HTTP/idiomatic ↔ internal/Java-shaped split is the established two-mapper boundary
(feature-003): endpoint bodies go through the Scala-aware `JsonSupport` mapper, but component payloads
(the agent `Request`) cross a *separate internal* mapper that cannot read `scala.Option`, so the wire type
must be Java-shaped. `delegated` rides the **internal** side, so it is invisible to the API caller (FR-005)
— they express intent in natural language; the model picks `ForwardTool`; the guard is set behind the
boundary. A boolean carried on the request is the only place the delegate can learn it is a delegate (it
sees only the `Request`).

**Alternatives considered**: (a) *Prompt-only guard* ("do not forward again") — rejected: soft; the model
may disobey, and each errant hop is a full LLM round-trip. (b) *Depth counter* — rejected by Simplicity
(one hop is the whole requirement; a boolean suffices). (c) *Expose `delegated` on the HTTP DTO* —
rejected: it would force the API caller to set an internal concern (violates FR-005).

---

## R5 — Bounded chat-history tokens via `readLast(N)`

**Decision**: Configure session memory with `MemoryProvider.limitedWindow().readLast(N)` (a small N, e.g.
10). Optionally rely on the global `akka.javasdk.agent.memory.limited-window.max-size` ceiling as a
backstop.

**Rationale**: The runtime replays prior turns as context on each call; `readLast(N)` caps that to the
last N messages — the documented token-control lever (`memory.html.md`) — and it is a pure builder call
(`String`/`int`), fully Scala-safe. Relevant to A2A specifically: a **delegated reply** is folded back
into the caller's context as a tool result, and `listTodos` returns the whole list into the model each
call — both count against the window, so bounding it matters more here than in a plain chat.

**Alternatives considered**: *LLM compaction* (a consumer on `SessionMemoryEntity` events + a
`CompactionAgent` that summarizes old turns) — rejected as **out of scope** and, notably, **not
Scala-clean**: it calls `SessionMemoryEntity::getHistory` / `::compactHistory` by **method reference**
(the entity wall again), so it would need Java. `readLast(N)` gets the token bound with none of that.

---

## R6 — Where each kind of state lives (memory role split)

**Decision**: **Chat history** lives in the SDK-internal `SessionMemoryEntity` (runtime-registered, keyed
by `username` used as the session id) — **not** listed in our descriptor and **not** touched by our code
beyond `.memory(...)`. **To-dos** live in our own `TodoEntity` `KeyValueEntity`, also keyed by `username`,
and are read/written explicitly by command.

**Rationale**: The two are durable entities with **opposite roles**. Session memory is an *append-only
transcript the model needs replayed automatically* → the SDK provides and manages it for free (bare
`String` in/out, no wire type crosses our code). The to-do list is *structured state we mutate by command
and expose to the model only through a tool* → that is our domain entity. You could not store to-dos in
session memory (opaque, not mutable-by-id), and storing chat in a `KeyValueEntity` would mean hand-rolling
replay + windowing that session memory gives free. Keying both by `username` is what makes "distinct
usernames → distinct durable assistants" true with no per-user bootstrapping code.

**Testing implication** (carried from feature-006): with `TestModelProvider` the mock is fed only the
**current turn**, so multi-turn *recall* is **not observable offline** — it is verified by a live smoke
test. *Retention* and *isolation* of stored state (session memory and to-dos) are verifiable offline,
though reading `SessionMemoryEntity` directly needs Java (method-ref); our offline coverage proves
isolation at the to-do/HTTP layer and defers recall to the live run.

---

## R7 — Agent chaining is the "not recommended" path; we take it deliberately (the key finding)

**Decision**: Implement delegation as **request-based agent chaining** — `PersonalAssistantAgent` A's
`ForwardTool` invokes `PersonalAssistantAgent` B through `componentClient.forAgent().dynamicCall(...)` and
relays the reply — **knowing the SDK documentation explicitly discourages agent chaining** and steers
toward Workflows.

**The doc says** (`akka-context/sdk/agents/extending.html.md`, "Akka components as function tools"):

> **Agents cannot be used as tools for other agents.** While an agent can define its own tools by
> annotating methods with `@FunctionTool`, you cannot pass an agent class to another agent's
> `effects().tools()` method.
>
> Agent chaining (where one agent calls another agent) is not a recommended pattern. Instead, use
> Workflows to orchestrate multiple agents. Workflows provide better control over the execution flow,
> error handling, and state management when coordinating between multiple agents.

Two distinct constraints are bundled there:

1. **Hard rule — you cannot pass an `Agent` class to `.tools()`.** We **comply**: `ForwardTool` is a plain
   tool object (not an agent, not an agent class); it calls `componentClient.forAgent().inSession(target)
   .dynamicCall("personal-assistant-agent")` *internally*. We never hand `PersonalAssistantAgent.class` to
   `.tools()`. So the hard rule is not violated.
2. **Soft guidance — "agent chaining … is not a recommended pattern; use Workflows."** This we
   **deliberately deviate from**, and the deviation is the point of the capability.

**Rationale for deviating**:

- **The recommended alternative (Workflow) forces Java.** Workflow step wiring and the `WorkflowClient`
  are Java-method-reference-only with no `dynamicCall` (feature-004). Routing cap-6 through a Workflow
  would drag the whole capability into Java — defeating the entire purpose (proving A2A delegation as
  idiomatic Scala).
- **A Workflow can't even express this shape.** Workflows have a **fixed step topology**; cap-6 delegates
  to *an arbitrary user's assistant chosen by the model at runtime by username*. The target is dynamic and
  data-dependent — not encodable as a static step graph.
- **cap-2 already covers Workflow orchestration.** `GreetingWorkflow` (Java) is the project's
  Workflow-orchestrates-agents data point. A Workflow here would add no new SDK coverage.

**The genuinely recommended path for *dynamic* delegation** is not a Workflow but an **`AutonomousAgent`
with a `Delegation.to(...)` capability** (AGENTS.md, "Autonomous Agent Orchestrating Multiple Agents
(Dynamic)"). That stays Scala (feature-005: no wall) and is the blessed model-driven delegation primitive.
cap-6 chooses the **lighter** request-based-chaining path on purpose, to map what you give up by doing so.
That recommended path is the **committed next capability (cap-7)** — see `ROADMAP.md` — which will contrast
recommended-vs-discouraged delegation head-to-head.

**What you give up by chaining (accepted risks)**:

| Concern | Workflow / AutonomousAgent-Delegation | cap-6 request-based chaining |
|---|---|---|
| Retry / error handling around the cross-agent call | Framework-managed (`RecoverStrategy`, task retries) | None — a failed delegate surfaces as a failed tool call |
| Durability of the in-flight cross-agent call | Durable (workflow state / task) | **Not** durable mid-flight — caller retries (R3) |
| Loop prevention | N/A (topology is fixed) or framework-bounded | **Our own** structural one-hop guard (R4) |
| Observability of the delegated step | Per-step / per-task lifecycle | Just a tool-call span inside A's turn |
| Target selection | Static (Workflow) / model-chosen (Delegation) | Model-chosen by username (dynamic) — **the win** |
| Language | Workflow = Java; Delegation = Scala | **Scala** |

**Alternatives considered**: (a) **Workflow** — rejected: forces Java, and can't express dynamic
by-username targeting; already covered by cap-2. (b) **`AutonomousAgent` Delegation** — *not* rejected on
merit; it is the recommended dynamic-delegation primitive and the **committed cap-7** (`ROADMAP.md`). cap-6
intentionally takes the request-based-chaining path first to document its limits (this table). (c) **Do nothing / single
agent** — rejected: no delegation, no capability.

**Bottom line**: cap-6's headline finding is not merely "delegation is Scala-clean" (R1) but "**the SDK
discourages request-based agent chaining, yet it works and stays idiomatic Scala — at the cost of the
framework-managed retry/durability/observability you'd get from a Workflow or an AutonomousAgent
Delegation, which we substitute with a hand-rolled one-hop guard and a sync+retry contract.**"

---

## R8 — Language-of-consumer rule, and the Scala↔Java compile direction (verified)

**Decision**: Place each shared type in **the language of its consumer(s)**, and only ever cross the
Java/Scala boundary in the **Scala→Java** direction (the clean one), never Java→Scala (the noisy one).

- `Todo` / `TodoList` → consumed **only** by the Java `TodoEntity` + `TodoTools` → **Java**.
- `AssistantRequest` → consumed **only** by the Scala endpoint (validation) → **Scala**.
- `PersonalAssistantAgent.Request` → consumed by Scala (agent, endpoint, `ForwardTool`) **but must be
  Java-shaped** because it serializes across the internal mapper → **Java-shaped Scala case class** (the
  one type that must satisfy both a Scala consumer and Jackson).

**Rationale** — purity is *not* why the to-do types are Java (a common misread). Two things decide it:

1. **Serialization**: `TodoList` is entity state → crosses the internal Jackson mapper → must be
   Java-*shaped*. That alone permits an annotated Scala case class, so it is not decisive.
2. **Consumer language + boundary asymmetry** (decisive): the to-do types' only callers are Java, and the
   **Java→Scala** call direction is the *noisy* one — Java calling a Scala `object`/case class means
   `TodoList$.MODULE$.empty()` and `scala.Option` juggling (the exact friction cap-2 hit, which it solved
   by keeping a Java copy of `TimeOfDay` — see the comment in `TimeOfDay.java`). The **Scala→Java**
   direction is clean (a Java record is just a normal class/methods to Scala). So we keep the to-do data
   Java (matching its Java consumers) and let the *Scala* agent reach *Java* `TodoTools` — never the
   reverse.

**Empirical check** (this build, not theory): a throwaway Scala source referencing the Java `TodoList`
(`TodoList.empty().add("x").nextId`) **compiled — `BUILD SUCCESS`**. And the real `PersonalAssistantAgent`
(Scala) constructing `new TodoTools(componentClient, username)` (Java) compiles. So the Scala→Java seam is
confirmed to work in this mixed module. The `pom.xml` recipe (`sendJavaToScalac=false`, `-proc:none`,
`-parameters`) is about not letting scalac clobber javac's `-parameters` output and not regenerating the
descriptor — **not** a ban on cross-references. The friction that matters is at the *language* level
(Java→Scala noise), which the language-of-consumer rule sidesteps entirely.

**Alternatives considered**: (a) *All-Scala domain, annotate `TodoList` for Jackson* — rejected: its only
consumers are Java, so it would force a Java→Scala dependency (the noisy direction) for zero benefit. (b)
*Java copies of the Scala `AssistantRequest`* — unnecessary: nothing Java consumes it.

---

## Cross-cutting: descriptor & build

- **Descriptor** gains `PersonalAssistantAgent` under `agent`, a **new `key-value-entity`** key listing
  `TodoEntity`, and `PersonalAssistantEndpoint` under `http-endpoint`. `ForwardTool` and `TodoTools` are
  tool objects, **not** components (absent). `SessionMemoryEntity` stays absent (runtime-owned, R6).
- **Build**: no `pom.xml` change. The feature-004 mixed-build recipe (`-proc:none`, `-parameters`,
  `sendJavaToScalac=false`) already lets Java `TodoEntity`/`TodoTools` compile alongside the Scala sources.

## Summary of decisions

| # | Decision | Language impact |
|---|---|---|
| R1 | Delegation via agent `dynamicCall` | **Scala** (positive: no wall) |
| R2 | To-do list = Java `KeyValueEntity` + Java `TodoTools` tool object | **Java** (negative: wall) |
| R3 | Sync + retry (not durable-async) | Scala; documents the sync limit |
| R4 | HTTP `{message}` DTO ↔ Java-shaped internal `Request`; `delegated` structural one-hop guard | Both (two-mapper) |
| R5 | `readLast(N)` bounded window | Scala |
| R6 | Chat = SessionMemoryEntity; to-dos = our KeyValueEntity; both keyed by username | — |
| R7 | Request-based agent **chaining** — the SDK-discouraged path — taken deliberately (Workflow forces Java + can't do dynamic by-username; AutonomousAgent Delegation is the recommended alt / possible cap-7) | **Scala** (trades framework retry/durability for a hand-rolled one-hop guard + sync/retry) |
| R8 | Language-of-consumer rule; only cross Scala→Java (clean), never Java→Scala (noisy); verified Scala→Java compiles here | — |
