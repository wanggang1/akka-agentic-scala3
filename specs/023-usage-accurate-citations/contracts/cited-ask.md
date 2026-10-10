# Contract: `POST /cited-ask`

A new, parallel RAG surface (capability 21, fork B4 / feature 023). Cap-8's `POST /ask` is unchanged (FR-001, SC-004).

## Request

```
POST /cited-ask
Content-Type: application/json

{ "question": "<string>" }
```

- `question` absent, `null`, or blank → **400 Bad Request** (plain-text reason). No retrieval, no model
  call (FR-008). Unknown properties are ignored.

## Responses

### 200 OK — answered

```json
{
  "answer": "<grounded answer>",
  "citedSources": ["<label>", "..."],
  "retrievedSources": ["<label>", "<label>", "<label>"]
}
```

- `citedSources` — **usage-accurate**: the source labels the model reported using, intersected with the
  retrieved set, in retrieval order, de-duplicated (FR-003/FR-004). A subset of `retrievedSources`.
- `retrievedSources` — **ground truth**: every retrieved label (top-K=3), i.e. exactly what cap-8's
  `/ask` would cite (FR-006). Present so divergence is visible from one response.
- **Invariant:** `citedSources ⊆ retrievedSources` always (SC-002).

### 200 OK — declined

```json
{ "answer": "I don't know", "citedSources": [], "retrievedSources": ["<label>", "..."] }
```

- The corpus does not answer the question. `citedSources` is empty (FR-005). `retrievedSources` still
  reflects what was offered. A failed/malformed model turn maps to this same decline shape (R3), never a
  500.

### 200 OK — answered but no verifiable usage (FR-007)

```json
{ "answer": "<grounded answer>", "citedSources": [], "retrievedSources": ["<label>", "..."] }
```

- The model gave a real answer but reported no sources, or only labels that were not retrieved. We **cite
  nothing** rather than fall back to the full retrieved set — self-report failure stays visible.

### 400 Bad Request — invalid input

Plain-text reason; blank/absent question.

## Acceptance checks (map to SC / tests)

| Check | Maps to |
|-------|---------|
| Focused answer cites a strict subset of `retrievedSources`, fewer than `/ask` | SC-001, US1 |
| Every `citedSources` label appears in `retrievedSources` | SC-002 |
| Out-of-corpus question → decline, `citedSources == []`, no fabrication | SC-003, US2 |
| A reported label not in the retrieved set is dropped | FR-004, US3 |
| Non-decline answer with no usable reported sources → `citedSources == []` | FR-007 |
| cap-8 `/ask` tests still pass unchanged; `/ask` still cites full set | SC-004 |
| Live faithfulness over in-corpus questions recorded in README/research | SC-005, US3 |
