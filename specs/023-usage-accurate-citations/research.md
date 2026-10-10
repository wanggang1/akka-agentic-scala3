# Research: Usage-accurate citations (capability 21, fork B4 / feature 023)

Phase 0. Findings are recorded here **as measured** (CLAUDE.md). Anything not yet measured is marked
UNVERIFIED and carries no confident prose.

## R1 — Structured output on the local model (qwen3:8b via Ollama)

**Decision:** The new agent uses `responseConformsTo(classOf[CitedAnswer])`, a Java-shaped reply record
carrying `answer` + `usedSources`.

**Rationale:** `application.conf` sets `model-provider = ollama`, `model-name = "qwen3:8b"`. Structured
output via `responseConformsTo` is already proven on this exact model by `DeclineJudge` (cap-15),
`CompactionAgent` (cap-17) and `GreetingAgent` (cap-1) — all parse a Java-shaped record back from qwen3.
This surface has **no tools**, so the one known structured-output hazard does not apply: the
`[[gemini-tools-vs-structured-output]]` finding is that *function-calling + JSON mime type together* are
rejected (Gemini), forcing `responseAs` + `onFailure`. With no tools, `responseConformsTo` is the right
call. (If run under Gemini via `MODEL_PROVIDER=googleai-gemini`, the same no-tools reasoning holds.)

**Alternatives considered:** `responseAs(classOf[...])` with hand-written JSON instructions — only needed
when `responseConformsTo` is unavailable (tools present). Rejected: no tools here.

**UNVERIFIED until implementation:** whether qwen3:8b reliably emits `usedSources` as a JSON string array
of the *exact* parenthesized source labels it was shown. This is **the** thing B4 exists to measure
(FR-010/SC-005); the endpoint defends against unreliability by intersecting with the retrieved set (R4).

## R2 — New agent, not a change to `DocsAgent`

**Decision:** Add a **new** component `CitingDocsAgent` (`@Component(id = "citing-docs-agent")`) in
`docs.application`, leaving `docs-agent` and its `ask: Effect[String]` untouched.

**Rationale:** A request-based `Agent` allows exactly one command handler (AGENTS.md), and `DocsAgent.ask`
returns a bare `String`. A structured, usage-reporting reply is a different return type, so it must be a
second agent. FR-001 requires cap-8 be untouched; a new component satisfies it by construction. The input
`Request`/`Passage` (Java-shaped wire types) are **reused** from `DocsAgent` read-only — retrieval and the
grounding context are identical, so duplicating them would be gratuitous (constitution §IV Simplicity).

**Descriptor:** Scala components are not discovered by the javac processor
(`[[scala-akka-component-descriptor]]`), so the new agent gets a hand-added line under `agent` and the new
endpoint a line under `http-endpoint` in
`META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`. A missing line fails startup for the
whole service → `mvn clean verify` is the gate (CLAUDE.md).

## R3 — How decline and failed turns travel through a structured reply

**Decision:** Decline = the model sets `answer` to the shared `DocsAgent.DontKnow` sentinel with an empty
`usedSources`. A failed/malformed turn is absorbed by `onFailure` into the **same** decline shape
(`CitedAnswer(DontKnow, [])`), so the endpoint maps it to a `200` decline that cites nothing.

**Rationale:** Reusing `DocsAgent.DontKnow` keeps the two surfaces' decline semantics identical and the
sentinel defined once. cap-8 carried a *separate* `FailedPrefix` only because `POST /evaluate` must not
judge a failure as a decision; **this surface has no evaluator consumer**, so collapsing failed→decline is
correct and simpler here (constitution §IV). `onFailure` must return the reply type `T`, so its fallback is
a `CitedAnswer`, not a tagged `String` — a structural difference from cap-8's `onFailure` that returns a
`String`. A log line is kept on the failure path (a silently swallowed exception was cap-6's invisible
bug, `[[cap6-graceful-null-handling-followup]]`).

**Alternatives considered:** a dedicated `declined: Boolean` field on `CitedAnswer`. Rejected: models emit
a word/sentinel more reliably than a JSON boolean (the `DeclineJudge` rationale), and reusing the existing
sentinel avoids a second decline convention.

## R4 — Where the citation decision lives, and the intersection floor

**Decision:** A pure domain function `UsageCitations.select(reportedUsed, retrievedLabels, declined)`
computes the usage-accurate list; the endpoint calls it. Rule:
- `declined` → `Nil` (FR-005; a decline overrides any reported usage).
- else → `reportedUsed` **intersected with** `retrievedLabels`, de-duplicated, order preserved from
  `retrievedLabels` (stable, matches cap-8's ordering). A reported label not in `retrievedLabels` is
  dropped (FR-004 — it was never offered, so it cannot have been used).
- an intersection that comes out empty on a non-decline answer → `Nil` (FR-007, the user's resolved
  choice: **cite nothing**, never fall back to cap-8's full set).

**Rationale:** Putting the logic in the domain keeps it framework-free and unit-testable in isolation
(constitution §II, §III) — the whole decision is provable without a runtime. The intersection is the
**honesty floor**: even though B4 reopens self-report (cap-7 D6), a *cited* label is still guaranteed to be
one that was genuinely retrieved — self-report can only ever *narrow* the ground-truth set, never invent a
citation. That is the precise, defensible sense in which B4 is "more honest about usage" without being
fully at the model's mercy.

**Ground-truth kept alongside (FR-006):** the response carries both `citedSources` (usage-accurate) and
`retrievedSources` (= what cap-8's `/ask` would cite). Divergence is observable from one response, which is
what makes the measurement (R6) possible from the wire alone.

## R5 — Guardrail parity (scope decision)

**Decision:** The new surface is **not** attached to capability 12's guardrails. `application.conf`
attaches every rule to `agents = ["docs-agent"]`; `citing-docs-agent` is a new id and is therefore
ungoverned. We do **not** add it.

**Rationale:** cap-12 is a separate capability; wiring governance onto a new surface is scope creep for a
*citation-accuracy* capability (constitution §IV, YAGNI). The grounding contract is unchanged — the agent
still answers only from supplied passages and declines otherwise — so the surface is not *functionally*
less safe for its purpose; it simply lacks the jailbreak defense-in-depth. This is a deliberate, recorded
scope boundary, not an oversight. Making it governed later is a one-line config add (append the id to each
rule's `agents`). **Flagged for the user at the plan gate** in case they want parity now.

## R6 — Measuring self-report faithfulness (FR-010 / SC-005)

**Decision:** Correctness of `select` and the decline/intersection rules is proven **offline** with a
deterministic `TestModelProvider` fixed response (per `[[akka-testing-slow-and-failing-calls]]` and cap-8's
own endpoint IT). The self-report *faithfulness* finding requires a **live** run against qwen3:8b — a small
set of in-corpus questions — recorded as a README "live Q-H" block and summarised back into this file, as
every prior capability has done.

**What the measurement records, per question:** the retrieved set, the model's `usedSources`, the
usage-accurate intersection, and a judgement of subset-faithfulness (no hallucinated labels — guaranteed by
R4; and whether the model under-reported a genuinely-used source, judged by reading the answer). The
headline number is how far self-report narrows the retrieved set and whether that narrowing is trustworthy.

**MEASURED (2026-10-10, live, Ollama `qwen3:8b`).** Six runs — five in-corpus grounded answers, one
out-of-corpus decline. Each retrieved K=3.

| # | Question (abbrev) | retrieved | model `usedSources` → `citedSources` | verdict |
|---|---|---|---|---|
| 1 | survive a restart | durability-tasks, cap-3-help-desk, cap-4-session-memory | **[]** → [] | **under-reported** (answer clearly used durability-tasks) |
| 2 | coordinator picks specialist | cap-7-activity-coordinator, cap-6-delegation, cap-3-help-desk | [cap-7-activity-coordinator] → **[cap-7-activity-coordinator]** | **faithful + precise** (1 of 3) |
| 3 | why Java not Scala | interop-method-ref-wall, interop-two-mapper, durability-tasks | [interop-method-ref-wall, interop-two-mapper] → **same (2 of 3)** | **faithful + precise** (dropped the irrelevant 3rd) |
| 4 | remember across requests | cap-6-delegation, cap-4-session-memory, cap-1-greeting | **[]** → [] | **under-reported** (answer clearly used cap-4-session-memory) |
| 5 | capital of France (out-of-corpus) | cap-3-help-desk, cap-1-greeting, cap-7-activity-coordinator | decline → **[]** | **correct decline** |
| 6 | survive a restart (re-run of #1) | durability-tasks, cap-3-help-desk, cap-4-session-memory | **[]** → [] | **under-reported, stable** |

**The finding — the unreliability is UNDER-reporting, not hallucination.**
- **The honesty floor held perfectly:** across every run, *no* cited label was ever one that was not
  retrieved — 0 invariant violations (SC-002). When qwen3 named a source, it was always a real one, and on
  #2/#3 it even narrowed correctly to exactly the passages the answer used. So the half of the cap-7-D6
  fear that is about *fabricated* citations did **not** materialise: the intersection prevents it by
  construction, and the model never even tried.
- **But qwen3:8b under-reports badly:** 3 of 5 grounded answers (#1, #4, #6 — and #1/#6 show it is *stable*,
  not a fluke) returned an **empty** `usedSources` despite an answer plainly grounded in a retrieved
  passage. B4 then cites nothing. Precision is excellent *when it reports*; the recall of the reporting
  itself is poor on an 8B local model.
- **This vindicates FR-007 ("cite nothing").** Because we did **not** fall back to cap-8's full retrieved
  set, the under-report is **visible in the wire output** (`citedSources: []` beside a non-empty
  `retrievedSources` and a real answer) — which is the whole point of the measurement. Falling back would
  have silently masked it.
- **The crisp comparison with cap-8:** on the very question cap-8's README uses to illustrate
  *over*-citation ("survive a restart" → cap-8 cites all three), B4 on this model cites **nothing** — it
  trades cap-8's over-citation for under-citation. Neither is "correct"; they are opposite failure modes of
  the same hard problem, and B4's is at least honest about its own uncertainty rather than confidently
  over-inclusive. A larger/stronger model would likely report more of its used sources (the prompt and
  structured schema are not the bottleneck — #2/#3 prove the model *can* do it); that is the natural
  follow-up, and needs a model beyond the free-tier local one.

## R7 — Endpoint surface

**Decision:** New endpoint class `CitedDocsEndpoint` (`@HttpEndpoint`, `POST /cited-ask`), injecting
`ComponentClient` + `KnowledgeStore`, calling the new agent via
`dynamicCall[DocsAgent.Request, CitedAnswer]("citing-docs-agent")` (Scala can't use Java method refs —
`[[scala-akka-componentclient-dynamiccall]]`). A separate class (not a method on `DocsEndpoint`) keeps
cap-8's file literally untouched (FR-001) and the surfaces independently testable.

**Validation reused:** `AskQuestion.validate` (blank/absent → `400`, no retrieval, no model call — FR-008).
Retrieval reused: `knowledgeStore.retrieve(question, TopK=3)`.
