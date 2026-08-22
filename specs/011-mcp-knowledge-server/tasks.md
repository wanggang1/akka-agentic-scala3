# Tasks: MCP Knowledge Server (feature 011)

**Input**: Design documents from `/specs/011-mcp-knowledge-server/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/mcp-jsonrpc.md

**Tests**: Included and **gating** — the spec makes offline JSON-RPC testability a core requirement
(FR-007, SC-003). Retrieval correctness reuses cap-8's existing `KnowledgeStoreTest` (no new test
needed for it; asserted for SC-004 in T012).

**Workflow reminder (CLAUDE.md):** build **one component + its test at a time**, and **STOP for user
approval between major steps**. The task IDs below map onto that cadence — do not batch ahead.

## Format: `[ID] [P?] [Story] Description`
- **[P]** = independent file, no ordering dependency.
- **[Story]** = US1 (retrieve tool, P1), US2 (corpus resource, P2), US3 (interop verdict, P2).

---

## Phase 1: Setup

- [ ] **T001** Create the package dirs `src/main/scala/com/gwgs/akkaagentic/mcp/api/` and
  `src/test/scala/com/gwgs/akkaagentic/mcp/api/`. No code yet. (Reuses cap-8's `docs.*` — nothing to
  scaffold there.)

---

## Phase 2: Foundational (BLOCKING — pin the transport before any behavior)

**⚠️ CRITICAL**: The exact JSON-RPC envelope over `/mcp` (any `initialize` handshake, required
`Accept` headers, single-JSON vs. `text/event-stream` response framing) is the one empirical unknown
(research R4). Nail it with a trivial tool before building the real one, so US1's assertions rest on a
known-good envelope.

- [ ] **T002** [US1] Create a **minimal** `KnowledgeMcpEndpoint` in
  `src/main/scala/com/gwgs/akkaagentic/mcp/api/KnowledgeMcpEndpoint.scala`:
  `@McpEndpoint(serverName = "akka-agentic-knowledge-mcp", serverVersion = "0.1.0")` +
  `@Acl(...INTERNET)`, constructor `(knowledgeStore: KnowledgeStore)`, and ONE stub `@McpTool`
  `retrieve` returning a fixed String. Imports from `akka.javasdk.annotations.mcp.*`.
- [ ] **T003** [US1] Register it: add a **new** `mcp-endpoint = ["com.gwgs.akkaagentic.mcp.api.KnowledgeMcpEndpoint"]`
  key to `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`
  (with a comment noting the new key, cap-9, research R1). **Verify `mvn compile`** (confirms R1: Scala
  `@McpEndpoint` compiles; confirms R6: no `pom.xml` change).
- [ ] **T004** [US1] **Envelope spike** — write `KnowledgeMcpEndpointIntegrationTest` (extends
  `TestKitSupport`) with ONE test that POSTs a `tools/list` JSON-RPC body to `/mcp` via `httpClient`
  and asserts the stub `retrieve` is advertised. Iterate headers/framing until green; **record the
  final working envelope** (headers, any `initialize` step, how to read the body) in
  `contracts/mcp-jsonrpc.md` "Notes". This unblocks all behavior assertions.

**Checkpoint:** endpoint discovered over real JSON-RPC; envelope known. → STOP for approval.

---

## Phase 3: User Story 1 — Retrieve grounded passages over MCP (P1) 🎯 MVP

**Goal**: `retrieve` runs cap-8's deterministic search and returns top-K `{source, score, text}`,
score-descending, with validation + default/clamp.
**Independent Test**: JSON-RPC `tools/call` returns the semantically-correct top passage (incl.
paraphrase), ordered by score, matching cap-8 for the same question/K.

- [x] **T005 — DONE (revised, T005/T006).** Implement the real `retrieve` in `KnowledgeMcpEndpoint.scala`.
  **Final shape differs from the plan:** a **single bare `question: String` param** (`@Description`),
  **no wrapper record, no manual `inputSchema`** — the SDK reflects the bare param into a top-level
  `question` property and scalac emits the name (research R2, positive finding). Flow:
  `AskQuestion.validate(Option(question))` → on `Left`, `throw` (the SDK's tool error channel → a
  well-formed `isError:true` result, **no retrieval**, FR-005); on `Right`,
  `knowledgeStore.retrieve(q, TopK=3)` → render `List[Retrieved]` to a compact JSON String
  `[{source,score,text}, …]` via `JsonSupport.encodeToString`. **`mvn compile` ✅.**
  - **~~maxResults~~ deferred (SDK-3.6.0 bug, T006):** `Optional[Integer]` throws on a supplied value;
    plain `Integer` is forced required; manual schema breaks binding. Tool ships **fixed top-K 3**
    (mirrors cap-8). Revisit on SDK upgrade — see research R2 / SDK-3.6.0 tracker.
- [x] **T006 — DONE.** `KnowledgeMcpEndpointIntegrationTest` — 6 assertions, all offline/deterministic,
  **`mvn verify` green (6/6)**:
  - `tools/list` advertises `retrieve` with required `question` (SC-001).
  - In-corpus **paraphrase** (no shared keywords) → correct top `source`, results **score-descending**
    (SC-002).
  - **SC-004 parity**: sources/order/scores **match a direct `KnowledgeStore.fromCorpus().retrieve`**
    for the same question + K (one retrieval behind two surfaces).
  - Fixed top-K → **3** results.
  - **Out-of-corpus** question → nearest passages, `isError:false`, top score **below** the in-corpus
    case's (no error, no fabrication — M1).
  - Blank `question` → `isError:true` `"question must not be blank"`, retrieval did **not** run (FR-005).
  - *(Removed vs plan: the `maxResults` default/clamp assertions — deferred with the param, see T005.)*

**Checkpoint:** MVP complete — retrieval callable over MCP, fully offline-tested. → STOP for approval.

---

## Phase 4: User Story 2 — Corpus as an MCP resource (P2, only if clean)

**Goal**: `resources/list` + `resources/read` expose the corpus source labels.
**Independent Test**: `resources/read` on the corpus URI returns the known labels.

- [x] **T007 — DONE.** Added zero-arg `@McpResource(uri = "knowledge://corpus/sources", name =
  "Knowledge corpus sources", mimeType = "application/json")` `corpusSources()` returning
  `JsonSupport.encodeToString(KnowledgeCorpus.passages.map(_.source))`. **`mvn compile` ✅.** **Did NOT
  snag** — a zero-arg static `@McpResource` is Scala-clean (R5: no input schema, no param/mapper concern),
  the simplest MCP surface. The tool's `maxResults` friction does not touch resources.
- [x] **T008 — DONE.** Integration test extended (now 8/8 green, `mvn verify`): `resources/list`
  advertises the corpus resource (URI + name + `application/json`); `resources/read` returns the JSON
  array of labels asserted **equal to `KnowledgeCorpus.passages.map(_.source)`** (ground truth, no model).

**Checkpoint:** discovery resource works (or is documented as dropped). → STOP for approval.

---

## Phase 5: User Story 3 — Document the interop verdict (P2)

**Goal**: Record whether `@McpEndpoint` is Scala-authorable, with evidence — extends the caps 1–8
findings series (SC-005).

- [ ] **T009** [US3] Add **"Scala interop notes §11"** to `README.md`: `@McpEndpoint` is Scala-clean
  (endpoint, reflective JSON-RPC dispatch, **no method-ref wall** — the wall is a *client* property);
  new descriptor key `mcp-endpoint`; tool input kept Java-shaped for the internal mapper (R2); no
  `pom.xml` change; server-side only (client `.mcpTools()` deferred). Cite the T003/T004 evidence
  (compiles + JSON-RPC test green) and the T005 tool-result decision. Also add a cap-9 usage section
  (curl JSON-RPC examples from `quickstart.md`).
- [ ] **T010** [P] [US3] Update the exploration roadmap memory + repo `ROADMAP.md`: cap-9 status, the
  interop verdict one-liner, and the deferred client-side `.mcpTools()` follow-up.

**Checkpoint:** findings recorded. → STOP for approval.

---

## Phase 6: Polish & Validation

- [ ] **T011** Run the `quickstart.md` flow against a live `mvn compile exec:java` (Ollama not needed —
  no model call): `tools/list`, `tools/call` (in-corpus + paraphrase + blank), and the P2 resource.
  Capture actual output; reconcile any wording in `quickstart.md`/`contracts/`.
- [ ] **T012** [P] *(optional, live)* Point an MCP Inspector / Claude Desktop (Streamable HTTP) at
  `http://localhost:9000/mcp`, discover + call `retrieve` — nice-to-have end-to-end smoke, not a gate.
- [ ] **T013** Final `mvn verify` (full suite green, coverage not decreased — Constitution III) and a
  clean-tree review before handing off for PR.

---

## Dependencies & Execution Order

- **T001** → **T002** → **T003** (compile gate) → **T004** (envelope spike, BLOCKS all behavior).
- **US1 (T005–T006)** after T004. This is the MVP; stop and validate here.
- **US2 (T007–T008)** after US1 — independent, and may be dropped (T007 note).
- **US3 (T009–T010)** after the mechanism is proven (needs US1 done; US2 outcome folded in).
- **Polish (T011–T013)** last.

### Parallel opportunities
- Very little parallelism by design — it's essentially one endpoint file + one test file, built the
  CLAUDE.md incremental way (component, then its test, STOP). **T010** [P] (a memory/doc file) is the
  only safely-parallel task; **T012** [P] is an optional independent live check.

## Implementation Strategy
- **MVP = Phases 1–3** (T001–T006): retrieve over MCP, offline-tested. Demoable on its own.
- Then US2 (resource) and US3 (verdict) as independent increments.
- Commit after each task or logical group; **STOP for approval at every checkpoint** (CLAUDE.md).
