# Learning Roadmap

A personal path for exploring **Akka agentic** capabilities on this Scala 3 + Akka Java SDK
service. Each capability is built as its own [spec-driven feature](specs/) so the work stays
small and reviewable. This page is the one-glance answer to *"what's done, what's next?"* — the
full design detail for any feature lives in its `specs/<id>/` folder.

## Where we are

> **You are here:** Feature 4 (Session memory) — **done and merged (PR #11)** (offline suite green + live
> Gemini smoke test: recall across turns on one session id, isolation across ids). Back in Scala. This was
> the **last** of the four planned capabilities — **the roadmap is complete.**
>
> **📄 Retrospective:** [`FINDINGS.md`](FINDINGS.md) consolidates what all four capabilities taught us —
> the single `dynamicCall` finding that explains every Scala-vs-Java outcome, plus the practical rubric.

## The path

| # | Capability | Feature spec | Status |
|---|------------|--------------|--------|
| — | Baseline greeting agent (foundation) | [`specs/001-greeting-agent`](specs/001-greeting-agent/) | ✅ Done — merged |
| 1 | **Tools + structured output** — agent returns a typed `{greeting, tone, timeOfDay}` object and calls a `@FunctionTool` | [`specs/002-agent-tools-structured`](specs/002-agent-tools-structured/) | ✅ Done — merged (PR #5) |
| 2 | **Multi-agent Workflow** — orchestrate two agents (tone → compose) through an Akka `Workflow`; async start/poll HTTP. **Implemented in Java** (see below) | [`specs/004-multi-agent-workflow`](specs/004-multi-agent-workflow/) | ✅ Done — merged (PR #9) |
| 3 | **Autonomous Agent** — durable, model-driven help-desk agent with a typed task + knowledge-base tool; async start/poll HTTP. **Back in Scala** (see below) | [`specs/005-autonomous-agent`](specs/005-autonomous-agent/) | ✅ Done — merged (PR #10) |
| 4 | **Session memory** — multi-turn chat; context replayed across requests via the SDK's `SessionMemoryEntity`, keyed by a caller-supplied session id; synchronous HTTP. **Scala** (see below) | [`specs/006-session-memory`](specs/006-session-memory/) | ✅ Done — merged (PR #11) |

**Status legend:** ✅ done · 📋 planned (spec written) · 🚧 in progress · ⬜ not started

> **Capability 2 is written in Java, not Scala.** The Akka `Workflow` API is keyed entirely on
> Java *method references* resolved from `SerializedLambda` — step wiring (`transitionTo`,
> `stepTimeout`, `RecoverStrategy.failoverTo`) **and** `WorkflowClient.method(...)`. There is no
> string/step-name overload and no `dynamicCall` on `WorkflowClient` (unlike agents), so a Scala
> lambda's mangled `$anonfun` name never resolves and a Scala workflow can't wire its own steps
> or be invoked. This is the workflow analogue of feature 003's two-mapper finding; the least-
> friction path is to write the whole capability in Java (`com.gwgs.akkaagentic.team.*`), fully
> decoupled from the Scala capability 1. See README "Scala interop notes" §4.

> **Capability 3 is back in Scala — the wall was Workflow-specific, not intrinsic.** The
> `AutonomousAgent` API is keyed on `Class` references, `Task` constants, and annotations —
> `forAutonomousAgent(Class, id)`, `runSingleTask(Task)`, `forTask(id).get(Task)`,
> `Task.name(...).resultConformsTo(Class)`, `AgentDefinition.capability(...)` — with **no**
> `SerializedLambda` method reference anywhere (verified against the SDK 3.6.0 bytecode). So a Scala
> agent and a Scala caller compile to exactly what the SDK expects, and cap-3 is idiomatic Scala with
> none of cap-2's friction. This narrows the roadmap's through-line: the method-reference wall is
> **specific to the Workflow API**, not to durable multi-step orchestration in general — the
> Autonomous Agent, a *more* capable orchestration primitive, is Scala-friendly. The only carried-over
> constraint is the familiar one (the task result stays Java-shaped, per feature 003's two-mapper
> finding). Bonus: the Scala `@Get("/help/{taskId}")` path binding works without scalac `-parameters`.
> Takeaway: **prefer the Autonomous Agent over a Workflow when a Scala capability needs the model to
> drive the loop.** See README "Scala interop notes" §5.

> **Capability 4 stays in Scala — session memory is friction-free to *build*, with two *testing*
> limits.** The multi-turn chat is **Scala**, and building on session memory adds no new interop cost:
> it is keyed by the `.inSession(id)` string, the `MemoryProvider` API is builder-based (no method-ref
> wall), the backing `SessionMemoryEntity` is runtime-registered (no descriptor entry), and the agent
> payload is a bare `String` (no Java-shaped wire type — the least-interop capability yet). Testing it
> surfaced the nuance (feature 006 research R6): a mocked model is fed **only the current turn**, so
> multi-turn **recall** is proven by the live smoke test, not the offline mock; and proving **retention/
> isolation** offline (by reading `SessionMemoryEntity`) must be **Java**, because the EventSourcedEntity
> client is method-ref-only with no `dynamicCall` — the cap-2 `WorkflowClient` wall recurring. Takeaway:
> **the method-ref wall is not Workflow-specific after all — it is a property of every SDK client with no
> `dynamicCall` escape hatch (Workflow *and* EventSourcedEntity clients); the Agent/AutonomousAgent
> clients have it, so they're Scala-callable).** See README "Scala interop notes" §6.

> **Test-language rule: match the test to the code under test.** Scala code gets Scala tests;
> Java code gets Java tests — each capability stays one language end-to-end. This isn't just
> style: the same method-reference wall applies in tests. Pure domain tests, `httpClient`
> endpoint tests, and agent tests (via `dynamicCall`) *can* be Scala, but a test that drives a
> **Workflow** must be Java — `WorkflowClient` exposes only `.method(Wf::start)` (no
> `dynamicCall`), which a Scala lambda can't satisfy. **Now confirmed for entities too:** cap-4's
> `SessionMemoryIntegrationTest` had to be Java because the `EventSourcedEntity` client is likewise
> `.method(Entity::cmd)`-only (no `dynamicCall`), so a Scala caller can't query `SessionMemoryEntity`.
> So "all tests in one language" isn't achievable *or* desirable here; matching the code under test is
> the low-friction path.

## Ideas / follow-ups

Not on the four-capability path, captured so they're not forgotten:

- **Make Jackson Scala-aware** — ✅ *done and merged (PR #7), [`specs/003-scala-native-json`](specs/003-scala-native-json/).*
  Registered `DefaultScalaModule` via an `@Setup` `Bootstrap`
  (discovered through a top-level `akka.javasdk.service-setup` descriptor entry). **Finding:** the
  SDK uses *two* Jackson mappers — the public one (`JsonSupport`) covers **HTTP endpoint bodies**
  only; **component payloads** (agent `Request`/`Result`, and by extension workflow state, entity
  events, view rows, task results) go through a *separate internal* mapper the public hook can't
  reach. So only HTTP DTOs (`GreetRequest`/`GreetReply`) went idiomatic-`Option`; everything
  component-serialized **stays Java-shaped**. Consequence: capabilities 2–4 below can't use
  idiomatic `Option` wire types either — keep them Java-shaped. See README "Scala interop notes" §3.

## Also merged along the way

Small additions made outside the four-capability path, useful as reference:

- **Input validation** — blank `user`/`text` and malformed JSON rejected with `400`, no model call (PR #3).
- **Health endpoint** — `GET /health`, added to prove descriptor-driven component discovery for Scala components (PR #4).

## How this doc is kept current

Updated only when a feature changes status (planned → in progress → done) — a handful of edits
per feature, folded into the feature's own workflow. If this table and the `specs/` folder ever
disagree, `specs/` is the source of truth.
