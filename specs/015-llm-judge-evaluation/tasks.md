# Tasks: LLM-as-judge evaluation (cap-13)

**Input**: Design documents from `/specs/015-llm-judge-evaluation/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: Test tasks ARE included. Constitution III makes them mandatory, and FR-015/SC-008 require
the functional surface to be provable offline. Research R3 established that this is achievable for
**both** judges — including the SDK's own — so there is no "live-only" excuse anywhere in this
capability except a real judge's *opinion*, which no test asserts.

**Organization**: Grouped by user story so each is independently implementable and testable. Each
phase is a coherent, compiling, test-green unit — which CLAUDE.md makes a commit boundary.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1–US5)
- Exact file paths are given in every task

## Path Conventions

Single Maven module, mixed Scala/Java. Production Scala under
`src/main/scala/com/gwgs/akkaagentic/`, tests under `src/test/scala/com/gwgs/akkaagentic/`. This
capability lives entirely in a **new** `eval` package (plan.md, Structure Decision) — deliberately not
inside `docs`, so that "capability 8 is untouched" is checkable with `git diff`.

---

## Phase 1: Setup

**Purpose**: Establish the baseline this capability must not move.

- [x] T001 Run `mvn clean verify` and record the passing unit + integration test counts in `specs/015-llm-judge-evaluation/research.md` under a new "Baseline" heading — SC-003 is "capability 8's and capability 12's suites pass unmodified", which needs a number to compare against
- [x] T002 [P] Record the current `git rev-parse HEAD` and the output of `git ls-files -s src/main/scala/com/gwgs/akkaagentic/docs/ src/main/resources/application.conf` in `specs/015-llm-judge-evaluation/research.md` — the blob hashes that T031 will re-check for byte-identity (SC-003)

**Checkpoint**: Baseline captured; any later deviation is a regression, not a surprise.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Run the two assumptions that would reshape the whole design if wrong, then build the pure
domain both judges depend on.

**⚠️ CRITICAL**: T003 gates every production task. Both facts it checks are **bytecode-confirmed but
never executed**. Capability 12's equivalent probe disproved a design assumption before code was
written; this one exists for the same reason.

- [x] T003 Write the discovery test `src/test/scala/com/gwgs/akkaagentic/eval/application/BuiltInJudgeProbeIntegrationTest.scala` and record **four** facts in `specs/015-llm-judge-evaluation/research.md` under "R1/R3 — measured" before writing any production code:
      **(a)** `componentClient.forAgent().inSession(id).dynamicCall[HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result]("hallucination-evaluator").invoke(...)` reaches the SDK-owned agent from Scala and returns a `Result` (research R1);
      **(b)** `TestKit.Settings.DEFAULT.withModelProvider(classOf[HallucinationEvaluator], provider)` genuinely overrides the evaluator's explicitly-configured `.model(...)`, proven by the scripted response coming back rather than a connection failure (research R3);
      **(c)** what the SDK's parser requires of the scripted reply — confirm `{"explanation": "...", "label": "factual"}` maps to `passed = true` and `"hallucinated"` to `false`;
      **(d)** what an **unrecognised** label does — expected `IllegalArgumentException("Unknown evaluation label [...]")` from the SDK's own `toEvaluationResult`, which is the deterministic trigger the `errored` outcome depends on
- [x] T004 Record the **negative control** for FR-013 in `specs/015-llm-judge-evaluation/research.md`: attempt the documented `.method(HallucinationEvaluator::evaluate)` form from Scala, capture the exact compiler or runtime message, then remove the attempt. The finding must state what a Scala author actually sees when they follow the documentation, not merely that "it does not work"
- [x] T005 [P] Write failing unit tests in `src/test/scala/com/gwgs/akkaagentic/eval/domain/ReferenceTextTest.scala`: rendering preserves passage order and the `[n] (source) text` shape, an empty list renders empty, and `isEmpty` is true for blank/whitespace-only
- [x] T006 [P] Write failing unit tests in `src/test/scala/com/gwgs/akkaagentic/eval/domain/EvaluationApplicabilityTest.scala`: a refusal-prefixed answer is `NotApplicable`, empty reference text is `NotApplicable`, an ordinary answer is `Applicable`, and — the one that protects the capability's second half — **a decline is `Applicable`, not `NotApplicable`** (SC-006, and data-model.md's note)
- [x] T007 [P] Create `src/main/scala/com/gwgs/akkaagentic/eval/domain/ReferenceText.scala` — pure functions only, **no Akka import** (Constitution II): `render(passages: List[(String, String)])` and `isEmpty(text)`
- [x] T008 [P] Create `src/main/scala/com/gwgs/akkaagentic/eval/domain/EvaluationApplicability.scala` — the `Applicability` enum and `of(answer, referenceText, refusalPrefix)`. The refusal prefix is a **parameter**, not an import of `DocsAgent.BlockedPrefix`, so `domain` never depends on `application` (research D6)

**Checkpoint**: The two design-critical assumptions are measured facts; the pure domain is green.
Commit gate — `feat(015): cap-13 foundational — built-in judge probe + applicability rules`.

---

## Phase 3: User Story 1 — A developer learns whether an answer was actually grounded (Priority: P1) 🎯 MVP

**Goal**: `POST /evaluate` answers a question exactly as `/ask` does and returns a grounding verdict
from the SDK's built-in judge, reached from Scala.

**Independent Test**: submit an in-corpus question with a scripted grounded answer and a scripted
`factual` verdict; assert a `passed` verdict attributed to `hallucination-evaluator`. Then script an
unsupported answer and a `hallucinated` verdict; assert `failed`.

- [x] T009 [US1] Create `src/main/scala/com/gwgs/akkaagentic/eval/application/AnswerEvaluator.scala` — a plain class (**not** a component, so no descriptor entry) constructed with `(componentClient, knowledgeStore)`. Retrieve top-3, call `dynamicCall[DocsAgent.Request, String]("docs-agent")`, render reference text, decide applicability, and call the built-in judge by id. Wrap each judge call so a throw becomes an `Errored` verdict, never a failed request (FR-007)
- [x] T010 [US1] Create `src/main/scala/com/gwgs/akkaagentic/eval/api/EvaluationEndpoint.scala` — `@HttpEndpoint`, `@Acl(INTERNET)`, `POST /evaluate`, idiomatic `Option`-typed `EvaluateRequest` and plain `EvaluateReply`/`VerdictReply` per [contracts/evaluate-endpoint.md](contracts/evaluate-endpoint.md). Reuse capability 8's `AskQuestion.validate` unchanged — validation first, `400` before retrieval/assistant/judge (FR-010)
- [x] T011 [US1] Add `com.gwgs.akkaagentic.eval.api.EvaluationEndpoint` under `http-endpoint` in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`
- [x] T012 [US1] Write `src/test/scala/com/gwgs/akkaagentic/eval/api/EvaluationEndpointIntegrationTest.scala` with three model providers in one TestKit (`DocsAgent`, `HallucinationEvaluator`, and later `DeclineJudge`) — a first for this project. Cover: a grounded answer scored `passed`; an unsupported answer scored `failed`; the verdict names `hallucination-evaluator` (FR-005, SC-001, SC-009's attribution half); a blank question returns `400` with no model call (FR-010). **Fixture hazard**: scripted answers must not contain `http`, `www.` or `see also:` — capability 12's `linked answer guard` is enforcing on `docs-agent` responses
- [x] T013 [US1] Add the parity assertions to `EvaluationEndpointIntegrationTest.scala` (research D9, SC-002): for the same question and the same scripted answer, `/evaluate`'s `answer` and `citedSources` equal `/ask`'s, and the reference text the judge received equals an independent `KnowledgeStore.retrieve(question, 3)` rendering. This is what converts the deliberate pipeline duplication into a checked invariant

**Checkpoint**: US1 is independently shippable — the SDK's own judge is called from Scala and its
verdict is returned, offline. Commit gate — `feat(015): cap-13 US1 — grounding verdict via the built-in judge`.

---

## Phase 4: User Story 2 — A developer learns whether a decline was appropriate (Priority: P1)

**Goal**: An evaluator authored in Scala judges the decline decision in both directions, and is
instrumented by the platform exactly like the SDK's own.

**Independent Test**: script a decline against passages that do not cover the question (`passed`), a
decline against passages that do (`failed`), and an *answer* against passages that do not (`failed`).

- [x] T014 [US2] Create `src/main/scala/com/gwgs/akkaagentic/eval/application/DeclineJudge.scala` — `@Component(id = "decline-judge", name = ..., description = ...)`, `@AgentRole("evaluator")`, one command handler `evaluate(EvaluationRequest): Agent.Effect[Result]`. `Result` **must** extend `akka.javasdk.agent.EvaluationResult` — research R2 proved this is the *only* thing that makes the runtime treat the reply as a verdict and route it to metrics/traces (FR-011). `EvaluationRequest`/`ModelResult`/`Result` are Java-**shaped** Scala case classes with explicit `@JsonCreator`/`@JsonProperty` (they cross the internal mapper, README §3)
- [x] T015 [US2] Write the judge's system message in `DeclineJudge.scala` stating the rule **two-sidedly**: a decline is `appropriate` only when the reference text does not answer the question, and an *answer* is `inappropriate` when the reference text does not support one. Use `MemoryProvider.none()` and `responseConformsTo(ModelResult)` + `.map(...)`, mirroring the SDK's own judges so ours is indistinguishable to a consumer (SC-007). `ModelResult.toResult` throws on any label other than `appropriate`/`inappropriate`
- [x] T016 [US2] Add `com.gwgs.akkaagentic.eval.application.DeclineJudge` under `agent` in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` — the first descriptor change since capability 11, and the pointed contrast with capability 12's zero
- [x] T017 [US2] Extend `AnswerEvaluator.scala` to call `dynamicCall("decline-judge")` alongside the built-in judge, independently — one judge erroring must never suppress the other (FR-007, SC-004)
- [x] T018 [US2] Write `src/test/scala/com/gwgs/akkaagentic/eval/application/DeclineJudgeIntegrationTest.scala` covering the three US2 acceptance scenarios (appropriate decline, inappropriate decline, unwarranted answer), all scripted offline
- [x] T019 [US2] Write `src/test/scala/com/gwgs/akkaagentic/eval/application/EvaluatorDescriptorTest.scala` pinning research R2's descriptor facts, so a future SDK change breaks the suite rather than the service: **(a)** `classOf[EvaluationResult].isAssignableFrom(classOf[DeclineJudge.Result])` — the quiet failure mode, an un-instrumented judge that still compiles and works; **(b)** the descriptor file lists `DeclineJudge` under `agent`; **(c)** the descriptor file does **not** list `hallucination-evaluator`/`toxicity-evaluator`/`summarization-evaluator`'s classes, because they are provided components (R1c); **(d)** `AgentRegistry.agentsWithRole("evaluator")` contains both `decline-judge` and `hallucination-evaluator` (FR-003, SC-007) — *(d) landed in `DeclineJudgeIntegrationTest`, which already holds a TestKit; (a)–(c) stayed a pure unit test, plus an added `DeclineJudgeModelResultTest` for the label mapping*

**Checkpoint**: Both halves of the evaluator story — calling and authoring — are proven. Commit gate —
`feat(015): cap-13 US2 — authored decline judge + descriptor pinning`.

---

## Phase 5: User Story 3 — Judging never disturbs the thing being judged (Priority: P1)

**Goal**: Capability 8 and capability 12 are provably untouched, and a judge failure never reaches the
answer.

**Independent Test**: `git diff` over capability 8's and capability 12's production sources is empty,
their suites pass unmodified, and a scripted judge failure still returns the answer.

- [x] T020 [US3] Write `src/test/scala/com/gwgs/akkaagentic/eval/api/EvaluationErrorsIntegrationTest.scala`: script the built-in judge to reply with an unrecognised `label` so the SDK's own `toEvaluationResult` throws (T003(d)); assert the verdict is `errored`, that its explanation carries the raw reason, that the **other** judge still returns its verdict, and that `answer` and `citedSources` are unaffected (FR-007, SC-004). Nothing is broken to produce this — the trigger is a scripted label
- [x] T021 [US3] Add to `EvaluationErrorsIntegrationTest.scala` the refusal path end to end: send capability 12's jailbreak text to `POST /evaluate` and assert `200` (not `422`), an empty `citedSources`, and **both** verdicts `not-applicable` with no judge model consumed (FR-012, SC-006, contracts §"refused upstream"). This path is reachable only because capability 12's rule guards `docs-agent` and this capability configures nothing
- [x] T022 [US3] Add the empty-reference-material case as a **domain** test in `EvaluationApplicabilityTest.scala` and note honestly in `specs/015-llm-judge-evaluation/research.md` that it is not reachable through `POST /evaluate` with capability 8's canned corpus (retrieval always returns top-3) — an edge case covered where it is actually reachable, rather than staged
- [x] T023 [US3] Run capability 8's and capability 12's existing suites unmodified and confirm the T001 baseline counts are unchanged apart from this capability's additions (SC-003)

**Checkpoint**: The capability provably costs its predecessors nothing. Commit gate —
`feat(015): cap-13 US3 — errored + not-applicable outcomes, cap-8/12 untouched`.

---

## Phase 6: User Story 4 — Verdicts are attributable and inspectable (Priority: P2)

**Goal**: Every verdict names its judge and its outcome, and evaluation can be switched off.

**Independent Test**: every verdict in every response carries a non-empty `judge` and one of the four
outcome strings; with `eval.enabled = false` the answer still returns and `verdicts` is empty.

- [ ] T024 [US4] Add the `eval { enabled = true, enabled = ${?EVAL_ENABLED} }` block to `src/main/resources/application.conf` and read it via an injected `com.typesafe.config.Config` in `EvaluationEndpoint.scala` (the capability 10 `McpClientAgent` pattern). When off, answer normally and return `verdicts: []` with no judge call (FR-009)
- [ ] T025 [US4] Write `src/test/scala/com/gwgs/akkaagentic/eval/api/EvaluationDisabledIntegrationTest.scala` — the same fixture as the enabled test but with `withAdditionalConfig("eval.enabled = false")`, differing in **exactly one key**, so SC-005 ("zero lines of code changed") is proven by configuration rather than asserted in prose. This mirrors capability 12's `report-only` pair
- [ ] T026 [US4] Add attribution assertions across `EvaluationEndpointIntegrationTest.scala`: every verdict's `judge` is non-empty and equals the expected component id, and `outcome` is always one of `passed`/`failed`/`errored`/`not-applicable` (FR-005, SC-007). Assert explicitly that **no** verdict reports `unknown` — the capability 12 contrast that research R5 established
- [ ] T027 [US4] Verify FR-011 by observation rather than assumption: confirm from the running TestKit that a verdict from `DeclineJudge` is recorded the same way as one from `hallucination-evaluator` (the descriptor's evaluator flag, T019(a)), and record in `research.md` what is and is not observable from application code — the honest counterpart to capability 12's telemetry-only finding

**Checkpoint**: Verdicts are readable, attributable and switchable. Commit gate —
`feat(015): cap-13 US4 — attribution and the enable/disable switch`.

---

## Phase 7: User Story 5 — The interop question is answered with evidence (Priority: P2)

**Goal**: The finding is recorded, and backed by a test that fails if the mechanism regresses.

- [ ] T028 [US5] Promote the T003 probe into a permanent regression test at `src/test/scala/com/gwgs/akkaagentic/eval/application/BuiltInJudgeIntegrationTest.scala`: `dynamicCall("hallucination-evaluator")` reaches the SDK-owned agent, and the TestKit override replaces its configured model. If a future SDK stops registering the evaluators as provided components, or changes the override precedence, this fails (FR-013, SC-009)
- [ ] T029 [US5] Write the consolidated interop finding into `specs/015-llm-judge-evaluation/research.md`: the documented `.method(…)` form and what it actually does from Scala (T004), the `dynamicCall` mechanism and *why* it reaches components we do not own (`agentClassById` holds the runtime's agents too), the descriptor asymmetry (ours needs a line, the built-ins do not), and the offline story (R3)
- [ ] T030 [US5] Record in `docs/sdk-3.6.0-limitations.md` any divergence measured during implementation — in particular anything T003/T004 found that contradicts `akka-context/sdk/agents/llm_eval.html.md`. If nothing diverged, state that explicitly rather than leaving the section absent
- [ ] T031 [US5] Assert SC-003 mechanically: run `git diff --stat main -- src/main/scala/com/gwgs/akkaagentic/docs/` and confirm empty output, and compare `git ls-files -s` blob hashes for capability 8's and capability 12's production sources against T002's record. Paste the command and its (empty) output into `research.md` — the claim is worth nothing unless it is shown

**Checkpoint**: The headline deliverable exists and is regression-protected. Commit gate —
`feat(015): cap-13 interop finding — dynamicCall reaches SDK-owned components`.

---

## Phase 8: Polish, Documentation & Live Verification

- [ ] T032 [P] Add **README §15** documenting the finding, and update the through-line it sharpens: §4/§6/§13's "the wall is a client property" becomes *"…and the agent client's `dynamicCall` reaches components we do **not** own — where capabilities 4, 6 and 11 each had to quarantine Java for a runtime-owned component, capability 13 did not, because that component happened to be an agent"*
- [ ] T033 [P] Add a **Capability 13 usage section** to `README.md` after capability 12's, with the four `curl` flows from [quickstart.md](quickstart.md): an answer judged, a decline judged, a refusal reported as not-applicable, and validation-first. State the three-model-calls cost and the off switch plainly
- [ ] T034 [P] Add the capability 13 row to `ROADMAP.md` and the capability 13 entry to `FINDINGS.md`, including the deliberate contrast with capability 12: governance costs **zero** descriptor lines because a guardrail is not a component; evaluation costs **one** because a judge is
- [ ] T035 [P] Update `specs/015-llm-judge-evaluation/quickstart.md` against what was actually measured — correct any example whose shape differs from the implementation, as capability 12 had to
- [ ] T036 Run `mvn clean verify` (not `mvn verify`) and record the final unit + integration counts against the T001 baseline
- [ ] T037 Live smoke test against Ollama `qwen3:8b`: the four flows above, end to end. Record what was seen in `README.md` and `research.md` — **including anything unflattering**, as capability 12 did with the jailbreak variant that got through. Assert nothing about verdict *values*: a live judge's opinion is not deterministic, and the offline suite is the proof

**Checkpoint**: Capability complete, documented, and honestly reported.

---

## Dependencies

```
Phase 1 (T001–T002)
   └─> Phase 2 (T003 gates everything; T005–T008 parallel after T003)
          ├─> Phase 3 US1 (T009–T013)
          │      └─> Phase 4 US2 (T014–T019)   [T017 extends T009]
          │             └─> Phase 5 US3 (T020–T023)
          │                    └─> Phase 6 US4 (T024–T027)
          │                           └─> Phase 7 US5 (T028–T031)
          │                                  └─> Phase 8 (T032–T037)
```

**MVP scope**: Phases 1–3. That alone delivers the capability's headline — the SDK's own judge called
from Scala, proven offline — and is independently shippable.

**Parallel opportunities**: T005/T006/T007/T008 (four different files); T032/T033/T034/T035 (four
different documents).

---

## Traceability

| Requirement | Proven by |
|---|---|
| FR-001 grounding judgement | T009, T012 |
| FR-002 authored decline judge, two-sided | T014, T015, T018 |
| FR-003 platform-standard result shape | T014, T019(a), T019(d) |
| FR-004 judge sees exactly the grounding passages | T013 (parity) |
| FR-005 judge identity + three outcomes | T012, T020, T026 |
| FR-006 `/ask` unchanged | T023, T031 |
| FR-007 judge failure never affects the answer | T020 |
| FR-008 no automated action on a verdict | design (no gating path exists); T012 asserts `200` throughout |
| FR-009 switchable off | T024, T025 |
| FR-010 validation first | T010, T012 |
| FR-011 verdicts reach metrics/traces | T014 (`EvaluationResult`), T019(a), T027 |
| FR-012 not-applicable ≠ failed | T021, T022, T006 |
| FR-013 interop: calling an SDK-owned judge | T003, T004, T028, T029 |
| FR-014 interop: authoring a judge + descriptor | T016, T019, T029 |
| FR-015 offline-provable set enumerated | T003, T036, research.md "What is offline-provable" |
| SC-001 both verdicts produced, attributed | T012, T026 |
| SC-002 reference material identical to the passages | T013 |
| SC-003 cap-8/cap-12 byte-identical + suites pass | T023, T031 |
| SC-004 errored judge, answer unchanged | T020 |
| SC-005 off/on with zero code changed | T025 |
| SC-006 refusal + empty reference are not-applicable | T021, T022 |
| SC-007 authored and platform verdicts indistinguishable | T015, T019(d), T026 |
| SC-008 offline suite in the ordinary verify command | T036 |
| SC-009 finding published | T029, T030, T032, T034 |
