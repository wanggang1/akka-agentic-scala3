# Quickstart: MCP Client Agent (cap-10)

Closes the loop: **agent → MCP client → this service's own `/mcp` server (cap-9) → `KnowledgeStore`
(cap-8)** — fully offline, no API key.

## Run

```shell
ollama pull qwen3:8b            # once (a tool-calling model)
mvn compile exec:java          # local Ollama; cap-9 /mcp + cap-10 /grounded-ask both served
```

The agent points at `RemoteMcpTools.fromService("akka-agentic-scala3")` — the service's own name — so
no external server is needed; the loop is entirely in-process.

## In-corpus question → grounded answer (the model calls `retrieve`)

```shell
curl -s -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" \
  -d '{"question":"why must some components be written in Java instead of Scala?"}'
# {"answer":"Because the Workflow and entity component clients are keyed on Java method references
#  with no dynamicCall escape hatch, a Scala lambda can't satisfy them, so those are written in Java…"}
```

## Out-of-corpus question → honest decline (no fabrication)

```shell
curl -s -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" \
  -d '{"question":"what is the capital of France?"}'
# {"answer":"I don't know — the knowledge corpus doesn't cover that."}
```

## Validation-first (no model/tool call)

```shell
curl -i -X POST http://localhost:9000/grounded-ask \
  -H "Content-Type: application/json" -d '{"question":"  "}'
# 400 Bad Request — question must not be blank
```

## How this differs from cap-8

| | cap-8 (`/ask`) | cap-10 (`/grounded-ask`) |
|---|---|---|
| Who retrieves | the **endpoint**, always, once (top-K) | the **model**, on demand, 0..N times, via a remote MCP tool |
| Retrieval transport | direct DI call to `KnowledgeStore` | JSON-RPC to this service's own `/mcp` (cap-9) |
| Citations | ground-truth `citedSources` (endpoint owns retrieval) | none (model owns retrieval) |
| Multi-hop / query rewrite | no | possible (within `max-tool-call-steps`) |
| Interop question | custom-dependency DI is Scala-clean | **`.mcpTools(url)` is Scala-clean** (SC-007) |

## Test (offline, no model)

```shell
mvn verify
```

Offline tests cover: validation-first (blank/malformed → 400, no calls), the wiring, and — if the
tool-loop spike (S2) holds — a scripted `retrieve` tool call that round-trips to the real in-process
`/mcp` with the received `ToolResult` equal to a direct `KnowledgeStore.retrieve` (SC-005 parity).
The closed loop with a real model is proven by a live smoke test.

## Interop takeaway (SC-007)

Consuming a remote MCP server from Scala is **friction-free**: `RemoteMcpTools.fromServer/fromService`
is a URL-string builder with no method references (bytecode-verified), so `.mcpTools(...)` sits on the
Scala-clean side of the wall — like `.tools()` and the DI/Task/Agent clients, unlike the
Workflow/entity clients. The wall is a `ComponentClient`-method-ref property; the MCP client is not a
`ComponentClient`.
