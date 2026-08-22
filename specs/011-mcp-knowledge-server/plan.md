# Implementation Plan: MCP Knowledge Server

**Branch**: `011-mcp-knowledge-server` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/011-mcp-knowledge-server/spec.md`

## Summary

Expose capability 8's in-process semantic retrieval as a **Model Context Protocol** tool, so any
MCP client (another Akka service's agent, a third-party MCP host, or a raw JSON-RPC test) can query
the knowledge corpus without going through cap-8's HTTP endpoint. The mechanism is a single new
**`@McpEndpoint`** class that constructor-injects the existing `KnowledgeStore` (cap-8 R5 custom DI)
and advertises one `@McpTool` — `retrieve` — which validates its question, runs the deterministic
vector search, and returns the top-K passages (source + score + text) as a text result. An optional
`@McpResource` lists the corpus source labels for discovery (P2).

**Interop verdict (the sandbox's headline research question), resolved in Phase 0 from SDK 3.6.0
bytecode + docs:** `@McpEndpoint` is **Scala-clean**. It is an *endpoint* (no `@Component`, like HTTP
/gRPC endpoints), invoked *reflectively* from JSON-RPC — there is **no ComponentClient method
reference** for us to author, so none of the Workflow/entity method-ref wall applies. The only wiring
is a new hand-maintained descriptor key, **`mcp-endpoint`** (confirmed present in the SDK's
`ComponentType` constant pool). This is the MCP analogue of cap-1's HTTP endpoint: annotation-driven,
Scala-authorable, offline-testable.

## Technical Context

**Language/Version**: Scala 3 (on the Java-first Akka SDK 3.6.0), JDK 21.
**Primary Dependencies**: `akka-javasdk` 3.6.0 (`akka.javasdk.annotations.mcp.{McpEndpoint,McpTool,
McpResource}`, optional base `akka.javasdk.mcp.AbstractMcpEndpoint`); cap-8's already-promoted
langchain4j retrieval stack (reused transitively via `KnowledgeStore`). **No new dependency.**
**Storage**: None new — the in-memory `InMemoryEmbeddingStore` seeded at startup, exactly as cap-8.
**Testing**: `TestKitSupport` + the testkit `httpClient` POSTing hand-crafted JSON-RPC MCP payloads to
`/mcp` (the SDK ships no MCP-specific testkit; docs prescribe this). Deterministic, no model, no
network. Reuse cap-8's `KnowledgeStoreTest` for the retrieval half.
**Target Platform**: Akka runtime (local `exec:java`; `mvn verify` for tests).
**Project Type**: Single service (this repo). New package `com.gwgs.akkaagentic.mcp.*`.
**Performance Goals**: N/A beyond cap-8 (one embed + in-memory search per call; ~22 MB ONNX model
loaded once at startup).
**Constraints**: Offline (no API key, no network); deterministic retrieval so tests assert exact
passages/order; validation-first (blank question → JSON-RPC/tool error, no retrieval).
**Scale/Scope**: One endpoint class, one tool, one optional resource. ~9-passage canned corpus.
Server-side only — client `.mcpTools()` consumption is out of scope.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First** ✅ — Built on the SDK's `@McpEndpoint` primitive. **No new external
  dependency**: the MCP annotations are in `akka-javasdk` (already present), and retrieval reuses
  cap-8's already-justified langchain4j stack. Nothing custom replaces an SDK facility.
- **II. Design Principles** ✅ — *Domain independence*: retrieval, corpus, and question validation
  stay in cap-8's `docs.domain`/`docs.application`, untouched; the MCP layer only orchestrates.
  *API isolation*: the endpoint owns its MCP tool input/output types; it never exposes
  `KnowledgeStore.Retrieved` on the wire (a `toApi`-style mapping to a text result). *Single
  responsibility*: the endpoint is a pure protocol surface — no business logic. *Descriptive naming*:
  `KnowledgeMcpEndpoint`, tool `retrieve`.
- **III. Test Coverage** ✅ — New offline JSON-RPC integration test for tool discovery + tool call +
  validation; retrieval correctness already covered by cap-8's deterministic `KnowledgeStoreTest`
  (reused, asserting the shared behavior of SC-004).
- **IV. Simplicity** ✅ — Reuses cap-8 wholesale; adds one endpoint. Tool returns a **plain text
  result the endpoint builds** (no dependence on reflective return-type serialization or param-name
  emission). P2 resource included **only if** it proves clean; prompts omitted (YAGNI — no prompt
  use case here). Client-side consumption deferred.

**Result: PASS, no violations.** Complexity Tracking table omitted (nothing to justify).

*Post-Phase-1 re-check: still PASS — the design added no abstraction beyond the one endpoint and its
two small wire types; see Phase 1 below.*

## Project Structure

### Documentation (this feature)

```text
specs/011-mcp-knowledge-server/
├── plan.md              # This file
├── spec.md              # Feature spec (/akka.specify)
├── research.md          # Phase 0 — interop verdict + JSON-RPC test envelope decisions
├── data-model.md        # Phase 1 — tool I/O + reused cap-8 entities
├── quickstart.md        # Phase 1 — how to call the tool (curl JSON-RPC + inspector)
├── contracts/
│   └── mcp-jsonrpc.md    # Phase 1 — tools/list + tools/call request/response contracts
└── checklists/
    └── requirements.md   # Spec quality checklist (/akka.specify)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/mcp/
└── api/
    └── KnowledgeMcpEndpoint.scala      # @McpEndpoint: @McpTool retrieve (+ optional @McpResource)

# Reused UNCHANGED from cap-8 (no edits):
src/main/scala/com/gwgs/akkaagentic/docs/domain/       # KnowledgeCorpus, Passage, AskQuestion (validation)
src/main/scala/com/gwgs/akkaagentic/docs/application/   # KnowledgeStore (injected), DocsAgent (not used here)
src/main/scala/com/gwgs/akkaagentic/application/Bootstrap.scala  # already provides KnowledgeStore via DependencyProvider

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
    # ADD a new `mcp-endpoint = [ "...mcp.api.KnowledgeMcpEndpoint" ]` key

src/test/scala/com/gwgs/akkaagentic/mcp/
└── api/
    └── KnowledgeMcpEndpointIntegrationTest.scala   # JSON-RPC over httpClient: discovery + call + validation
```

**Structure Decision**: A new top-level package `com.gwgs.akkaagentic.mcp.api` for the endpoint,
keeping the MCP protocol surface separate from cap-8's `docs.*` (which stays a pure HTTP + retrieval
capability). No `domain`/`application` subpackages are needed — the capability introduces no new
domain logic or components; it reuses cap-8's `docs.domain.AskQuestion` for validation and
`docs.application.KnowledgeStore` for retrieval. This honors "single responsibility" (the MCP package
is *only* the protocol adapter) and "domain independence" (no MCP concern leaks into `docs.*`).

## Complexity Tracking

> No constitution violations — table intentionally empty.
