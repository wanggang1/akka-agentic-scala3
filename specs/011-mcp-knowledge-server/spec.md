# Feature Specification: MCP Knowledge Server

**Feature Branch**: `011-mcp-knowledge-server`
**Created**: 2026-08-21
**Status**: Draft
**Input**: User description: "MCP server (capability 9): an @McpEndpoint that exposes the existing cap-8 KnowledgeStore semantic retrieval as an MCP tool, so any MCP client (another Akka service, or a third-party MCP host) can query the in-process knowledge corpus over JSON-RPC. Server-side only — self-contained and offline-testable via JSON-RPC (no live model, like cap-8's deterministic retrieval half). Scope: expose MCP tool(s) for grounded retrieval; optionally MCP resources/prompts if they fit naturally. The client-side .mcpTools() wiring (an agent consuming a remote MCP server) is explicitly OUT of scope — a live-only follow-up. This is a Scala-on-Java-SDK learning sandbox; a key research question is whether @McpEndpoint is Scala-authorable or hits a method-ref/annotation-processor wall like Workflows/entities did."

## User Scenarios & Testing *(mandatory)*

This is capability 9 in a **Scala-on-Java-SDK learning sandbox**. The "users" are MCP
*clients* — another Akka service's agent (via `.mcpTools()`), a third-party MCP host (Claude
Desktop, an IDE, an inspector), or a test harness speaking raw JSON-RPC. The feature makes the
existing capability-8 semantic-retrieval corpus reachable over the **Model Context Protocol**
instead of only over the cap-8 HTTP endpoint.

### User Story 1 - Retrieve grounded passages over MCP (Priority: P1)

An MCP client connects to the server, discovers the available tools, and calls a retrieval tool
with a natural-language question. The server runs semantic search over the in-process knowledge
corpus and returns the most similar passages, each with its source label and similarity score —
the same deterministic retrieval that capability 8 exposes over HTTP, now available as an MCP
tool any protocol-compliant client can call.

**Why this priority**: This is the entire point of the capability — exposing retrieval as an MCP
tool. Everything else is optional polish. It is a complete, demonstrable MVP on its own.

**Independent Test**: Drive the endpoint with raw JSON-RPC (tool discovery + tool call) in an
integration test and assert the returned passages, sources, and ordering — no live model needed,
because retrieval is deterministic (the cap-8 R6 property).

**Acceptance Scenarios**:

1. **Given** the server is running, **When** a client sends a `tools/list` JSON-RPC request,
   **Then** the response advertises a retrieval tool with a name, a description, and an input
   schema declaring a required question parameter and an optional result-count parameter.
2. **Given** a question whose subject is covered by the corpus, **When** a client calls the
   retrieval tool, **Then** the result contains the top-K passages ordered by descending
   similarity, each carrying its source label, its text, and its score.
3. **Given** a paraphrased in-corpus question (no shared keywords with the source passage),
   **When** the tool is called, **Then** the semantically-correct passage is still returned —
   proving real semantic (not keyword) retrieval, matching cap-8.
4. **Given** a question the corpus does not cover, **When** the tool is called, **Then** the
   server returns a well-formed result (the nearest passages with their low scores, or an
   explicit empty result) rather than an error or a fabrication.

---

### User Story 2 - Discover the corpus as an MCP resource (Priority: P2)

An MCP client lists and reads *resources* to learn what knowledge the server holds — e.g. the
set of source labels in the corpus, or the corpus contents — so a human or agent can understand
the server's coverage before querying it.

**Why this priority**: Resources are a natural, low-cost addition that makes the server
self-describing and improves the demo, but retrieval (P1) delivers value without it. Included
only if `@McpEndpoint` resources prove to be cleanly Scala-authorable.

**Independent Test**: Send `resources/list` and `resources/read` JSON-RPC requests and assert
the corpus metadata comes back — offline, no model.

**Acceptance Scenarios**:

1. **Given** the server is running, **When** a client sends `resources/list`, **Then** the
   response advertises at least one resource describing the knowledge corpus.
2. **Given** a resource URI from that list, **When** a client sends `resources/read`, **Then**
   the server returns the corpus source labels (and/or contents) as the resource body.

---

### User Story 3 - Prove the interop verdict for `@McpEndpoint` in Scala (Priority: P2)

As the maintainer of this Scala-on-Java-SDK sandbox, the capability must answer one research
question and record the verdict: **can an MCP endpoint be authored in Scala, or does it hit a
wall** like Workflows (method-ref) and entities (method-ref) did, or is it clean like Agents,
AutonomousAgents, Tasks, and custom DI? The deliverable includes the documented finding and the
descriptor wiring that follows from it.

**Why this priority**: The interop verdict is the whole reason this sandbox exists; every prior
capability produced one. It is P2 only because it is a documentation/finding outcome layered on
the P1 mechanism, not a separate runtime behavior.

**Independent Test**: The capability compiles and its JSON-RPC integration test passes with the
endpoint authored in Scala (verdict: clean); or, if a wall is hit, the finding documents the
precise failure and the minimal Java quarantine that resolves it (matching how cap-2/cap-4/cap-6
handled their walls).

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** the interop notes are read, **Then** they
   state plainly whether `@McpEndpoint` is Scala-authorable, with the evidence (compiles + test
   green, or the specific wall and its workaround).
2. **Given** a new Scala MCP endpoint, **When** the service starts, **Then** the runtime
   discovers it — via the hand-maintained component descriptor if MCP endpoints require a
   descriptor entry, or the finding records that they do not.

---

### Edge Cases

- **Blank / missing question argument**: the tool call is rejected with a well-formed JSON-RPC
  error (or an MCP tool-error result), before any retrieval runs — mirroring cap-8's
  validation-first contract.
- **Result-count omitted**: a sensible default (the cap-8 top-K, 3) is used.
- **Result-count out of range** (zero, negative, or very large): clamped to a safe range rather
  than erroring or exhausting the corpus.

> **⚠️ SDK-3.6.0 deviation (T006) — result-count is NOT tunable; the tool ships a fixed top-K (3).**
> The `maxResults` argument in FR-002/FR-006 and the two edge cases above could not be expressed on
> Akka SDK 3.6.0: the documented optional-param shape (`Optional<T>` bare param) throws
> `"Optional cannot be cast to Integer"` on any *supplied* value; a plain `Integer` binds values but
> the SDK forces it *required*; a manual `inputSchema` on bare params breaks binding entirely. So the
> `retrieve` tool takes only `question` and returns a fixed top-K (3), exactly mirroring cap-8's
> `DocsEndpoint`. **Revisit on SDK upgrade** — re-add optional `maxResults` and its clamping tests. See
> research R2 and the project's SDK-3.6.0 upgrade tracker.
- **Question longer than the corpus vocabulary / gibberish**: retrieval still returns its
  nearest matches with low scores; no crash, no fabrication.
- **Unknown tool or resource name**: standard MCP "not found" error, not a server crash.
- **Corpus not yet loaded at first call**: the corpus is seeded eagerly at startup (as in
  cap-8), so the first call already has embeddings available.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST expose an MCP server endpoint that speaks the Model Context
  Protocol over JSON-RPC and is reachable by standard MCP clients.
- **FR-002**: The MCP server MUST advertise a **retrieval tool** via MCP tool discovery, with a
  descriptive name, a clear description, and an input schema declaring a **required** question
  parameter. *(An optional result-count parameter was designed but is deferred to an SDK upgrade —
  see the SDK-3.6.0 deviation note under Edge Cases; the tool ships a fixed top-K of 3.)*
- **FR-003**: When the retrieval tool is called, the server MUST run semantic vector search over
  the in-process knowledge corpus and return the top-K passages **ordered by descending
  similarity**, each with its **source label**, its **text**, and its **similarity score**.
- **FR-004**: The retrieval tool MUST reuse capability 8's existing `KnowledgeStore` retrieval —
  the same in-process embeddings, corpus, and scoring — so results are consistent across the two
  surfaces and no new retrieval logic is introduced.
- **FR-005**: The retrieval tool MUST validate its arguments before retrieving: a blank or
  missing question is rejected with a well-formed MCP/JSON-RPC error and no retrieval is run.
- **FR-006**: The retrieval tool MUST return capability 8's top-K (3). *(Originally: default when
  omitted + clamp out-of-range. Reduced to a fixed top-K on SDK 3.6.0 — see the deviation note under
  Edge Cases; restore the tunable count on SDK upgrade.)*
- **FR-007**: The capability MUST be **offline-testable end-to-end** via JSON-RPC, with no live
  model and no network — retrieval is deterministic, so a test can assert exact passages,
  sources, and ordering (the clean half, mirroring cap-8's R6).
- **FR-008**: Every new Scala component introduced by this capability MUST be registered so the
  runtime discovers it (hand-maintained component descriptor, per the project's established Scala
  interop workaround) — or the finding MUST record that MCP endpoints need no descriptor entry.
- **FR-009**: The capability MUST document the **interop verdict** for `@McpEndpoint` in Scala:
  whether it is cleanly Scala-authorable, or hits a method-ref / annotation-processor wall, with
  the evidence and any Java quarantine required (in `README.md` "Scala interop notes" and the
  specs research log, consistent with capabilities 1–8).
- **FR-010** *(optional, P2)*: The MCP server SHOULD expose the knowledge corpus as an MCP
  **resource** (source labels and/or contents) discoverable via `resources/list` and readable
  via `resources/read`, **only if** MCP resources prove cleanly Scala-authorable; if they hit a
  wall, drop the resource and record why.

### Out of Scope

- **Client-side `.mcpTools()` wiring** — an agent in this or another service *consuming* a remote
  MCP server. That is a live-only follow-up (it needs a running MCP server to point at and a live
  model to exercise the tool loop), explicitly deferred.
- **New retrieval or embedding logic** — this capability is a new *protocol surface* over cap-8's
  existing store, not a new knowledge system.
- **Persistence / indexing pipeline** — the corpus is seeded at startup as in cap-8; a durable
  indexing workflow (Java-only) remains out of scope.
- **Authentication / multi-tenant access control** on the MCP endpoint beyond the project's
  standard ACL annotation.

### Key Entities *(include if feature involves data)*

- **Retrieval tool**: the MCP-advertised tool. Attributes: name, description, input schema
  (required question; the designed optional result count is deferred to an SDK upgrade — fixed
  top-K 3 on 3.6.0). Behavior: validate → retrieve top-K → return passages.
- **Retrieved passage**: one search result. Attributes: source label, passage text, similarity
  score. Ordered by descending score. (Reuses cap-8's `KnowledgeStore.Retrieved`.)
- **Knowledge corpus**: the canned, in-process, self-referential passage set from capability 8
  (passages describing the project's capabilities and interop findings), seeded into an in-memory
  vector store at startup. Reused unchanged.
- **Corpus resource** *(optional)*: an MCP resource exposing the corpus source labels and/or
  contents for discovery.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An MCP client that has never seen this server can, using only MCP discovery,
  identify the retrieval tool and call it successfully — tool name, description, and input schema
  are self-describing enough to require no out-of-band documentation.
- **SC-002**: For an in-corpus question, the tool returns the semantically-correct top passage as
  the highest-scored result — including for a **paraphrased** question with no keyword overlap —
  demonstrating true semantic retrieval identical to capability 8.
- **SC-003**: The full retrieve-over-MCP path (discovery + tool call + result assertion) is
  verified in an **offline** test with **no model server and no network**, and the assertions are
  **deterministic** (exact passages/sources/order) across repeated runs.
- **SC-004**: Retrieval results returned over MCP match capability 8's HTTP results for the same
  question and K — same passages, same order, same source labels — proving one shared retrieval
  behind two surfaces.
- **SC-005**: The capability ships a **documented interop verdict** stating whether `@McpEndpoint`
  is Scala-authorable, with reproducible evidence, extending the project's interop findings series
  (caps 1–8) to MCP.
- **SC-006**: Argument validation holds: a blank/missing question yields a well-formed error and
  triggers **no** retrieval — provable offline. *(The result-count portion — omitted→default,
  out-of-range→clamp — is deferred to an SDK upgrade; the tool returns a fixed top-K of 3. See the
  Edge Cases deviation note.)*

## Assumptions

- **Reuse cap-8's `KnowledgeStore` and corpus verbatim.** No new corpus, embeddings, or scoring;
  the store is already constructor-injectable via `Bootstrap`'s `DependencyProvider` (cap-8 R5,
  Scala-clean), so the MCP endpoint can obtain it the same way.
- **The `@McpEndpoint` is expected to be Scala-authorable** (like HTTP endpoints, Agents, Tasks —
  all annotation/`Class`-keyed with no method-ref client), but this is the capability's open
  research question; the plan's first task is to confirm it against the SDK 3.6.0 API, and the
  spec accommodates either verdict (clean, or a documented Java quarantine).
- **Offline JSON-RPC testing is the primary verification** (mirroring cap-8's deterministic
  retrieval half). A live end-to-end demo against a real MCP host (e.g. an inspector or Claude
  Desktop) is a nice-to-have smoke test, not a gate.
- **Fixed top-K = 3**, matching cap-8. *(A tunable/clamped count was designed but deferred to an SDK
  upgrade — SDK-3.6.0 deviation, see Edge Cases.)*
- **Standard project ACL** applies to the endpoint; no bespoke auth is introduced.
- **The client-side consumption** (`.mcpTools()`) is deferred to a future live-only capability, as
  the cap-8 fork note anticipated ("retrieval-as-a-tool via remote MCP — cap-9 territory").
