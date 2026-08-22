# Contract — MCP JSON-RPC surface (feature 011)

Transport: **stateless Streamable HTTP** at path **`/mcp`** (MCP spec 2025-03-26). Payloads are
JSON-RPC 2.0.

> **✅ Envelope confirmed (T004 spike, against the TestKit).** The framing is minimal:
> - **No `initialize` handshake** — `tools/list` (and `tools/call`) work on the very first request; the
>   stateless transport keeps no session, so each request stands alone.
> - **Request** = `POST /mcp`, `Content-Type: application/json`, `Accept: application/json,
>   text/event-stream`, body = the raw JSON-RPC object.
> - **Response** = **plain `application/json`**, a single JSON-RPC object (NOT an `text/event-stream`
>   frame), HTTP **`200`**. The body is read directly as JSON — no SSE `data:` parsing.
> - Observed `tools/list` reply for the stub (empty-arg tool): `{"jsonrpc":"2.0","id":1,"result":
>   {"tools":[{"name":"retrieve","description":"…","inputSchema":{"type":"object","properties":{}}}]}}`.
>   (Final: the reflected `inputSchema` carries just `question` under `properties`, with `required:
>   ["question"]` — a single bare param, fixed top-K, no `maxResults` on 3.6.0; see §3.)

The server identity is set on the annotation:
`@McpEndpoint(serverName = "akka-agentic-knowledge-mcp", serverVersion = "0.1.0")`.

---

## 1. `tools/list` — discovery (SC-001)

**Request**
```json
{ "jsonrpc": "2.0", "id": 1, "method": "tools/list" }
```

**Response (shape asserted)** — advertises exactly one tool:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "retrieve",
        "description": "Semantic search over the knowledge corpus; returns the most similar passages with their source labels and similarity scores.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "question":   { "type": "string",  "description": "The natural-language question to search for." },
            "maxResults": { "type": "integer", "description": "How many passages to return (default 3)." }
          },
          "required": ["question"]
        }
      }
    ]
  }
}
```
Assertions: a tool named `retrieve` exists and `question` is required. *(The `maxResults` property
shown above is the design intent; on SDK 3.6.0 the schema carries only `question` — see §3 deviation.)*

---

## 2. `tools/call` — retrieval, in-corpus (SC-002 / SC-004)

**Request**
```json
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": {
    "name": "retrieve",
    "arguments": { "question": "how does the assistant remember a conversation across turns?" }
  }
}
```

**Response (shape asserted)** — a tool result whose text content is the JSON array the endpoint built,
top-K (default 3) passages, **score-descending**:
```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "[{\"source\":\"cap-4-session-memory\",\"score\":0.79,\"text\":\"The multi-turn chat agent holds a conversation using the runtime's session memory ...\"}, {\"source\":\"cap-6-...\",\"score\":0.7,\"text\":\"...\"}, {\"source\":\"...\",\"score\":0.6,\"text\":\"...\"}]" }
    ],
    "isError": false
  }
}
```
Assertions:
- The top passage's `source` is the semantically-correct one (`cap-4-session-memory` for the memory
  question; a **paraphrased** query with no shared keywords still ranks it first — SC-002).
- Results are ordered by **descending** `score`.
- For the same `question` + `k`, the sources/order **match cap-8's `/ask` retrieval** (SC-004) — same
  `KnowledgeStore` behind both surfaces.

---

## 3. `tools/call` — fixed top-K (SC-006 / edge cases)

> **⚠️ SDK-3.6.0 deviation (T006).** The `maxResults` argument below is **not implemented** — the tool
> takes only `question` and returns a **fixed top-K of 3** (cap-8's `TopK`). The optional param could
> not be expressed on 3.6.0 (`Optional[T]` bare param throws on a supplied value; plain `Integer` is
> forced required; manual `inputSchema` breaks bare-param binding). **Revisit on SDK upgrade** — see
> research R2. The clamping rows are retained as the design intent to restore later.

- `arguments: { "question": "…" }` → **3** passages (the fixed top-K; the only shape the tool accepts).
- *(deferred)* `maxResults: 1` → **1**; `maxResults: 0`/negative → floored to **1**; `maxResults: 9999`
  → the **whole corpus** (langchain4j caps at the number of stored passages).

---

## 4. `tools/call` — validation failure (FR-005 / SC-006)

**Request** (blank question)
```json
{ "jsonrpc": "2.0", "id": 4, "method": "tools/call",
  "params": { "name": "retrieve", "arguments": { "question": "   " } } }
```

**Response (shape asserted)** — a well-formed **tool error**, and **no retrieval run**:
```json
{ "jsonrpc": "2.0", "id": 4,
  "result": { "content": [ { "type": "text", "text": "question must not be blank" } ], "isError": true } }
```
(Exact error framing — a tool-result with `isError: true` vs. a JSON-RPC `error` object — is pinned in
the first implementation task; either is acceptable so long as it is well-formed and no retrieval
occurs. The message reuses cap-8's `AskQuestion` message.)

---

## 5. `resources/list` + `resources/read` — corpus discovery (P2, if clean — R5)

**`resources/list`** advertises one resource:
```json
{ "jsonrpc":"2.0","id":5,"method":"resources/list" }
→ result.resources[0] = { "uri":"knowledge://corpus/sources", "name":"Knowledge corpus sources",
                          "mimeType":"application/json", "description":"The source labels of all passages in the corpus." }
```

**`resources/read`** returns the source labels:
```json
{ "jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"knowledge://corpus/sources"} }
→ result.contents[0].text = "[\"cap-1-greeting\",\"cap-3-help-desk\",\"cap-4-session-memory\", ...]"
```
Assertion: the list contains the known corpus labels (e.g. `cap-4-session-memory`,
`interop-method-ref-wall`, `durability-tasks`).

---

## Notes for the implementer

- **First task = pin the envelope.** Stand up the endpoint with a trivial `retrieve`, then iterate raw
  JSON-RPC bodies through the testkit `httpClient` against `/mcp` until `tools/list` and `tools/call`
  round-trip. Capture the exact required headers (e.g. `Accept: application/json, text/event-stream`),
  any `initialize` step, and the response framing here before asserting behavior.
- The tool **text** content is the endpoint-built JSON string (research R3) — assert by parsing it, not
  by string equality on floating-point scores (assert ordering + sources; scores with a tolerance if
  at all).
