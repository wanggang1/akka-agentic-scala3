# Research: MCP Client Agent (cap-10)

Phase 0 findings. The headline interop question is settled **statically** here (bytecode); two
mechanics (self-call topology, offline-testability of the tool loop) carry a residual risk resolved
by an **early spike** in Phase 2, mirroring cap-9's envelope spike (T004/T005).

---

## R1 — HEADLINE: `.mcpTools(RemoteMcpTools.fromServer/fromService(String))` is Scala-clean (no method-ref wall)

**Decision**: Consume the remote MCP server with
`effects().…mcpTools(RemoteMcpTools.fromService(<serviceName>))`. This is idiomatic Scala with **no**
friction.

**Evidence (bytecode-verified, `javap` on `akka-javasdk-3.6.0.jar`)** — `RemoteMcpTools` is a builder
keyed entirely on **strings and value config**, never on Java method references:

```
public interface akka.javasdk.agent.RemoteMcpTools {
  static RemoteMcpTools fromServer(String);          // URL string
  static RemoteMcpTools fromService(String);         // deployed service name string
  RemoteMcpTools withToolNameFilter(Predicate<String>);
  RemoteMcpTools withAllowedToolNames(Set<String>);
  RemoteMcpTools withAllowedToolNames(String, String...);
  RemoteMcpTools withToolInterceptor(RemoteMcpTools.ToolInterceptor);   // interface impl
  RemoteMcpTools addClientHeader(akka.http.javadsl.model.HttpHeader);
  RemoteMcpTools withTimeout(java.time.Duration);
}
```

There is **no `Function`/`SerializedLambda` parameter** anywhere, so nothing for
`impl.client.MethodRefResolver` to choke on — exactly like `.tools()` (§ cap-6), the
Agent/AutonomousAgent `dynamicCall` clients (§2/§5), `DependencyProvider` (§10), and `TaskClient`
(§7), and unlike the Workflow/entity clients (§4/§6).

**Rationale / through-line**: cap-9 established "the method-ref wall is a *client* property; an MCP
*server* endpoint has no client to author, so it's Scala-clean." Cap-10 tests the *consuming* side —
where there **is** an outbound call — and finds it Scala-clean too, because the outbound MCP call is
configured by a **URL-string builder**, not a `ComponentClient` method reference. So the refined
rule stands: *what makes a call Java-only is a `ComponentClient` keyed on a `SerializedLambda` with
no `dynamicCall` escape — not "making an outbound call" per se.* `RemoteMcpTools` is not a
`ComponentClient` at all.

**Alternatives considered**: none for the mechanism — this is the SDK's only remote-MCP-consumption
API. (`.tools()` with a hand-rolled HTTP client to `/mcp` would work but re-implements JSON-RPC and
abandons the whole point of the capability.)

---

## R2 — Self-call topology: `fromService(ownServiceName)` vs `fromServer(localUrl)`

**Decision**: Point the agent at **`RemoteMcpTools.fromService(<resolved service name>)`** — this
service's own deployed name. The name is **config-overridable with an artifactId fallback** rather
than a bare constant: `McpClientAgent` injects `com.typesafe.config.Config` (injectable into any
component, incl. Agents) and resolves the name by precedence —
`mcp-client.knowledge-service-name` (explicit override, if non-blank) → `akka.javasdk.dev-mode.service-name`
(the SDK's own resolved name = the artifactId in dev-mode, if non-blank) → `"akka-agentic-scala3"`
(the artifactId hard fallback). This keeps the self-call correct if the service is ever deployed under
a different name (set the override key / `KNOWLEDGE_MCP_SERVICE_NAME` env), without hardcoding.

> **Name disambiguation** — three distinct identifiers, easy to conflate: (1) the **Akka service
> name** `akka-agentic-scala3` (artifactId / deploy name) — what `fromService` routes on; (2) the
> **MCP protocol `serverName`** `akka-agentic-knowledge-mcp` (in cap-9's `@McpEndpoint`) — a handshake
> identifier, **not** used for routing; (3) the **path** `/mcp`. `fromService` uses (1).

**Rationale**:
- The SDK doc (`agents/extending.html.md`, note 1) says: *"For MCP endpoints in **other Akka
  services**, use HTTP and the deployed service name"* via `fromService(name)`, and *"the service
  ACLs apply just like for HTTP endpoints."* Cap-10 targets an MCP endpoint in the *same* service, so
  a self-call by the service's own name is the closest idiomatic fit — portable across TestKit, local
  `exec:java`, and a real deploy (the runtime resolves the name; no host/port is hardcoded).
- `fromServer("http://localhost:9000/mcp")` hardcodes host+port (brittle: TestKit binds an ephemeral
  port, a deploy has neither `localhost` nor `9000`) and the doc frames `fromServer` as the
  **third-party / public-internet, HTTPS** path — the wrong tool for an in-cluster call.

**Residual risk → Spike S1** — ✅ **RESOLVED (both sub-risks clear; no cap-9 edit needed).** Spike
a throwaway spike (green, 2026-08-23; since retired — its coverage folded into
`McpClientEndpointIntegrationTest`, which drives the same loop through `POST /grounded-ask`) proved:
1. **Self-routing works.** `RemoteMcpTools.fromService("akka-agentic-scala3")` — with
   `TestKit.Settings.withServiceName("akka-agentic-scala3")` — resolved to **this** service's own
   in-process `KnowledgeMcpEndpoint` `/mcp`. A service *can* address itself by name; the `fromServer`
   fallback was **not** needed.
2. **ACL: no edit needed.** cap-9's endpoint kept its INTERNET-only `@Acl` and the self-service call
   **still went through** (the real `/mcp` returned real passages — proven by the parity assertion).
   So the anticipated additive ACL broadening was **unnecessary** — cap-9 is untouched. (A self /
   same-service MCP call is not denied by an INTERNET-only ACL in this SDK.)

**TestKit alignment**: `TestKit.Settings.withServiceName("akka-agentic-scala3")` sets the running
service's name in tests so `fromService("akka-agentic-scala3")` resolves to self. (Bytecode confirms
`withServiceName(String)` and a `serviceName` field on `TestKit$Settings`.)

---

## R2b — `fromService(name)` resolves to ONE endpoint (`/mcp`), and tool-name uniqueness is per-agent, not app-global

**Finding (bytecode-verified, `akka-javasdk-3.6.0`)** — how a service that hosts *multiple* MCP
servers is addressed, and where tool names must be unique:

- **`@McpEndpoint.path()` defaults to `/mcp`** (`AnnotationDefault: "/mcp"`). Multiple `@McpEndpoint`s
  in one service must therefore sit at **distinct paths** (only one can own `/mcp`; others need an
  explicit `@McpEndpoint(path = "/mcp-foo")`).
- **`fromService(name)` hardcodes the `/mcp` path** — its factory does
  `makeConcatWithConstants` with the recipe **`http://<name>/mcp`**. So `fromService` reaches **exactly
  one** endpoint (the default-path `/mcp` one), *not* an aggregate of all MCP servers in the service.
  To reach a non-default-path MCP server, use **`fromServer("http://<name>/mcp-foo")`** (the full-URL
  form takes the string verbatim; `fromService` has no path parameter).

**Tool-name uniqueness scope** — the relevant answer for "if we add more MCP servers":
1. **Within one MCP server** (one `tools/list`): names must be unique. (Trivially.)
2. **Across different MCP servers in the app**: **no** global uniqueness — two separate `@McpEndpoint`s
   may each expose a `retrieve` tool; they are distinct servers at distinct paths.
3. **The binding constraint is per-agent-per-request**: when one agent registers several tool sources
   *together* — multiple `.mcpTools(RemoteMcpTools…)`, its own `@FunctionTool`s, and `.tools()`
   objects — the model is presented a **single flat tool namespace** for that call, so names must be
   unique **among the tools that one agent exposes in one request**. `withAllowedToolNames(...)` /
   `withToolNameFilter(...)` exist precisely to filter/disambiguate each server's contribution.

**Relevance to cap-10**: this service has exactly one MCP server (`/mcp`), so
`fromService("akka-agentic-scala3")` is unambiguous. This finding is forward-looking — it governs the
day a second MCP server or a filtered multi-server agent is added (and explains why a future
"consume two MCP servers" agent would lean on `fromServer(url)` + the tool-name filters).

---

## R3 — Offline-testability of the MCP tool loop: PLAUSIBLE (a positive contrast to cap-7 D9)

**Decision**: Attempt a **faithful offline test** in which the mocked model *scripts a `retrieve`
tool call* that round-trips to the real in-process `/mcp`, then answers from the real `ToolResult`.
Confirm feasibility in **Spike S2**; if it doesn't hold, fall back to the established split (prove
tool-grounding live; offline covers validation + wiring + shared-store parity).

**Evidence (bytecode, `TestModelProvider`)** — the mock model *can* drive a tool-calling turn:

```
void fixedResponse(TestModelProvider$ToolInvocationRequest);        // model emits a tool call
WhenToolReplyClause whenToolResult(Predicate<ToolResult>);          // then replies given the result
WhenToolReplyClause whenToolResult(ToolResult);
// nested: ToolInvocationRequest, ToolResult, WhenClause, WhenToolReplyClause
```

So — unlike cap-7's delegation (where `AutonomousAgentTools.delegateTo` delivered an *untyped*
payload the worker couldn't deserialize → silent false-green, D9) — a plain **MCP tool** is a normal
function-tool call: the mock emits `ToolInvocationRequest("retrieve", {"question": …})`, the SDK
dispatches it to the **real** remote MCP server (the in-process `KnowledgeMcpEndpoint`), and the
returned JSON String arrives as a `ToolResult` the mock can assert on and answer from. That makes the
whole loop — model → MCP client → our MCP server → `KnowledgeStore` — potentially reproducible with
**no live model**.

**Rationale for still spiking it**: the untested link is whether the SDK actually performs the real
remote-MCP HTTP round-trip *during a `TestModelProvider` run* (vs. short-circuiting), and whether the
scripted `retrieve` name/args bind through the remote-tool path the same way a `@FunctionTool` does.
S2 asserts the `ToolResult` the mock receives equals a direct `KnowledgeStore.retrieve` for the same
question — which, if it passes, *is* SC-005 (parity) proven offline.

**✅ RESOLVED — the loop IS faithfully offline-testable (no fallback needed).** The spike (green,
2026-08-23; retired, folded into `McpClientEndpointIntegrationTest`): the mock scripted
`whenUserMessage(...).reply(ToolInvocationRequest("retrieve", {"question":…}))` then
`whenToolResult(name=="retrieve").thenReply(tr => AiResponse(...))`. The SDK **performed the real
round-trip** to the in-process `/mcp`, and the captured `ToolResult.content()` **equaled**
`KnowledgeStore.fromCorpus().retrieve(question, 3)` (same source labels, same order; top =
`interop-method-ref-wall`). So the whole loop — model → MCP client → our MCP server → `KnowledgeStore`
— is reproducible with **no live model**, and **SC-005 parity is proven offline**. This is the clean
positive contrast to cap-7 D9: a remote **MCP tool** returns a typed `String` result the mock
receives intact, whereas cap-7's `delegateTo` delivered an untyped payload (false-green). No
`withMockedHttpService` / live-only fallback was needed.

**Fallbacks (in order)**:
1. `TestKit.Settings.withMockedHttpService(<serviceName>, fn)` — serve canned JSON-RPC for `/mcp`
   from the test (still exercises the model's tool loop; slightly less faithful — the passages are
   canned, not retrieved). Bytecode confirms this hook exists.
2. Live smoke test only for the closed loop (cap-7 precedent), offline for everything reproducible.

---

## R4 — Component shape & reuse (two-mapper boundary holds)

**Decision**:
- **New code is thin**: one request-based `McpClientAgent` (Scala) + one synchronous `McpClientEndpoint`
  (`POST /grounded-ask` or similar). **No** new domain/corpus/retrieval — reuse cap-9's
  `KnowledgeMcpEndpoint` (the server) and cap-8's `KnowledgeStore` (via the same `Bootstrap` DI, only
  for the *test* parity assertion and possibly nothing in the agent path).
- **Agent payload**: a **bare `String`** in and out (the cap-4/cap-8 reply shape) — no Java-shaped
  result record needed, because the agent returns free-text answer, and grounding comes from the tool
  (not a typed structured result). The agent `Request`, if a record is used for the message, stays
  **Java-shaped** (crosses the internal mapper, §3); simplest is a bare `String` userMessage.
- **HTTP DTOs**: idiomatic `Option`-typed Scala case classes (§3), validated via the existing
  `AskQuestion.validate` domain type (reused from cap-8) or a cap-10-local validation mirroring it.
- **No `citedSources`** (spec Assumption): the endpoint no longer owns retrieval, so it cannot compute
  ground-truth citations. The reply is `{answer}` only. This is the deliberate cap-8-fork tradeoff.

**Rationale**: Simplicity (constitution IV) + API isolation (II). Reusing `AskQuestion` keeps the
validation-first contract identical to cap-8 with zero new domain logic.

**Descriptor**: add the new `agent` and `http-endpoint` to the hand-maintained descriptor
(`META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`). The MCP server
(`KnowledgeMcpEndpoint`) is already registered under `mcp-endpoint` from cap-9 — no change there
beyond the possible ACL broadening (R2).

---

## R5 — Grounding as a soft constraint; tool-call step budget

**Decision**: The agent's system prompt instructs it to (a) use the `retrieve` tool to ground
answers about the sandbox's knowledge and (b) honestly decline when the retrieved passages don't
cover the question — the **same soft-constraint** posture as cap-8 (grounding is instruction-
following, not a runtime guarantee; documented, not "proven per answer").

- `akka.javasdk.agent.max-tool-call-steps` defaults to **100** — ample for a single-hop (or few-hop)
  retrieval; no config change needed. The agentic-RAG multi-hop *possibility* (model reformulates and
  retrieves again) is available for free within that budget; we neither force nor forbid it.
- **Decline shape**: reuse cap-8's sentinel approach only if a typed decline is needed; since cap-10
  drops citations and returns bare text, a plain natural-language decline in the answer text is
  sufficient (SC-002 asserts "not fabricated," verified by the mock scripting a decline and — live —
  by a genuinely out-of-corpus question).

**Rationale**: matches the rest of the project; keeps the surface minimal (YAGNI).

---

## R6 — Tool transport is invisible to the model: `@FunctionTool` and `@McpTool` are interchangeable at the model layer

**Finding**: The system prompt does **not** name the transport ("MCP") and shouldn't — the model can't
use that fact. Every tool the agent registers — a local `@FunctionTool` method, an external `.tools()`
object, or a remote `@McpTool` via `.mcpTools(url)` — is presented to the model through the **same
function-calling interface**: a flat list of `{name, description, inputSchema}`. The model selects a
tool by **name**, emits a call, and receives a result; whether the SDK dispatches that to an in-process
method or an HTTP JSON-RPC round-trip is hidden. So `@FunctionTool` and `@McpTool` are **interchangeable
from the model's / prompting point of view**.

**Evidence (our own spike)**: the mock drove the MCP tool with `ToolInvocationRequest("retrieve", {…})`
— by **name, identically to how a `@FunctionTool` call is scripted**. Nothing in that request marked it
as MCP; that's exactly why the mock could drive it without knowing the transport.

**Where they are NOT interchangeable** (below the model layer only): authoring (agent/`.tools()` method
vs a remote endpoint), registration (`@FunctionTool`/`.tools()` vs `.mcpTools(...)`), dispatch
(in-process vs JSON-RPC), and — the useful one — **where the tool description the model sees comes
from**: for an MCP tool it is the **server's advertised `tools/list`** (cap-9's
`@McpTool(description = "Semantic search over the knowledge corpus…")`), not the client. So the agent's
prompt doesn't strictly need to describe `retrieve` at all; naming it + stating the grounding/decline
policy is **steering**, and that is all the prompt should carry about the tool.

---

## Open items carried into Phase 2 (tasks)

- **S1 (topology spike)**: `fromService(self)` resolves + ACL — broaden cap-9 ACL if needed.
- **S2 (testability spike)**: scripted `retrieve` tool call round-trips to real `/mcp` under
  `TestModelProvider`; `ToolResult` == direct `KnowledgeStore.retrieve` (proves SC-005 offline if it
  holds).
- Both are front-loaded (like cap-9's T004/T005) so the interop verdict (SC-007) and the test strategy
  are settled before the endpoint/agent are finalized.
