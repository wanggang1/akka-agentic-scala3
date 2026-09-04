# Feature Specification: Agent Guardrails — runtime-enforced checks around a model call (cap-12)

**Feature Branch**: `014-agent-guardrails`
**Created**: 2026-09-03
**Status**: Draft
**Input**: User description: "Capability 12 — agent guardrails: runtime-enforced input/output validation on an existing agent. Add Akka guardrails (`TextGuardrail`, configured under `akka.javasdk.agent.guardrails`) to this Scala 3 / Akka Java SDK learning sandbox. The headline deliverable is a RESOLVED SCALA-INTEROP FINDING, on equal footing with the feature. The interop question is a new axis: guardrails are instantiated reflectively by the runtime from a config class-name string, so this is the BYTECODE-SHAPE axis (cap-11 R2's hazard class), not the ComponentClient method-reference wall. Guard cap-8's DocsAgent rather than inventing a new feature surface. Two guardrails: the built-in SimilarityGuard for jailbreak on model-request, and a custom Scala guardrail on model-response. Demonstrate report-only true vs false. Tests must run fully offline."

## Context

This is capability 12 in a learning sandbox that explores Akka agentic features on Scala 3 over the
Java-first Akka SDK, one concept at a time. Capabilities 1–11 covered agents, workflows, autonomous
agents, session memory, human-in-the-loop, two flavours of delegation, RAG, both sides of MCP, and a
CQRS read model. **Governance — constraining what may reach a model and what a model may return —
has never been built here.** Cap-12 closes that gap.

Three things make this capability distinctive in the series:

1. **It probes the project's *second* interop axis, not the first.** Every capability up to cap-10
   was framed by one question: *is this API keyed on a `Class`/`String` (Scala-fine) or on a Java
   method reference (Scala-impossible)?* Cap-11 discovered a second, independent axis — **bytecode
   shape**: the SDK reflects on a class and expects a particular *form* (a `public static` nested
   class with a zero-arg constructor), which Scala's inner-class encoding does not produce.
   Guardrails sit squarely on that second axis and nowhere near the first. A guardrail is named in
   configuration by class-name **string** and built by the runtime through reflection; there is no
   client and no lambda anywhere. So the question is not *"can Scala call this?"* but **"does a
   Scala class compile to the shape the runtime's reflective constructor lookup expects?"** —
   cap-11's hazard class, on a mechanism that has nothing to do with Views.

2. **It is the first capability whose machinery wraps a model call rather than making one.** Every
   prior capability *invoked* something. A guardrail is invoked *by the runtime*, around an
   interaction our code already performs — the first time this project registers behaviour that the
   platform calls back into. That inversion is the reason it is worth building: it exercises a
   registration path (config-declared, reflectively constructed, runtime-enforced) that none of the
   eleven previous capabilities touched.

3. **It is the honest counterpart to a limitation cap-8 documented and did not fix.** Capability 8's
   grounding is a *soft* constraint: `DocsAgent` is *instructed* to answer only from the supplied
   passages or reply with a decline sentinel, but nothing enforces it — an LLM may ignore the
   instruction and blend in its own knowledge, and cap-8's README says so plainly. A guardrail is
   the mechanism that can turn some of that instruction into enforcement, and — just as usefully —
   the mechanism that will show exactly **how much of it cannot be enforced this way** (see
   Assumptions).

The target already exists. Capability 8's `DocsAgent` (`POST /ask`) answers a question grounded in
passages retrieved by the endpoint, or declines. Cap-12 adds governance **around** it and changes
neither its retrieval, its prompt, nor its HTTP contract for well-behaved traffic.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A hostile prompt never reaches the model (Priority: P1)

An operator needs assurance that prompt-injection and jailbreak attempts are stopped by the platform
rather than by each developer remembering to call a validation library. A caller submits a question
crafted to override the assistant's instructions ("ignore all previous instructions and…"). The
request is rejected **before any model call happens**, and the rejection is recorded with a name and
a category an auditor can read.

**Why this priority**: This is the whole point of runtime-enforced governance — a check that cannot
be bypassed, forgotten, or misconfigured by an individual feature. It is also the only story that
delivers value entirely on its own: it needs no custom code, only configuration, so it is a viable
standalone slice.

**Independent Test**: Submit a known jailbreak-style question to `POST /ask` and assert the response
is a distinct refusal (not an answer, and not the ordinary "I don't know" decline), and that no model
call was made. Fully offline — the check runs before the model.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a caller submits a jailbreak-style question, **Then**
   the response is a refusal that identifies *why* it was refused, and the model is never called.
2. **Given** the service is running, **When** a caller submits an ordinary in-corpus question,
   **Then** the answer is produced exactly as in capability 8 — the guardrail is invisible to
   legitimate traffic.
3. **Given** a refusal has occurred, **When** an operator inspects the service logs, **Then** the
   guardrail's name and category appear, together with the explanation of the decision.

---

### User Story 2 - A response that breaks a hard rule is blocked (Priority: P1)

An operator needs assurance that the assistant cannot emit an answer that violates a rule the
business cares about, even when the model ignores its instructions. The assistant is required to
answer only from the local corpus; an answer that points the reader at an external web source is, by
construction, not from that corpus. Such an answer is blocked rather than returned.

**Why this priority**: This is the half of governance that no prompt can guarantee, and it is where
this capability's interop finding lives — the check is a class we author, which the runtime
constructs reflectively from configuration. Equal priority to Story 1 because request-side and
response-side enforcement are separate mechanisms and either alone is an incomplete demonstration.

**Independent Test**: Script the model to return an answer containing an external link, then assert
the caller receives a block rather than that answer. Fully offline via the test model provider.

**Acceptance Scenarios**:

1. **Given** the model returns an answer citing an external web source, **When** the caller requests
   an answer, **Then** the answer is not delivered and the caller receives a block that names the
   rule violated.
2. **Given** the model returns an ordinary grounded answer, **When** the caller requests an answer,
   **Then** the answer is delivered unchanged.
3. **Given** the model returns the honest decline sentinel, **When** the caller requests an answer,
   **Then** the decline is delivered — a decline is never mistaken for a violation.

---

### User Story 3 - A soft rule is recorded but not enforced (Priority: P2)

A compliance-minded operator wants visibility into a rule *before* enforcing it — to see how often it
would fire in production without risking a false block on a legitimate answer. The assistant is
instructed to answer in one or two sentences; a longer answer is a style violation, not a safety
one, so it is **recorded and allowed through**.

**Why this priority**: The record-versus-block distinction is the governance point of the feature
(the same declaration switches between them), and it is the safe on-ramp real teams use. It is P2
because Stories 1 and 2 already demonstrate enforcement; this adds the second mode.

**Independent Test**: Script the model to return an over-long answer and assert the caller still
receives it, while the violation appears in the observability record.

**Acceptance Scenarios**:

1. **Given** a rule is configured as record-only, **When** the model returns an answer violating it,
   **Then** the caller still receives the answer.
2. **Given** the same conditions, **When** an operator inspects the logs, **Then** the violation was
   recorded with its name, category, and explanation.
3. **Given** the same rule is switched to enforcing, **When** the model returns a violating answer,
   **Then** the caller receives a block instead — with **no code change**, only a configuration
   change.

---

### User Story 4 - Governance is declared, not wired (Priority: P3)

A developer adding a new agent to the service wants a rule to apply to it without editing the
agent's source. A rule is attached to agents by declaration — by naming the agents it covers, or by
covering every agent that carries a given role — and it takes effect without touching the agents
themselves.

**Why this priority**: It demonstrates that governance is not bolt-on, which is the architectural
claim the feature exists to test. P3 because the first three stories already deliver the enforcement
value; this proves the *attachment* mechanism.

**Independent Test**: Confirm the guarded agent's source contains no reference to any guardrail, and
that changing which agents a rule covers is a configuration-only edit.

**Acceptance Scenarios**:

1. **Given** the guarded agent's source, **When** it is inspected, **Then** it contains no reference
   to any guardrail — the rules are attached from outside.
2. **Given** an agent that a rule does not name, **When** it is exercised, **Then** the rule does not
   fire for it.

---

### Edge Cases

- **A blocked interaction must not masquerade as an honest decline.** Capability 8's agent degrades
  any failed turn to the "I don't know" sentinel. If a guardrail block travels the same path, a
  *governance event* would be silently reported to the caller as an ordinary *"the corpus doesn't
  cover this"* — indistinguishable, unauditable, and wrong. The three outcomes (answer / honest
  decline / blocked) MUST stay distinguishable to the caller.
- **Validation still runs first.** A blank or malformed question is rejected before any guardrail
  evaluates, exactly as today — governance does not displace input validation.
- **A rule that fails to load must fail loudly at startup**, not silently leave the agent
  unguarded. A misspelled class name in configuration is a governance hole; the service should
  refuse to start rather than run unprotected.
- **A rule must not fire on the empty string** or on an answer that is only whitespace.
- **What a rule can see is only text.** A rule receives the text under evaluation and nothing else —
  no question/answer pairing, no retrieved passages, no session identity. Any rule requiring that
  context is out of reach of this mechanism (see Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST evaluate every question bound for the guarded assistant against a
  jailbreak/prompt-injection rule **before** the model is called, and MUST NOT call the model when
  that rule blocks.
- **FR-002**: The system MUST evaluate every answer the guarded assistant produces against
  response-side rules **before** the answer is delivered to the caller.
- **FR-003**: Each rule MUST carry a name and a category, and MUST produce an explanation of its
  decision; name, category and explanation MUST appear in the service's observability record for
  every evaluation that blocks or records a violation.
- **FR-004**: Each rule MUST be independently configurable as **enforcing** (the interaction is
  aborted) or **record-only** (the violation is recorded and the interaction continues), switchable
  without a code change.
- **FR-005**: The caller MUST be able to distinguish three outcomes: a grounded answer, an honest
  decline, and a blocked interaction. A block MUST NOT be delivered as an answer or as a decline,
  and MUST identify the rule that blocked it.
- **FR-006**: Rules MUST be attached to agents by declaration (naming agents, or covering agents by
  role) with **no change to the guarded agent's own source**.
- **FR-007**: A rule MUST be able to read its own settings from configuration, so the same rule can
  be deployed with different thresholds or parameters without recompilation.
- **FR-008**: Well-behaved traffic MUST be unaffected: an ordinary in-corpus question MUST return
  the same answer and citations it returns today, and an out-of-corpus question MUST still return
  the honest decline.
- **FR-009**: Existing input validation MUST continue to run first — a blank or malformed question
  is rejected before any rule evaluates and before any model call.
- **FR-010**: The service MUST fail to start if a configured rule cannot be loaded, rather than
  starting with that rule silently inactive.
- **FR-011**: The jailbreak rule MUST operate offline, with no new third-party dependency and no
  network access.
- **FR-012**: Every requirement above MUST be verifiable offline, with no live model and no API key.
- **FR-013**: The capability MUST record, with evidence, whether an author-supplied rule written in
  Scala can be constructed by the runtime's reflective, configuration-driven loading — including
  **which Scala class forms work and which do not** — and MUST state whether such a rule requires an
  entry in the hand-maintained component descriptor.

### Key Entities

- **Rule (guardrail)**: A named, categorized check over a piece of text. Attributes: name, category,
  where it applies (request side or response side), which agents it covers, and whether it enforces
  or only records. Produces a pass/fail decision plus an explanation.
- **Evaluation outcome**: The result of applying one rule to one piece of text — passed or not, with
  a human-readable explanation. Consumed by the runtime (to abort or continue) and by observability.
- **Guarded interaction**: A single question-to-answer exchange with the assistant, which may be
  stopped on the way in, stopped on the way out, or completed.

## Assumptions

- **The guarded agent is capability 8's `DocsAgent` (`POST /ask`).** It is the best fit because its
  grounding is documented as a soft, unenforced instruction, so governance has something real to
  attach to. No other capability is modified.
- **Rules see text only.** The evaluation input is the text under review with no accompanying
  context, so a rule cannot compare an answer against the passages that were retrieved for it. A
  *true* grounding check is therefore **out of reach of this mechanism** — this capability enforces
  rules that are decidable from the answer text alone, and will document the limitation rather than
  fake it. This is the honest boundary of what cap-12 can fix about cap-8.
- **"An answer must not point at an external web source" is decidable from text alone** and is a
  sound proxy rule here, because the local corpus contains no external links: a link in an answer is
  evidence the model went outside its sources. This is a *proxy*, not proof of ungroundedness, and
  will be described as such.
- **A blocked interaction is reported to the caller as an unprocessable request** carrying the rule
  and category, distinct from both a `200` answer and the `200` honest decline. The exact status is
  an implementation decision for planning; the *distinguishability* is the requirement (FR-005).
- **Capability 8's failure fallback may need adjusting** so a governance block is not converted into
  the honest-decline sentinel (see Edge Cases). Whether it does is a research question for planning;
  if it does, the change is confined to how a *block* is surfaced and must not alter behaviour for
  ordinary model failures.
- **Two custom rule shapes are exercised deliberately**: one that takes its settings from
  configuration and one that takes none. The runtime's loading path tries a settings-taking form
  first and a no-settings form second, so covering both is what makes the interop finding complete
  rather than anecdotal.
- **Guardrails are not components** and therefore need no descriptor entry. This is an assumption to
  be **confirmed by evidence**, not asserted (FR-013).
- **No new dependency.** The jailbreak rule uses example data already shipped inside the SDK.
- Tests follow the project norm: offline, deterministic, no API key. Live verification is a
  supplementary smoke test, not the proof.

## Out of Scope

- Changing capability 8's retrieval, corpus, prompt, citation logic, or HTTP request contract.
- Guarding any other agent in the service (the attachment mechanism is demonstrated, not applied
  broadly).
- PII detection and data sanitization — a separate SDK feature with its own semantics.
- A semantic or model-based grounding/hallucination judge. Scoring an answer *against its sources*
  needs the sources, which this mechanism does not supply, and a model-as-judge is its own
  capability (the evaluation/LLM-judge candidate on the roadmap).
- Building a management UI, an audit store, or alerting on top of the recorded evaluations.
- Rate limiting, authentication, or any other request-level control unrelated to content.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A jailbreak-style question is refused without any model call, in 100% of the scripted
  attempts in the test suite, and the caller receives the governance outcome carrying the rule's
  explanation.

  > **Relaxed 2026-09-04, on measurement.** This criterion originally required the caller-facing
  > response to *name* the rule (`default jailbreak`) and its category. That is not achievable on
  > Akka SDK 3.6.3: application code receives the rule's **explanation and nothing else**. The
  > composed audit line naming the rule and category lives on an SPI-internal exception and reaches
  > logs, metrics and traces only (research divergence #4). The `rule` and `category` fields stay in
  > the response — a rule *we* author names itself in its own explanation — but for the SDK's own
  > `SimilarityGuard` they read `unknown`. What SC-001 asserts is therefore the part that is real:
  > the refusal happened, no model ran, and the caller can tell a block from a decline. Attribution
  > by name remains guaranteed in the service's output, which is what FR-003 and SC-007 require.
- **SC-002**: An ordinary in-corpus question returns the same answer and the same cited sources as
  before this capability — capability 8's existing tests pass unchanged.
- **SC-003**: An out-of-corpus question still returns the honest decline, and that decline is never
  reported as a governance block.
- **SC-004**: An answer violating an enforcing rule is never delivered to the caller; an answer
  violating a record-only rule always is.
- **SC-005**: Switching a rule between enforcing and record-only changes the caller-visible outcome
  with **zero** lines of code changed.
- **SC-006**: The guarded agent's source contains **zero** references to any rule, name, or
  category.
- **SC-007**: Every blocked or recorded evaluation is attributable: its name, category and
  explanation are recoverable from the service's output. (Note: for a rule the SDK owns, the
  service's *output* is the only place they are recoverable — see SC-001.)
- **SC-008**: The whole suite runs with no network access and no API key, and completes as part of
  the project's ordinary verification command.
- **SC-009**: The capability publishes a documented, evidence-backed answer to the interop question:
  which Scala class forms the runtime's reflective loading accepts, which it rejects, and why —
  including the descriptor question — recorded in the project's findings, README, and roadmap.
