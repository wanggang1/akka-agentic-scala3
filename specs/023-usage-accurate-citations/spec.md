# Feature Specification: Usage-accurate citations

**Feature Branch**: `023-usage-accurate-citations`
**Created**: 2026-10-10
**Status**: Draft
**Input**: User description: "Usage-accurate citations for the RAG ask endpoint (cap-8 / feature 010). Today the /ask endpoint cites what was retrieved (top-K=3 passages), not what the answer actually used, causing over-citation. This capability (B4) adds usage-accurate citations: the agent returns, via structured output, which source labels it actually used to compose the answer, and the endpoint cites only those. This deliberately reopens the cap-7 D6 self-report tension (the model now self-reports its sources, which cap-8 was originally designed to avoid). The capability must keep the existing ground-truth retrieval citations available for comparison/honesty, and must measure whether the local model reports its used sources reliably. Decline sentinel still cites nothing. Build it as a new surface so cap-8's /ask is left untouched."

## Context & background *(why this exists)*

Capability 8 (feature 010) answers a question grounded in a local document corpus and returns the
answer plus a `citedSources` list. Those citations are the **top-K passages that were retrieved** and
handed to the model — ordered by similarity — **not** the passages the answer actually drew on. Because
the endpoint always offers K=3 passages and always cites all three, an answer composed from a single
passage still cites the other two. Cap-8 chose this on purpose: retrieval-side citations are *ground
truth* — computed outside the model, un-fakeable — which is exactly how cap-8 sidesteps capability 7's
D6 finding that a model which self-reports its sources is unreliable (small local models under-report).

This capability (roadmap **B4**) accepts the opposite trade for a **new, parallel surface**: ask the
model which sources it actually used and cite only those. This is more honest about *usage* but
reintroduces the self-report unreliability cap-8 avoided — **a genuine tension, not a free upgrade.**
The capability's real deliverable is therefore not just the feature but a **measurement**: on this
stack (a local model via Ollama), how faithfully does the model report the sources it used, and how far
does its self-report diverge from the ground-truth retrieved set?

Cap-8's `/ask` surface, its agent, and its contract are left **completely untouched**, so no existing
caller pays for this change.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Precise citations for a focused answer (Priority: P1)

A caller asks a question that the corpus answers from a **single** passage, even though two other
passages were also retrieved as semantically near. The caller wants the response to cite only the
passage the answer actually used, not all three.

**Why this priority**: This is the whole point of B4 — it is the behavior that distinguishes this
surface from cap-8's `/ask`. Without it there is no capability.

**Independent Test**: Call the new endpoint with a question known (from cap-8's own README example,
*"what makes agent work survive a restart?"*) to answer from one passage while retrieving three. Assert
the usage-accurate citation list is a **subset** of the retrieved set and contains the one passage the
answer used, demonstrating reduced over-citation versus cap-8.

**Acceptance Scenarios**:

1. **Given** a corpus question whose answer uses one of three retrieved passages, **When** the caller
   asks the new endpoint, **Then** the response returns the grounded answer, a usage-accurate citation
   list naming only the used source(s), and the full ground-truth retrieved list alongside it.
2. **Given** the same question asked of cap-8's `/ask`, **When** compared, **Then** `/ask` still cites
   all three retrieved sources (cap-8 is unchanged) while the new surface cites fewer.

---

### User Story 2 - Honest decline still cites nothing (Priority: P1)

A caller asks a question the corpus does not cover. The assistant must decline and cite nothing, with
no fabricated sources — exactly as cap-8 does.

**Why this priority**: The honesty guarantee (FR-005 of cap-8) must not regress on the new surface. A
self-report mechanism that invents citations on a decline would be strictly worse than cap-8.

**Independent Test**: Ask an out-of-corpus question; assert the response is the decline sentinel, the
usage-accurate citation list is empty, and no source is fabricated.

**Acceptance Scenarios**:

1. **Given** an out-of-corpus question, **When** the caller asks the new endpoint, **Then** the response
   declines and cites nothing.
2. **Given** the model names source labels on a decline, **When** the response is built, **Then** the
   decline still cites nothing (a decline overrides any reported usage).

---

### User Story 3 - Measure self-report faithfulness (Priority: P2)

An evaluator (the project itself, recording findings) wants to know how reliably the local model reports
the sources it used, so the self-report-vs-ground-truth trade can be stated honestly in the docs rather
than assumed.

**Why this priority**: B4's declared purpose is to *measure* the tension it reopens, not merely to ship
a feature. The measurement is what makes the capability worth building over the two ground-truth
alternatives (lower TopK, score threshold).

**Independent Test**: Run a small set of in-corpus questions live against the local model and record,
per question, the reported used-sources, the retrieved set, and whether the reported set is a faithful
subset (no hallucinated labels, no omitted genuinely-used source where that can be judged). The finding
is written into `research.md` and the README section.

**Acceptance Scenarios**:

1. **Given** a live in-corpus question, **When** the answer is produced, **Then** the response carries
   both the self-reported used-sources and the ground-truth retrieved set, so divergence is observable
   from the response alone.
2. **Given** the model reports a source label that was **not** among the retrieved passages, **When** the
   response is built, **Then** that label is dropped from the usage-accurate citations (a source that was
   never offered cannot have been used — this preserves the floor that every cited label was at least
   genuinely retrieved).

---

### Edge Cases

- **Model names a non-retrieved label** → dropped from usage-accurate citations; it may be recorded for
  the faithfulness measurement, but it is never cited (it was never offered as context).
- **Model returns a non-decline answer but names no usable sources** (none, or only non-retrieved
  labels) → the answer is returned with an **empty** usage-accurate citation list; the ground-truth
  retrieved list is still shown (FR-007).
- **Guardrail refusal or failed model turn** → the new surface mirrors cap-8's existing behavior for
  these (a refusal is distinguishable from a decline; a failed turn is not judged as a decision). See
  Assumptions.
- **Blank or absent question** → rejected with a validation error before any retrieval or model call
  (mirrors cap-8).
- **Model emits malformed structured output** → treated as a failed turn, not a 500 (mirrors cap-8's
  failure handling).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a **new** question-answering surface that leaves cap-8's `/ask`
  endpoint, its agent, and its response contract completely unchanged.
- **FR-002**: The system MUST retrieve grounding passages from the same local corpus and hand them to
  the model as context, as cap-8 does (same retrieval, reused — not a second corpus).
- **FR-003**: The model MUST return, together with its grounded answer, the set of **source labels it
  used** to compose that answer (structured output).
- **FR-004**: The system MUST compute the usage-accurate citation list as the model-reported used-sources
  **intersected with** the actually-retrieved source labels, so a cited label is always one that was
  genuinely offered as context (a label the model names but that was not retrieved is dropped).
- **FR-005**: When the assistant declines (the corpus does not answer the question), the system MUST cite
  nothing, regardless of any sources the model may have reported — a decline overrides reported usage.
- **FR-006**: The response MUST carry **both** the usage-accurate citation list and the ground-truth
  retrieved-source list, so a caller (and the project's measurement) can see the divergence between what
  was used and what was retrieved from the response alone.
- **FR-007**: When the model returns a non-decline answer but reports no usable source labels (none, or
  only labels that were not among the retrieved passages), the system MUST return the answer with an
  **empty** usage-accurate citation list (it still shows the ground-truth retrieved list per FR-006).
  An unverifiable usage claim is treated as "no honest citation available" rather than being papered
  over with cap-8's full retrieved set — this keeps self-report failure visible, which is the point of
  the measurement (FR-010 / SC-005).
- **FR-008**: The system MUST reject a blank or absent question with a validation error before any
  retrieval or model call (mirrors cap-8 FR behavior).
- **FR-009**: Each question MUST be answered independently (no cross-question memory), as cap-8 does.
- **FR-010**: The capability MUST produce a recorded **measurement** of how faithfully the local model
  reports its used sources (subset faithfulness: no hallucinated labels; divergence from the retrieved
  set), written into the feature's research notes and the project README, so the reopened self-report
  tension is documented from evidence rather than assumed.

### Key Entities *(include if feature involves data)*

- **Grounded answer with used-sources**: the model's structured reply — a text answer plus the list of
  source labels the model claims it used (or the decline sentinel). This is the new, self-reported
  signal B4 introduces.
- **Retrieved passage**: a corpus passage offered as context, carrying its source label and similarity
  score (reused from cap-8; the score is already available). The set of these labels is the ground-truth
  retrieved list.
- **Response**: the answer, the usage-accurate citation list (reported ∩ retrieved, honoring the decline
  rule), and the ground-truth retrieved-source list, side by side.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a question whose answer uses fewer than all retrieved passages, the new surface's
  usage-accurate citation list is a strict subset of the retrieved set — i.e. it cites strictly fewer
  sources than cap-8's `/ask` would for the same question, in at least one documented example.
- **SC-002**: Every label in the usage-accurate citation list was, for that same request, among the
  retrieved passages (no cited label is one that was never offered) — in 100% of responses.
- **SC-003**: An out-of-corpus question cites zero sources and returns the decline, with no fabricated
  citation — in 100% of decline responses.
- **SC-004**: Cap-8's `/ask` behavior is unchanged: its existing tests pass without modification, and it
  still cites the full retrieved set.
- **SC-005**: The capability ships a documented measurement, over a small set of live in-corpus
  questions, of how often the model's reported used-sources are a faithful subset of the retrieved set
  (and where they diverge) — stated as evidence in the docs.

## Assumptions

- **New surface = new agent method/component.** A request-based Agent allows only one command handler and
  cap-8's `DocsAgent.ask` returns a bare String, so a usage-reporting, structured-output answer is a
  **separate** agent (new component id), leaving `docs-agent` untouched per FR-001. (Implementation
  detail; confirmed at planning.)
- **Structured output over the local model.** The model is prompted to return a structured object
  (answer + used-source labels). Prior findings note some providers reject function-calling + JSON
  structured output together; this surface has no tools, so structured output alone is expected to work,
  but the planning phase verifies the provider in use.
- **Guardrail / failed-turn parity is mirrored, not re-invented.** The new surface reuses cap-8's
  established sentinel approach for a refused interaction and a failed turn (a refusal is not a decline;
  a failed turn is not judged as a decision). Whether capability 12's jailbreak guardrail also guards the
  new agent id is settled at planning; the default is to mirror cap-8's honest-decline-on-failure so the
  new surface is never strictly less safe.
- **Measurement is offline-deterministic for correctness, live for faithfulness.** Correctness of the
  intersection/decline logic is proven offline with a deterministic mocked model; the self-report
  *faithfulness* finding (SC-005) requires a live run against the local model, as other capabilities do.
- **Endpoint path.** A distinct path (e.g. a new route alongside `/ask`) is used so cap-8's `/ask` is
  untouched; the exact path is chosen at planning.

## Out of scope

- Changing cap-8's `/ask`, its agent, or its response shape.
- The two ground-truth alternatives to over-citation (lower TopK, similarity-score threshold) — these
  are explicitly *not* B4; B4 is the self-report path.
- Retrieval-as-a-tool (model-driven retrieval) — that is cap-10's architecture, not this one.
- Multi-hop retrieval or re-ranking.
