# Research — MCP Knowledge Server (feature 011)

Phase 0 resolved every unknown against the SDK 3.6.0 bytecode (`akka-javasdk-3.6.0.jar`,
`akka-javasdk-annotation-processor-3.6.0.jar`) and `akka-context/sdk/mcp-endpoints.html.md`. No
`NEEDS CLARIFICATION` remain.

---

## R1 — Is `@McpEndpoint` Scala-authorable? (the headline interop question)

**Decision: YES — Scala-clean, no method-ref wall. It is the MCP analogue of cap-1's HTTP endpoint.**

**Evidence:**
- `@McpEndpoint` is an **endpoint**, not a `@Component` — same family as `@HttpEndpoint`/`@GrpcEndpoint`
  (doc: *"An Endpoint is a component that creates an externally accessible API. MCP Endpoints allow
  you to expose … to MCP clients"*). Annotation FQCNs confirmed in the jar:
  `akka.javasdk.annotations.mcp.{McpEndpoint, McpTool, McpResource, McpPrompt}`; optional base
  `akka.javasdk.mcp.AbstractMcpEndpoint` (+ `akka.javasdk.mcp.McpRequestContext`) for header/JWT
  access without constructor injection.
- **Why no wall:** the wall (cap-2 §4, cap-4/cap-6 §6) is a *client* property — the SDK resolving a
  Java `SerializedLambda` from `componentClient.forX().method(X::m)`. An MCP tool is invoked in the
  **opposite direction**: a *remote* MCP client calls *us*, and the SDK dispatches to our annotated
  method **reflectively** from the JSON-RPC payload. We author no `method(...)` reference at all. The
  retrieval call inside the tool is a **plain Scala method call** on the injected `KnowledgeStore`
  (`knowledgeStore.retrieve(q, k)`), not a ComponentClient call — so there is nothing for
  `MethodRefResolver` to choke on. This places `@McpEndpoint` firmly on the Scala-friendly side of the
  wall, alongside HTTP endpoints, Agents, AutonomousAgents, Tasks, and custom DI.

**Descriptor key = `mcp-endpoint`.** Confirmed from the SDK's `ComponentType` constant pool
(printable-strings dump lists exactly: `agent`, `autonomous-agent`, `consumer`,
`event-sourced-entity`, `grpc-endpoint`, `http-endpoint`, `key-value-entity`, `mcp-endpoint`,
`timed-action`, `view`, `workflow`). So — like every Scala component here — the endpoint must be
hand-added to the descriptor ([[scala-akka-component-descriptor]]) under a **new** `mcp-endpoint`
key. This is the one wiring step; there is no annotation-processor involvement (it is disabled via
`-proc:none`, §4).

**Alternatives considered:** authoring the endpoint in Java (as cap-2/cap-6's walled pieces were) —
**rejected**, because there is no wall to route around; a Scala endpoint compiles and is discovered
exactly like cap-1's `GreetingEndpoint`. Verdict is recorded for README §11 / SC-005.

---

## R2 — How does the tool receive its input from Scala? (param reflection vs manual schema)

**Decision (REVISED twice — T005 then T006; see the two "Empirical correction" blocks below): take a
single bare tool parameter** — `retrieve(@Description question: String)` — **no wrapper record, no
manual `inputSchema`, and no `maxResults`** (the optional count is blocked by an SDK-3.6.0 bug — T006 —
so the tool returns a fixed top-K of 3, mirroring cap-8). The earlier two-param `Optional[Integer]` shape
is retained in the narrative below only as the trail to that finding.

> **Empirical correction (T005, proven live against the TestKit).** The pre-implementation decision
> below (Java-shaped record + manual `inputSchema`) **failed at runtime** — every `tools/call` returned
> `isError` with *"Missing required tool parameter [input]"* / *"argument type mismatch"*. Two things the
> bytecode didn't reveal but the spike did:
> 1. **The SDK maps each tool *parameter* to a top-level JSON-Schema property by the parameter's name**
>    (the `add(n1, n2)` sample is literal: two params → two top-level properties). A single record param
>    named `input` therefore expects the caller to send `{"input": {...}}`, **not** `{"question": ...}` —
>    and a **manual `inputSchema`** is matched *by parameter name* the same way, so our top-level
>    `question`/`maxResults` schema never bound to the `input` param. Wrong call convention for our clients.
> 2. **scalac emits parameter names in this build** — the SDK read the real name `input` off the Scala
>    method, so the R2 fear ("Scala param names may not reflect") is **disproven**: the mixed-build
>    `-parameters` flag (§4) covers scalac too. This is a *positive* interop finding.
>
> So the fix is the SDK's *own* tool shape: **bare parameters**. `question: String` → a required
> top-level `question`; `maxResults: java.util.Optional[Integer]` → an optional top-level `maxResults`
> (the SDK treats `Optional<T>` as non-required). Converted with `maxResults.toScala.map(_.intValue)`.
> This removes the wrapper record, the manual schema, **and** the Scala-3 `inline val`-constant wrinkle
> the manual schema needed — strictly simpler. Verified live: valid call returns the top-K passages
> (`isError:false`), blank question returns our validation message with `isError:true`.
>
> **Also settled — R3's error channel.** A `@McpTool` **must return `String`** (SDK rejects other return
> types at startup: *"MCP tool method must return String"*), so there is no `Effect`/`Either`/typed-error
> return. The tool's error channel is to **throw**: the runtime converts a thrown exception into a
> well-formed MCP `{content:[{type:text,text:<msg>}], isError:true}` result (verified live) — the proper
> `isError` shape the calling model sees. The domain stays pure (`AskQuestion.validate` returns `Either`);
> the endpoint adapter throws on `Left`, mirroring `DocsEndpoint`'s `Left → badRequest`.

> **Second empirical correction (T006) — `maxResults` cannot be expressed on SDK 3.6.0; the tool ships
> a fixed top-K (3).** The T005 spike above only ever exercised `maxResults` *omitted* (Optional.empty).
> T006's full suite exercised a *supplied* value and hit a hard SDK wall — three dead ends, all proven
> against the TestKit:
> 1. **`Optional[Integer]` bare param** (the doc-recommended non-required shape, mcp-endpoints.html.md
>    line 54): a *supplied* value throws `"class java.util.Optional cannot be cast to class
>    java.lang.Integer"`. The cast is Optional→element, so it fails for **every** element type (String,
>    Long, …), not just Integer — the SDK deserializes the arg into an `Optional` but then force-casts it
>    to the element type during method invocation. Omitted works; any supplied value fails.
> 2. **Plain nullable `Integer` bare param**: supplied values bind fine, but the SDK marks a non-Optional
>    object param **required**, so *omitting* it fails with `"Missing required tool parameter
>    [maxResults]"`. The exact inverse of (1).
> 3. **Manual `inputSchema` over two bare params** (to relax `required` to `question` only): breaks
>    argument binding entirely — every call returns `"argument type mismatch"`. The manual-schema path is
>    tied to a single wrapper-record param (the `echo(input)` sample), incompatible with bare multi-params.
>
> All three are an **SDK bug/limitation, not a Scala-interop wall** (the reflective bare-param path itself
> works — R1/T005). With no shape that is both optional *and* functional, and per the user's call, the
> tool drops `maxResults` and returns a **fixed top-K of 3** — exactly mirroring cap-8's `DocsEndpoint`
> (which also uses a fixed `TopK`). This is a faithful cap-8 mirror, not a feature loss of the retrieval
> itself. **TODO: revisit on an SDK upgrade past 3.6.0** — re-add the optional `maxResults` (preferred
> shape `Optional[Integer]` if the cast bug is fixed) and restore the clamping tests. Tracked with the
> project's other 3.6.0-version-limited items (delegation offline-mockability D9, `readLast` tool-pair
> trim). The spec's SC-006/FR-006 result-count clauses and the contract §3 clamping rows carry matching
> deviation notes.

**The original (superseded) rationale — kept for the record:**

**Decision: take a single Java-shaped input record + a manual `inputSchema` string on `@McpTool`.**

**Rationale:** The doc says the input schema is *"reflectively created based on the input parameter
type"*, that *"only simple input parameter classes are supported"* (fields primitive/boxed/String),
non-required = `Optional<T>`, and — for full control — *"it is also possible to specify the JSON
Schema of the input manually in the annotation."* Two Scala-specific risks make reflection-from-params
the fragile path:
1. **Param-name emission.** A schema built from a bare method parameter (`retrieve(String question,
   …)`) needs the parameter *name* `question`, which requires `-parameters` at **scalac** compile
   time. cap-3 found Scala `@Get("/{taskId}")` path binding worked *without* scalac `-parameters`, but
   that binds by annotation *value*, not by reflected param name — so it is **not** evidence that
   param-name reflection works here. Unproven ⇒ avoid depending on it.
2. **Which Jackson mapper parses the tool input** — `McpEndpointDescriptorFactory` carries its own
   `objectMapper` (present in the jar), i.e. an internal mapper, **not** the HTTP `JsonSupport` mapper
   that has the Scala module (the two-mapper boundary, §3). So a plain annotation-free Scala case
   class as the input type risks *"Cannot construct instance of scala.Option"* / missing-name issues.

Both risks vanish by using the **established project pattern for anything crossing a non-HTTP mapper**:
a **Java-shaped Scala case class** with explicit `@JsonCreator`/`@JsonProperty` (exactly like
`DocsAgent.Request`, cap-8), plus a hand-written `inputSchema` JSON that matches it. Deterministic,
mapper-agnostic, no reliance on scalac param names.

Input record (endpoint-owned): `RetrieveInput(question: String, maxResults: Integer)` — Java-shaped,
with `maxResults` a **nullable `java.lang.Integer`** (NOT `Option[Integer]`): the internal mapper
*"Cannot construct instance of scala.Option"* (§3) — the very reason this record is Java-shaped — so a
Scala `Option` field here would itself fail to deserialize. Absent `maxResults` arrives as `null`; the
endpoint converts at the boundary with `Option(input.maxResults)` (the CLAUDE.md null→Option rule) and
then defaults/clamps (see R3). The `@McpTool` `inputSchema` marks only `question` required, so omitting
`maxResults` is valid and yields `null`. The endpoint validates `question` via cap-8's
`AskQuestion.validate`.

**Alternatives considered:**
- *Bare primitive/String params with `@Description`* — the simplest **if** scalac param names emit;
  flagged as a **research offshoot** the implementer may A/B (turn on scalac `-parameters`, try
  `retrieve(@Description String question, @Description Optional<Integer> maxResults)`). If it works
  cleanly it's even simpler; if not, the record+manual-schema path above is the committed fallback.
  Recorded as a live experiment, not a blocker.
- *An annotation-free Scala case class* — rejected (internal-mapper risk, §3).

---

## R3 — Tool result shape (what the tool returns)

**Decision: the tool returns a `String` that the endpoint builds** — a compact JSON array of the
retrieved passages (`[{"source","score","text"}, …]`, score-descending).

**Rationale:** The doc says a tool may return `String` (raw text) or *"other return types are turned
into JSON"* — but *by which mapper* is the same §3 ambiguity as R2. Building the JSON string ourselves
(a small `toApi`-style render over `KnowledgeStore.Retrieved`) sidesteps it entirely, is fully
deterministic for offline assertion (SC-003), keeps `Retrieved` off the wire (API isolation), and
gives the calling LLM clean structured text. Scores are included so a consuming model can judge
relevance (mirrors cap-8's retrieval-side ground truth). K defaults to **3** (cap-8's `TopK`) when
`maxResults` is `null`/absent, and is **floored at 1** for zero/negative values (FR-006 / edge cases).
No explicit upper clamp is needed — langchain4j's `maxResults` already returns at most the corpus size,
so a very large K just yields the whole corpus (see data-model L2 note).

**Decline semantics:** unlike cap-8's HTTP endpoint, the MCP tool does **not** call a model and does
**not** emit a decline — it returns whatever the corpus's nearest passages are (with their scores). A
low-score result *is* the honest "coverage is weak" signal; interpretation is left to the caller
(SC-002 edge case 4). This keeps the tool a pure retrieval surface (Simplicity), and is the correct
MCP shape: the *client's* model decides how to use low-scored context.

**Alternatives considered:** returning a Java-shaped result record and letting the SDK serialize it —
rejected (mapper ambiguity, and it would leak a wire type we'd have to shape anyway). Returning a
human-readable block instead of JSON — rejected (harder to assert exactly offline).

---

## R4 — Offline testing over JSON-RPC (no MCP testkit exists)

**Decision: drive `/mcp` with the testkit `httpClient` using hand-crafted JSON-RPC 2.0 payloads**,
following the MCP *stateless Streamable HTTP* transport (spec 2025-03-26). Assert on the JSON-RPC
responses. No model, no network.

**Rationale:** The doc is explicit: *"There are no specific test kit utilities for MCP. However, it is
possible to … use the testkit HTTP client together with handcrafted JSON-RPC MCP payloads to exercise
MCP tools, prompts and resources."* This is the same offline-provable posture as cap-8's deterministic
retrieval half (R6 there): retrieval is deterministic, so `tools/call` results are exact-assertable
and stable across runs (SC-003/SC-004).

**Open mechanics to pin during implementation (T-first task):** the exact JSON-RPC envelope and
whether the stateless transport requires an `initialize` handshake before `tools/list`/`tools/call`,
plus the response framing (single JSON object vs. an SSE-style `text/event-stream` body the httpClient
must read). The plan's first implementation task constructs and iterates these payloads against a
running TestKit until `tools/list` advertises `retrieve` and `tools/call` returns the passages; the
finalized envelopes are captured in `contracts/mcp-jsonrpc.md`. This is a *mechanics* unknown (payload
shape), not a *feasibility* unknown — the capability is testable; only the exact bytes need nailing
down empirically. Documented as such so implementation starts by proving the envelope, then asserts
behavior.

**Alternatives considered:** unit-testing the endpoint by directly `new`-ing it and calling the tool
method (the doc's other suggestion) — kept as a **fast inner-loop** sanity check (validates the
retrieve/clamp/render logic without JSON-RPC), but the **gating** test is the JSON-RPC one, because
only it proves the tool is actually *discoverable and callable over MCP* (SC-001).

---

## R5 — Corpus discovery as an MCP resource (P2)

**Decision: include one zero-parameter `@McpResource` returning the corpus source labels as JSON**
(`uri = "knowledge://corpus/sources"`, `mimeType = "application/json"`), **contingent** on it
compiling and testing cleanly in Scala; drop with a recorded reason if it hits any snag.

**Rationale:** `@McpResource` is a zero-arg public method returning `String`/`byte[]`/JSON (doc). A
zero-arg method has **no input schema and no param-name/mapper concern** (R2/R3 risks don't apply), so
it is the lowest-risk optional addition and makes the server self-describing (SC-001 supports "no
out-of-band docs"). Source labels come straight from `KnowledgeCorpus.passages.map(_.source)`. Return
a JSON string we build (same rationale as R3). Dynamic resource templates and prompts are **omitted**
(YAGNI — no per-URI fetch or prompt-template use case here; Simplicity).

**Alternatives considered:** exposing full passage contents as a resource — deferred (labels are
enough for discovery; contents are already reachable via the `retrieve` tool). A dynamic
`uriTemplate` per-source resource — rejected (YAGNI).

---

## R6 — Dependency injection & build impact

**Decision: constructor-inject `KnowledgeStore` into the endpoint (cap-8 R5 pattern); no `pom.xml`
change; add a `mcp-endpoint` descriptor key.**

**Rationale:**
- `KnowledgeStore` is already provided as a singleton by `Bootstrap`'s `createDependencyProvider()`
  and constructor-injected into `DocsEndpoint` — the MCP endpoint takes the same constructor param
  (`class KnowledgeMcpEndpoint(knowledgeStore: KnowledgeStore)`). `DependencyProvider` is `Class`-keyed
  → no method-ref wall (cap-8 R5). **No `ComponentClient` needed** (the endpoint does not call an
  agent — it retrieves directly), so the constructor is just the store.
- **Build:** the MCP annotations ship in `akka-javasdk` (already a dependency); the mixed Scala/Java
  build (§4) already compiles Scala endpoints. Expected **zero** `pom.xml` change — to be confirmed by
  `mvn compile`. The one wiring change is the descriptor: a new
  `mcp-endpoint = ["com.gwgs.akkaagentic.mcp.api.KnowledgeMcpEndpoint"]` block (FR-008).

**Alternatives considered:** none material — this mirrors the proven cap-8 injection exactly.

---

## Consolidated decisions

| # | Question | Decision |
|---|----------|----------|
| R1 | `@McpEndpoint` in Scala? | **Clean** — endpoint, reflective dispatch, no method-ref wall. Descriptor key `mcp-endpoint`. |
| R2 | Tool input from Scala | **FINAL (T005/T006): a single bare `question: String` param** — no wrapper record, no manual `inputSchema`. scalac emits param names (positive finding). `maxResults` **deferred** — on SDK 3.6.0 `Optional[T]` throws on a supplied value, plain `Integer` is forced required, manual schema breaks binding; tool ships **fixed top-K 3**. Revisit on SDK upgrade. |
| R3 | Tool result | Endpoint-built **JSON string** of **fixed top-K=3** `{source,score,text}`, score-desc (no upper clamp — langchain4j caps at corpus size); no decline/model. |
| R4 | Offline testing | testkit `httpClient` + hand-crafted **JSON-RPC** to `/mcp`; first task pins the exact envelope (incl. any `initialize`/SSE framing) into `contracts/`. Direct-call unit test as fast inner loop. |
| R5 | Resource (P2) | One zero-arg `@McpResource` → corpus **source labels** JSON, if clean; no prompts/dynamic templates (YAGNI). |
| R6 | DI & build | Inject `KnowledgeStore` (cap-8 R5); **no pom change** (verify); add `mcp-endpoint` descriptor key. |

**Interop takeaway for the findings series:** MCP endpoints extend the "wall is a *client* property"
rule (FINDINGS.md) — because an MCP tool is *called by* a remote client and *dispatched reflectively*,
there is no client method reference to author, so `@McpEndpoint` is Scala-clean like HTTP/gRPC
endpoints. The only Scala tax is the familiar hand-maintained descriptor entry (new key
`mcp-endpoint`) and keeping any wire type crossing the internal mapper Java-shaped (R2).
