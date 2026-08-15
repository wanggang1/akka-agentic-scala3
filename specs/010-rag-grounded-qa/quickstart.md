# Quickstart: RAG-Grounded Q&A (cap-8, feature 010)

## Build & test (offline — no API key, no network)
```bash
mvn verify
```
- `KnowledgeStoreTest` proves **semantic retrieval** with the real in-process ONNX embeddings
  (deterministic): a paraphrased in-corpus query returns the expected source as top match, and two
  distinct queries return different top sources.
- `DocsEndpointIntegrationTest` proves the HTTP contract with `TestModelProvider`: grounded 200 +
  citations, the decline path (empty citations), and validation-first 400.
- Note: the test suite loads the ~22 MB ONNX model once at startup (eager DI construction) — a small
  one-time cost.

## Run locally (live grounding)
Default is local Ollama (`qwen3:8b`); no key needed:
```bash
ollama pull qwen3:8b        # once
mvn compile exec:java
```
Or Gemini:
```bash
MODEL_PROVIDER=googleai-gemini GOOGLE_AI_GEMINI_API_KEY=… mvn compile exec:java
```

## Try it
```bash
# In-corpus, paraphrased → grounded answer + correct citation
curl -sS -X POST http://localhost:9000/ask -H 'Content-Type: application/json' \
  -d '{"question":"what makes agent work survive a restart without writing persistence code?"}'

# Out-of-corpus → honest "I don't know", no citation
curl -sS -X POST http://localhost:9000/ask -H 'Content-Type: application/json' \
  -d '{"question":"what is the capital of France?"}'

# Blank → 400 before any retrieval/model
curl -sS -i -X POST http://localhost:9000/ask -H 'Content-Type: application/json' -d '{"question":"  "}'
```

## What to verify live (SC-001..SC-006)
- Paraphrased in-corpus question → answer reflects the corpus fact and cites the right source (SC-001);
  a second, different in-corpus question cites a *different* source (SC-002).
- Out-of-corpus question → explicit decline, no fabricated facts, no citation (SC-003).
- The offline `KnowledgeStoreTest` already proves *which passages* are retrieved deterministically
  (SC-004); the live run proves the grounded answer end-to-end.
- Blank question → 400, no model call (SC-005). Whole flow runs with no key/network (SC-006).

## Verified live (Ollama `qwen3:8b`, 2026-08-15)
All four scenarios end-to-end against the real model:
- **In-corpus (paraphrased)** — "what makes agent work survive a restart without me writing persistence
  code?" → *"The agent's tasks and process state are automatically persisted by the runtime as durable
  records, ensuring recovery after restarts without requiring custom persistence code."*,
  `citedSources:["durability-tasks","cap-3-help-desk","cap-4-session-memory"]` (top = the right passage).
- **Out-of-corpus** — "what is the capital of France?" → `{"answer":"I don't know","citedSources":[]}` —
  declined **even though** top-k retrieved something (grounding is enforced by instruction; the empty-citation
  decline path fired). SC-003 ✓.
- **Semantic discrimination (SC-002)** — "how does the coordinator pick which specialist to consult?" cited
  `cap-7-activity-coordinator` first; "why can some components only be written in Java, not Scala?" cited
  `interop-method-ref-wall` first (and synthesized across `interop-two-mapper`).
- **Validation** — blank question → `400 Bad Request` (no retrieval, no model). SC-005 ✓.

## Interop findings this capability demonstrates (for README §10)
- **The in-process RAG stack ships with the SDK** (langchain4j 1.15.0 + all-minilm ONNX, in-jar) —
  only a `runtime→compile` scope promotion, no new version (research R1).
- **Custom-dependency DI from Scala**: `KnowledgeStore` provided via `Bootstrap`'s
  `createDependencyProvider()` and constructor-injected — the first non-`ComponentClient` injection in
  this project (R5; Class-keyed, no method-ref wall).
- **Two-mapper boundary holds**: the agent `Request` (with passages) is Java-shaped; the agent output
  is a bare `String`; HTTP DTOs are idiomatic `Option`/`List` (R4).
- **Citations are deterministic, not self-reported** — dodges cap-7's D6 unreliability (R3).
- **Retrieval is fully offline-verifiable** — the clean counter-example to cap-7's D9 (embeddings are
  deterministic in-process, unlike delegation) (R6).
- **Production indexing would use a Workflow** (Java-only §4); we seed at bootstrap to stay Scala.
```
