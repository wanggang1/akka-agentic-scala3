# Tasks: RAG-Grounded Q&A (cap-8, feature 010)

**Input**: Design documents from `/specs/010-rag-grounded-qa/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R6), data-model.md, contracts/docs-endpoint.md

**Tests**: INCLUDED (Constitution III + spec success criteria SC-001..SC-006).

**Organization**: by user story. US1 + US2 are both P1 (grounded answer / honest decline) and share the
agent + endpoint; US3 (P2) is the validation-first + isolation contract.

## Path Conventions
- Scala sources: `src/main/scala/com/gwgs/akkaagentic/docs/{domain,application,api}/`
- Shared: `src/main/scala/com/gwgs/akkaagentic/application/Bootstrap.scala`, `pom.xml`,
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`
- Tests: `src/test/scala/com/gwgs/akkaagentic/docs/{domain,application,api}/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: dependencies + package skeleton + descriptor wiring so components are discoverable.

- [X] T001 Add two SDK-aligned `compile` dependencies to `pom.xml` (research R1):
  `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.15.0-beta25` and
  `dev.langchain4j:langchain4j:1.15.0`. Run `mvn -o compile` to confirm they resolve offline (already in
  the local repo via the SDK) with no version conflict.
- [X] T002 Create the `docs` package skeleton (empty dirs / package objects as needed):
  `src/main/scala/com/gwgs/akkaagentic/docs/{domain,application,api}/`.
- [X] T003 Register the two components in the descriptor
  `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`:
  add `DocsAgent` under `agent` and `DocsEndpoint` under `http-endpoint`. (`KnowledgeStore`,
  `KnowledgeCorpus`, `AskQuestion`, `Passage` are NOT components.)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the retrieval engine + the two verify-early interop spikes (R1 ONNX load, R5 Scala DI).
No user story can proceed until retrieval works and DI is proven.

**⚠️ CRITICAL**: complete before any user-story phase.

- [X] T004 [P] Create pure-Scala domain: `docs/domain/Passage.scala` (`case class Passage(source, text)`)
  and `docs/domain/KnowledgeCorpus.scala` (`object` with `val passages: List[Passage]` — a handful of
  self-referential passages about caps 1–9 + interop findings, each a distinct `source` label). No Akka.
- [X] T005 [P] Create `docs/domain/AskQuestion.scala` — `validate(question: Option[String]):
  Either[String, AskQuestion]` (`map(_.trim).filterNot(_.isBlank).map(AskQuestion(_)).toRight(...)`).
- [X] T006 Implement `docs/application/KnowledgeStore.scala` — holds
  `AllMiniLmL6V2QuantizedEmbeddingModel` + `InMemoryEmbeddingStore[TextSegment]`, seeded from
  `KnowledgeCorpus` at construction; `Retrieved(source, text, score)` + `retrieve(question, k):
  List[Retrieved]` (embed → `EmbeddingSearchRequest` → `store.search` → map matches). (Depends on T004.)
- [X] T007 **Spike (R1 + R5)** — verify the interop before building on it:
  (a) add `override def createDependencyProvider(): DependencyProvider` to `Bootstrap.scala` returning a
  provider that hands back a single eagerly-built `KnowledgeStore`; (b) confirm `mvn -o compile` builds
  the Scala `DependencyProvider`; (c) confirm the ONNX model loads at startup and one `retrieve(...)`
  call returns matches (a throwaway `main`/log or the T008 test). Record the outcome (DI works from
  Scala? startup cost?) as a note to fold into research R5/R1.
- [X] T008 [P] Offline retrieval test `src/test/scala/.../docs/application/KnowledgeStoreTest.scala`
  (**the RAG proof, SC-001/SC-002/SC-004**): construct `KnowledgeStore`, assert a *paraphrased*
  in-corpus query returns the expected `source` as the top match (semantic, not keyword), and that two
  distinct queries return *different* top sources. Deterministic, no model. (Depends on T006.)

**Checkpoint**: retrieval is real, deterministic, offline-proven; DI wiring compiles.

---

## Phase 3: User Story 1 — Grounded answer citing the right passage (P1) 🎯 MVP

**Goal**: an in-corpus question returns an answer grounded in retrieved passages + correct citation.

**Independent Test**: `POST /ask` with a paraphrased in-corpus question → 200, answer reflects the
corpus fact, `citedSources` names the right passage.

- [X] T009 [US1] Implement `docs/application/DocsAgent.scala` — request-based `Agent`,
  `@Component(id="docs-agent", ...)`. Java-shaped `DocsAgent.Request(question, passages: java.util.List[
  DocsAgent.Passage])` + Java-shaped `DocsAgent.Passage(source, text)`; `DontKnow` sentinel constant.
  `ask(request): Effect[String]` — system message "answer only from the sources; else reply exactly
  `<DontKnow>`"; user message = question + labeled passages block; `.onFailure(_ => DontKnow)`.
- [X] T010 [US1] Implement `docs/api/DocsEndpoint.scala` — `@HttpEndpoint`, `@Acl(INTERNET)`; inject
  `ComponentClient` + `KnowledgeStore`. DTOs `AskRequest(Option[String])` (`@JsonIgnoreProperties`),
  `AskReply(answer, citedSources: List[String])`. `POST /ask` happy path: validate → retrieve top-k →
  `dynamicCall[Request,String]("docs-agent")` → cite `passages.map(_.source).distinct` → 200. (Decline
  branch + validation added in US2/US3.)
- [X] T011 [US1] Endpoint integration test (grounded path) in
  `src/test/scala/.../docs/api/DocsEndpointIntegrationTest.scala`: register `TestModelProvider` for
  `DocsAgent`, `fixedResponse` a grounded answer, `POST /ask` a paraphrased in-corpus question → assert
  200, answer present, `citedSources` non-empty and contains the expected source (SC-001).

**Checkpoint**: MVP — grounded, cited answers work end-to-end offline.

---

## Phase 4: User Story 2 — Honest "I don't know" for out-of-corpus (P1)

**Goal**: when the retrieved context doesn't support an answer, the model declines and NO citation is
returned (FR-004/FR-005, SC-003).

**Independent Test**: `POST /ask` an out-of-corpus question (model returns the sentinel) → 200, decline
text, `citedSources` empty.

- [X] T012 [US2] Add the decline branch to `DocsEndpoint` (T010 file): if the agent reply equals the
  `DocsAgent.DontKnow` sentinel (normalized prefix match), set `citedSources = List.empty`; else cite as
  in US1.
- [X] T013 [US2] Decline test in `DocsEndpointIntegrationTest`: `fixedResponse` the sentinel → `POST
  /ask` → assert 200, decline text, `citedSources` empty (SC-003).

**Checkpoint**: both P1 stories work — grounded answers cite, declines don't fabricate or cite.

---

## Phase 5: User Story 3 — Validation-first & per-request isolation (P2)

**Goal**: blank/absent question → 400 before retrieval/model; unknown fields tolerated; requests
independent (FR-006/FR-007/FR-008, SC-005).

**Independent Test**: blank/whitespace/missing question → 400 with no model call; extra field tolerated;
two unrelated questions don't influence each other.

- [X] T014 [US3] Add validation-first guard to `DocsEndpoint` (T010 file): `AskQuestion.validate(...)`
  at the top of `POST /ask` — `Left` ⇒ `HttpResponses.badRequest(msg)` with no retrieval/model call.
- [X] T015 [P] [US3] Validation unit test `src/test/scala/.../docs/domain/AskQuestionTest.scala`:
  present/blank/whitespace/absent cases → `Right`/`Left("question must not be blank")`.
- [X] T016 [US3] Endpoint validation + tolerance tests in `DocsEndpointIntegrationTest`: blank question
  → 400 (assert no model needed — omit `responseBodyAs`, per the httpClient failure-status pattern);
  unknown extra property tolerated → still answered; (optional) two distinct questions each return their
  own grounded answer, proving isolation (SC-005/FR-007/FR-008).

**Checkpoint**: full request contract matches prior capabilities.

---

## Phase 6: Polish & Cross-Cutting

- [X] T017 [P] Add `KnowledgeCorpus`/`Passage` unit sanity test (distinct source labels; non-empty text)
  in `src/test/scala/.../docs/domain/` (guards corpus integrity).
- [X] T018 Run full `mvn verify` — confirm all cap-8 tests green and no regression in caps 1–9; note the
  one-time ONNX load cost at suite startup.
- [X] T019 [P] README: add cap-8 to the project-layout block, a "Scala interop notes §10" entry (R1 the
  bundled RAG stack, R5 Scala DI, R3 deterministic citation, R6 the D9 counter-example), and a
  "Capability 8 — POST /ask" walkthrough with curl (grounded + decline + 400). Fold the T007 spike
  outcome into research R5/R1.
- [X] T020 **Live verification** (Ollama `qwen3:8b` or Gemini): paraphrased in-corpus question → grounded
  + correctly cited; a *different* in-corpus question cites a different source (SC-002); out-of-corpus →
  honest decline, no citation (SC-003); blank → 400 (SC-005). Record results in quickstart/README.
- [X] T021 Update memory (`akka-agentic-exploration-roadmap`) + `ROADMAP.md`/`FINDINGS.md` with cap-8
  status and the R1/R5 findings.

---

## Dependencies & Execution Order

- **Setup (P1)**: T001 → T002 → T003. T001 blocks compilation of `KnowledgeStore`.
- **Foundational (P2)**: T004/T005 [P]; T006 depends on T004; T007 depends on T006 (+ Bootstrap); T008
  depends on T006. **Blocks all user stories.**
- **US1 (P3)**: T009 → T010 → T011 (needs Foundational: KnowledgeStore + DI).
- **US2 (P4)**: T012 → T013 (edits US1's endpoint + test files; do after US1).
- **US3 (P5)**: T014 (edits endpoint), T015 [P], T016. Independent of US2; can run after US1.
- **Polish (P6)**: after all stories.

### Within stories
- Tests after the code they exercise (offline retrieval test T008 is foundational — it's the RAG proof).
- US2 and US3 both edit `DocsEndpoint.scala`/`DocsEndpointIntegrationTest.scala` → sequence them (not [P]
  against each other).

### Parallel opportunities
- T004 ∥ T005 (different domain files).
- T008 ∥ T009 once T006 lands (retrieval test vs agent — different files), though both gate the endpoint.
- T015 ∥ T017 ∥ T019 (different files).

---

## Implementation Strategy

- **MVP** = Phases 1–3 (grounded, cited answer). Stop and validate `POST /ask` on an in-corpus question.
- **Increment**: add US2 (decline) → US3 (validation/isolation) → Polish (README + live + memory).
- **De-risk first**: T007 spike proves the two interop unknowns (Scala `DependencyProvider`; ONNX load)
  before the agent/endpoint are built on them — if DI misbehaves, fall back to a Scala `object`
  singleton (research R5) and record the finding.

## Notes
- Two-mapper boundary: `DocsAgent.Request`/`Passage` Java-shaped; agent output bare `String`; HTTP DTOs
  idiomatic (data-model.md).
- Citations are endpoint-side ground truth from retrieval, not model self-report (R3) — the decline
  sentinel is the only model-authored signal.
- Commit after each phase (or logical group); keep `.env` out of every commit (`git check-ignore .env`).
