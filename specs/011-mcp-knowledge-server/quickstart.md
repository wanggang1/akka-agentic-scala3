# Quickstart — MCP Knowledge Server (feature 011)

Capability 9 exposes cap-8's semantic retrieval as an **MCP tool** at `POST /mcp` (JSON-RPC 2.0,
stateless Streamable HTTP). Server-side only — everything runs offline (in-process ONNX embeddings,
no API key, no model).

## Run

```shell
mvn compile exec:java     # serves /mcp alongside the cap-1..8 HTTP endpoints, on :9000
```

## Discover the tool

```shell
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# → advertises tool "retrieve" with an input schema: required "question", optional "maxResults".
```

## Call the tool

```shell
curl -s -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"retrieve",
                 "arguments":{"question":"how does the assistant remember a conversation across turns?"}}}'
# → result.content[0].text is a JSON array of the top-3 passages, score-descending:
#   [{"source":"cap-4-session-memory","score":0.79,"text":"..."}, ...]
```

Ask for fewer results, and try a paraphrase (no shared keywords) — the memory passage still ranks
first, proving semantic (not keyword) retrieval:

```shell
curl -s -X POST http://localhost:9000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"retrieve","arguments":{"question":"what keeps state alive after a crash without persistence code?","maxResults":1}}}'
# → single top passage, e.g. "durability-tasks".
```

Blank question is rejected before any retrieval (validation-first, reuses cap-8's message):

```shell
curl -s -X POST http://localhost:9000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call",
       "params":{"name":"retrieve","arguments":{"question":"   "}}}'
# → tool error "question must not be blank"; no retrieval runs.
```

## (P2) List the corpus as a resource

```shell
curl -s -X POST http://localhost:9000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":5,"method":"resources/list"}'
curl -s -X POST http://localhost:9000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"knowledge://corpus/sources"}}'
# → the source labels of every corpus passage.
```

## (optional) List prompts — the third MCP primitive

The MCP spec defines three server primitives — **tools**, **resources**, and **prompts**. This server
exposes tools and resources but **no prompts**, yet the SDK still answers `prompts/list` (a client may
probe all three during discovery). It returns an empty list rather than a "method not found" error:

```shell
curl -s -X POST http://localhost:9000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":7,"method":"prompts/list"}'
# → {"jsonrpc":"2.0","id":7,"result":{"prompts":[]}}
```

(Akka exposes prompts via `@McpPrompt` methods — see `akka-context/sdk/mcp-endpoints.html.md`; this
capability declares none.)

## Test (offline, no model)

```shell
mvn verify
```

`KnowledgeMcpEndpointIntegrationTest` drives `/mcp` with hand-crafted JSON-RPC via the testkit
`httpClient` and asserts discovery + tool call + validation deterministically. Retrieval correctness
is additionally covered by cap-8's reused `KnowledgeStoreTest`. (There is no MCP-specific testkit; the
JSON-RPC-over-httpClient approach is the SDK's prescribed method.)

## Optional live smoke (MCP Inspector / Claude Desktop)

Point any MCP client that speaks Streamable HTTP at `http://localhost:9000/mcp`, discover `retrieve`,
and call it — a nice-to-have end-to-end confirmation, not a gate. The client-side `.mcpTools()` path
(an Akka agent *consuming* a remote MCP server) is a separate, future, live-only capability.

## Notes

- **Interop verdict:** `@McpEndpoint` is Scala-clean (no method-ref wall — it's an endpoint invoked
  reflectively from JSON-RPC). The one Scala tax is the hand-maintained descriptor entry under the new
  `mcp-endpoint` key. See README §11 (to be written) and `research.md` R1.
- **One retrieval, two surfaces:** the same `KnowledgeStore` backs both `POST /ask` (cap-8, HTTP) and
  `POST /mcp` `retrieve` (cap-9, MCP) — same passages, same order (SC-004).
