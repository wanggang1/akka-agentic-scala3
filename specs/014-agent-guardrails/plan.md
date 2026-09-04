# Implementation Plan: Agent Guardrails (cap-12)

**Branch**: `014-agent-guardrails` | **Date**: 2026-09-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/014-agent-guardrails/spec.md`

## Summary

Put runtime-enforced governance around capability 8's `DocsAgent`: a **request-side** jailbreak rule
that stops hostile prompts before any model call, and two **response-side** rules — one enforcing,
one record-only — that judge the answer before it reaches the caller. Rules are declared in
configuration and constructed by the runtime from a class-name string; the guarded agent names none
of them.

The technical approach follows from Phase 0. The jailbreak rule needs **no code and no new
dependency**: the SDK's own `reference.conf` already declares a `SimilarityGuard`-based
`"default jailbreak"` rule, shipped disabled because `agents = []`, and enabling it for `docs-agent`
is a one-key override; the runtime evaluates it with the same in-process quantized all-MiniLM ONNX
model cap-8 already uses, so it stays fully offline. The two response-side rules are small **Scala
classes** — deliberately one with a `(GuardrailContext)` constructor and one with **no** constructor
parameters, because the runtime's loader tries exactly those two forms in that order. A Scala
`object` satisfies neither (its constructor is private), and shipping that as a **negative probe** is
what converts cap-11's bytecode-shape hazard from a View-specific curiosity into a documented,
cross-mechanism rule.

One risk drives the task order: a block is aborted by throwing `Guardrail.GuardrailException`, and
cap-8's `DocsAgent` ends with `.onFailure(_ => DontKnow)` — which would convert a governance event
into an honest decline and violate FR-005. The first implementation task is a test that determines
whether that actually happens, **before** any production code changes.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS on JDK 21 (Temurin), Java 21 for interop probes
**Primary Dependencies**: Akka SDK `3.6.3` (`akka-javasdk-parent`), runtime `akka-runtime-core_2.13` 1.6.15. **No new dependency** — the jailbreak rule's embedding model (`langchain4j-embeddings-all-minilm-l6-v2-q`) is already on the classpath from cap-8
**Storage**: N/A — this capability persists nothing (no entity, view, workflow or task)
**Testing**: JUnit 5 + AssertJ via `TestKitSupport`; `TestModelProvider` for the response side; `TestKit.Settings.withAdditionalConfig` for the report-only switch. Fully offline: no API key, no network
**Target Platform**: Akka runtime, local (`exec:java`) and deployed
**Project Type**: Single Maven module, mixed Scala/Java sources (scalac before javac — see README "Build")
**Performance Goals**: No regression to cap-8's `POST /ask`. The jailbreak rule embeds the question once per request against 10 cached example embeddings; the response-side rules are string operations
**Constraints**: Offline-capable end to end; capability 8's request contract and its existing tests unchanged; hand-maintained component descriptor unchanged
**Scale/Scope**: 3 declared rules over 1 agent; 2 new Scala guardrail classes + 1 negative probe; ~1 pure-domain rules object; 1 narrowly scoped edit to `DocsAgent` and `DocsEndpoint`

## Constitution Check

*GATE: evaluated before Phase 0, re-evaluated after Phase 1 design. Both passes recorded.*

| Principle | Verdict | Evidence |
|---|---|---|
| **I. Akka SDK First** (NON-NEGOTIABLE) | ✅ Pass | Governance uses the SDK's own `TextGuardrail` + `akka.javasdk.agent.guardrails` registration, and reuses the SDK's built-in `SimilarityGuard` rather than hand-rolling detection. No custom or third-party validation layer. |
| **I. Dependency justification** | ✅ Pass — **zero dependencies added** | Research R2: the embedding model the runtime needs is already resolved from the classpath via cap-8's existing artifact; the 10 jailbreak examples ship inside the SDK jar. |
| **II. Domain independence** | ✅ Pass *(by design decision)* | The *rule logic* (does this text carry an external reference? how many sentences?) goes in `docs/domain/` as pure functions with **no Akka import**; the `TextGuardrail` classes in `docs/application/` are thin adapters that read config and map a boolean to `Guardrail.Result`. Mirrors `AskQuestion.validate` / `DocsEndpoint`. |
| **II. API isolation** | ✅ Pass | The blocked outcome is a new endpoint-owned `BlockedReply`; no SDK or domain type is exposed. `AskReply` is unchanged. |
| **II. Single responsibility** | ✅ Pass | Two rules, each checking one thing, rather than one configurable mega-rule. |
| **II. Descriptive naming** | ✅ Pass | `LinkedAnswerGuard`, `AnswerLengthGuard`, `AnswerRules` — no `Manager`/`Service`/`Handler`. |
| **III. Test coverage** | ✅ Pass | Every FR maps to a test (see Phase 2 outline). Pure rules get unit tests with no runtime; enforcement gets integration tests; the interop findings get their own probes. Cap-8's existing tests must stay green — that *is* SC-002. |
| **IV. Simplicity / YAGNI** | ✅ Pass | Jailbreak = one config line, not a copied block. No `@AgentRole` (research R8) — this service has one agent worth guarding and a role would be speculative. No audit store, no UI, no evaluator framework. |

**Post-Phase-1 re-check**: ✅ still passing. The design added no component, no dependency, no
persistence and no descriptor entry; the only growth over Phase 0 was splitting rule logic into pure
domain functions, which strengthens II and III rather than straining them.

**Complexity Tracking**: not required — no violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/014-agent-guardrails/
├── spec.md              # /akka.specify output
├── plan.md              # This file
├── research.md          # Phase 0 — R1..R8, all resolved against the jars
├── data-model.md        # Phase 1 — value types + configuration-as-data
├── quickstart.md        # Phase 1 — offline verify + live curl walkthrough
├── contracts/
│   ├── ask-endpoint.md      # the three-outcome HTTP contract
│   └── guardrail-config.md  # the declaration contract
├── checklists/
│   └── requirements.md  # spec quality checklist (all pass)
└── tasks.md             # Phase 2 — created by /akka.tasks, NOT by this command
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/docs/
├── domain/
│   ├── AskQuestion.scala          # unchanged
│   ├── KnowledgeCorpus.scala      # unchanged
│   ├── Passage.scala              # unchanged
│   └── AnswerRules.scala          # NEW — pure predicates, no Akka import
├── application/
│   ├── KnowledgeStore.scala       # unchanged
│   ├── DocsAgent.scala            # EDITED — onFailure must not swallow a block (R3)
│   ├── LinkedAnswerGuard.scala    # NEW — TextGuardrail, (GuardrailContext) ctor, enforcing
│   └── AnswerLengthGuard.scala    # NEW — TextGuardrail, NO-ARG ctor, record-only
└── api/
    └── DocsEndpoint.scala         # EDITED — new 422 blocked outcome + BlockedReply

src/main/resources/
├── application.conf               # EDITED — the three rule declarations
└── META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # UNCHANGED (proof for R5)

src/test/scala/com/gwgs/akkaagentic/docs/
├── domain/AnswerRulesTest.scala                     # NEW — pure unit tests, no runtime
├── application/GuardrailLoadingIntegrationTest.scala # NEW — R1 probes incl. the `object` negative
└── api/
    ├── DocsEndpointIntegrationTest.scala            # EDITED — cap-8 regression must stay green
    ├── GuardrailBlockingIntegrationTest.scala       # NEW — enforcing: 422, no model call
    └── GuardrailReportOnlyIntegrationTest.scala     # NEW — same rules, report-only via config override
```

**Structure Decision**: The capability lives **entirely inside the existing `docs` package** rather
than a new one, because it governs cap-8's agent and owns no feature surface of its own — a new
top-level package would imply a capability boundary that does not exist. It follows the project's
`domain` / `application` / `api` split: pure rule predicates in `domain` (no Akka, unit-testable with
no runtime), `TextGuardrail` adapters in `application` (they implement an SDK interface, so they
cannot be domain), and the caller-facing blocked shape in `api`. The negative probe lives in **test**
sources — it must never be loadable from production configuration.

## Key design decisions carried from Phase 0

1. **Enable, don't copy, the jailbreak rule.** `"default jailbreak".agents = ["docs-agent"]` — one
   line. The threshold, examples directory and category stay the SDK's (R2).
2. **Two constructor forms on purpose.** `LinkedAnswerGuard(ctx)` and `AnswerLengthGuard()` cover
   both loader attempts; the `object` probe covers the failure. Together they are the finding (R1).
3. **Verify the block/decline collision before changing anything.** Task 1 asserts a blocked
   interaction is not the `"I don't know"` sentinel; only then is `DocsAgent.onFailure` narrowed to
   let a `GuardrailException` propagate while still absorbing ordinary model failures (R3).
4. **`422` with `{blocked, rule, category, explanation}`** for both request- and response-side blocks;
   `200` answer, `200` decline and `400` validation are untouched (R6).
5. **Report-only is proven by configuration, not prose** — a second test class with
   `withAdditionalConfig` flips `report-only` and asserts the caller-visible outcome changes with no
   source edit (R7, SC-005).
6. **Descriptor untouched.** A green suite with an unchanged descriptor is the evidence that a
   guardrail is not a component (R5).

## Known risks

| Risk | Handling |
|---|---|
| A block may **not** surface as a throwable our handler sees | Task 1 is a discovery test; if the hypothesis is wrong, the endpoint distinguishes the outcome another way and `DocsAgent` is not edited at all |
| `SimilarityGuard` may flag a legitimate question (false positive) | SC-002 requires cap-8's existing tests to pass unchanged; if the shipped 0.75 threshold is too aggressive for this corpus, raising it is a config change, recorded as a finding |
| The TestKit may not apply guardrail config the way the runtime does | Task 1 also establishes this; if guardrails do not engage under TestKit, the offline claim (FR-012) is at risk and the capability's testing story becomes a finding in its own right |

## Phase 2 outline *(not executed by this command)*

`/akka.tasks` will expand these into dependency-ordered tasks. The intended order — discovery first,
then pure logic, then adapters, then enforcement, then documentation — is:

1. Discovery test: does a block reach us as an exception, and does the TestKit engage guardrails?
2. `AnswerRules` pure predicates + unit tests.
3. `LinkedAnswerGuard` (config-taking) + `AnswerLengthGuard` (no-arg), declared in `application.conf`.
4. `DocsAgent`/`DocsEndpoint` edits for the three-outcome contract + blocking integration tests.
5. Report-only test class (config override only).
6. Interop probes: the `object` negative, the bad-class-name startup failure, the unchanged descriptor.
7. Documentation: README §14 + cap-12 usage section, `ROADMAP.md` row, `FINDINGS.md`, and the two
   documentation divergences recorded in `docs/`.
