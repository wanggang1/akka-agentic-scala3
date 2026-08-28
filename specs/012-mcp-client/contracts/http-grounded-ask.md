# Contract: HTTP surface — `POST /grounded-ask` (cap-10)

Synchronous, single request/response (like cap-4 `/chat` and cap-8 `/ask`). No polling handle.

## Request

```
POST /grounded-ask
Content-Type: application/json

{ "question": "why must some components be written in Java instead of Scala?" }
```

- `question` (string, required, non-blank).
- Unknown extra fields are tolerated (FR-007).

## Responses

### 200 OK — grounded answer

```json
{ "answer": "Some components hit a method-reference wall: the Workflow and entity clients are keyed on Java method references (SerializedLambda) with no dynamicCall escape, which a Scala lambda cannot satisfy, so those are written in Java …" }
```

- Present when the model answered (whether it called `retrieve` or, for an out-of-scope question,
  declined — a decline is still `200` with a natural-language "I don't know" in `answer`).
- **No `citedSources` field** — cap-10 does not own retrieval (cap-8-fork tradeoff).

### 400 Bad Request — validation-first

- Blank/whitespace-only or absent `question` → `400` with the validation message
  (`question must not be blank`), **before** any model or MCP tool call (FR-005, SC-004).
- Malformed JSON body → `400` (FR-006).

### 200 OK — degraded (remote MCP unreachable / agent turn failed)

- If the `retrieve` tool cannot be reached (or the agent turn otherwise fails) during the request, the
  request **degrades gracefully to a clean `200`** whose `answer` is a fixed honest "unavailable"
  message — **not** a raw `500`, no hang, no stack trace leaked to the caller. The agent's `.onFailure`
  fallback degrades the failed turn to that message and `logger.warn`s the real cause server-side
  (cap-6's robustness pattern, FR-010).

  ```json
  { "answer": "I'm unable to answer right now because the knowledge service is unavailable." }
  ```

## Behavioral notes

- **Agentic RAG**: the model decides whether/when to call `retrieve` and with what query — 0..N tool
  calls within `max-tool-call-steps` (default 100). Contrast cap-8, which always retrieves top-K once
  in the endpoint before the model runs.
- **Grounding is a soft constraint** (§R5): the system prompt instructs answer-only-from-passages +
  decline; not a runtime guarantee.

---

# Contract: MCP consumption — the outbound call (cap-10 → cap-9)

Cap-10 consumes cap-9's server; it does **not** re-implement JSON-RPC. The SDK's remote-MCP client
performs the round-trip when the model requests the tool.

## Wiring (agent side)

```
effects()
  .systemMessage(<grounding instructions>)
  .mcpTools(RemoteMcpTools.fromService("akka-agentic-scala3"))   // self-service name (R2)
  .userMessage(question)
  .thenReply()               // bare String reply; .onFailure(fallback) for FR-010
```

- **Target**: this service's own MCP endpoint (`KnowledgeMcpEndpoint`, `/mcp`, from cap-9).
- **Tool advertised by the server**: `retrieve(question: String) → String` (JSON array of
  `{source, score, text}`, fixed top-K = 3). Cap-10 consumes it as-is.
- **ACL** (R2/S1): a self-service call presents a *service* principal; cap-9's endpoint may need its
  `@Acl` broadened to also allow the calling service (INTERNET retained). Confirmed/adjusted in the
  topology spike.

## What the model sees

- `tools/list` → the `retrieve` tool + its input schema (`required: [question]`).
- `tools/call` → the JSON-string result (grounded passages) as a `ToolResult`, which the model uses
  to compose `answer`.

## Parity guarantee (SC-005)

For a given question, the passages surfaced through the agent's `retrieve` tool call MUST equal a
direct `KnowledgeStore.retrieve(question, 3)` against the shared store — one corpus reached two ways.
Asserted offline in the tool-loop test if S2 holds (the mock's received `ToolResult` == direct
retrieval), else live.
