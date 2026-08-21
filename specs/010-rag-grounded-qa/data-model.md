# Data Model: RAG-Grounded Q&A (cap-8, feature 010)

All types are Scala. The **two-mapper boundary** (README §3) governs shape: HTTP DTOs are idiomatic
(`Option`/`List`, annotation-free); the **agent `Request`** crosses the SDK-internal mapper so it and
its nested `Passage` are **Java-shaped** (Jackson-annotated, `java.util.List`). Agent **output** is a
bare `String`, so no Java-shaped result type is needed (cap-4 pattern).

## Domain layer — `com.gwgs.akkaagentic.docs.domain` (pure Scala, no Akka)

### `Passage`
The domain unit of knowledge.
| Field | Type | Notes |
|---|---|---|
| `source` | `String` | citation label, unique per passage (e.g. `"cap-3-help-desk"`) |
| `text` | `String` | the passage body embedded and retrieved |

### `KnowledgeCorpus`
The fixed demo corpus.
- `val passages: List[Passage]` — a handful of hand-written passages about this project's
  capabilities and interop findings (self-referential, so in/out-of-corpus questions are easy to
  construct). Subject matter is not load-bearing.
- Pure `object`; no Akka; no randomness → stable across runs.

### `AskQuestion` (parse-don't-validate)
| Field | Type | Notes |
|---|---|---|
| `question` | `String` | proven non-blank, trimmed |

- `def validate(question: Option[String]): Either[String, AskQuestion]`
  - `question.map(_.trim).filterNot(_.isBlank).map(AskQuestion(_)).toRight("question must not be blank")`
  - Absent/blank/whitespace → `Left("question must not be blank")` → endpoint 400 **before** retrieval
    or model call (FR-006).

## Application layer — `com.gwgs.akkaagentic.docs.application`

### `KnowledgeStore` (plain class — NOT a component; injected via DI)
Holds an in-process `EmbeddingModel` (`AllMiniLmL6V2QuantizedEmbeddingModel`) and an
`InMemoryEmbeddingStore[TextSegment]`, seeded from `KnowledgeCorpus` at construction.
- `final case class Retrieved(source: String, text: String, score: Double)`
- `def retrieve(question: String, k: Int): List[Retrieved]`
  - embed the question → `EmbeddingSearchRequest(maxResults = k[, minScore])` → `store.search` →
    map each `EmbeddingMatch[TextSegment]` to `Retrieved`, ordered by descending score.
- **Deterministic**: same corpus + question ⇒ same result (FR-009) → offline-testable retrieval.

### `DocsAgent` (request-based `Agent`; `@Component(id = "docs-agent")`)
Single command handler; grounds an answer in supplied passages, or declines.
- **`DocsAgent.Request`** — Java-shaped (crosses internal mapper, R4):
  `@JsonCreator (@JsonProperty("question") question: String, @JsonProperty("passages") passages: java.util.List[DocsAgent.Passage])`
  - **`DocsAgent.Passage`** — Java-shaped `(@JsonProperty("source") source: String, @JsonProperty("text") text: String)`
    (a wire copy of the domain `Passage`; the domain type stays idiomatic).
- `def ask(request: Request): Effect[String]`
  - system message: answer **only** from the sources; if they don't contain the answer, reply exactly
    with the decline sentinel; do not use outside knowledge.
  - user message: the question + a labeled passages block (each `"[<source>] <text>"`).
  - `.onFailure(_ => <sentinel or safe message>)` — degrade a failed turn cleanly (AGENTS.md checklist).
  - returns the model's answer text (bare `String`).
- **Decline sentinel**: a shared constant (e.g. `DocsAgent.DontKnow = "I don't know"`), referenced by
  the endpoint for the citation decision.

## API layer — `com.gwgs.akkaagentic.docs.api` (idiomatic Scala DTOs)

### `DocsEndpoint` (`@HttpEndpoint`, `@Acl(INTERNET)`; injects `ComponentClient` + `KnowledgeStore`)
- **`AskRequest`** `(question: Option[String])` — `@JsonIgnoreProperties(ignoreUnknown = true)`
  (tolerate extra fields, FR-007).
- **`AskReply`** `(answer: String, citedSources: List[String])` — empty `citedSources` on decline.
- `POST /ask`:
  1. `AskQuestion.validate(request.question)` → `Left` ⇒ `400`; `Right(valid)` ⇒ continue.
  2. `passages = knowledgeStore.retrieve(valid.question, K)` (single retrieval locus, R4).
  3. call `DocsAgent` (`dynamicCall`, R? — see below) with `Request(valid.question, passages.map(toWire).asJava)`.
  4. citations: reply == sentinel ⇒ `List.empty`; else ⇒ `passages.map(_.source).distinct`.
  5. `200 AskReply(answer, citedSources)`.
- **Call mechanism**: `componentClient.forAgent().inSession(UUID).dynamicCall[Request, String]("docs-agent")`
  (§2 — Scala lambdas can't use Java method refs; `dynamicCall` by component id).

## Wiring / descriptor
Add to `META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`:
- `agent += "com.gwgs.akkaagentic.docs.application.DocsAgent"`
- `http-endpoint += "com.gwgs.akkaagentic.docs.api.DocsEndpoint"`
`Bootstrap` stays the sole `service-setup` entry (now also provides `KnowledgeStore`).
`KnowledgeStore`, `KnowledgeCorpus`, `AskQuestion`, `Passage` are **not** components.

## Relationships
```
AskRequest(Option) ─validate→ AskQuestion ─┐
                                            ├→ KnowledgeStore.retrieve → List[Retrieved] ──┐
DocsEndpoint (retrieves once) ──────────────┘                                              │
      │ Request{question, passages(Java-shaped)}                                           │
      ▼                                                                                    │
   DocsAgent.ask → String answer ── endpoint cites from retrieved sources (unless decline)─┘
      ▼
   AskReply{answer, citedSources}  (idiomatic Scala DTO)
```
