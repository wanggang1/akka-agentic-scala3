# Phase 1 Data Model: Agent Guardrails (cap-12)

**Feature**: `014-agent-guardrails` | **Date**: 2026-09-03

This capability introduces **no persistent state**. There is no entity, no view, no workflow, and no
task — nothing is stored, and nothing survives a restart, because a guardrail evaluates one piece of
text and returns a verdict. What follows are the value types involved and where each one comes from.

## Types owned by the SDK (we consume, not define)

### `Guardrail.Result` — the verdict

`akka.javasdk.agent.Guardrail.Result`, a Java record.

| Field | Type | Meaning |
|---|---|---|
| `passed` | `boolean` | `true` = the text is acceptable |
| `explanation` | `String` | why — surfaced in logs, metrics and traces (FR-003) |

Static `Guardrail.Result.OK` is the passing constant. **Note**: the published documentation calls
this `TextGuardrail.Result`; that type does not exist (research R1/divergences).

### `GuardrailContext` — a rule's own settings

`akka.javasdk.agent.GuardrailContext`: `name(): String` and `config(): com.typesafe.config.Config`.
The `Config` is the rule's *own* configuration section, so a rule reads its settings by plain key
(`ctx.config.getInt("max-sentences")`), not by absolute path.

### `TextGuardrail` — the interface implemented

`Guardrail.Result evaluate(String text)`. The input is **only** the text: no question/answer pairing,
no retrieved passages, no session identity. This is the modelling constraint that shapes everything
below (spec Assumptions), and the reason a true grounding check is out of scope.

## Types this feature defines

### `LinkedAnswerGuard` (response side, enforcing)

A rule: *an answer must not direct the reader to an external web source.* The corpus contains no
links, so a link is evidence the model answered from outside its sources — a **proxy** for
ungroundedness, not proof of it (spec Assumptions).

- **Constructor**: `(ctx: GuardrailContext)` — the settings-taking form (research R1, attempt 1).
- **Setting**: `link-markers: [String]` — substrings that indicate an external reference, read from
  the rule's own config section, defaulted in `reference.conf`-style application config rather than
  hard-coded, so FR-007 is satisfied by construction.
- **Verdict**: fails when the text contains any marker; the explanation names the marker found.
- **Neutral cases**: empty/blank text passes (edge case); the decline sentinel passes (SC-003).

### `AnswerLengthGuard` (response side, record-only)

A rule: *an answer should be at most a few sentences,* mirroring the instruction in cap-8's system
message. A style rule, not a safety one — hence record-only (US3).

- **Constructor**: **no parameters** — the no-settings form (research R1, attempt 2). Its existence
  is the point: it exercises the loader's second path, which the documentation does not mention.
- **Threshold**: a constant in the class. Deliberately *not* configurable — that is what makes it the
  no-arg form, and the contrast with `LinkedAnswerGuard` is the finding.
- **Verdict**: fails when the sentence count exceeds the constant; the explanation reports the count.

### `ObjectFormGuard` (negative probe — **not** shipped in production config)

A Scala `object` implementing `TextGuardrail`, used only by the test that proves the runtime
**cannot** construct it (research R1). It exists to convert a bytecode prediction into a recorded
result, and is never referenced by `application.conf`.

### `BlockedReply` (API type)

The endpoint's representation of a blocked interaction — see `contracts/ask-endpoint.md`.

| Field | Type | Meaning |
|---|---|---|
| `blocked` | `Boolean` | always `true`; makes the shape unambiguous to a client |
| `rule` | `String` | the configured rule name that fired |
| `category` | `String` | e.g. `JAILBREAK`, `HALLUCINATED` |
| `explanation` | `String` | the rule's own explanation |

## Configuration as data

Rules are *declared*, not constructed, so the configuration **is** part of this feature's data model.

| Rule name | Class | Category | Side | Mode |
|---|---|---|---|---|
| `default jailbreak` | `akka.javasdk.agent.SimilarityGuard` (SDK) | `JAILBREAK` | `model-request` | enforcing |
| `linked answer guard` | `…docs.application.LinkedAnswerGuard` | `HALLUCINATED` | `model-response` | enforcing |
| `answer length guard` | `…docs.application.AnswerLengthGuard` | `FORMAT` | `model-response` | record-only |

All three attach via `agents = ["docs-agent"]`. The first is the SDK's own pre-declared rule, enabled
by overriding that one key (research R2).

## What is deliberately absent

- **No descriptor entry.** Guardrails are not components (research R5); the hand-maintained
  `META-INF` descriptor is unchanged, and that being true with a green suite is the evidence.
- **No new dependency.** The jailbreak rule's embedding model is already on the classpath (research R2).
- **No persisted audit record.** Evaluations go to logs, metrics and traces via the runtime; building
  a queryable audit store is explicitly out of scope.
