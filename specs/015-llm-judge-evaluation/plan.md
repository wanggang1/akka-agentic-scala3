# Implementation Plan: LLM-as-judge evaluation (cap-13)

**Branch**: `015-llm-judge-evaluation` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/015-llm-judge-evaluation/spec.md`
**Research**: [research.md](./research.md) — R1–R6 all resolved from the shipped jars before design

## Summary

Add a **second surface** over capability 8's existing pipeline that answers a question exactly as
`POST /ask` does and then has two **LLM judges** rate the result: the SDK's built-in
`hallucination-evaluator` (is the answer supported by the passages it was given?) and an authored
`decline-judge` (was the decision to decline, or not to decline, the right one?). Verdicts are
returned with the answer; nothing is gated, retried or rewritten.

The design is shaped by four research results, each of which removed a choice rather than opened one:

- **R1** — the documented call form (`.method(HallucinationEvaluator::evaluate)`) is the method-reference
  wall, but the built-in evaluators are **provided components** with real `@Component` ids, and
  `dynamicCall` resolves off `agentClassById` by string. So Scala reaches them, and no Java quarantine
  class is needed. *This is the capability's headline finding.*
- **R2** — an evaluator is an ordinary `Agent`; what makes the runtime treat its reply as a verdict is
  the **return type implementing `EvaluationResult`**, not any annotation. Ours therefore needs one
  descriptor line under the existing `agent` key — a deliberate contrast with cap-12's zero.
- **R3** — `TestKit.Settings.withModelProvider` is keyed by component id and its override **beats** the
  evaluator's explicit `.model(...)`. The SDK's own judge is scriptable offline. The capability's proof
  is therefore offline, not live.
- **R4** — no `Consume.From*` source exists for a request-based agent, so the documented asynchronous
  pattern is unavailable without inventing durable state capability 8 lacks. Evaluation gets its own
  surface — which makes **SC-003 (cap-8/cap-12 byte-identical) provable by `git diff`**.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS on JDK 21 (Temurin), compiled by `scala-maven-plugin`
**Primary Dependencies**: `akka-javasdk` 3.6.3 (runtime `akka-runtime-core_2.13` 1.6.15). **No new
dependency** — the judges use the model provider already configured; cap-8's `KnowledgeStore` is reused
as-is.
**Storage**: none. Evaluation is stateless and computed per request; no entity, no view, no journal.
**Testing**: JUnit 5 + AssertJ; `TestKitSupport` for integration; `TestModelProvider` for **both**
agents under test (`docs-agent` and the two judges) — see R3. `mvn clean verify`, offline, no API key.
**Target Platform**: the existing Akka service (`akka-agentic-scala3`), local JVM
**Project Type**: single-module Scala service on the Java-first Akka Java SDK
**Performance Goals**: `POST /ask` unchanged (it is not touched). `POST /evaluate` costs three model
calls (one answer + two verdicts) and is explicitly not a low-latency surface.
**Constraints**: capability 8 and capability 12 production sources **byte-identical** (SC-003); domain
layer free of Akka imports; component payloads Java-shaped, HTTP DTOs idiomatic Scala; every functional
behaviour offline-provable (R3 makes this achievable).
**Scale/Scope**: one new package, one agent, one plain orchestration class, one endpoint, two domain
files; two descriptor lines; one config key.

## Constitution Check

*GATE: evaluated before Phase 0 and re-evaluated after Phase 1 design.*

| Principle | Assessment | Verdict |
|---|---|---|
| **I. Akka SDK First** | The judge is an SDK `Agent`; the built-in judge is an SDK-provided component; the surface is an SDK HTTP endpoint; verdicts reach metrics/traces through the SDK's own `EvaluationResult` detection (R2), not hand-rolled telemetry. **No new dependency** — R3 removed the last reason to reach for a test double of our own. | ✅ PASS |
| **II. Design Principles** | *Domain independence*: applicability rules are pure predicates over strings with no Akka import; capability 8's sentinels are passed **in** as parameters (D6) so `domain` never depends on `application`. *API isolation*: the endpoint owns idiomatic `Option`-typed request/response types; the SDK's `HallucinationEvaluator.Result` never appears on the wire. *Single responsibility*: retrieval (cap-8's store), answering (cap-8's agent), judging (two judges), orchestration (`AnswerEvaluator`), HTTP (endpoint) are five separate things. *Descriptive naming*: `DeclineJudge`, `AnswerEvaluator`, `EvaluationEndpoint`, `EvaluationApplicability`. | ✅ PASS |
| **III. Test Coverage** | Domain rules unit-tested; the authored judge, the built-in judge, the endpoint, attribution, the errored outcome, the disabled flag and the refusal path are all integration-tested offline (R3). A test pins the descriptor requirement (R2) and a test pins parity with `/ask` (D9). | ✅ PASS |
| **IV. Simplicity** | Two judges, not five (spec). One switch mechanism, not two (D8 — `disabledComponents` rejected with reason). No entity, no view, no consumer, no stored history. One deliberate ~5-line duplication, declared in research and **converted into a checked invariant** by the parity test rather than abstracted away. | ✅ PASS |

**Post-design re-evaluation**: unchanged — no violation was introduced by Phase 1. The
**Complexity Tracking** table is therefore omitted (no violations to justify).

One judgement call worth naming rather than burying: D9's duplication is the kind of thing Constitution
IV would normally push back on. It is accepted because the alternative — extracting a shared pipeline —
edits capability 8 and breaks SC-003, and because the duplication is *the thing under test*: it must
reproduce cap-8's sequence exactly, and a failing parity test is a better guarantee of that than shared
code would be an excuse for not checking.

## Project Structure

### Documentation (this feature)

```text
specs/015-llm-judge-evaluation/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — R1..R6 resolved from the jars
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── evaluate-endpoint.md
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/akka.tasks — not created here)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/eval/
├── domain/
│   ├── EvaluationApplicability.scala   # pure: is this subject judgeable? (refused / no reference material)
│   └── ReferenceText.scala             # pure: passages -> the reference text a judge is given
├── application/
│   ├── DeclineJudge.scala              # @Component(id="decline-judge") @AgentRole("evaluator")
│   │                                   #   Agent; Result implements EvaluationResult  (R2)
│   └── AnswerEvaluator.scala           # plain class (NOT a component): retrieve -> answer -> judge
└── api/
    └── EvaluationEndpoint.scala        # POST /evaluate; idiomatic Option-typed DTOs

src/main/resources/
├── META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf   # + DeclineJudge, + EvaluationEndpoint
└── application.conf                                                     # + eval.enabled

src/test/scala/com/gwgs/akkaagentic/eval/
├── domain/
│   ├── EvaluationApplicabilityTest.scala
│   └── ReferenceTextTest.scala
└── api/
    ├── EvaluationEndpointIntegrationTest.scala        # happy paths, attribution, parity, validation
    ├── EvaluationErrorsIntegrationTest.scala          # errored verdict, refusal -> not-applicable
    └── EvaluationDisabledIntegrationTest.scala        # eval.enabled = false
src/test/scala/com/gwgs/akkaagentic/eval/application/
├── BuiltInJudgeIntegrationTest.scala                  # R1 + R3: dynamicCall to an SDK-owned agent, scripted offline
└── EvaluatorDescriptorTest.scala                      # R2: the descriptor + EvaluationResult requirements

UNCHANGED (asserted, not assumed):
  src/main/scala/com/gwgs/akkaagentic/docs/**          # capability 8 + capability 12's one edit
  src/main/resources/application.conf guardrails block # capability 12
```

**Structure Decision**: a new top-level capability package `com.gwgs.akkaagentic.eval.*` in the
project's standard `domain` / `application` / `api` layering. It is deliberately *not* placed inside
`docs.*`: capability 8's package must stay untouched for SC-003, and a separate package makes
"capability 13 changed nothing in capability 8" checkable with `git diff --stat`.

## Phase 1 design notes

### The two judges

| | Built-in | Authored |
|---|---|---|
| Component id | `hallucination-evaluator` (SDK) | `decline-judge` (ours) |
| Registered by | `ComponentLocator$.agentProvidedComponents` — automatic | our descriptor, key `agent` |
| Request | `HallucinationEvaluator.EvaluationRequest(query, referenceText, answer)` — a Java record | `DeclineJudge.EvaluationRequest(question, referenceText, answer)` — a Java-**shaped** Scala case class |
| Result | `HallucinationEvaluator.Result(explanation, passed)` | `DeclineJudge.Result(explanation, passed) extends EvaluationResult` |
| Called from Scala by | `dynamicCall("hallucination-evaluator")` | `dynamicCall("decline-judge")` |
| Offline scripting | `withModelProvider(classOf[HallucinationEvaluator], provider)` (R3) | `withModelProvider(classOf[DeclineJudge], provider)` |

`DeclineJudge` follows the SDK's own judge shape exactly — a `ModelResult(explanation, label)` parsed
by `responseConformsTo`, mapped to a `Result` implementing `EvaluationResult`, with
`MemoryProvider.none()` — so that ours and the SDK's are indistinguishable to a consumer (SC-007), and
so the `label -> passed` translation is where malformed model output is caught (FR-005's *errored*).

Its labels are `appropriate` / `inappropriate`, and its system message must state the two-sided rule
explicitly: a decline is *appropriate* only when the reference text does not answer the question, and an
**answer** is inappropriate when the reference text does not support one. That two-sidedness is what
makes it cover a criterion no built-in does (US2 scenarios 2 and 3).

### Orchestration (`AnswerEvaluator`)

A plain class constructed by the endpoint with `(componentClient, knowledgeStore)` — not a component, so
it needs no descriptor entry and no injection of its own (only components and endpoints are injected).
Sequence, per request:

1. Retrieve top-3 (cap-8's `TopK`, duplicated knowingly — D9).
2. `dynamicCall[DocsAgent.Request, String]("docs-agent")` — the same call `DocsEndpoint` makes, so
   capability 12's guardrails apply here too (that is what makes the refusal path reachable, R6).
3. Decide applicability **in the domain**, before any judge runs: a `BlockedPrefix` reply or empty
   passages yields `NotApplicable` and **no model calls**.
4. Otherwise call both judges, independently, each in its own session, each wrapped so that a failure
   becomes an **errored** verdict rather than a failed request (FR-007).

### Configuration

```hocon
eval {
  enabled = true
  enabled = ${?EVAL_ENABLED}
}
```

read through an injected `com.typesafe.config.Config` (the cap-10 `McpClientAgent` pattern). When off,
`POST /evaluate` still answers and returns an empty verdict list — the answer path is never the thing
that breaks (FR-007/FR-009). `ServiceSetup.disabledComponents` was considered and rejected in D8.

### Descriptor

Two new lines — the first descriptor change since capability 11, and a pointed contrast with capability
12's zero:

```hocon
agent = [ ..., "com.gwgs.akkaagentic.eval.application.DeclineJudge" ]
http-endpoint = [ ..., "com.gwgs.akkaagentic.eval.api.EvaluationEndpoint" ]
```

The three built-in judges are **not** listed — they are provided components (R1c). A test asserts both
halves of that sentence, so a future SDK change breaks the suite rather than the service.

### Testing strategy

Everything functional is offline (R3). The seams:

- `withModelProvider(classOf[DocsAgent], m1)` scripts the **answer**;
  `withModelProvider(classOf[HallucinationEvaluator], m2)` and
  `withModelProvider(classOf[DeclineJudge], m3)` script the **verdicts** — three independent providers in
  one TestKit, which is itself a first for this project.
- The **errored** outcome is provoked deterministically by scripting a judge to reply with an
  unrecognised `label`, which makes the SDK's own `toEvaluationResult` throw (R3). Nothing is broken to
  produce it.
- The **refusal** path is driven end to end by sending capability 12's jailbreak text, which is blocked
  upstream by a rule cap-13 does not configure.
- **Parity** (D9): `/evaluate`'s answer and citations equal `/ask`'s for the same question and the same
  scripted answer, and the reference text equals an independent `KnowledgeStore.retrieve` rendering.
- **Fixture hazard**: scripted answers must avoid `http`, `www.` and `see also:` — capability 12's
  `linked answer guard` is enforcing on `docs-agent` responses.

Live verification is a supplementary smoke test against Ollama and asserts nothing about verdict values.

## Risks

| Risk | Mitigation |
|---|---|
| `dynamicCall`'s handler lookup uses `Class.getMethods().find(...)`; a class with more than one public `Effect`-returning method would be ambiguous | `HallucinationEvaluator` has exactly one (`evaluate`); `LlmAsJudge`'s helpers are `protected`. `DeclineJudge` will also have exactly one, per the SDK's one-handler-per-agent rule. Covered by the built-in-judge test. |
| A judge that silently is not recognised as an evaluator (return type misses `EvaluationResult`) | `EvaluatorDescriptorTest` asserts the result type implements it — the quiet failure mode R2 identified |
| Three model calls per `/evaluate` request in a live environment | Not on the answer path; off by one config key (FR-009); documented as a cost, not hidden |
| Judge verdict values are non-deterministic | Rule, not caveat: no test asserts a live verdict's value; scripted verdicts only |
