# Feature Specification: LLM-as-judge evaluation — judging a grounded answer against its sources (cap-13)

**Feature Branch**: `015-llm-judge-evaluation`
**Created**: 2026-09-05
**Status**: Draft
**Input**: User description: "Capability 13 — LLM-as-judge evaluation of capability 8's grounded RAG answers. Cap-8's grounding is a SOFT constraint: the agent is instructed to answer only from the supplied passages, and nothing verifies that it did. Cap-12's guardrails could not close that gap — `TextGuardrail.evaluate` receives the answer text alone, with no question and no passages, so a grounding check is structurally out of reach on that interface. An evaluator sees all three. Use the SDK's built-in `HallucinationEvaluator` (query, referenceText, answer) and author one custom evaluator in Scala covering a criterion the built-ins do not: whether an 'I don't know' decline was APPROPRIATE. Evaluation must not change `POST /ask`'s contract or latency. The headline deliverable is a resolved interop finding: whether a Scala caller can invoke the SDK's built-in evaluator agents (the docs use `.method(ToxicityEvaluator::evaluate)` — the project's known method-reference wall — but these are Agents, and `dynamicCall(componentId)` is the one documented escape hatch), and whether a custom evaluator agent authored in Scala works end to end. Secondary: whether evaluation is faithfully testable offline with `TestModelProvider`, given the judge itself calls a model and the built-in evaluators are SDK-owned classes."

## Context

This is capability 13 in a learning sandbox that explores Akka agentic features on Scala 3 over the
Java-first Akka SDK, one concept at a time. Capabilities 1–12 covered agents, workflows, autonomous
agents, session memory, human-in-the-loop, two flavours of delegation, RAG, both sides of MCP, a
CQRS read model, and runtime governance. **Nothing in the project has ever judged the quality of a
model's output.** Every capability so far asserted *structure* — that a call happened, that a
citation matched what was retrieved, that a block was not a decline — and deliberately stopped short
of asserting *substance*, because substance is not deterministic. Cap-13 is where the project
confronts that.

Four things make this capability distinctive in the series:

1. **It reverses cap-12's interop axis and returns to the project's first one.** A guardrail was
   registered by *configuration*, constructed reflectively from a class-name string, and got **no**
   descriptor entry — cap-11's bytecode-shape axis. An evaluator is the opposite in every respect:
   it is an ordinary request-based **Agent**. The SDK's built-ins extend `Agent` and carry their own
   component ids; one we author is a component like any other and takes a descriptor line. So the
   governing question is once again the **method-reference wall** — and with a twist the project has
   never tested. The documentation calls a built-in evaluator with a Java method reference
   (`.method(ToxicityEvaluator::evaluate)`), which is exactly the form Scala cannot produce. The
   agent client's `dynamicCall(componentId)` is the one documented escape hatch — but every previous
   use of it in this project targeted an agent **we declared**. Whether it reaches an agent the
   **SDK owns and the runtime registers** is a genuinely open question, and answering it settles
   whether the SDK's own evaluator library is usable from Scala at all.

2. **It is the first time a model's output is the thing under test rather than the thing delivered.**
   Every prior capability used a model to produce something a caller reads. Here the model's output
   is a *verdict about other output* — a test oracle. That inverts the project's usual offline story:
   twelve capabilities have been provable offline precisely because the deterministic half
   (retrieval, routing, validation, blocking) was separable from the generative half. An evaluator
   is generative *by construction*. Establishing what can still be proven offline, and stating
   plainly what cannot, is a first-class outcome here rather than a caveat at the end.

3. **It closes — or honestly measures — the exact gap two earlier capabilities documented and could
   not fix.** Capability 8's README says outright that grounding is instruction-following, a soft
   constraint, not a runtime guarantee. Capability 12 tried and discovered a *structural* reason it
   could not help: `TextGuardrail.evaluate` receives the answer text alone. An evaluator's request
   carries the question, the reference text, and the answer together — the three inputs a grounding
   judgement requires. Cap-13 is therefore not a new idea bolted on; it is the designated successor
   to a limitation this project already wrote down twice.

4. **It exercises both halves of the evaluator story — one the SDK ships, one we write.** The
   built-in judge proves the *calling* path (an SDK-owned agent, from Scala). The custom judge
   proves the *authoring* path (a Scala class whose result type implements the SDK's
   `EvaluationResult`, so its verdicts are captured into the platform's metrics and traces). Either
   alone would leave half the interop question open.

The target already exists and is not being changed. Capability 8's `POST /ask` retrieves passages,
has an assistant answer from them or decline, and cites what was retrieved. Cap-13 judges those
answers. It does not gate them.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A developer learns whether an answer was actually grounded (Priority: P1)

A developer tuning the assistant's prompt or corpus needs to know whether an answer stayed within
the passages it was given, or drifted into the model's own knowledge. Today the only evidence is
reading the answer and judging by eye. The developer submits a question through an evaluation
surface; the system produces the answer exactly as the ordinary question path would, then reports a
verdict on whether that answer is supported by the passages that grounded it, with a written
explanation of the reasoning.

**Why this priority**: This is the capability's reason to exist — the soft-grounding gap capability 8
documented and capability 12 could not reach. It is also a viable standalone slice: it needs no
authored judge, only the ability to call the one the platform already ships, which is precisely the
headline interop question.

**Independent Test**: Submit an in-corpus question and an answer that contradicts the passages;
assert the verdict distinguishes them, and that the reference text the judge saw is the same set of
passages that were retrieved.

**Acceptance Scenarios**:

1. **Given** a question the corpus covers, **When** the assistant answers from the retrieved
   passages, **Then** the grounding verdict passes and the explanation refers to the reference
   material.
2. **Given** the same question, **When** the answer asserts something the passages do not support,
   **Then** the grounding verdict fails and the explanation says what was unsupported.
3. **Given** any evaluated question, **When** the developer inspects the verdict, **Then** the
   question, the reference text, and the answer that were judged are all recoverable — a verdict is
   never reported without the evidence it was formed from.

---

### User Story 2 - A developer learns whether a decline was appropriate (Priority: P1)

Capability 8's honest decline is currently correct *by construction* — the tests assert that an
out-of-corpus question produces the decline sentinel, never that declining was the *right* call. Two
failure modes are therefore invisible: declining when the passages did in fact contain the answer (a
false decline, the assistant being uselessly cautious), and answering when they did not (the failure
grounding is supposed to prevent). The developer needs a verdict on the decline decision itself, and
no built-in judge covers it.

**Why this priority**: It is the half of the capability that proves the *authoring* path, and it
targets a real, currently-unmeasured behaviour of an existing feature. It is independently testable:
it needs the assistant's answer and the passages, not the built-in judge.

**Independent Test**: Present the judge with two contrived pairs — a decline against passages that
plainly cover the question, and a decline against passages that plainly do not — and assert the
verdicts differ in the expected direction.

**Acceptance Scenarios**:

1. **Given** passages that do not cover the question, **When** the assistant declines, **Then** the
   decline is judged appropriate.
2. **Given** passages that clearly cover the question, **When** the assistant declines anyway,
   **Then** the decline is judged inappropriate and the explanation identifies the passage that
   covered it.
3. **Given** passages that do not cover the question, **When** the assistant answers instead of
   declining, **Then** the verdict fails — an unwarranted answer is as much a defect as an
   unwarranted decline.
4. **Given** any verdict from this judge, **When** it is recorded, **Then** it carries the same
   pass/explanation shape as the platform's own judges, so both are reported alike.

---

### User Story 3 - Judging never disturbs the thing being judged (Priority: P1)

An operator must be certain that adding evaluation did not change the product. Callers of the
ordinary question path see the same answers, the same citations, the same refusals and declines, and
wait no longer than before — whether or not evaluation is switched on.

**Why this priority**: Equal in priority to the judgements themselves. An evaluation capability that
silently degrades the feature it evaluates has negative value, and the project's standing rule is
that a new capability leaves its predecessor's contract intact. Capability 12 earned one line of
change in capability 8; capability 13 should earn none.

**Independent Test**: Run capability 8's and capability 12's existing test suites unchanged and
assert they pass, and assert the ordinary question path's sources are untouched.

**Acceptance Scenarios**:

1. **Given** the evaluation capability is present, **When** a caller uses the ordinary question path,
   **Then** the response is identical in shape and content to before, and no judge runs in that
   request.
2. **Given** the evaluation capability is present, **When** capability 8's and capability 12's test
   suites run, **Then** they pass without modification.
3. **Given** a judge is unavailable or fails, **When** an evaluation is requested, **Then** the
   failure is reported as a failed evaluation and never as a failed answer.

---

### User Story 4 - Verdicts are attributable and inspectable (Priority: P2)

A developer comparing two prompt variants, or reviewing yesterday's behaviour, needs to see verdicts
rather than infer them. Each verdict must say which judge produced it, whether it passed, and why,
and must be reachable without attaching a debugger or grepping logs.

**Why this priority**: Verdicts nobody can read are not evaluation. It is P2 rather than P1 because
the judgements must exist before there is anything to inspect.

**Independent Test**: Perform an evaluation and assert that every judge that ran is named in the
result alongside its outcome and explanation, and that a judge that did not run is absent rather
than silently reported as passing.

**Acceptance Scenarios**:

1. **Given** an evaluation has run, **When** the developer reads the result, **Then** each verdict
   names its judge, states pass or fail, and carries an explanation.
2. **Given** one judge fails to produce a verdict, **When** the developer reads the result, **Then**
   that judge is reported as errored — distinct from both "passed" and "failed".
3. **Given** a verdict, **When** an operator inspects the service's telemetry, **Then** the same
   evaluation outcome is visible there too, without the service having to log it by hand.

---

### User Story 5 - The interop question is answered with evidence (Priority: P2)

A developer arriving at this repository to learn what is and is not writable in Scala on this SDK
needs a documented, reproducible answer for evaluators — specifically whether the SDK's own judges
can be called from Scala despite the documentation's method-reference form, and whether a judge
authored in Scala is accepted by the platform end to end.

**Why this priority**: In this project the interop finding is a deliverable on equal footing with the
feature. P2 only because it is recorded from what the other stories build.

**Independent Test**: The finding is backed by a test that fails if the mechanism regresses, not by
prose alone.

**Acceptance Scenarios**:

1. **Given** the capability is complete, **When** a developer reads the project's findings, **Then**
   they learn whether a Scala caller can invoke an SDK-owned evaluator agent, by what mechanism, and
   what happens if the documented mechanism is used instead.
2. **Given** the capability is complete, **When** a developer reads the project's findings, **Then**
   they learn whether an evaluator authored in Scala is registered and called like any other agent,
   including whether it needs a descriptor entry.
3. **Given** the capability is complete, **When** a developer reads the project's findings, **Then**
   they learn precisely how much of evaluation is provable offline and what is verifiable only
   against a live model.

---

### Edge Cases

- **The judge disagrees with itself across runs.** A model-based verdict is not deterministic. A
  verdict is therefore evidence, never a contract; no test may assert a specific verdict from a live
  model, and no automated action may be taken on one.
- **The answer being judged is a refusal, not an answer.** A governance block from capability 12
  produces a sentinel, not prose. Judging a refusal for grounding is meaningless; the system must
  recognise this case rather than feed the sentinel to a judge and report a nonsense verdict.
- **Nothing was retrieved, or the passages are empty.** A grounding judgement against no reference
  text has no meaning and must be reported as not-applicable rather than as a failure.
- **The judge returns a label the system does not recognise, or malformed output.** A small local
  model will do this. The evaluation must be reported as errored, with the raw response recoverable,
  and must not be silently coerced to pass or fail.
- **A blank or malformed evaluation request.** Rejected before retrieval, before the assistant, and
  before any judge — the project's standing validation-first contract.
- **The judge is asked about a question the assistant declined.** This is not an error but the
  central case for the second judge; it must not be confused with the refusal case above.
- **Evaluation is slower than the thing it evaluates** (two extra model round-trips). This is
  expected and must not be hidden; it is also the reason evaluation is kept off the ordinary answer
  path.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST be able to judge an answer against the question asked and the reference
  material that grounded it, using the evaluation capability the platform already provides, and MUST
  report a pass/fail outcome with an explanation.
- **FR-002**: The system MUST provide a judge, authored in this project, that assesses whether the
  assistant's decision to decline was appropriate given the reference material — covering both a
  decline that should have been an answer and an answer that should have been a decline.
- **FR-003**: An authored judge's verdict MUST use the platform's standard evaluation result shape,
  so that platform-owned and project-owned verdicts are reported, recorded, and read identically.
- **FR-004**: The reference material a judge sees MUST be exactly the material that grounded the
  answer — the same passages, in the same form — so a verdict cannot be formed from evidence the
  assistant never had.
- **FR-005**: Every evaluation result MUST identify which judge produced it, and MUST distinguish
  three outcomes: passed, failed, and errored (no usable verdict).
- **FR-006**: The ordinary question path (`POST /ask`) MUST be unchanged: same request contract, same
  response contract, same behaviour for answers, declines, refusals and validation errors, and no
  additional latency. Capability 8's and capability 12's existing tests MUST pass unmodified.
- **FR-007**: A judge failure — an unreachable model, malformed output, an unrecognised label — MUST
  degrade to an errored evaluation and MUST NEVER fail, alter, or delay an answer.
- **FR-008**: The system MUST NOT take automated action on a verdict. A verdict is observational;
  nothing is blocked, retried, or rewritten because a judge failed it.
- **FR-009**: Evaluation MUST be switchable off without code changes, because judges cost model calls
  and are not wanted in every environment.
- **FR-010**: Existing input validation MUST continue to run first — a blank or malformed request is
  rejected before retrieval, before the assistant, and before any judge.
- **FR-011**: Verdicts MUST reach the platform's metrics and traces through the standard mechanism,
  not only through hand-written log statements.
- **FR-012**: An evaluation over a refusal or over empty reference material MUST be reported as
  not-applicable, distinct from a failed verdict.
- **FR-013**: The capability MUST record, with evidence, whether a Scala caller can invoke an
  SDK-owned evaluator agent; by which mechanism; and what happens when the mechanism the
  documentation shows is attempted from Scala instead.
- **FR-014**: The capability MUST record, with evidence, whether an evaluator authored in Scala is
  discovered and invoked like any other agent of this project, including whether it requires an
  entry in the hand-maintained component descriptor.
- **FR-015**: The capability MUST record, with evidence, exactly which of its behaviours are provable
  with no live model and which are verifiable only against one — and the offline-provable set MUST
  be covered by tests that run in the project's ordinary verification command with no network access
  and no API key.

### Key Entities

- **Evaluation subject**: what is being judged — the question asked, the reference material
  retrieved, and the answer produced. Assembled from the existing question path; never invented.
- **Verdict**: one judge's opinion of one subject — which judge, whether it passed, and a written
  explanation. May instead be errored or not-applicable.
- **Evaluation**: one subject together with every verdict formed about it.

## Assumptions

- **The evaluation surface is separate from the answer path.** The platform's documented asynchronous
  pattern reacts to a durable task's completion, but capability 8 is a request-based agent with no
  task and no entity of its own, so there is nothing to react to without inventing durable state
  capability 8 does not have — which would duplicate capability 11 and dilute this capability's
  interop question. Evaluation is therefore reached through **its own surface**, over the same
  pipeline, which also makes FR-006 provable by construction rather than by measurement: capability
  8's sources need not be touched at all. Whether the platform offers an interaction-completion hook
  that would allow the asynchronous shape without new durable state is a **research question for
  planning**; if one exists, it is an addition, not a replacement.
- **Two judges, not five.** One the platform ships (grounding) and one we author (decline
  appropriateness). This is the minimum that exercises both the calling path and the authoring path;
  more built-ins would add configuration lines and no new finding.
- **The reference material is the retrieved passages.** Capability 8 already computes them and
  already treats them as ground truth for citations; reusing them keeps the judge's evidence
  identical to the assistant's.
- **Verdicts are not assertions.** Tests may assert that a verdict was *produced*, was *attributed*,
  and was formed from the *right evidence*. Only tests driving a scripted, non-live judge may assert
  a verdict's *value*. Live behaviour is a smoke test, never the proof — the same discipline
  capability 7's delegation and capability 6's recall settled on.
- **The offline story is a research question, not a given.** A judge calls a model, and the
  platform's judges are classes this project does not own. Whether a scripted model provider can be
  attached to an SDK-owned agent is unknown and must be **settled by measurement early**, because a
  negative answer changes the testing design for the whole capability. A negative answer is an
  acceptable, publishable outcome; an unexamined assumption is not.
- **Verdict identity may be limited by the platform, as it was for guardrails.** Capability 12 found
  that a rule's name and category never reach application code. The equivalent question for
  evaluations — how much of a verdict reaches the caller versus only telemetry — is to be
  established by measurement and reported honestly, not assumed.
- **No new third-party dependency is expected.** Judges use the model provider already configured.
- Tests follow the project norm: offline, deterministic, no API key, in `mvn clean verify`.

## Out of Scope

- Changing capability 8's retrieval, corpus, prompt, citation logic, agent, or HTTP contract; and
  changing capability 12's rules or configuration.
- Gating, retrying, or rewriting an answer because a judge failed it — evaluation is observational
  here by explicit requirement (FR-008). Acting on verdicts is a later capability.
- The platform's other built-in judges (toxicity, summarization). Neither fits this corpus — the
  assistant answers from a curated technical corpus, and produces answers rather than summaries — and
  each would add configuration without adding a finding. Both remain one declaration away.
- A stored history of evaluations, trend analysis, or a dashboard over past verdicts.
- A curated regression dataset with expected answers, and scoring runs across it. That is an
  evaluation *harness*, a natural successor, and a different capability.
- Judging any other agent in the service.
- Human review or labelling of verdicts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an evaluated question, a grounding verdict and a decline-appropriateness verdict are
  both produced, each naming its judge and carrying an explanation, in 100% of the suite's scripted
  evaluations.
- **SC-002**: The reference material recorded on an evaluation is identical to the passages the
  assistant was given for that same question — asserted by comparison, not by inspection.
- **SC-003**: Capability 8's and capability 12's test suites pass **unmodified**, and capability 8's
  and capability 12's production sources are **byte-identical** to their state before this
  capability.
- **SC-004**: A judge that errors or returns unusable output produces an errored evaluation, and the
  answer for that same question is returned unchanged — demonstrated by a scripted failure, not
  argued.
- **SC-005**: Evaluation can be turned off, and back on, with **zero** lines of code changed.
- **SC-006**: An evaluation over a refusal, and an evaluation over empty reference material, are each
  reported as not-applicable and are never reported as failed verdicts.
- **SC-007**: A verdict produced by the authored judge and a verdict produced by the platform's judge
  are indistinguishable in shape to a consumer of the results.
- **SC-008**: The offline-provable portion of the suite runs with no network access and no API key as
  part of the project's ordinary verification command, and the portion that is *not* offline-provable
  is enumerated explicitly rather than left implicit.
- **SC-009**: The capability publishes a documented, evidence-backed answer to the interop question:
  whether a Scala caller can invoke an SDK-owned evaluator agent and by what mechanism; whether an
  evaluator authored in Scala works end to end and whether it needs a descriptor entry; and what the
  documented Java method-reference form does when attempted from Scala — recorded in the project's
  findings, README, and roadmap.
