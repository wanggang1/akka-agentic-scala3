# Learning Roadmap

A personal path for exploring **Akka agentic** capabilities on this Scala 3 + Akka Java SDK
service. Each capability is built as its own [spec-driven feature](specs/) so the work stays
small and reviewable. This page is the one-glance answer to *"what's done, what's next?"* — the
full design detail for any feature lives in its `specs/<id>/` folder.

## Where we are

> **You are here:** Feature 10 (MCP client) — **🚧 in progress**
> ([`specs/012-mcp-client`](specs/012-mcp-client/)). A request-based `McpClientAgent` grounds its answers by
> calling the **remote `retrieve` MCP tool** of this service's own cap-9 `/mcp` server — the model decides
> whether/when to retrieve (**agentic RAG**), closing the loop in-process (agent → MCP client → our MCP
> server → `KnowledgeStore`), fully offline. `POST /grounded-ask`, synchronous. **Scala end-to-end, tests
> included** (8 offline tests + live smoke on Ollama `qwen3:8b`). **Interop verdict (R1):** consuming a remote
> MCP server is **Scala-clean** — `.mcpTools(RemoteMcpTools.fromService/fromServer(...))` is a **URL-string
> builder** with no method reference (bytecode-verified), so the *outbound* side hits no method-ref wall
> either (the MCP client isn't a `ComponentClient`). No new domain (reuses cap-8's `AskQuestion`/
> `KnowledgeStore`), **no `citedSources`** (the model owns retrieval — cap-8-fork tradeoff), no cap-9 ACL edit
> (a same-service `/mcp` call passes the INTERNET-only ACL). **Positive testing result vs cap-7 D9:** a remote
> MCP tool is a normal typed-`String` function-tool call, so `TestModelProvider` scripts a **real** `retrieve`
> round-trip offline (SC-005 parity vs a direct `KnowledgeStore.retrieve`). Remaining: `/akka.analyze`, then
> commit + PR.
>
> **⏭️ Next:** roadmap open — caps 1–9 merged; the MCP server/client loop is closed. Candidate directions:
> guardrails, evaluation/LLM-judge, streaming, or a Views/read-model capability.
>
> Capabilities 1–9 are **✅ done and merged**; 5–9 were exploratory follow-ups beyond the original four.
>
> **📄 Retrospective:** [`FINDINGS.md`](FINDINGS.md) consolidates the single `dynamicCall` finding that
> explains every Scala-vs-Java outcome, plus the practical rubric. Caps 5–10 extend the through-line: the
> wall is **client-specific** — `TaskClient` (cap-5), the custom `DependencyProvider` (cap-8), an MCP
> endpoint's reflective dispatch (cap-9, no client at all), and `RemoteMcpTools`'s URL-string builder
> (cap-10) are all on the Scala-friendly side of it. **The MCP through-line is complete: server and client
> are both Scala-clean for the same reason — neither authors a `ComponentClient` method reference.**

## The path

| # | Capability | Feature spec | Status |
|---|------------|--------------|--------|
| — | Baseline greeting agent (foundation) | [`specs/001-greeting-agent`](specs/001-greeting-agent/) | ✅ Done — merged |
| 1 | **Tools + structured output** — agent returns a typed `{greeting, tone, timeOfDay}` object and calls a `@FunctionTool` | [`specs/002-agent-tools-structured`](specs/002-agent-tools-structured/) | ✅ Done — merged (PR #5) |
| 2 | **Multi-agent Workflow** — orchestrate two agents (tone → compose) through an Akka `Workflow`; async start/poll HTTP. **Implemented in Java** (see below) | [`specs/004-multi-agent-workflow`](specs/004-multi-agent-workflow/) | ✅ Done — merged (PR #9) |
| 3 | **Autonomous Agent** — durable, model-driven help-desk agent with a typed task + knowledge-base tool; async start/poll HTTP. **Back in Scala** (see below) | [`specs/005-autonomous-agent`](specs/005-autonomous-agent/) | ✅ Done — merged (PR #10) |
| 4 | **Session memory** — multi-turn chat; context replayed across requests via the SDK's `SessionMemoryEntity`, keyed by a caller-supplied session id; synchronous HTTP. **Scala** (see below) | [`specs/006-session-memory`](specs/006-session-memory/) | ✅ Done — merged (PR #11) |
| 5 | **Human-in-the-loop approval gate** *(exploratory follow-up)* — a `DraftAgent` drafts a reply, an **unassigned approval task** gates it, a `PublishAgent` runs only on approval; the Autonomous Agent **external-input** pattern (a three-task dependency chain, no Workflow); async start/poll + a human decision endpoint. **Scala, tests included** (see below) | [`specs/007-human-approval-gate`](specs/007-human-approval-gate/) | ✅ Done — merged (PR #12) |
| 6 | **Agent-to-agent delegation** *(exploratory)* — a personal assistant per username (persisted chat history + to-do list) that **delegates** to another user's assistant by username; request-based **agent chaining** — the SDK-discouraged path — proven idiomatic **Scala**, with the to-do store quarantined into Java; synchronous HTTP + a structural one-hop guard | [`specs/008-agent-to-agent`](specs/008-agent-to-agent/) | ✅ Done — merged |
| 7 | **AutonomousAgent delegation** — the **recommended** dynamic multi-agent delegation primitive (`Delegation.to(...)`), the blessed counterpart to cap-6's hand-rolled chaining; stays Scala. Request-based delegation isn't faithfully mockable offline in SDK 3.6.0 (D9) → delegation proven live | [`specs/009-autonomous-delegation`](specs/009-autonomous-delegation/) | ✅ Done — merged |
| 8 | **RAG-grounded Q&A** — a `DocsAgent` answers grounded only in passages retrieved by in-process semantic embeddings (all-MiniLM ONNX, in-jar, offline) from a canned corpus, or honestly declines; custom-dependency DI is Scala-clean; retrieval is deterministic → offline-testable; synchronous HTTP | [`specs/010-rag-grounded-qa`](specs/010-rag-grounded-qa/) | ✅ Done — merged (PR #17) |
| 9 | **MCP server** — an `@McpEndpoint` at `/mcp` exposes cap-8's retrieval as a JSON-RPC `retrieve` tool + a corpus-sources resource; **Scala-clean** (endpoint, reflective dispatch — no method-ref wall; new `mcp-endpoint` key); a `@McpTool` must return String (throw→isError); fixed top-K 3 (SDK-3.6.0 optional-param bug). Server-side only | [`specs/011-mcp-knowledge-server`](specs/011-mcp-knowledge-server/) | ✅ Done — merged (PR #18) |
| 10 | **MCP client** — a request-based `McpClientAgent` grounds via the **remote `retrieve` MCP tool** of this service's own cap-9 `/mcp` (agentic RAG — the model decides when to retrieve); closes the loop in-process, fully offline; `POST /grounded-ask`. **Scala-clean** — `.mcpTools(RemoteMcpTools.fromService(...))` is a URL-string builder, no method-ref wall; no cap-9 ACL edit; no citations (model owns retrieval). The tool loop **is** offline-testable (real `retrieve` round-trip via `TestModelProvider`) — a positive contrast to cap-7 D9 | [`specs/012-mcp-client`](specs/012-mcp-client/) | 🚧 In progress |

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

> **Capability 5 stays in Scala — `TaskClient` is on the Scala-friendly side of the wall.** The
> human-in-the-loop approval gate is **Scala end-to-end, tests included** — the clean counter-example to
> cap-2's Java Workflow and cap-4's forced Java entity test. The human decision goes through
> `componentClient.forTask(id)`, whose whole surface (`create`/`get`/`result`/`assign`/`complete`/`fail`)
> is keyed on value objects and strings — **no `SerializedLambda` method reference** (verified against SDK
> 3.6.0 bytecode) — so there is nothing for `MethodRefResolver` to choke on, unlike a Workflow
> `pause`/`resume` gate. Two design choices keep it that way: **(1)** the mechanism is the Autonomous Agent
> *external-input* pattern — a three-task chain (`draft → unassigned gate → publish`) where the runtime
> withholds `publish` until a human completes the gate — **not** a Workflow; **(2)** the three task ids are
> *derived* from one `caseId`, so there is **no Entity** storing the mapping — which matters because an
> entity client *would* reintroduce the method-ref wall (§6, cap-4) and force Java. Result: a durable,
> human-gated, multi-step flow that is idiomatic Scala including verification. Takeaway: **the wall is
> client-specific — `TaskClient` (like the Agent/AutonomousAgent clients) has no method-ref requirement;
> the Workflow and EventSourcedEntity clients do.** See README "Scala interop notes" §7.

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

## Known SDK-3.6.0 limitations (revisit on upgrade)

A few capabilities hit **version-specific** SDK bugs (distinct from the structural Scala-vs-Java findings
in [`FINDINGS.md`](FINDINGS.md)) — worked around and consolidated in
[`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md) as a single "re-check when we bump the
SDK" list: cap-9's non-tunable MCP `maxResults`, cap-7's un-mockable request-based delegation (D9), and
cap-6's `readLast(N)` tool-pair trim. Do the bump on its own branch with a full `mvn verify`.

## How this doc is kept current

Updated only when a feature changes status (planned → in progress → done) — a handful of edits
per feature, folded into the feature's own workflow. If this table and the `specs/` folder ever
disagree, `specs/` is the source of truth.
