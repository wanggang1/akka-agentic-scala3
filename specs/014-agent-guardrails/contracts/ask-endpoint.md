# Contract: `POST /ask` under guardrails (cap-12)

**Feature**: `014-agent-guardrails` | **Endpoint**: `DocsEndpoint` (capability 8, unchanged path)

Cap-12 adds **one** new outcome to an existing endpoint. The request contract is untouched, and both
existing success outcomes are byte-for-byte what capability 8 returns today (SC-002/SC-003).

## Request — unchanged

```http
POST /ask
Content-Type: application/json

{ "question": "why must some components be written in Java instead of Scala?" }
```

Unknown properties are tolerated. A blank, whitespace-only, absent or `null` `question` is rejected
before anything else runs (FR-009).

## Responses

### 200 — grounded answer *(unchanged from cap-8)*

```json
{ "answer": "Some components must use Java because …", "citedSources": ["interop-method-ref-wall"] }
```

### 200 — honest decline *(unchanged from cap-8)*

```json
{ "answer": "I don't know", "citedSources": [] }
```

A decline is **never** reported as a block (SC-003).

### 422 — blocked by a rule *(new)*

```json
{
  "blocked": true,
  "rule": "default jailbreak",
  "category": "JAILBREAK",
  "explanation": "Content similarity [0.83] exceeds threshold [0.75]"
}
```

Same shape whether the rule fired on the way in (before any model call) or on the way out
(before delivery) — the caller learns *which* rule and *why*, not *where* in the pipeline:

```json
{
  "blocked": true,
  "rule": "linked answer guard",
  "category": "HALLUCINATED",
  "explanation": "Answer contains an external reference marker 'http'"
}
```

### 400 — invalid input *(unchanged from cap-8)*

```text
question must not be blank
```

## The three-outcome guarantee (FR-005)

| Outcome | Status | Discriminator |
|---|---|---|
| Answer | 200 | `answer` present, not the sentinel |
| Decline | 200 | `answer` == `"I don't know"`, `citedSources` empty |
| Blocked | 422 | `blocked: true` + `rule` + `category` |
| Invalid | 400 | plain-text message |

A record-only rule produces **no** caller-visible change: the answer is delivered as a normal `200`
and the violation appears only in the service's observability output (US3, SC-004).

## Ordering guarantees

1. Input validation (`400`) — before retrieval, before any rule, before the model (FR-009).
2. Retrieval — unchanged; endpoint-side, top-3.
3. **Request-side rules** — before the model is called. A block here means **no model call happened**
   (FR-001, SC-001).
4. Model call.
5. **Response-side rules** — before the answer is returned (FR-002).
6. Citations computed endpoint-side, as before.
