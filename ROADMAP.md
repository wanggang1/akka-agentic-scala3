# Learning Roadmap

A personal path for exploring **Akka agentic** capabilities on this Scala 3 + Akka Java SDK
service. Each capability is built as its own [spec-driven feature](specs/) so the work stays
small and reviewable. This page is the one-glance answer to *"what's done, what's next?"* — the
full design detail for any feature lives in its `specs/<id>/` folder.

## Where we are

> **You are here:** Feature 13 (LLM-as-judge evaluation) — **in progress on `015-llm-judge-evaluation`**
> ([`specs/014-agent-guardrails`](specs/014-agent-guardrails/)).
> Runtime-enforced governance around cap-8's `DocsAgent`: a request-side jailbreak rule that refuses
> hostile prompts **before any model call**, plus two response-side rules — one enforcing, one
> record-only. The guarded agent names none of them; rules are declared in configuration and built
> reflectively from a class-name string, so there is **no descriptor entry** and **no new dependency**.
>
> **Interop verdict — all three Scala class forms load, and the prediction that an `object` would fail
> was wrong.** `class G(ctx: GuardrailContext)` (loader attempt 1) and `class G` (no-arg, attempt 2 —
> a path the docs never mention) both work, as expected. A Scala **`object`** was predicted to fail
> because its module class's only constructor is `private`; it **loads**, because Akka's
> `ReflectiveDynamicAccess` calls `setAccessible(true)`. The runtime then holds a *fresh instance*, not
> `MODULE$` — harmless only because scalac makes object fields **static**. That **corrects** the
> generalisation cap-11 invited: the deciding property is **whether a constructor with the required
> parameter types exists at all**, not whether it is public (cap-11's inner `TableUpdater` has *none*
> → fails; an `object` has one, merely private → succeeds). Same bytecode-shape axis, different
> failure reason.
>
> Three more results, all measured rather than reasoned: **(a)** the jailbreak rule needed **one config
> line and zero Scala** — the SDK ships `"default jailbreak"` complete but inert (`agents = []`), and
> the runtime evaluates it with the same in-process all-MiniLM ONNX model cap-8 already loads.
> **(b)** A block **cannot be rethrown** from `onFailure` — the SDK catches it as a *"Failure mapping
> error"* and the type is erased crossing the component client — so it travels the reply channel behind
> a shared sentinel. **(c)** A rule's **name and category never reach application code**: the public
> `GuardrailException` carries the bare explanation, and the composed audit line reaches traces and
> metrics only. Rules we author name themselves inside their explanation; the SDK's `SimilarityGuard`
> cannot, and reports `unknown`. Neither limit is Scala-specific — a Java agent hits both identically.
>
> **Previously:** Feature 11 (Views / read-model) — **✅ merged to `main` 2026-08-29 (PR #23)**
> ([`specs/013-views-read-model`](specs/013-views-read-model/)). The CQRS **read side** over cap-6's
> per-username `TodoEntity`: a `View` projects every entity state change into one summary row per assistant
> (`total`/`open`/`completed`), serving both a keyed lookup and the cross-user *"who still has open work?"*
> query — the question an entity, addressable only by its own id, cannot answer. `GET /todo-summaries/...`,
> **read-only** (to-dos are still written only through cap-6's assistant, which is untouched). The project's
> **first entirely model-free capability**: no `TestModelProvider`, mocked or live, anywhere in its tests.
>
> **Interop verdict — the first capability split *across* the component/caller boundary.** Every previous
> encounter with the method-ref wall pulled the **whole component** into Java (cap-2's Workflow, cap-6's
> entity). Here the **View component stays Scala** and only the **caller** is Java, which sharpens the
> through-line to: *the wall is a property of the client, and it travels no further than the class that holds
> the method reference*. Three results: **(R1)** `ViewClient` is method-reference-only (no `dynamicCall`;
> `akka.japi.function.Function` is `Serializable`) → the querying endpoint is Java. **(R2, new hazard class)**
> the `TableUpdater` must live in the **companion `object`**, not as an inner class — the SDK instantiates
> updaters via `getDeclaredClasses()` + a **zero-arg** `getDeclaredConstructor()`, and a Scala inner class has
> only `U($outer)`. This is the first finding that turns on **bytecode shape** rather than on a `Class`- vs
> method-ref-keyed API. **(R3, corrected in PR review)** "Java can't reference
> Scala" was a **latent build defect**, not a language boundary: `maven-compiler-plugin` (parent POM) ran
> before `scala-maven-plugin` (ours), so javac ran first and the capability **did not build from clean** —
> masked throughout development by incremental builds reusing a `target/classes` that already held the Scala
> output. Fix: bind `scala-maven-plugin` to `process-resources` / `process-test-resources` with
> `sendJavaToScalac=true`, so scalac runs first and javac compiles last against its output (which is also why
> `-parameters` now survives). **Both directions compile**, the view rows are Jackson-annotated **Scala** case
> classes, and the Java quarantine is **exactly one class** — the endpoint holding the method reference. README
> §8's "never Java→Scala" is repealed as a mechanical law and survives only as ergonomics guidance.
> **(R6, settled empirically)**
> a keyed query returns `Optional`, empty on no match, so `404` and an all-zero `200` stay distinguishable.
>
> **⏭️ Next:** undecided — choose between **evaluation / LLM-judge** (attacks cap-8's
> soft-grounding gap — and cap-12 sharpened the case for it, since `TextGuardrail.evaluate` receives
> text only and so *cannot* check grounding; an evaluator that sees question *and* answer is the natural
> successor) and **streaming** (highest novelty *and* risk — `StreamEffect` / Scala `Source` interop is
> genuinely unknown).
>
> Capabilities 1–12 are **✅ done and merged**; 5–12 were exploratory follow-ups beyond the original four.
>
> **📄 Retrospective:** [`FINDINGS.md`](FINDINGS.md) consolidates the single `dynamicCall` finding that
> explains every Scala-vs-Java outcome, plus the practical rubric. Caps 5–11 extend the through-line: the
> wall is **client-specific** — `TaskClient` (cap-5), the custom `DependencyProvider` (cap-8), an MCP
> endpoint's reflective dispatch (cap-9, no client at all), and `RemoteMcpTools`'s URL-string builder
> (cap-10) are all on the Scala-friendly side of it; cap-11 shows the wall claiming a component's **caller
> while the component itself stays Scala**, and adds a second, independent hazard axis: **reflected bytecode
> shape** — which cap-12 then re-tested on an unrelated mechanism and **corrected**: on that axis what
> matters is whether a constructor with the right parameter types exists, not whether it is public.

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
| 10 | **MCP client** — a request-based `McpClientAgent` grounds via the **remote `retrieve` MCP tool** of this service's own cap-9 `/mcp` (agentic RAG — the model decides when to retrieve); closes the loop in-process, fully offline; `POST /grounded-ask`. **Scala-clean** — `.mcpTools(RemoteMcpTools.fromService(...))` is a URL-string builder, no method-ref wall; no cap-9 ACL edit; no citations (model owns retrieval). The tool loop **is** offline-testable (real `retrieve` round-trip via `TestModelProvider`) — a positive contrast to cap-7 D9 | [`specs/012-mcp-client`](specs/012-mcp-client/) | ✅ Done — merged (PR #19) |
| 11 | **Views / read-model** — a `View` projects cap-6's `TodoEntity` state into one summary row per username; keyed lookup + the cross-user "who has open work" query an entity can't answer; `GET /todo-summaries/...`, read-only, **no model anywhere** (first fully model-free capability). **First split across the component/caller boundary:** the View is **Scala** (its `TableUpdater` in the companion `object` — a **bytecode-shape** requirement, a new hazard class), only the querying **endpoint** is Java (`ViewClient` is method-ref-only) — the rows are Jackson-annotated **Scala** case classes, once a build-order fix made Java→Scala references compile. Keyed query returns `Optional`; new `view` descriptor key | [`specs/013-views-read-model`](specs/013-views-read-model/) | ✅ Done — merged (PR #23) |
| 12 | **Agent guardrails** — runtime-enforced governance around cap-8's `DocsAgent`: a request-side jailbreak rule (refused **before any model call**), plus response-side rules, one enforcing and one record-only; new `422` outcome on `POST /ask`, `200` answer / `200` decline / `400` validation untouched. Rules are declared in **configuration** and built reflectively from a class-name string — **not components**, so the descriptor is unchanged; the guarded agent names no rule (asserted by a test that reads its source). **All three Scala class forms load**, including `object` — the predicted failure was wrong (`setAccessible` opens the private ctor), which **corrects** cap-11's bytecode-shape rule to "does a ctor with those param types exist", not "is it public". Jailbreak = **one config line, zero Scala, no new dependency**. Two measured limits: a block can't be rethrown (type erased at the client → reply-channel sentinel), and a rule's name/category never reach application code (traces only → rules self-tag their explanation) | [`specs/014-agent-guardrails`](specs/014-agent-guardrails/) | ✅ Done — merged (PR #25) |
| 13 | **LLM-as-judge evaluation** — judges cap-8's answers with the SDK's built-in **`hallucination-evaluator`** (is the answer supported by its passages?) and an authored **`decline-judge`** (was declining — or not declining — right?); `POST /evaluate` over the same pipeline, four outcomes (`passed`/`failed`/`errored`/`not-applicable`), nothing gated. **Capability 8 is byte-identical** — not discipline but a research result: there is no `Consume.From*` source for a request-based agent, so evaluation could only ever have had its own surface. **Headline: `dynamicCall` reaches components the SDK OWNS** — the built-in evaluators are ordinary Agents *and* provided components, so the escape hatch resolves them off `agentClassById`; caps 4/6/11 each quarantined Java for a runtime-owned component, cap-13 has **no Java at all**. An authored evaluator is an ordinary agent whose **return type** implementing `EvaluationResult` (not an annotation) is what routes verdicts to metrics/traces — **one** descriptor line, against cap-12's zero. Fully offline-tested **including the SDK's own judge** (the TestKit's per-agent model override beats `LlmAsJudge`'s explicit `.model(...)`) | [`specs/015-llm-judge-evaluation`](specs/015-llm-judge-evaluation/) | 🚧 In progress |

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

## Candidate next capabilities

The roadmap is **open** — caps 1–10 are merged, the MCP server/client loop is closed, and cap-11 (Views)
is implemented. These are the leading candidates for cap-12, each framed by what it explores and the
project's signature question:
*is it Scala-clean, or does it hit the method-reference wall?* (See [`FINDINGS.md`](FINDINGS.md) for the
wall — the single `dynamicCall` property that has predicted every Scala-vs-Java outcome so far.) None is
specced yet; pick one and start via `/akka.specify`.

| Candidate | What it explores | Fit in this sandbox | Interop bet |
|---|---|---|---|
| **Guardrails** | SDK-enforced constraints around a model call — input (reject/sanitize a prompt) and output (block/redact/rewrite a reply): moderation, PII filtering, jailbreak/injection detection, topic allow/deny, "must be grounded" | Harden an existing agent (e.g. cap-8 `DocsAgent` / cap-10 `McpClientAgent`) — block off-policy input, refuse ungrounded output | **Likely Scala-clean** (class/annotation-registered like tools) — verify it's not method-ref wired. Offline-testable via `TestModelProvider` scripting a violating reply |
| **Evaluation / LLM-as-judge** | A second model call scores a first agent's output against criteria (relevance, groundedness, tone, correctness) — the automated quality-gate / regression-test pattern | A `JudgeAgent` scoring whether `DocsAgent`'s answer is *actually* grounded in retrieved passages — attacks cap-8's **soft-grounding** gap (we instruct grounding but don't prove it) | **Scala-clean** (just another `Agent`). Testing caveat: judgment is model-driven → offline only mocks the verdict, real judging is live (like cap-7's delegation) |
| **Streaming** | Stream the reply token-by-token — `StreamEffect` instead of `Effect<T>`, endpoint emitting SSE / chunked responses (the "typing" UX) | A streaming variant of cap-4 chat or cap-10 grounded-ask | **Unknown — highest novelty.** Stresses the HTTP-endpoint-as-framework-boundary finding; Scala `Source`/stream interop with the SDK streaming API is the open question. Highest-risk, highest-learning pick |

**Views** is no longer a candidate — it was built as **capability 11** (see the path table above), and it
closed the biggest component-coverage gap while overturning its own interop bet: the prediction was
"likely Java", but only the *caller* turned out to be Java while the View component stayed Scala.

**Strongest remaining pick for this project's theme (Scala-on-Java-SDK interop):** **guardrails** — it is
the one candidate that wraps a model call with SDK-registered machinery, so it directly re-tests the
"is it `Class`-keyed or method-ref-keyed?" question on a new surface. **Streaming** is the genuinely
unknown interop territory; **guardrails** and **eval/LLM-judge** harden or extend existing agents and are
likely Scala-clean.

Relevant docs already in-repo: `akka-context/sdk/agents/guardrails.html.md`,
`akka-context/sdk/agents/llm_eval.html.md`, `akka-context/sdk/agents/streaming.html.md`,
`akka-context/sdk/views.html.md`.

### Evaluated, not pursued (as an agent-safety capability)

- **Scala 3 Capture Checking / tracked capabilities** ([Odersky, "Tracked Capabilities for Safer
  Agents"](https://martinodersky.substack.com/p/tracked-capabilities-for-safer-agents)) — evaluated and
  **declined as a capability**. It targets a different architecture (agents that *generate and run code*,
  compiled with capture checking), whereas ours are **tool-calling** agents dispatched **reflectively by
  the SDK** — there is no agent-generated Scala source to check, the SDK's reflective/Java-interop core is
  exactly what the feature's "safe subset" disables, and we're on Scala 3.3.8 LTS (the article's form needs
  3.7+ nightly). For the actual goal (agent safety on this SDK), **Guardrails** above is the pragmatic
  lever. A narrow **pure-`domain`-layer capture-checking spike** is possible as a *Scala-3 feature*
  demonstration only (explicitly not agent safety). Full evaluation + spike scope:
  [`docs/capture-checking-evaluation.md`](docs/capture-checking-evaluation.md).

## Also merged along the way

Small additions made outside the four-capability path, useful as reference:

- **Input validation** — blank `user`/`text` and malformed JSON rejected with `400`, no model call (PR #3).
- **Health endpoint** — `GET /health`, added to prove descriptor-driven component discovery for Scala components (PR #4).

## Known SDK-3.6.x limitations (revisit on upgrade)

A few capabilities hit **version-specific** SDK bugs (distinct from the structural Scala-vs-Java findings
in [`FINDINGS.md`](FINDINGS.md)) — worked around and consolidated in
[`docs/sdk-3.6.0-limitations.md`](docs/sdk-3.6.0-limitations.md) as a single "re-check when we bump the
SDK" list: cap-9's non-tunable MCP `maxResults`, cap-7's un-mockable request-based delegation (D9),
cap-6's `readLast(N)` tool-pair trim, and cap-12's **six guardrail documentation divergences** — of which
two would change a design if fixed: a block cannot be rethrown to the caller (the type is erased crossing
the component client), and a rule's name and category never reach application code at all. Do the bump on
its own branch with a full `mvn clean verify`.

## How this doc is kept current

Updated only when a feature changes status (planned → in progress → done) — a handful of edits
per feature, folded into the feature's own workflow. If this table and the `specs/` folder ever
disagree, `specs/` is the source of truth.
