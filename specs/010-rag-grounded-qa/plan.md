# Implementation Plan: RAG-Grounded Q&A

**Branch**: `010-rag-grounded-qa` | **Date**: 2026-08-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/010-rag-grounded-qa/spec.md`

## Summary

Capability 8 demonstrates **Retrieval-Augmented Generation** in Scala 3 on the Akka Java SDK.
A request-based `DocsAgent` answers a question grounded **only** in passages retrieved from a
small local corpus by **semantic vector similarity**, and honestly declines when the corpus does
not support an answer. Retrieval runs on **in-process embeddings** (the langchain4j
`all-minilm-l6-v2-q` ONNX model, packaged in-jar) over an `InMemoryEmbeddingStore` — **already on
the SDK 3.6.0 classpath**, so no network, no API key, and no new dependency *version*. The
retrieval utility (`KnowledgeStore`) is a plain Scala class injected into the agent via the
existing `Bootstrap` `ServiceSetup`'s `DependencyProvider`. HTTP surface is synchronous
`POST /ask` (like cap-4). Citations are computed **deterministically endpoint-side** from what
retrieval returned (not model self-report), so *which passages ground an answer* is verifiable
offline while the answer text is mocked by `TestModelProvider`.

## Technical Context

**Language/Version**: Scala 3 (compiled by `scala-maven-plugin`) on the Java-first Akka SDK
**Primary Dependencies**: `akka-javasdk` 3.6.0; langchain4j `1.15.0` (core/aggregator) +
`langchain4j-embeddings-all-minilm-l6-v2-q` `1.15.0-beta25` — **both already in the SDK 3.6.0
dependency tree** (see research R1); the ONNX model artifact only needs promotion `runtime → compile`.
**Storage**: In-memory vector store (`InMemoryEmbeddingStore`), seeded at startup from a canned
Scala corpus. No database, no Entity. (The Task/Entity durability discussion does not apply — this
capability is stateless request/response.)
**Testing**: `mvn verify`; `TestModelProvider` mocks the model. Embeddings are real and
deterministic in tests (in-process ONNX), so retrieval is asserted offline.
**Target Platform**: Local JVM (Ollama by default for live); offline for tests.
**Project Type**: Single Akka service (this repo).
**Performance Goals**: Interactive single round-trip. First request loads the ONNX model
(~one-time, at startup via eager DI construction), then retrieval is sub-second on a tiny corpus.
**Constraints**: Fully offline; no API key; deterministic retrieval; validation-first.
**Scale/Scope**: A handful of hand-written passages (demo corpus), top-k = a small fixed number.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — PASS. Components are SDK primitives: request-based
  `Agent`, HTTP `Endpoint`, `ServiceSetup` + `DependencyProvider`. **External dependency
  justification** (required by Principle I): the langchain4j embedding artifacts are **already in the
  akka-javasdk 3.6.0 dependency tree** — we add no new artifact *version*, only promote the in-process
  ONNX model from `runtime` to `compile` scope (and pin the aggregator that carries
  `InMemoryEmbeddingStore`) so Scala source may reference them. Vector-embedding retrieval is exactly
  what the SDK delegates to langchain4j in its own *Ask Akka* RAG sample; it is not reimplementable
  "with a small amount of application code" without shipping an embedding model. Documented in
  research R1.
- **II. Design Principles** — PASS. Domain independence: `KnowledgeCorpus`, `Passage`, `AskQuestion`
  are pure Scala (no Akka). API isolation: endpoint owns its request/response DTOs. Single
  responsibility: `KnowledgeStore` = retrieval only; `DocsAgent` = grounded answer only; endpoint =
  HTTP + deterministic citation. Descriptive naming: `KnowledgeStore`/`DocsAgent`/`Passage`.
- **III. Test Coverage** — PASS. Unit: `AskQuestion` validation, `KnowledgeCorpus`, `KnowledgeStore`
  retrieval (deterministic/offline — the headline RAG test). Integration: `DocsEndpoint` (validation
  contract, grounded 200, decline path) via `TestModelProvider`.
- **IV. Simplicity** — PASS. No vector DB, no Workflow, no Entity. Agent I/O is a bare `String`
  (no Java-shaped result type needed — cap-4 pattern). Citation is deterministic endpoint logic, not
  a structured model result (dodges cap-7's D6 self-report unreliability). Reuses bundled deps.

**Result: PASS (no violations; Complexity Tracking not required).**

## Project Structure

### Documentation (this feature)

```text
specs/010-rag-grounded-qa/
├── plan.md              # This file
├── research.md          # Phase 0 output (R1..R6 below)
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── docs-endpoint.md # Phase 1 output — POST /ask contract
└── checklists/
    └── requirements.md  # from /akka.specify
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/docs/
├── domain/
│   ├── KnowledgeCorpus.scala   # canned passages (Passage(source, text)); pure data
│   └── AskQuestion.scala       # validate(Option[String]) -> Either[String, AskQuestion]
├── application/
│   ├── KnowledgeStore.scala    # embeddings + InMemoryEmbeddingStore; retrieve(q, k): List[Retrieved]
│   └── DocsAgent.scala         # request-based Agent; injects retrieved passages; grounded/declines
└── api/
    └── DocsEndpoint.scala      # POST /ask (sync); DTOs; deterministic citation from retrieval

src/main/scala/com/gwgs/akkaagentic/application/
└── Bootstrap.scala             # MODIFIED: add createDependencyProvider() -> provides KnowledgeStore

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
                                # MODIFIED: + DocsAgent (agent), + DocsEndpoint (http-endpoint)

pom.xml                         # MODIFIED: 2 explicit compile deps (SDK-aligned versions)

src/test/scala/com/gwgs/akkaagentic/docs/
├── domain/AskQuestionTest.scala          # validation unit tests
├── application/KnowledgeStoreTest.scala  # OFFLINE deterministic retrieval (the RAG proof)
└── api/DocsEndpointIntegrationTest.scala # endpoint contract via TestModelProvider
```

**Structure Decision**: New `docs` capability package (`domain`/`application`/`api`), mirroring the
prior six Scala capabilities. `KnowledgeStore`, `KnowledgeCorpus`, `AskQuestion` are **not**
components (not in the descriptor); only `DocsAgent` and `DocsEndpoint` are. `Bootstrap` is the one
shared file touched (it already carries the service-setup entry).

## Complexity Tracking

> No constitution violations — section intentionally empty.
