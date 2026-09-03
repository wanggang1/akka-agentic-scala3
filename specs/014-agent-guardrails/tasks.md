# Tasks: Agent Guardrails (cap-12)

**Input**: Design documents from `/specs/014-agent-guardrails/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: Test tasks ARE included. Constitution III makes them mandatory ("every behavioral change
MUST be accompanied by tests"), and FR-012/SC-008 require the whole suite to run offline. Tests are
written before the implementation they cover.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1–US4)
- Exact file paths are given in every task

## Path Conventions

Single Maven module, mixed Scala/Java. Production Scala under
`src/main/scala/com/gwgs/akkaagentic/`, tests under `src/test/scala/com/gwgs/akkaagentic/`.
This capability lives entirely inside the existing `docs` package (plan.md, Structure Decision).

---

## Phase 1: Setup

**Purpose**: Establish the regression baseline this capability must not break.

- [ ] T001 Run `mvn clean verify` and record the passing test count in `specs/014-agent-guardrails/research.md` as the pre-change baseline (SC-002 is "cap-8's tests pass unchanged", which needs a number to compare against)
- [ ] T002 [P] Record the current `POST /ask` responses for one in-corpus and one out-of-corpus question in `specs/014-agent-guardrails/research.md`, from the existing `src/test/scala/com/gwgs/akkaagentic/docs/api/DocsEndpointIntegrationTest.scala` expectations — the answer/decline shapes that must remain byte-identical

**Checkpoint**: Baseline captured; any later deviation is a regression, not a surprise.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Settle the one unknown that decides whether production code changes at all, then build
the block-surfacing path that **both** P1 stories depend on.

**⚠️ CRITICAL**: T003 gates everything. Do not write production code before it reports.

- [ ] T003 Write the discovery test `src/test/scala/com/gwgs/akkaagentic/docs/application/GuardrailProbeIntegrationTest.scala`: declare a trivially-always-failing `TextGuardrail` via `TestKit.Settings.withAdditionalConfig`, call `docs-agent`, and record **three** facts — (a) does the TestKit engage guardrails at all, (b) does a block arrive as a thrown `Guardrail.GuardrailException`, (c) does cap-8's `.onFailure(_ => DontKnow)` swallow it into the sentinel. Write the findings into `specs/014-agent-guardrails/research.md` under R3 before proceeding
- [ ] T004 [P] Write failing unit tests in `src/test/scala/com/gwgs/akkaagentic/docs/domain/AnswerRulesTest.scala` for both pure predicates: external-reference detection (positive, negative, empty/blank, the `"I don't know"` sentinel) and sentence counting (under, at, over the limit)
- [ ] T005 [P] Create `src/main/scala/com/gwgs/akkaagentic/docs/domain/AnswerRules.scala` — pure functions only, **no Akka import** (Constitution II): `containsExternalReference(text, markers)` and `sentenceCount(text)`. Shared file serving US2 and US3, which is why it is foundational rather than per-story
- [ ] T006 Add `BlockedReply(blocked, rule, category, explanation)` to `src/main/scala/com/gwgs/akkaagentic/docs/api/DocsEndpoint.scala` and map a guardrail block to `422` per [contracts/ask-endpoint.md](contracts/ask-endpoint.md), leaving the `200` answer, `200` decline and `400` validation paths untouched (depends on T003)
- [ ] T007 Narrow `.onFailure` in `src/main/scala/com/gwgs/akkaagentic/docs/application/DocsAgent.scala` so a `Guardrail.GuardrailException` propagates while ordinary model failures still degrade to the `DontKnow` sentinel (depends on T003 — **skip entirely if T003 shows blocks do not travel that path**)

**Checkpoint**: The three-outcome contract exists and is provable; user stories can now proceed.

---

## Phase 3: User Story 1 — A hostile prompt never reaches the model (Priority: P1) 🎯 MVP

**Goal**: A jailbreak-style question is refused before any model call, with an auditable name and category.

**Independent Test**: `POST /ask` with a jailbreak-style question returns `422` naming the
`default jailbreak` rule, and no scripted model response is consumed.

### Tests for User Story 1

- [ ] T008 [P] [US1] Write `src/test/scala/com/gwgs/akkaagentic/docs/api/GuardrailBlockingIntegrationTest.scala` with the request-side cases: a jailbreak-style question returns `422` with `rule = "default jailbreak"` and `category = JAILBREAK` (SC-001), and **no `TestModelProvider` response is scripted** so any model call would fail the test (FR-001)
- [ ] T009 [P] [US1] Extend `src/test/scala/com/gwgs/akkaagentic/docs/api/DocsEndpointIntegrationTest.scala` with the regression guard: an ordinary in-corpus question still returns `200` with the same answer and `citedSources`, and a blank question still returns `400` before any rule runs (SC-002, FR-009)

### Implementation for User Story 1

- [ ] T010 [US1] Enable the SDK's pre-declared rule in `src/main/resources/application.conf` with the single override `akka.javasdk.agent.guardrails."default jailbreak".agents = ["docs-agent"]` — do **not** copy the block (research R2)
- [ ] T011 [US1] Verify the log line for a blocked request carries name, category and explanation (FR-003, SC-007) and note the observed explanation text in `specs/014-agent-guardrails/research.md`

**Checkpoint**: MVP — governance is enforced with zero production code written. Commit this gate.

---

## Phase 4: User Story 2 — A response that breaks a hard rule is blocked (Priority: P1)

**Goal**: An answer pointing at an external web source is blocked rather than delivered.

**Independent Test**: Script the model to return a linked answer; the caller gets `422` naming
`linked answer guard`, not the answer.

### Tests for User Story 2

- [ ] T012 [P] [US2] Add response-side cases to `src/test/scala/com/gwgs/akkaagentic/docs/api/GuardrailBlockingIntegrationTest.scala`: a scripted answer containing a link returns `422` with `category = HALLUCINATED` and the answer text is absent from the response body
- [ ] T013 [P] [US2] Add the pass-through cases to the same file: an ordinary grounded answer is delivered unchanged with its citations, and the `"I don't know"` sentinel is delivered as a `200` decline — never as a block (SC-003)

### Implementation for User Story 2

- [ ] T014 [US2] Create `src/main/scala/com/gwgs/akkaagentic/docs/application/LinkedAnswerGuard.scala` — a **top-level Scala class** with a single plain `(ctx: GuardrailContext)` parameter list (no curried, no `using`), reading `link-markers` from `ctx.config` and delegating the decision to `AnswerRules.containsExternalReference` (research R1)
- [ ] T015 [US2] Declare the rule in `src/main/resources/application.conf` per [contracts/guardrail-config.md](contracts/guardrail-config.md): `class`, `agents = ["docs-agent"]`, `category = HALLUCINATED`, `use-for = ["model-response"]`, `report-only = false`, `link-markers`

**Checkpoint**: Both enforcement sides work. Commit this gate.

---

## Phase 5: User Story 3 — A soft rule is recorded but not enforced (Priority: P2)

**Goal**: A style violation is recorded and the answer still delivered; flipping one config key
turns it into a block.

**Independent Test**: An over-long scripted answer is delivered `200`; the same rule set to
enforcing returns `422` — with no source change between the two runs.

### Tests for User Story 3

- [ ] T016 [P] [US3] Write `src/test/scala/com/gwgs/akkaagentic/docs/api/GuardrailReportOnlyIntegrationTest.scala`: an over-long scripted answer is delivered as a normal `200` with its citations (SC-004), proving a record-only rule is caller-invisible
- [ ] T017 [US3] Add the SC-005 pair to the same file: a second TestKit configuration that flips **only** `report-only` to `false` via `TestKit.Settings.withAdditionalConfig`, asserting the same input now yields `422` — the zero-code-change claim demonstrated by the suite, not by prose

### Implementation for User Story 3

- [ ] T018 [US3] Create `src/main/scala/com/gwgs/akkaagentic/docs/application/AnswerLengthGuard.scala` — a top-level Scala class with **no constructor parameters** (the loader's second attempt), threshold as a class constant, delegating to `AnswerRules.sentenceCount`. The no-arg shape is deliberate and load-bearing for the finding (research R1)
- [ ] T019 [US3] Declare it in `src/main/resources/application.conf` with `category = FORMAT`, `use-for = ["model-response"]`, `report-only = true`

**Checkpoint**: Both governance modes demonstrated. Commit this gate.

---

## Phase 6: User Story 4 — Governance is declared, not wired (Priority: P3)

**Goal**: Rules attach from outside; the guarded agent knows nothing about them.

**Independent Test**: The agent source contains no guardrail reference, and a rule does not fire for
an agent it does not name.

- [ ] T020 [P] [US4] Assert SC-006 mechanically: a test (or a documented `grep` in `specs/014-agent-guardrails/research.md`) showing `src/main/scala/com/gwgs/akkaagentic/docs/application/DocsAgent.scala` contains **zero** occurrences of `Guardrail`, any rule name, or any category
- [ ] T021 [US4] Add a negative-attachment case in `src/test/scala/com/gwgs/akkaagentic/docs/application/GuardrailProbeIntegrationTest.scala`: exercise another existing agent (e.g. `chat-agent`) with input that would trip a `docs-agent` rule and assert it is unaffected

**Checkpoint**: The attachment mechanism is proven, not asserted.

---

## Phase 7: Interop Findings (FR-013 / SC-009) — the capability's headline deliverable

**Purpose**: Convert the Phase 0 predictions into recorded results. These carry no story label
because they serve the project's research goal, not a user journey.

- [ ] T022 [P] Add the negative probe `ObjectFormGuard` as a Scala **`object`** implementing `TextGuardrail` in `src/test/scala/com/gwgs/akkaagentic/docs/application/GuardrailLoadingIntegrationTest.scala`, and assert the runtime **cannot** construct it (its constructor is private; only `MODULE$` exists) — the cross-mechanism confirmation of cap-11 R2's bytecode-shape hazard. It must never appear in `src/main/resources/application.conf`
- [ ] T023 [P] In the same file, assert both positive forms load: the `(GuardrailContext)` class and the no-arg class, covering the loader's two attempts (research R1)
- [ ] T024 [P] In the same file, assert a deliberately misspelled `class` value fails service startup rather than leaving the agent silently unguarded (FR-010, research R4)
- [ ] T025 Confirm `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` is **unchanged** and the suite is green — the evidence that a guardrail is not a component (FR-013, research R5)
- [ ] T026 Record all four results in `specs/014-agent-guardrails/research.md`, including whether each prediction held

**Checkpoint**: The interop finding is resolved with evidence. Commit this gate.

---

## Phase 8: Polish & Documentation

- [ ] T027 Run `mvn clean verify` (clean, not incremental — cap-11's lesson) and confirm the count exceeds the T001 baseline with no failures
- [ ] T028 [P] Add README "Scala interop notes" **§14** covering: the reflective config-driven loading path, which Scala class forms load and which do not, the pre-declared `"default jailbreak"` discovery, and the block-vs-decline collision with cap-8's `onFailure`
- [ ] T029 [P] Add the README **Capability 12** usage section with the curl walkthrough from [quickstart.md](quickstart.md)
- [ ] T030 [P] Add the cap-12 row and update "Where we are" in `ROADMAP.md`
- [ ] T031 [P] Add the cap-12 findings to `FINDINGS.md`, extending the through-line: the wall is a *client* property, and this is the **second** hazard axis — reflected bytecode shape — now confirmed on a mechanism unrelated to Views
- [ ] T032 [P] Record the two documentation divergences (`TextGuardrail.Result` does not exist; the undocumented zero-arg constructor path) in `docs/sdk-3.6.0-limitations.md` or a new `docs/` note
- [ ] T033 Live smoke test against Ollama `qwen3:8b` following `quickstart.md`, and add the verified transcript to the README cap-12 section

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: no dependencies
- **Phase 2 (Foundational)**: depends on Phase 1 — **blocks all user stories**. T003 additionally blocks T006/T007 within the phase
- **Phase 3 (US1)** and **Phase 4 (US2)**: both depend on Phase 2; independent of each other
- **Phase 5 (US3)**: depends on Phase 2; independent of US1/US2 (it declares its own rule)
- **Phase 6 (US4)**: depends on at least one rule existing — practically, after Phase 3
- **Phase 7 (Interop)**: depends on Phase 2 only; T025 wants the full rule set declared, so run after Phase 5
- **Phase 8 (Polish)**: depends on everything

### Critical path

`T003 → T006/T007 → T010 (US1) → T014/T015 (US2) → T018/T019 (US3) → T022–T026 → docs`

T003 is the true gate: it decides whether T007 is written at all.

### Parallel Opportunities

- T004 and T005 are the same logical unit but different files (test / implementation) — write the test first, then the implementation
- T008 and T009 touch different test files → parallel
- T012 and T013 touch the same file → **not** parallel with each other, but both are parallel with US3's tasks
- T022, T023 and T024 all touch `GuardrailLoadingIntegrationTest.scala` → sequential within that file
- T028–T032 touch different documents → fully parallel

---

## Parallel Example: after Phase 2 completes

```text
# US1 and US2 test-writing can proceed together (different files):
Task: "T008 request-side blocking test in .../api/GuardrailBlockingIntegrationTest.scala"
Task: "T009 cap-8 regression guard in .../api/DocsEndpointIntegrationTest.scala"

# US3 is independent of both:
Task: "T016 report-only test in .../api/GuardrailReportOnlyIntegrationTest.scala"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 → Phase 2 (T003 first; it may remove T007 from the plan entirely)
2. Phase 3 — jailbreak enforcement, which needs **one config line** and no production Scala
3. **STOP and validate**: a hostile prompt is refused, ordinary traffic is untouched
4. That alone is a demonstrable capability

### Incremental delivery

MVP (US1) → response-side enforcement (US2) → record-only mode (US3) → attachment proof (US4) →
interop findings (Phase 7) → documentation (Phase 8). Each phase is a coherent, compiling,
test-green unit.

### Commit discipline

Per `CLAUDE.md`, **commit at each approved gate** with a scoped message
(e.g. `feat(014): cap-12 US1 — enable the SDK's jailbreak guard for docs-agent`), not one large
commit at the end. Documentation (Phase 8) is its own final commit.

---

## Notes

- This capability adds **no dependency, no component, no persistence and no descriptor entry** — if
  any of those changes, something has gone wrong with the design
- The `ObjectFormGuard` probe must stay in **test** sources; production config must never reference it
- SC-002 is a hard constraint: cap-8's existing tests pass **unchanged**. If the shipped jailbreak
  threshold (0.75) false-positives on a legitimate corpus question, raising it is a config change and
  becomes a recorded finding
- Verify with `mvn clean verify`, never bare `mvn verify` (an incremental build masked a broken one
  in cap-11)

**Total**: 33 tasks — Setup 2 · Foundational 5 · US1 4 · US2 4 · US3 4 · US4 2 · Interop 5 · Polish 7
