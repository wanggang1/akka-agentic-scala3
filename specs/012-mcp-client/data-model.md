# Data Model: MCP Client Agent (cap-10)

Cap-10 introduces **no new persisted data** and **no new domain corpus**. It reuses cap-8's
`KnowledgeStore` / `KnowledgeCorpus` and cap-9's MCP server verbatim. The only new types are the
HTTP DTOs and (optionally) a validated question value — all transient.

## Reused types (no change)

| Type | Origin | Role in cap-10 |
|---|---|---|
| `KnowledgeStore` | cap-8 (`docs.application`) | The retrieval backend; reached **only** via cap-9's MCP `retrieve` tool at request time. Used **directly** in tests to assert shared-store parity (SC-005). |
| `KnowledgeCorpus` / `Passage` | cap-8 (`docs.domain`) | The canned corpus; unchanged. |
| `KnowledgeMcpEndpoint` | cap-9 (`mcp.api`) | The remote MCP server the agent consumes. **Possible additive edit**: broaden `@Acl` to also allow the calling service principal (R2/S1), INTERNET retained. |
| `AskQuestion` (+ `validate`) | cap-8 (`docs.domain`) | Reused for validation-first rejection (blank question → `Left`). Keeps the contract identical to cap-8. |

## New types (all transient — API isolation, §II)

### `McpClientEndpoint.AskRequest` (HTTP request DTO)

Idiomatic Scala, `Option`-typed, Scala-aware Jackson (§3 — HTTP body mapper).

| Field | Type | Rule |
|---|---|---|
| `question` | `Option[String]` | Required at the domain boundary: `None`/blank → `400` via `AskQuestion.validate` before any model/tool call (FR-005). Unknown extra JSON fields tolerated (FR-007). |

### `McpClientEndpoint.AskReply` (HTTP response DTO)

| Field | Type | Notes |
|---|---|---|
| `answer` | `String` | The grounded answer, or an honest natural-language decline. **No `citedSources`** — cap-10 does not own retrieval, so ground-truth citations aren't available (deliberate cap-8-fork tradeoff; spec Assumption). |

### Agent payload (crosses the internal mapper, §3)

- **User message**: a bare `String` (the question). No wrapper record needed → nothing Java-shaped
  to declare.
- **Reply**: a bare `String` (the answer text). The cap-4/cap-8 shape — no typed structured result,
  so no `@JsonCreator`/`@JsonProperty` types cross the internal mapper.

> If a request record is preferred over a bare `String` for the agent method, it MUST be Java-shaped
> (Jackson-annotated) per §3. The default choice is a **bare `String`** to keep the surface minimal.

## Validation rules

1. **Question non-blank** — `AskQuestion.validate(Option(question))`:
   - `Left(message)` → HTTP `400` with `message`, **no** model/tool call (FR-005, SC-004).
   - `Right(valid)` → proceed to the agent.
2. **Malformed body** → SDK auto-`400` (FR-006), as in every other capability.
3. **Grounding** (soft, §R5) — the model is *instructed* to answer only from retrieved passages and
   to decline otherwise; not a runtime guarantee.

## State transitions

None. Synchronous request → (validate) → agent (model may call `retrieve` 0..N times) → answer.
No durable task, no entity, no workflow. The only durable state in the whole path is cap-8's
startup-seeded embedding store, owned by cap-8.
