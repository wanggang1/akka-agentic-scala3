# Data Model: LLM-as-judge evaluation (cap-13)

**Feature**: `015-llm-judge-evaluation` · **Date**: 2026-09-05

No persistent state is introduced. Everything below is computed per request and discarded; there is no
entity, no view, no journal and no stored history of verdicts (spec, Out of Scope). What *does* persist
is telemetry — verdicts reach metrics and traces through the SDK's own evaluator detection (research
R2), which is why FR-011 needs no data of ours.

Three layers, and the boundary rules that separate them (README §3, Constitution II):

| Layer | Serialized by | Shape rule |
|---|---|---|
| `eval.domain` | nothing — never leaves the JVM | idiomatic Scala; **no Akka import** |
| `eval.application` (agent payloads) | the SDK's **internal** mapper | **Java-shaped**: explicit `@JsonCreator`/`@JsonProperty`, `java.util.List` |
| `eval.api` (HTTP bodies) | `JsonSupport`'s mapper, Scala module registered by `Bootstrap` | idiomatic Scala; `Option` fields |

---

## Domain (`com.gwgs.akkaagentic.eval.domain`)

### `ReferenceText`

Pure rendering of retrieved passages into the single string a judge is given as *reference text*.

| Function | Signature | Rule |
|---|---|---|
| `render` | `(passages: List[(String, String)]) => String` | Each passage rendered as `[n] (source) text`, newline-separated, in the order given — deliberately the **same** shape `DocsAgent` renders into its user message, so the judge sees what the assistant saw |
| `isEmpty` | `(text: String) => Boolean` | Blank or whitespace-only |

Takes `(source, text)` pairs rather than cap-8's `KnowledgeStore.Retrieved`, so the domain depends on
nothing from `application`.

### `EvaluationApplicability`

Pure decision: **is this subject judgeable at all?** Evaluated *before* any judge is called, so an
unjudgeable subject costs zero model calls (research R6).

```scala
enum Applicability:
  case Applicable
  case NotApplicable(reason: String)
```

| Function | Signature |
|---|---|
| `of` | `(answer: String, referenceText: String, refusalPrefix: String) => Applicability` |

Rules, in order:

1. `answer` starts with `refusalPrefix` → `NotApplicable("the interaction was refused by a guardrail")`
2. `referenceText` is empty → `NotApplicable("no reference material was retrieved")`
3. otherwise → `Applicable`

**The refusal prefix is a parameter, not an import** (research D6). Capability 8's
`DocsAgent.BlockedPrefix` lives in `application`; the domain must not depend on it, so the caller
supplies it — the same technique capability 12 used for `AnswerRules.firstExternalReferenceMarker`.

> Note on a **decline**: a decline is *not* inapplicable. Judging whether a decline was warranted is the
> entire point of the authored judge (US2). Only a **refusal** — capability 12's governance block — is
> inapplicable, because there is no answer to judge. Conflating the two would silently delete the
> capability's second half.

---

## Application (`com.gwgs.akkaagentic.eval.application`)

### `DeclineJudge` — the authored evaluator

An ordinary `Agent`, `@Component(id = "decline-judge")`, `@AgentRole("evaluator")`, one command handler.

```scala
final case class EvaluationRequest @JsonCreator() (      // crosses the internal mapper -> Java-shaped
    @JsonProperty("question")      question: String,
    @JsonProperty("referenceText") referenceText: String,
    @JsonProperty("answer")        answer: String)

final case class ModelResult @JsonCreator() (            // what the model must emit
    @JsonProperty("explanation") explanation: String,
    @JsonProperty("label")       label: String)          // "appropriate" | "inappropriate"

final case class Result @JsonCreator() (                 // the verdict
    @JsonProperty("explanation") explanation: String,
    @JsonProperty("passed")      passed: Boolean)
  extends EvaluationResult
```

- **`Result extends akka.javasdk.agent.EvaluationResult` is load-bearing, not decorative.** It is the
  *only* thing that makes the runtime treat this agent as an evaluator and route its verdicts into
  metrics and traces (research R2 — `Reflect$.isEvaluatorAgent` tests exactly this). Removing the
  `extends` leaves a compiling, working, silently un-instrumented agent.
- `ModelResult.toResult` maps `appropriate → passed = true`, `inappropriate → false`, and **throws on
  any other label** — mirroring the SDK's own judges, and giving the *errored* verdict a deterministic
  trigger (research R3).
- The judgement is two-sided by design: a decline is appropriate only when the reference text does not
  answer the question, and an *answer* is inappropriate when the reference text does not support one.

### `AnswerEvaluator` — orchestration (a plain class, **not** a component)

Constructed by the endpoint with `(componentClient, knowledgeStore)`.

```scala
final case class Verdict(judge: String, outcome: Outcome, explanation: String)

enum Outcome:
  case Passed, Failed, Errored, NotApplicable

final case class Evaluation(
    question: String,
    answer: String,
    citedSources: List[String],
    referenceText: String,
    verdicts: List[Verdict])
```

| Field | Derivation |
|---|---|
| `answer` | `dynamicCall[DocsAgent.Request, String]("docs-agent")` — the same call `DocsEndpoint` makes, so capability 12's guardrails apply |
| `citedSources` | retrieved source labels, distinct; **empty** on a decline or a refusal — capability 8's rule, reproduced and then pinned by the parity test (research D9) |
| `referenceText` | `ReferenceText.render(retrieved)` |
| `verdicts` | one per judge; `NotApplicable` for both when `EvaluationApplicability.of` says so, otherwise the judge's `passed` mapped to `Passed`/`Failed`, or `Errored` if the call threw |

`Outcome` has four cases on purpose. `Errored` and `NotApplicable` are **not** `Failed`: a judge that
could not form an opinion, and a subject that cannot be judged, are different from a verdict of "no"
(FR-005, FR-012, SC-006). Collapsing either into `Failed` would report a working system as a broken one.

---

## API (`com.gwgs.akkaagentic.eval.api`)

Idiomatic Scala; `Option` on the way in, plain fields on the way out.

```scala
@JsonIgnoreProperties(ignoreUnknown = true)
final case class EvaluateRequest(question: Option[String])

final case class VerdictReply(judge: String, outcome: String, explanation: String)

final case class EvaluateReply(
    question: String,
    answer: String,
    citedSources: List[String],
    verdicts: List[VerdictReply])
```

`outcome` is rendered as a lowercase, hyphenated string (`passed`, `failed`, `errored`,
`not-applicable`) rather than exposing the enum, so the wire contract does not move when the enum does
(Constitution II, API isolation). `referenceText` is **not** returned: it can be large, and the
contract's promise is that the verdict was formed from the retrieved passages — which the parity test
proves and `citedSources` identifies.

---

## Validation

Reuses capability 8's `AskQuestion.validate` unchanged — a blank or absent question is rejected `400`
before retrieval, before the assistant, and before any judge (FR-010). No new validation type is
introduced, and capability 8's domain is read, never modified.

## What is deliberately absent

| Not modelled | Why |
|---|---|
| A stored `Evaluation` entity / history | Out of scope; verdicts live in the response and in telemetry |
| A score or confidence number | `EvaluationResult` is a boolean plus an explanation; inventing a score would be fabricating precision the judge did not express |
| A judge registry of our own | `AgentRegistry.agentsWithRole("evaluator")` already lists both ours and the SDK's (research R2) |
| Expected answers / a golden dataset | That is an evaluation *harness* — a successor capability, explicitly out of scope |
