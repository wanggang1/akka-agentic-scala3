# Implementation Plan: MCP Client Agent (cap-10)

**Branch**: `012-mcp-client` | **Date**: 2026-08-23 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/012-mcp-client/spec.md`

## Summary

A request-based `McpClientAgent` (Scala) whose model grounds its answers by calling a **remote MCP
tool** — `retrieve` — served by this same service's cap-9 `/mcp` endpoint, reached with
`.mcpTools(RemoteMcpTools.fromService("akka-agentic-scala3"))`. A synchronous `McpClientEndpoint`
(`POST /grounded-ask`) validates the question first (reusing cap-8's `AskQuestion.validate`), then
invokes the agent and returns the bare-text answer. This closes the loop **agent → MCP client → our
MCP server (cap-9) → `KnowledgeStore` (cap-8)** fully offline, and demonstrates cap-8's flagged
"retrieval-as-a-tool / agentic RAG" fork (the model decides whether/when to retrieve, vs cap-8's
always-once endpoint pre-retrieval).

**Headline outcome (SC-007), settled statically in Phase 0**: `.mcpTools(...)` is **Scala-clean** —
`RemoteMcpTools` is a URL-string/config builder with no method references (bytecode-verified, R1), so
the *consuming* side of MCP hits no method-ref wall, extending cap-9's "the wall is a client property"
through-line to outbound calls.

## Technical Context

**Language/Version**: Scala 3 on the Java-first Akka SDK 3.6.0 (`scala-maven-plugin`; mixed build).
**Primary Dependencies**: `akka-javasdk` only — `Agent`, `RemoteMcpTools`, `@HttpEndpoint`. Reuses
cap-9 `KnowledgeMcpEndpoint` + cap-8 `KnowledgeStore`/`AskQuestion`. **No new dependency.**
**Storage**: none new (cap-8's startup-seeded in-memory embedding store is the only durable state,
owned by cap-8).
**Testing**: `TestKitSupport` + `httpClient` (Scala); `TestModelProvider` with **tool-call scripting**
(`fixedResponse(ToolInvocationRequest)` + `whenToolResult`) for the tool loop (R3). No live model in
`mvn verify`.
**Target Platform**: Akka runtime; local Ollama (`qwen3:8b`) or Gemini for live; offline for build.
**Project Type**: single service (Scala capability added to the existing mixed module).
**Performance Goals**: single synchronous request; `max-tool-call-steps` default 100 is ample.
**Constraints**: fully offline (in-jar ONNX embeddings, no API key, no external host); validation-first;
two-mapper boundary (§3) respected; each new Scala component hand-added to the descriptor.
**Scale/Scope**: ~2 new source files (agent + endpoint), 1 reused domain validator, ~1 additive ACL
edit to cap-9; tests: 1 endpoint integration test (validation + wiring + tool-loop/parity) + live smoke.

## Constitution Check

*GATE: must pass before Phase 0 research. Re-checked after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — ✅ Uses only Akka SDK primitives (`Agent`, `RemoteMcpTools`,
  `@HttpEndpoint`). **No new external dependency** — the MCP consumption API ships in `akka-javasdk`;
  retrieval reuses cap-8's stack (already in the SDK dep tree). No justification needed.
- **II. Design Principles** — ✅ Domain independence: validation reuses the pure `AskQuestion`
  (no framework). API isolation: endpoint defines its own `AskRequest`/`AskReply`, never exposes
  `KnowledgeStore.Retrieved` or domain internals. Single responsibility: agent grounds + answers,
  endpoint validates + adapts. Descriptive names (`McpClientAgent`, `McpClientEndpoint`).
- **III. Test Coverage** — ✅ Endpoint integration test (validation-first, wiring, and the
  tool-loop/parity if S2 holds); live smoke for the real closed loop. No coverage decrease.
- **IV. Simplicity** — ✅ Thin: 2 new files, bare-`String` agent payload, no citations, no task/entity/
  workflow, no new corpus. Reuses cap-8/cap-9 verbatim. YAGNI: no tunable retrieval count, no
  multi-hop machinery beyond what the SDK gives for free.

**Result: PASS** (no violations; Complexity Tracking not needed).

### Post-Phase-1 re-check

Design keeps the surface at 2 new components + 1 additive ACL edit; no new abstractions introduced;
two-mapper boundary respected (bare-String payload). **Still PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/012-mcp-client/
├── plan.md              # this file
├── spec.md              # /akka.specify output
├── research.md          # Phase 0 — R1 (interop, settled), R2 topology, R3 testability, R4/R5
├── data-model.md        # Phase 1 — reused vs new types, validation
├── contracts/
│   └── http-grounded-ask.md   # Phase 1 — HTTP surface + MCP consumption contract
├── quickstart.md        # Phase 1 — run/curl/test + cap-8 contrast + interop takeaway
├── checklists/
│   └── requirements.md  # spec quality checklist (all pass)
└── tasks.md             # /akka.tasks output (NOT created here)
```

### Source Code (repository root)

```text
# Capability 10 — Scala (MCP client: agentic RAG via a remote MCP tool)
src/main/scala/com/gwgs/akkaagentic/mcpclient/application/  # McpClientAgent (Agent + .mcpTools(fromService))
src/main/scala/com/gwgs/akkaagentic/mcpclient/api/          # McpClientEndpoint (POST /grounded-ask, synchronous)
# Reuses (no new domain): cap-8 docs.domain.AskQuestion (+ validate), docs.application.KnowledgeStore;
#                         cap-9 mcp.api.KnowledgeMcpEndpoint (the consumed /mcp server)

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
    # + add McpClientAgent under `agent`, McpClientEndpoint under `http-endpoint`
    # (KnowledgeMcpEndpoint already under `mcp-endpoint` from cap-9)

# Possible additive edit (R2/S1): broaden KnowledgeMcpEndpoint @Acl to also allow the calling
#   service principal (INTERNET retained) so the self-service MCP call is permitted.

src/test/scala/com/gwgs/akkaagentic/mcpclient/api/McpClientEndpointIntegrationTest.scala
    # validation-first (blank/malformed → 400, no calls); tool-loop grounding (scripted retrieve →
    # real /mcp round-trip, ToolResult == direct KnowledgeStore.retrieve = SC-005) if S2 holds;
    # else parity asserted directly + tool loop proven live.
```

**Structure Decision**: New top-level package `com.gwgs.akkaagentic.mcpclient` (its own `application`
+ `api`), parallel to cap-9's `mcp`. No `domain` subpackage — validation is reused from cap-8's
`docs.domain.AskQuestion`, honoring "depend on existing domain, don't duplicate" (and the
language-of-consumer rule: both are Scala). All Scala (R1 — no method-ref wall).

## Phase 2 approach (for /akka.tasks — not generated here)

**Spike-first, mirroring cap-9's T004/T005 envelope spike**, because two mechanics carry residual
risk even though the interop verdict is already settled:

1. **S1 — topology spike**: prove `RemoteMcpTools.fromService("akka-agentic-scala3")` resolves to the
   in-process `/mcp` (TestKit + local), and settle the ACL question — broaden cap-9's `@Acl` if a
   self-service principal is denied (additive; INTERNET retained). Fallback: `fromServer(base-url)`.
2. **S2 — tool-loop testability spike**: prove a `TestModelProvider`-scripted `retrieve` call actually
   round-trips to the real in-process MCP server and the received `ToolResult` equals a direct
   `KnowledgeStore.retrieve` (→ SC-005 offline). Fallback ladder: `withMockedHttpService` canned
   JSON-RPC → live-only for the closed loop.
3. Then build the **agent** (grounding system prompt + `.mcpTools(...)` + `.onFailure` fallback for
   FR-010), the **endpoint** (`AskQuestion.validate` → agent → `AskReply`), descriptor entries, the
   integration test, live smoke, and docs (README §12 + cap-10 usage; ROADMAP/FINDINGS; memory).

## Complexity Tracking

No constitution violations — section intentionally empty.
