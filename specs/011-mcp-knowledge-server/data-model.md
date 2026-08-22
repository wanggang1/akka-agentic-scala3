# Data Model — MCP Knowledge Server (feature 011)

This capability introduces **no new domain model and no new component**. It adds one endpoint and two
small endpoint-owned wire types, and reuses cap-8's domain/application verbatim.

## New types (owned by `com.gwgs.akkaagentic.mcp.api.KnowledgeMcpEndpoint`)

> **⚠️ Implementation reality (T005/T006) — this section was superseded on two points; kept for the
> design trail. See research R2 for the empirical corrections.**
> 1. **No `RetrieveInput` wrapper record.** The tool uses a **bare `question: String` parameter** (the
>    SDK reflects each bare param into a top-level schema property by name; a wrapper record + manual
>    `inputSchema` failed at runtime). So `RetrieveInput` below does not exist.
> 2. **No `maxResults` on SDK 3.6.0.** The optional result count could not be expressed (the
>    `Optional[T]` bare param throws on a supplied value; plain `Integer` is forced *required*). The tool
>    returns a **fixed top-K of 3** (cap-8's `TopK`) and takes only `question`. **Revisit on SDK
>    upgrade.** Everything about `maxResults` below (the row, the `k = math.max(…)` flow) is deferred.

### `RetrieveInput` — MCP tool input *(Java-shaped, crosses the internal mapper — R2)*
| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `question` | `String` | yes | The natural-language query. Validated non-blank via `AskQuestion.validate` before retrieval. |
| `maxResults` | nullable `java.lang.Integer` | no | Desired result count. Absent arrives as **`null`**; the endpoint does `Option(maxResults).map(_.intValue).getOrElse(3)` at the boundary → default **3** (cap-8 `TopK`), then clamps `≥ 1`. |

- Declared as a Scala `case class` with explicit `@JsonCreator` / `@JsonProperty` (like
  `DocsAgent.Request`), because MCP tool input is parsed by the SDK-internal mapper, not the
  Scala-aware HTTP mapper (§3). **`maxResults` MUST be a nullable Java `Integer`, not `Option[Integer]`**:
  the internal mapper cannot construct `scala.Option` (the very reason the record is Java-shaped), so a
  `null` is converted to `Option` in application code, never on the wire. The `@McpTool` carries a
  **manual `inputSchema`** matching this shape, with only `question` marked required.

### `RetrievedPassage` — one element of the tool result *(internal render shape, not a wire component)*
| Field | Type | Notes |
|-------|------|-------|
| `source` | `String` | The passage's source label (e.g. `cap-4-session-memory`). |
| `score` | `Double` | Cosine similarity; higher = closer. Ordered descending. |
| `text` | `String` | The passage text. |

- The tool **returns a `String`**: a compact JSON array `[{"source","score","text"}, …]` the endpoint
  builds from `List[KnowledgeStore.Retrieved]` (a `toApi`-style render). `RetrievedPassage` is just the
  internal shape of that render; it is never handed to the SDK serializer (R3 — mapper-agnostic,
  deterministic for offline assertion).

### Corpus resource body *(P2, if clean — R5)*
- A zero-arg `@McpResource` (`uri = "knowledge://corpus/sources"`, `mimeType = "application/json"`)
  returns a JSON array of the corpus **source labels** — `KnowledgeCorpus.passages.map(_.source)`.
  No new type; a built `String`.

## Reused from capability 8 — UNCHANGED

| Type | Package | Role here |
|------|---------|-----------|
| `KnowledgeStore` | `docs.application` | Injected via `Bootstrap` DI; `retrieve(question, k)` runs the deterministic vector search. Not modified. |
| `KnowledgeStore.Retrieved(source, text, score)` | `docs.application` | Search result the endpoint renders into the tool's JSON string. |
| `KnowledgeCorpus.passages` / `Passage(source, text)` | `docs.domain` | The canned corpus + its source labels (for the resource). |
| `AskQuestion.validate(Option[String])` | `docs.domain` | Parse-don't-validate guard: blank/absent question → tool error, no retrieval (FR-005). |

## Mapping / flow (tool `retrieve`)

```
JSON-RPC tools/call {question, maxResults?}
  → RetrieveInput (internal mapper parses; maxResults absent ⇒ null)
  → AskQuestion.validate(Option(question))            // blank ⇒ MCP tool error, no retrieval
  → k = math.max(Option(maxResults).map(_.intValue).getOrElse(3), 1)   // null→3; floor at 1
  → knowledgeStore.retrieve(question, k)              // deterministic; score-descending;
                                                      //   langchain4j already caps k at corpus size
  → List[Retrieved] → render JSON String [{source,score,text}, …]
  → JSON-RPC tools/call result (text content)
```

> **On the upper bound (L2):** an explicit `corpusSize` clamp is unnecessary — langchain4j's
> `EmbeddingSearchRequest.maxResults` already returns at most the number of stored segments, so a very
> large `k` simply yields the whole corpus. Only the lower floor (`≥ 1`) is enforced in code.

No persistence, no events, no state transitions — the endpoint is stateless; the only state is the
in-memory embedding store seeded once at startup inside the injected `KnowledgeStore` (cap-8).

## Descriptor change

Add to `META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` a **new key**:

```hocon
mcp-endpoint = ["com.gwgs.akkaagentic.mcp.api.KnowledgeMcpEndpoint"]
```

(New top-level component-type key `mcp-endpoint`, confirmed from the SDK `ComponentType` constant
pool — research R1.)
