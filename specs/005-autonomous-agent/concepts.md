# Concepts: AutonomousAgent and Task (capability 3)

This document explains the two primitives capability 3 is built on — the **AutonomousAgent** and the
**Task** — and when you would actually reach for them. `quickstart.md` shows how to build, run and
exercise the capability; `research.md` records the Scala-interop findings. This one answers the
"what is it and why" that neither of those sets out directly.

It is grounded in this module's real code:
`HelpDeskAgent` / `HelpDeskTasks` / `HelpAnswer` (`application/`) and `HelpDeskEndpoint` (`api/`).

> **Read the honest caveat first.** `HelpDeskAgent` is a *minimal demo* chosen to exercise the
> AutonomousAgent API with the smallest possible example (it was the first orchestration-flavored
> component authored in Scala, proving there is no method-reference wall — see `research.md` R1). Its
> actual workload — take a question, maybe call one tool, answer — is a textbook **request-based
> `Agent`**, not a case that *needs* an AutonomousAgent. Read the ["When to use"](#when-to-use-an-autonomousagent)
> section for what genuinely warrants one. The demo teaches the *mechanism*, not the *use case*.

---

## What is an AutonomousAgent?

An `AutonomousAgent` is a **model-driven component that runs as a durable process**. You do not write
a command handler for it. You write a single `definition()` that *declares* what the agent may do, and
the runtime drives the model through a decision loop until the work is done.

In capability 3 that declaration is the whole of the agent's behavior:

```scala
override def definition(): AgentDefinition =
  define()
    .instructions(HelpDeskAgent.Instructions)                               // tone / rules for the model
    .capability(TaskAcceptance.of(HelpDeskTasks.ANSWER).maxIterationsPerTask(5))  // what it accepts + loop bound
```

That declares four things:

1. **Purpose** — `@Component(description = "...")`, injected into the system message.
2. **Instructions** — procedural guidance (tone, when to use the tool, how to complete). *Not*
   orchestration — the loop itself belongs to the runtime.
3. **Accepted task type(s)** — `TaskAcceptance.of(HelpDeskTasks.ANSWER)`, with a `maxIterationsPerTask`
   safety bound.
4. **Tools** — here, the `@FunctionTool def lookupPolicy(...)` defined on the agent, plus the built-in
   `complete_task` tool the SDK generates from the task's result type.

The agent class is **stateless** from your perspective: it holds no `var`, no accumulated question or
partial answer. All of that lives in the Task (below).

### The decision loop

Nobody writes the loop — the runtime runs it, and the **model makes the branch decision at each
iteration**:

```
┌─ iteration N ───────────────────────────────────────────────┐
│ 1. Send to model: system(description + instructions)         │
│    + the task's per-request instructions (the user question) │
│    + available tools: [lookupPolicy, complete_task]          │
│    + everything accumulated from prior iterations            │
│ 2. Model replies with ONE of:                                │
│      (a) a tool call      → runtime runs it, appends the      │
│                              result to context, LOOP AGAIN    │
│      (b) complete_task(HelpAnswer{...}) → task COMPLETED      │
│      (c) "cannot make progress"         → task FAILED(reason) │
└──────────────────────────────────────────────────────────────┘
   reaching maxIterationsPerTask(5) → runtime terminates the task (safety net)
```

A concrete run of *"How do I reset my password?"*:

- **Iteration 1** — the instructions say to consult the knowledge base for known topics, so the model
  chooses to call `lookupPolicy("password-reset")`. The runtime runs the Scala method, appends its
  result, loops.
- **Iteration 2** — with the entry now in context, the model decides it has enough and calls
  `complete_task` with `HelpAnswer(answer=…, category="account", citedTopics=["password-reset"],
  confidence=90)`. Task **COMPLETED**.

For *"What's the capital of France?"* the model would most likely **skip** `lookupPolicy` and complete
on iteration 1 with `citedTopics` empty — because it judges, from the tool/instruction text, that the
question is not one of the known topics. That is a **probabilistic model judgment, not a guarantee**:
it might instead call `lookupPolicy("capital-of-france")`, get a graceful `"No knowledge-base entry…"`
string (a miss is never an error, by design), and only then answer. Both are valid loop paths and both
reach the same typed `HelpAnswer`. The *sequence of steps is itself a model decision* — that is the
defining property of an autonomous agent, and the reason `lookupPolicy` is written to tolerate either
choice.

Contrast capability 2's **Workflow**, where *you* wire `step → step` explicitly. There the sequence is
code; here it is the model's.

---

## What is a Task?

"Task" names two different things, and only one of them is durable state.

### The task *definition* — a schema, not a component

`HelpDeskTasks.ANSWER` is a plain `Task[HelpAnswer]` constant: a name, a description, and the result
type.

```scala
object HelpDeskTasks:
  val ANSWER: Task[HelpAnswer] =
    Task.name("Answer")
      .description("Answer a user's help question, optionally consulting the knowledge base.")
      .resultConformsTo(classOf[HelpAnswer])
```

It is **not durable** and **not an Akka component** — it is not listed in the component descriptor. It
is the template. The SDK uses `resultConformsTo(HelpAnswer)` to build the `complete_task` tool's JSON
schema *and* to deserialize the model's completion into a `HelpAnswer`. Per-request context is added at
call time: `HelpDeskTasks.ANSWER.instructions(question)`.

### The task *instance* — the durable, SDK-managed state

What exists after `runSingleTask(...)` (or `forTask(id).create(...)`) is a **persistent entity the
runtime creates and manages**. It has an id and a lifecycle:

```
PENDING → ASSIGNED → IN_PROGRESS → COMPLETED   (typed result: HelpAnswer)
                                  → FAILED      (failureReason)
```

The runtime persists the task's status, accumulated loop context, and final typed result — each
iteration — to the service's durable store, and recovers them after a crash or restart. **You never
store or load it yourself**; you only hold its id and ask for a typed snapshot:

```scala
componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)   // -> TaskSnapshot[HelpAnswer]
```

So: *definition = the template you write; instance = runtime-owned durable state you address by id.*

### Why the result type is Java-shaped

`HelpAnswer` is a Jackson-annotated Scala case class (`@JsonCreator` / `@JsonProperty`, a
`java.util.List`) rather than an idiomatic Scala record. It is the loop's **exit payload**: the SDK
turns it into the `complete_task` schema and deserializes the model's tool arguments back into it,
both through the SDK's *internal* Jackson mapper — which the feature-003 Scala-module hook does not
reach (the two-mapper finding; `research.md` R3). The HTTP DTOs in `HelpDeskEndpoint` stay idiomatic
Scala; `HelpAnswer` is the one type that must cross that boundary. The `@Description`s on its fields are
not cosmetic — they flow into the generated schema so the model knows what each field means.

---

## How AutonomousAgent and Task relate

They go together, but the coupling is looser than "an agent can only run if you hand it a task":

- **The agent is driven by tasks.** There is no command handler; the only way to make it do anything is
  to give it a task (or task ids) through `ComponentClient` — `runSingleTask(task)` for the common
  one-task case, `assignTasks(ids…)` for several.
- **But a task exists independently of any agent.** It can be created up front, queried by external
  clients while it runs, and handed between agents. Its typed result **outlives the agent instance that
  produced it**.

Capability 3's endpoint shows exactly this independence:

```scala
// POST /help — start a throwaway agent instance on a fresh UUID, get a task id back immediately
val taskId = componentClient
  .forAutonomousAgent(classOf[HelpDeskAgent], UUID.randomUUID().toString)
  .runSingleTask(HelpDeskTasks.ANSWER.instructions(valid.text))

// GET /help/{taskId} — read the typed result later, by id; the agent instance is long gone
componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)
```

The agent instance was a disposable `UUID`; the **task** is the durable, queryable record of the work.
That is why no wrapping Workflow is needed for durability or to "await" the result — `runSingleTask`
returns the id immediately, and the task *is* the durable handle.

Put plainly: **the AutonomousAgent is the stateless process that does the work; the Task is the durable,
typed, queryable state that work reads from and writes to.**

### Is Task specific to AutonomousAgent? Yes.

Task is the **coordination primitive of the autonomous-agent family**, not a general-purpose state
mechanism. Each component family has its own unit of durable work:

| Component | Its unit of work / durable state |
|---|---|
| **AutonomousAgent** | **Task** (typed result, own lifecycle) |
| request-based Agent | command return value + session memory |
| EventSourcedEntity | event journal + state |
| KeyValueEntity | key-value state |
| Workflow | workflow state + steps |
| View | projected read rows |

You would not use a Task with a request-based `Agent` or an Entity — they have no notion of it. The one
subtlety is that a Task is **not owned by a single agent instance** (which is what lets its result be
durable and queryable after the instance is gone) — but it is still part of the AutonomousAgent
machinery, not a facility other components share.

---

## When to use an AutonomousAgent

Reach for an `AutonomousAgent` when **at least one** of these holds:

1. **Investigative / open-ended work** — the *number and order of steps is a model judgment*, not
   something you can wire in advance. ("Research this topic": search, read what you found, maybe search
   again, then synthesize.)
2. **Multi-agent coordination** — delegation, handoff, teams, moderation — *without* you writing the
   orchestration as explicit steps.
3. **Durable multi-step execution that must survive crashes/restarts.**
4. **Typed results with their own lifecycle** — the result must outlive the request, be queried later,
   or move between agents.

Use a **request-based `Agent`** (optionally inside a **Workflow**) when the interaction is
request-response, you want fine-grained control of each prompt/effect, or the orchestration is a fixed
sequence of steps where the model is consulted at most once per step and does not decide what comes
next.

### The sharpening test

> **Do *you* decide what happens next, or does the *model*?**
>
> - *You* decide the sequence (lookup → answer; withdraw → deposit) → request-based Agent, or a Workflow
>   if it is genuinely multi-step.
> - *The model* decides the sequence, or you need several agents coordinating → **AutonomousAgent**.

By this test, a **fixed knowledge source with one lookup — capability 3's own shape — is the first
case.** It is served well by a request-based Agent; capability 3 uses an AutonomousAgent only to
demonstrate the primitive. The point where an AutonomousAgent stops being overhead and becomes the whole
value is when the task becomes *"go find out what you need to answer this, from sources you choose as
you go, and give me a typed result I can query later."*

### Better-fitting examples (elsewhere in this repo)

- **`activities` capability** — an `ActivityCoordinator` with `Delegation.to(WeatherAgent, ActivityAgent)`:
  the model decides at runtime whether it even needs the weather agent before suggesting activities
  (trigger 2).
- **`approvals` capability** — `DraftAgent` / `PublishAgent` use the task lifecycle as a durable,
  queryable record across a human-in-the-loop gate (triggers 3 + 4).

---

## See also

- `quickstart.md` — build, run, and exercise capability 3 (the async start → poll flow).
- `research.md` — the Scala-interop findings (no method-ref wall R1; descriptor key R2; Java-shaped
  task result R3; Gemini tools-vs-JSON R4).
- `akka-context/sdk/autonomous-agents.html.md` — the SDK reference, including the full "When to use an
  Autonomous Agent" rubric and the comparison table with request-based Agents.
