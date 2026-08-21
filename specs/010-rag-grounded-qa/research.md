# Research: RAG-Grounded Q&A (cap-8, feature 010)

Phase 0 decisions. Each: Decision / Rationale / Alternatives. IDs `R#` are referenced from plan.md,
README interop §10 (to be written), and tasks.md.

---

## R1 — The in-process RAG stack is ALREADY in the SDK 3.6.0 dependency tree (the headline finding)

**Decision**: Do **not** add a new dependency version. langchain4j is already pulled transitively by
`akka-javasdk` 3.6.0. `mvn -o dependency:list` shows, among others:

- `dev.langchain4j:langchain4j:jar:1.15.0:compile` — carries `store.embedding.inmemory.InMemoryEmbeddingStore`.
- `dev.langchain4j:langchain4j-core:jar:1.15.0:compile` — `EmbeddingModel`, `EmbeddingStore`,
  `data.embedding.Embedding`, `data.segment.TextSegment`, `store.embedding.{EmbeddingSearchRequest,
  EmbeddingMatch, EmbeddingSearchResult}`.
- `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:jar:1.15.0-beta25:runtime` — the
  quantized in-process model `model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel`.
- `dev.langchain4j:langchain4j-embeddings:jar:1.15.0-beta25:runtime` + `com.microsoft.onnxruntime`
  (native inference).

**The ONNX model is packaged IN the jar** — `unzip -l` on the `-q` artifact shows
`all-minilm-l6-v2-q.onnx` (~22 MB) and `all-minilm-l6-v2-q-tokenizer.json` (~712 KB). So embedding is
**fully offline, no download, no API key** — it fits this sandbox's ethos exactly.

**Only change needed**: promote scope so Scala *source* can reference the classes. Add two explicit
`compile` dependencies at the **SDK-aligned versions** (zero version conflict):

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-embeddings-all-minilm-l6-v2-q</artifactId>
  <version>1.15.0-beta25</version>   <!-- was runtime via SDK; make it compile -->
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j</artifactId>
  <version>1.15.0</version>          <!-- already compile transitively; make our direct use explicit -->
</dependency>
```

**Rationale**: Satisfies Constitution Principle I's dependency justification — no new stack, only
scope/explicitness on artifacts the SDK already ships. Making the direct dependency **explicit**
(rather than relying on it being transitively compile-scoped) is the Maven-correct way to depend on a
type we reference in source.

**Alternatives considered**:
- *MongoDB Atlas + OpenAI embeddings* (the Ask-Akka sample stack) — faithful to production but needs
  external infra + API key; breaks offline. Rejected (user decision).
- *Akka component-augmented "RAG"* (retrieve from a View/Entity, no embeddings) — no new dep, but
  mechanically ≈ cap-3's canned lookup; does not demonstrate *semantic* retrieval. Rejected (user
  decision: show real embeddings).
- *Add `langchain4j-embeddings-all-minilm-l6-v2` (non-`-q`, full precision)* — the SDK bundles the
  **quantized** `-q` variant; matching it avoids pulling a second model artifact/version. Chosen `-q`.

**Open risk**: `AllMiniLmL6V2QuantizedEmbeddingModel` loads a 22 MB model at construction. We build
`KnowledgeStore` **once** at startup (eager DI), so this is a one-time cost paid at service/TestKit
startup, not per request. Verify test-suite startup remains acceptable.

**VERIFIED (implementation T007/T008)**: deps resolve and compile with no version conflict; the ONNX
model is genuinely in-jar (offline confirmed). Startup cost: the model load dominates the
`KnowledgeStoreTest` suite at ~5 s for 4 tests (all sharing one seeded store); subsequent `retrieve`
calls are sub-second (a 4-test class after warm-up runs in ~0.15 s). One-time and acceptable.

---

## R2 — Retrieval design: `KnowledgeStore`, direct embed-and-search (no RetrievalAugmentor plumbing)

**Decision**: `KnowledgeStore` (plain Scala class, **not** a component) holds:
- an `EmbeddingModel` = `new AllMiniLmL6V2QuantizedEmbeddingModel()`,
- an `InMemoryEmbeddingStore[TextSegment]`,
seeded at construction: for each `Passage`, embed its text and `store.add(embedding, TextSegment.from(text, Metadata.from("source", source)))`.

`retrieve(question: String, k: Int): List[Retrieved]` embeds the question, builds an
`EmbeddingSearchRequest` (maxResults = k, optional minScore), calls `store.search(...)`, and maps each
`EmbeddingMatch[TextSegment]` to `Retrieved(source, text, score)`.

**Rationale**: The Ask-Akka sample uses `EmbeddingStoreContentRetriever` + `DefaultRetrievalAugmentor`
+ `ContentInjector`. That machinery earns its keep with large corpora and prompt templating; for a
demo it is indirection. Direct `embed` + `store.search` is flatter (Constitution IV) and makes the
*deterministic retrieval* the test asserts trivially observable. We do our own prompt augmentation in
`DocsAgent` (a labeled-passages block), which is clearer to read and to teach.

**Alternatives**: `EmbeddingStoreContentRetriever`/`RetrievalAugmentor` (heavier, sample-faithful) —
kept as a documented "production would use this" note, not used.

---

## R3 — Grounding & citation: bare-`String` agent + deterministic endpoint-side citation

**Decision**: `DocsAgent.ask(question: String): Effect[String]` — the agent returns just the answer
text. It is called by the endpoint **after** retrieval; the endpoint passes the retrieved passages
into the agent (see R4 for how the passages reach the agent) and, on reply, decides citations
**deterministically**:
- if the reply is the decline sentinel (`I don't know` / configured constant) → `citedSources = []`
  (FR-005: a decline cites nothing);
- otherwise → `citedSources =` the distinct source labels of the passages that were retrieved.

**Rationale**:
- Keeps the agent wire type a **bare `String`** (like cap-4) — no Java-shaped `Result` crossing the
  internal mapper (two-mapper boundary §3 stays trivial).
- Citations are **ground truth from retrieval**, not model self-report — directly avoids cap-7's D6
  finding (small models under-report what they used). The passages that grounded the answer are
  exactly the ones we retrieved and injected, so endpoint-side citation is both simpler and *more*
  accurate than asking the model.
- The decline **sentinel** is the one signal we do take from the model (grounded vs not); it is easy
  to instruct and easy to mock in tests.

**Alternatives**:
- *Structured `Result{answer, citedSources}`* (model authors citations) — Java-shaped result +
  unreliable self-report (D6). Rejected.
- *Always cite retrieved sources* — would cite on the decline path too, violating FR-005. Rejected.

**Risk**: sentinel matching must be robust (case/trailing punctuation). Mitigate: instruct an exact
sentinel and match on a normalized prefix; tests pin both branches.

---

## R4 — How retrieved passages reach the agent, and where retrieval is invoked

**Decision**: The **agent** owns retrieval via its injected `KnowledgeStore`. `DocsAgent` is
constructor-injected with `KnowledgeStore`; inside `ask`, it retrieves top-k, builds the augmented
user message (system message = "answer only from the sources below; else reply `<sentinel>`"; user
message = the question + a labeled passages block), and replies. To let the **endpoint** compute
citations deterministically, the endpoint independently calls `knowledgeStore.retrieve(...)` for the
same question (retrieval is deterministic, so both see the same passages) **or** — preferred to avoid
double work — the agent returns only the answer and the **endpoint** is the sole retriever, passing
the passages to the agent.

**Chosen shape (single retrieval, endpoint-driven):** the **endpoint** injects `KnowledgeStore`,
retrieves once, and calls the agent with an augmented request carrying the passages; the agent just
grounds+answers. Endpoint then cites from the passages it retrieved. This keeps retrieval in one place
(endpoint) and citation trivially consistent.

- **Agent input** therefore is **not** a bare `String` but a small request `{question, passages}`. Per
  the two-mapper boundary (§3), an agent `Request` crossing the internal mapper must be **Java-shaped**
  → `DocsAgent.Request` is a Jackson-annotated Scala case class (like cap-1's `GreetingAgent.Request`),
  passages as a `java.util.List` of a Java-shaped `Passage` (source, text). Agent **output** stays a
  bare `String`.

**Rationale**: one retrieval per request; citations are exactly the injected passages; the model never
authors citations. Cost: reintroduces one Java-shaped `Request` (acceptable, well-trodden since cap-1).

**Alternative (agent-driven retrieval + endpoint re-retrieval for citations):** two retrievals per
request, relies on determinism to stay consistent — wasteful and fragile. Rejected. **Interop
consequence:** because retrieval is endpoint-driven, `KnowledgeStore` is injected into the **endpoint**
(and not necessarily the agent) — DI target is the endpoint. See R5.

---

## R5 — Dependency injection of a Scala utility (interop question #1, to VERIFY in the spike)

**Decision**: Provide `KnowledgeStore` as a singleton via the existing `Bootstrap` (`@Setup
ServiceSetup`) by adding `override def createDependencyProvider(): DependencyProvider`. The provider's
`getDependency(cls)` returns the pre-built `KnowledgeStore` when `cls == classOf[KnowledgeStore]`
(else `null`, per the SDK contract). The SDK then constructor-injects it into the `DocsEndpoint`
(and/or `DocsAgent`). Build the `KnowledgeStore` **once**, eagerly, inside `createDependencyProvider`
(seeds embeddings at startup).

**Rationale**: This is the SDK's sanctioned custom-dependency path (exactly the Ask-Akka `Knowledge`
bootstrap, in Java). **New interop territory for this project** — prior caps injected only the
SDK-provided `ComponentClient`. The open question is purely whether the Java `DependencyProvider`
SAM/interface is cleanly implementable and wired from **Scala**; expected yes (it is Class-keyed, no
method-ref wall — like every §5/§7 finding). **Verify in T00x spike** (compile + a trivial injected
call) before building on it.

**Alternatives**: a Scala `object KnowledgeStore` singleton referenced directly (no DI) — works, but
(a) sidesteps the interop lesson we want to document, and (b) an `object`'s eager init timing is less
controlled than DI construction at startup. We use DI deliberately to explore it.

**Risk**: if Scala DI misbehaves, fallback is the `object` singleton (documented), and the interop note
becomes "DI of a custom dependency does/doesn't work from Scala" — either way a finding.

**VERIFIED (implementation T007)**: it works. The Scala `override def createDependencyProvider():
DependencyProvider` returning an anonymous `new DependencyProvider: override def getDependency[T](cls:
Class[T]): T` compiles cleanly and injects `KnowledgeStore` into `DocsEndpoint`'s constructor — proven
by the passing endpoint integration tests (which exercise the injected store's real retrieval). No
method-ref wall, as predicted. The fallback `object` singleton was not needed. **Finding: custom
constructor DI is Scala-clean on this SDK — the `DependencyProvider` interface is `Class`-keyed, so it
joins the Agent/AutonomousAgent/Task clients on the Scala-friendly side of the wall.**

---

## R6 — Testing split: retrieval offline & deterministic; grounded answer live/mocked

**Decision** (mirrors caps 4/6/9):
- **Retrieval is proven OFFLINE and deterministically.** `KnowledgeStoreTest` (unit, no TestKit
  needed — `KnowledgeStore` is a plain class) embeds a corpus and asserts that a **paraphrased**
  in-corpus query returns the expected source as the top match (semantic, not keyword), and that two
  distinct queries return *different* top sources (SC-001/SC-002). This is the real RAG proof and it
  needs no model.
- **The grounded answer is mocked** in `DocsEndpointIntegrationTest` via `TestModelProvider`: fix the
  model reply for an in-corpus question → assert 200 + answer + **citations = retrieved sources**; fix
  the reply to the **decline sentinel** → assert 200 + **no citations** (SC-003). Validation-first 400
  and unknown-property tolerance as in prior caps.
- **End-to-end grounding is proven LIVE** (Ollama/Gemini) in the quickstart: a paraphrased in-corpus
  question yields a grounded, correctly-cited answer; an out-of-corpus question yields an honest
  decline. Documented, not part of `mvn verify`.

**Rationale**: embeddings are deterministic in-process, so — unlike model output — retrieval *is*
faithfully testable offline (the opposite of cap-7's D9 delegation-mock limitation). This is the clean
counter-example: **RAG's retrieval half is fully offline-verifiable; only the generative half needs a
live/mocked model.**

**Alternatives**: mocking embeddings — pointless and less honest; the real model is tiny and offline.

---

## Resolved / open

- **Resolved**: dependency stack (R1), retrieval API (R2), grounding+citation (R3), agent I/O shape and
  retrieval locus (R4), DI mechanism (R5), test split (R6).
- **To verify in the implementation spike (first tasks)**: R5 (Scala `DependencyProvider` wiring
  compiles and injects) and R1's startup-cost note (ONNX load time in the test suite).
- **Deferred (out of scope, noted)**: durable/runtime indexing via a Workflow (Java-only §4) — we seed
  at bootstrap; `EmbeddingStoreContentRetriever`/`RetrievalAugmentor` production plumbing (R2).
