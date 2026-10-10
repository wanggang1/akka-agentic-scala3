# Data Model: Usage-accurate citations (feature 023)

All new types live under `com.gwgs.akkaagentic.docs.*`. Wire types that cross the SDK's **internal**
mapper are Java-shaped (explicit Jackson annotations, `java.util.List`), per README §3; domain and API
types are idiomatic Scala.

## Reused from cap-8 (unchanged)

- **`DocsAgent.Request`** (`application`, Java-shaped) — `{ question: String, passages: List[Passage] }`.
  Reused read-only as the new agent's command parameter (R2).
- **`DocsAgent.Passage`** (`application`, Java-shaped) — `{ source: String, text: String }`.
- **`DocsAgent.DontKnow`** (`application`, `String` constant) — the shared decline sentinel (R3).
- **`KnowledgeStore.Retrieved`** (`application`) — `{ source: String, text: String, score: Double }`.
  The set of `.source` labels is the ground-truth retrieved list.
- **`AskQuestion`** (`domain`) — `validate(Option[String]): Either[String, ValidQuestion]` (FR-008).

## New — application (agent reply, Java-shaped)

### `CitingDocsAgent.CitedAnswer`

The structured object the model must emit.

| Field | Type | Notes |
|-------|------|-------|
| `answer` | `String` | The grounded answer, or exactly `DocsAgent.DontKnow` on a decline. |
| `usedSources` | `java.util.List[String]` | Source labels (the parenthesized `(label)` tokens) the model claims it used. Empty on a decline. **Self-reported — untrusted until intersected (R4).** |

- `@JsonCreator` / `@JsonProperty` annotated (crosses the internal mapper).
- On a failed/malformed turn, `onFailure` returns `CitedAnswer(DontKnow, emptyList)` (R3).

## New — domain (pure selection logic)

### `UsageCitations` (object)

The honesty rule, framework-free and unit-testable in isolation (constitution §II/§III).

```
select(reportedUsed: List[String], retrievedLabels: List[String], declined: Boolean): List[String]
```

| Input | Meaning |
|-------|---------|
| `reportedUsed` | the model's self-reported `usedSources` (already converted from the Java list) |
| `retrievedLabels` | ground-truth labels actually offered as context, de-duplicated, in retrieval order |
| `declined` | true iff the answer is the decline sentinel |

**Rules (R4):**
1. `declined == true` → `Nil` (FR-005).
2. otherwise → the members of `retrievedLabels` that also appear in `reportedUsed`, in
   `retrievedLabels` order, de-duplicated (FR-004).
3. a non-decline answer whose rule-2 result is empty → `Nil` (FR-007: cite nothing, never fall back to
   the full retrieved set).

**Invariant (SC-002):** every returned label ∈ `retrievedLabels`. A self-reported label absent from
`retrievedLabels` is silently dropped (it was never offered).

## New — api (endpoint request/response, idiomatic Scala)

### `CitedDocsEndpoint.CitedAskRequest`

| Field | Type | Notes |
|-------|------|-------|
| `question` | `Option[String]` | Absent/null → `None` → `400` (annotation-free, `@JsonIgnoreProperties(ignoreUnknown=true)`), mirroring cap-8's `AskRequest`. |

### `CitedDocsEndpoint.CitedAskReply`

| Field | Type | Notes |
|-------|------|-------|
| `answer` | `String` | The grounded answer, or the decline sentinel. |
| `citedSources` | `List[String]` | **Usage-accurate** — `UsageCitations.select(...)`. Empty on decline / unverifiable usage (FR-005/FR-007). |
| `retrievedSources` | `List[String]` | **Ground truth** — every retrieved label, i.e. what cap-8's `/ask` would cite (FR-006). Lets a caller see the divergence from one response. |

API-owned record; never exposes the agent reply or domain types directly (constitution §II).

## Relationships / flow

```
CitedAskRequest.question
   └─ AskQuestion.validate ──(Left)→ 400
                            └─(Right valid)→ KnowledgeStore.retrieve(q, TopK=3) → List[Retrieved]
                                              │  retrievedLabels = retrieved.map(_.source).distinct
                                              └─ DocsAgent.Request(q, passages)
                                                   └─ citing-docs-agent (responseConformsTo CitedAnswer)
                                                        └─ CitedAnswer{answer, usedSources}
             declined = answer == DontKnow
             citedSources = UsageCitations.select(usedSources, retrievedLabels, declined)
             → 200 CitedAskReply(answer, citedSources, retrievedSources = retrievedLabels)
```
