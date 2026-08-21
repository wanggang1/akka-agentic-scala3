# Contract: `DocsEndpoint` — RAG-grounded Q&A (cap-8)

Synchronous HTTP surface (like cap-4, no polling). Base ACL: `INTERNET`. Content type
`application/json`. Validation-first: a blank/absent question is rejected before any retrieval or
model call.

## `POST /ask`

Answer a question grounded only in retrieved corpus passages.

**Request body** (idiomatic Scala; unknown fields tolerated):
```json
{ "question": "how does the autonomous help desk decide to look something up?" }
```
| Field | Type | Required | Notes |
|---|---|---|---|
| `question` | string | yes | must be non-blank after trim |

**Responses**

| Status | When | Body |
|---|---|---|
| `200 OK` | grounded answer produced | `AskReply` with non-empty `citedSources` |
| `200 OK` | corpus does not support an answer | `AskReply` with `answer` = decline text and **empty** `citedSources` |
| `400 Bad Request` | blank/absent `question`, or malformed JSON | plain-text reason; **no** retrieval, **no** model call |

`AskReply`:
```json
{
  "answer": "The help-desk agent runs a model-driven loop and decides on its own whether to consult a knowledge-base tool before completing a typed task.",
  "citedSources": ["cap-3-help-desk"]
}
```
| Field | Type | Notes |
|---|---|---|
| `answer` | string | grounded answer, or the honest decline text |
| `citedSources` | string[] | distinct source labels of the passages that grounded the answer; **empty** on decline |

### Behavour notes
- **Semantic retrieval**: `question` need not share wording with the source passage; retrieval is by
  embedding similarity (SC-001/SC-002).
- **Citations are ground truth**: they are the sources of the passages actually retrieved and injected,
  computed endpoint-side — not model self-report. On the decline path they are empty (FR-005).
- **Determinism**: for a fixed corpus, the same `question` retrieves the same passages every time
  (FR-009) — the basis of the offline retrieval test.
- **Isolation**: each request is independent; no memory across requests (FR-008).

## Examples

```bash
# In-corpus, paraphrased — grounded answer + citation
curl -sS -X POST http://localhost:9000/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"what makes agent work survive a restart without me writing persistence code?"}'
# 200 {"answer":"...tasks are durable; the runtime persists task + agent state...","citedSources":["cap-3-help-desk"]}

# Out-of-corpus — honest decline, no citation
curl -sS -X POST http://localhost:9000/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"what is the capital of France?"}'
# 200 {"answer":"I don't know","citedSources":[]}

# Blank question — 400 before any retrieval/model
curl -sS -i -X POST http://localhost:9000/ask \
  -H 'Content-Type: application/json' -d '{"question":"  "}'
# 400 Bad Request — question must not be blank
```
