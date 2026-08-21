# Feature Specification: RAG-Grounded Q&A

**Feature Branch**: `010-rag-grounded-qa`
**Created**: 2026-08-15
**Status**: Draft
**Input**: User description: "Capability 8 — RAG (Retrieval-Augmented Generation) demo. A grounded documentation/Q&A agent that answers questions using ONLY retrieved context from a small local knowledge corpus, demonstrating real RAG mechanics (embeddings + semantic vector similarity + retrieval-augmentation) while staying fully offline/no-API-key like the rest of this sandbox."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Grounded answer from the corpus (Priority: P1)

A user asks a question whose answer is contained in the service's knowledge corpus. The
service finds the most semantically relevant passage(s), gives them to the assistant as
context, and returns an answer that is grounded in that retrieved material — together with
a citation of which source passage(s) informed the answer.

**Why this priority**: This is the whole point of RAG — an answer the model could not reliably
give on its own becomes accurate and attributable because relevant knowledge was retrieved and
injected. Without this, there is no capability.

**Independent Test**: Submit a question that paraphrases a fact in the corpus (using different
wording than the source, to prove *semantic* retrieval, not keyword match) and confirm the
answer reflects the corpus fact and cites the correct source passage.

**Acceptance Scenarios**:

1. **Given** a corpus containing a passage about topic X, **When** the user asks about X using
   wording different from the passage, **Then** the response answers from that passage's content
   and names it as the cited source.
2. **Given** the same corpus, **When** two different in-corpus questions are asked, **Then** each
   answer cites the passage relevant to *that* question (retrieval discriminates by meaning).

---

### User Story 2 - Honest "I don't know" for out-of-corpus questions (Priority: P1)

A user asks a question the corpus does not cover. The service must not fabricate an answer; it
returns an explicit "I don't know" (or equivalent) rather than a hallucinated response.

**Why this priority**: A grounded assistant is only trustworthy if it declines when it lacks
supporting knowledge. Demonstrating *refusal* is as important as demonstrating retrieval — it is
what separates RAG from an ungrounded model guess.

**Independent Test**: Submit a question clearly outside the corpus and confirm the response is an
honest "don't know", with no fabricated facts and no misleading citation.

**Acceptance Scenarios**:

1. **Given** a corpus that does not mention topic Z, **When** the user asks about Z, **Then** the
   response states it does not know / cannot answer from the available knowledge, and cites nothing.
2. **Given** an out-of-corpus question, **When** the answer is returned, **Then** it contains no
   invented source citation.

---

### User Story 3 - Validation-first and per-request isolation (Priority: P2)

The service rejects an empty or missing question before doing any retrieval or model work, and
each request is answered independently (no state bleeds between requests).

**Why this priority**: Consistency with the rest of the service's request contract (every prior
capability rejects blank input up front) and a correctness guarantee that answers depend only on
the corpus + the current question, not on prior requests.

**Independent Test**: Submit a blank/whitespace/missing question and confirm a validation error is
returned with no retrieval or model call; submit two unrelated questions and confirm neither
influences the other's answer.

**Acceptance Scenarios**:

1. **Given** a request with a blank or absent question, **When** it is submitted, **Then** the
   service returns a validation error and performs no retrieval and no model call.
2. **Given** a request carrying an unexpected extra field, **When** it is submitted, **Then** the
   field is tolerated and the question is still answered.
3. **Given** two independent questions submitted in sequence, **When** each is answered, **Then**
   neither answer depends on the other request's content.

---

### Edge Cases

- **Corpus has weakly-related but not truly relevant content**: retrieval always returns its
  best-scoring passages, so an out-of-corpus question can still surface a low-relevance passage.
  The assistant must still decline when that passage does not actually answer the question
  (grounding is enforced by instruction, not merely by retrieval returning something).
- **Very short or ambiguous question**: the service still attempts retrieval; if nothing relevant
  is found the "don't know" path applies.
- **Question matches multiple passages**: the most relevant passages (up to a fixed small number)
  are provided; the answer may synthesize across them and may cite more than one.
- **Empty/whitespace question**: rejected by validation before retrieval.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST maintain a small, fixed knowledge corpus of discrete passages, each
  with an identifiable source label, available from service startup.
- **FR-002**: The service MUST retrieve passages by *semantic similarity* to the question (meaning,
  not exact keyword match), returning up to a fixed small number of the most relevant passages.
- **FR-003**: The service MUST provide the retrieved passages to the assistant as grounding context
  for the question.
- **FR-004**: The assistant MUST answer using only the retrieved context, and MUST return an honest
  "I don't know" when the retrieved context does not support an answer (no fabrication).
- **FR-005**: A successful response MUST include the answer and a citation of which source
  passage(s) were retrieved as grounding; an "I don't know" response MUST cite nothing.
- **FR-006**: The service MUST reject a blank or missing question with a validation error before any
  retrieval or model interaction occurs.
- **FR-007**: The service MUST tolerate unexpected extra fields in the request without failing.
- **FR-008**: Each request MUST be answered independently of other requests (no cross-request state).
- **FR-009**: Retrieval behavior MUST be deterministic for a given corpus and question, so that
  *which passages are retrieved* can be verified without invoking a live model.
- **FR-010**: The corpus, embeddings, and retrieval MUST operate fully locally with no external
  network service or API key required.

### Key Entities *(include if feature involves data)*

- **Knowledge passage**: one unit of retrievable knowledge — its text content and a source label
  used for citation.
- **Knowledge corpus**: the fixed collection of passages the service can retrieve from.
- **Question**: the user's validated, non-blank query.
- **Grounded answer**: the assistant's response plus the list of cited source labels (empty when the
  assistant declines).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A question that paraphrases an in-corpus fact (different wording than the source)
  returns an answer grounded in that fact and citing the correct source passage.
- **SC-002**: Two distinct in-corpus questions each retrieve and cite the passage relevant to that
  question (retrieval discriminates by meaning, not a single fixed passage).
- **SC-003**: A clearly out-of-corpus question returns an explicit "I don't know" with no fabricated
  facts and no citation.
- **SC-004**: Which passages are retrieved for a given question is verifiable deterministically and
  offline (without a live model), and grounded end-to-end answers are demonstrated live.
- **SC-005**: A blank or missing question is rejected with a validation error and triggers no
  retrieval and no model call.
- **SC-006**: The service runs and answers with no network access and no API key configured.

## Assumptions

- **Small curated corpus, seeded at startup**: the demo uses a handful of hand-written passages
  loaded when the service starts (not a large ingested document set, and not re-indexed at runtime).
  This is sufficient to demonstrate semantic retrieval, grounding, and refusal.
- **Corpus subject matter**: the passages describe this project's own capabilities and Akka/Scala
  interop findings (a self-referential corpus), so in-corpus vs out-of-corpus questions are easy to
  construct and verify. The exact subject is not load-bearing to the capability.
- **Synchronous request/response**: a single question yields a single answer in one round-trip
  (no long-running poll), because retrieval + one grounded model call is fast.
- **Fixed small top-k**: a fixed, small number of passages are retrieved per question (exact number
  is an implementation tuning detail, not a user-facing contract).
- **Learning-sandbox intent**: like prior capabilities, this feature also exists to surface and
  document Scala-on-Java-first-SDK interop findings (dependency injection of a retrieval utility into
  a Scala agent; the serialization boundary between the HTTP surface and component payloads; adding a
  local embedding dependency aligned to the SDK's bundled versions). These are recorded as design
  notes, not user-facing requirements.

## Out of Scope

- Runtime document ingestion / re-indexing (adding to the corpus while running); the corpus is fixed
  at startup. A durable indexing pipeline (the production pattern) is explicitly deferred.
- External vector databases or hosted embedding/model APIs.
- Multi-turn conversational memory over retrieved context (that is capability 4's concern; here each
  question is answered independently).
- Streaming the answer token-by-token.
