# Phase 0 Research: Agent Guardrails (cap-12)

**Feature**: `014-agent-guardrails` | **Date**: 2026-09-03
**Method**: bytecode and packaged-config inspection of `akka-javasdk-3.6.3.jar`,
`akka-javasdk-testkit-3.6.3.jar` and `akka-runtime-core_2.13-1.6.15.jar`. Every decision below cites
what was actually read, not what the documentation says. Where the two diverge, that is recorded.

---

## R1 — How the runtime constructs a guardrail, and which Scala class forms it accepts

**Question**: Guardrails are named in configuration by class-name *string*. What does the runtime do
with that string, and which Scala class shapes satisfy it? This is the capability's headline interop
question.

**Evidence — `GuardrailProvider.createGuardrail` (decompiled)**:

```scala
system.dynamicAccess.createInstanceFor[Guardrail](
    cfg.implementationClass,
    (classOf[GuardrailContext] -> new GuardrailContextImpl(cfg.name, cfg.config)) :: Nil)
  .recoverWith { case _ => system.dynamicAccess.createInstanceFor[Guardrail](cfg.implementationClass, Nil) }
  .recoverWith { case _ => Failure(/* names the guardrail and akka.javasdk.agent.Guardrail */) }
  .get
```

Corroborated verbatim by the SDK's own packaged `reference.conf` (lines 164–166), which is more
precise than the published documentation:

> `class`: implementation class of the guardrail, must implement `akka.javasdk.agent.TextGuardrail`,
> **be public and have a public constructor**, optionally with a `akka.javasdk.agent.GuardrailContext`
> constructor parameter

**Decision**: Author custom guardrails as **top-level Scala `class`es**, and cover **both**
constructor forms the loader tries:

| Form | Scala source | Compiles to | Expected |
|---|---|---|---|
| Settings-taking | `class G(ctx: GuardrailContext) extends TextGuardrail` | `public G(GuardrailContext)` | loads on attempt 1 |
| No-settings | `class G extends TextGuardrail` | `public G()` | attempt 1 fails, loads on attempt 2 |
| **Companion `object`** | `object G extends TextGuardrail` | `G$` with a **private** ctor + `MODULE$` | **fails both attempts** |

**Rationale**: `DynamicAccess.createInstanceFor` matches a constructor by **exact declared parameter
types** and requires it to be accessible. A Scala `object` compiles to a final class whose only
constructor is *private*, reachable solely through the static `MODULE$` field — nothing in the two
attempts looks for that. This is the same failure mode as cap-11 R2's inner-class `TableUpdater`
(`U($outer)`, no zero-arg ctor), arrived at through an entirely different SDK mechanism, which is
what makes it a *hazard class* rather than a one-off.

**Two Scala-specific traps worth stating**, both following from "exact declared parameter types":
- A **curried** or **implicit/using** parameter list changes the erased constructor signature and
  will not match. Keep it a single, plain parameter list.
- A **`case class`** would also work (its constructor is public), but adds `apply`/`unapply`/equality
  that nothing uses — Simplicity says a plain `class`.

**To verify empirically** (this is a prediction until it runs): all three forms, with the `object`
form as a deliberate negative probe. The negative result is as much the deliverable as the positive
ones; it is what turns "use a class" from folklore into a documented rule.

**Alternatives considered**: authoring the guardrails in Java to sidestep the question entirely —
rejected, since resolving the question *is* the capability. Registering guardrails programmatically —
not offered by the SDK; configuration is the only registration path.

---

## R2 — The jailbreak guard: what `SimilarityGuard` actually is, and whether it can run offline

**Question**: Does the built-in `SimilarityGuard` need an embedding provider, a model, or network
access? FR-011 requires the jailbreak rule to work offline with no new dependency.

**Evidence**:
1. The SDK's `SimilarityGuard.evaluate(String)` **throws
   `IllegalStateException("Not expected to be called")`**. It is a *configuration holder*, not an
   implementation — it exposes only `threshold()` and `badExamplesResourceDir()`.
2. `GuardrailProvider$` special-cases it: `instanceof SimilarityGuard → SpiAgent$SimilarityGuard`,
   handing it to the runtime rather than adapting it like a normal `TextGuardrail`.
3. The runtime's `SimilarityGuardCache.createSimilarityGuard` constructs
   **`new AllMiniLmL6V2QuantizedEmbeddingModel()`** and **`new InMemoryEmbeddingStore()`**, then
   `SimilarityGuard$.initializeEmbeddingStore(store, model, examples)`; instances are cached per
   configuration.
4. The runtime jar contains **no** MiniLM artifact of its own (`unzip -l | grep -c minilm` → `0`), so
   that class is resolved from the application classpath.
5. The SDK jar ships **10 jailbreak example prompts** at `guardrail/jailbreak/prompt-{1..10}.txt`.

**Decision**: Use the built-in `SimilarityGuard` for the request side. It runs **fully offline** —
the same in-process quantized all-MiniLM-L6-v2 ONNX model capability 8 already uses, with an
in-memory store, no API key and no network.

**Rationale and a notable coupling**: because the runtime resolves the embedding model *from the
application classpath*, the jailbreak guard works here for the same reason cap-8's retrieval does —
`langchain4j-embeddings-all-minilm-l6-v2-q` is in the SDK dependency tree (cap-8 promoted it from
`runtime` to `compile` scope so Scala source could reference it). Cap-12 needs **no** dependency
change, but it is worth recording that a service *without* that artifact on its classpath would fail
to construct this guard.

**A second, larger finding — the guard is already declared, and disabled by exactly one thing.** The
SDK's packaged `reference.conf` ships a complete `"default jailbreak"` guardrail:

```conf
akka.javasdk.agent.guardrails."default jailbreak" {
  class = "akka.javasdk.agent.SimilarityGuard"
  agents = []          # not enabled until agents or agent-roles are defined
  agent-roles = []
  category = JAILBREAK
  report-only = false
  use-for = ["model-request"]
  threshold = 0.75
  bad-examples-resource-dir = "guardrail/jailbreak"
}
```

**Decision**: do **not** declare a new block. Enable the shipped one by overriding a single key:

```conf
akka.javasdk.agent.guardrails."default jailbreak".agents = ["docs-agent"]
```

**Rationale**: Simplicity — this is the smallest possible change that satisfies FR-001, and it
demonstrates the governance claim better than a hand-rolled copy would: the platform ships the
control, and the service *opts an agent into* it. Copying the block would duplicate a threshold and
a resource path we do not own.

**Alternatives considered**: writing our own jailbreak detector (rejected — reinventing a shipped
control, and it would need its own corpus); declaring a fresh `SimilarityGuard` block with our own
examples (rejected for now — no added value, and it hides the "it was already there" finding).

---

## R3 — How a block reaches the caller, and the collision with capability 8's failure fallback

**Question**: FR-005 requires a block to be distinguishable from an honest decline. Cap-8's
`DocsAgent` ends with `.onFailure(_ => DontKnow)`. Does a guardrail block travel that path?

**Evidence**:
- The SDK `reference.conf` (lines 168–170) states the mechanism outright: when a check does not
  pass, *"the execution can either be aborted by **throwing `Guardrail.GuardrailException`** or
  continue anyway."*
- `Guardrail.GuardrailException` exists in the SDK (`public final class … extends RuntimeException`)
  but **nothing in the SDK jar throws it** — the runtime does. `AgentGuardrailInteractions`
  constructs `SpiAgent$GuardrailFailure` and `SpiAgent$AgentException` on the failing path.
- The SDK-side adapter (`GuardrailProvider$SpiGuardrailAdapter`) only *reports*: it maps our
  `Guardrail.Result(passed, explanation)` onto `SpiAgent$Guardrail$Result(passed, explanation)`.
  The abort decision is entirely the runtime's.

**Decision**: Treat "a block is delivered as a thrown exception into the agent's effect pipeline" as
the **working hypothesis**, and make it the **first thing the implementation verifies** — with a test
that asserts a blocked interaction is *not* the `"I don't know"` sentinel. If confirmed, cap-8's
`onFailure` must discriminate: propagate a `GuardrailException`, keep the sentinel for ordinary model
failures. The endpoint then maps the propagated block to its own outcome.

**Rationale**: this is the one place cap-12 must touch capability 8, and the change is precisely
scoped — `onFailure` currently swallows *every* throwable, which was correct when the only throwables
were model failures. A governance event reported to the caller as *"the corpus doesn't cover this"*
is worse than useless: it is unauditable and actively misleading, which is why FR-005 exists.

**Risk if the hypothesis is wrong** (the runtime aborts without surfacing a throwable our handler
sees): the endpoint would instead need to distinguish the outcome some other way. The test is
written to *discover* which world we are in before any production code is changed, so the cost of
being wrong is one test, not a redesign.

**Alternatives considered**: removing `onFailure` from `DocsAgent` entirely — rejected, it is
load-bearing for cap-8's honest-decline behaviour on model failure and its removal would regress
cap-8's tests.

---

## R4 — A misconfigured guardrail must not leave the agent silently unguarded

**Question**: FR-010. What happens if the configured `class` cannot be loaded?

**Evidence**: `createGuardrail`'s third stage is `.recoverWith { … Failure(…) }` followed by
**`.get`** — a `Try.get` on a `Failure` rethrows. The failure message names the guardrail and the
`akka.javasdk.agent.Guardrail` type, i.e. it is built to be read by a human. `createGuardrail` is
called while the provider assembles its per-agent guardrail map, not lazily per request.

**Decision**: Rely on the SDK's own behaviour — a bad class name fails loudly. Verify with a test
that boots the TestKit with a deliberately bad class name and asserts startup fails.

**Rationale**: FR-010 is a real governance property (a typo must not silently disable a control),
and it is already guaranteed; the deliverable is the *evidence*, not new code. This also gives the
negative probe in R1 its assertion mechanism — the `object` form is expected to fail exactly here.

---

## R5 — Are guardrails components? (the hand-maintained descriptor question)

**Question**: This project hand-maintains
`META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` because the SDK's annotation
processor cannot see Scala sources (README §1). Does a guardrail need an entry?

**Evidence**: `GuardrailProvider` reads `akka.javasdk.agent.guardrails` **from `Config`** and
constructs implementations by class name. `TextGuardrail` carries no `@Component` annotation, is not
a component type in `ComponentType`, and never passes through component discovery.

**Decision**: **No descriptor entry.** Guardrails are configuration-registered, like cap-8's
`KnowledgeStore` (a DI-provided dependency) and cap-6's `ForwardTool`/`TodoTools` (plain tool
objects) — all non-components.

**Rationale**: registering them would be wrong, not merely unnecessary — the descriptor is the
component list, and a guardrail is not a component. **Verification is free and worth doing**: the
capability ships with an unchanged descriptor and green tests, which *is* the proof.

---

## R6 — What the caller sees (the three-outcome contract)

**Question**: FR-005 requires answer / decline / block to be distinguishable. The spec deliberately
left the status code to planning.

**Decision**:

| Outcome | Status | Body |
|---|---|---|
| Grounded answer | `200` | `{answer, citedSources:[…]}` — unchanged from cap-8 |
| Honest decline | `200` | `{answer:"I don't know", citedSources:[]}` — unchanged from cap-8 |
| **Blocked** | **`422`** | `{blocked:true, rule, category, explanation}` |
| Invalid input | `400` | unchanged from cap-8 (validation still runs first, FR-009) |

**Rationale**: `422 Unprocessable Content` already carries "well-formed but I will not process this"
in this project's vocabulary — cap-3 and cap-7 use it for a task the agent reports it cannot
complete. Reusing it keeps the service's HTTP dialect consistent. A distinct body shape (`blocked`
plus the rule's identity) is what makes SC-007 attributable from the caller's side, not only from the
logs. Both the request-side and the response-side block use the same shape; which rule fired is
carried in `rule`/`category`, so the caller need not care *where* in the interaction it happened.

**Alternatives considered**: `403 Forbidden` (rejected — implies an authorization decision about the
*caller*, not about the *content*); `200` with a `blocked` flag (rejected — collapses a refusal into
a success, and SC-003 explicitly requires a decline never be confused with a block).

---

## R7 — Offline testability, including the report-only switch

**Question**: FR-012/SC-008 demand the whole suite runs offline. SC-005 demands the enforcing ↔
record-only switch changes caller-visible behaviour with **zero code change**.

**Evidence**: `TestKit.Settings` exposes **`withAdditionalConfig(String)`** and
`withAdditionalConfig(Config)` alongside the familiar `withModelProvider(Class, ModelProvider)`.

**Decision**:
- **Request-side (jailbreak)**: needs *no model at all* — the guard runs before the model call, so a
  blocked request is provable with no `TestModelProvider` response scripted. (A test that asserts
  "the model was never called" is naturally satisfied: no scripted response means any model call
  would fail the test.)
- **Response-side**: drive with `TestModelProvider` scripting the exact answer to be judged — a
  linked answer (blocked), an over-long answer (recorded), an ordinary answer (delivered), and the
  decline sentinel (delivered).
- **SC-005**: two test classes over the **same guardrail class and the same production config**,
  differing only in a `withAdditionalConfig("…report-only = true")` override. The zero-code-change
  claim is then demonstrated by the test suite itself rather than asserted in prose.

**Rationale**: this keeps the project's rule that a finding is proven by a test, not by a README
sentence, and it means the record-only mode costs one config string rather than a second
implementation.

---

## R8 — Attaching rules without touching the agent

**Question**: FR-006 allows attachment by agent id **or** by role (`@AgentRole`).

**Decision**: Attach by **agent id** (`agents = ["docs-agent"]`). Do not introduce `@AgentRole` in
this capability.

**Rationale**: Simplicity/YAGNI. This service has one agent worth guarding; adding a role annotation
to satisfy a mechanism we would not otherwise use is exactly the speculative generality the
constitution forbids. FR-006's substance — *no change to the guarded agent's source* — is fully
satisfied by id-based attachment, and US4's negative scenario (a rule does not fire for an agent it
does not name) is testable by pointing another existing agent at the same endpoint-free path.
`@AgentRole` is noted in the findings as available and unexercised.

**Note**: the one edit to `DocsAgent` that *may* be required (R3's `onFailure` discrimination) is
about **surfacing** a block, not about *declaring* a rule — the agent still names no guardrail, so
FR-006 and SC-006 hold. If R3's hypothesis is disproved, even that edit disappears.

---

## Documentation divergences worth recording

Two places where the published docs and the shipped artifact disagree. Both matter to someone
writing this code from the docs alone:

1. **`TextGuardrail.Result` does not exist.** The guardrails page writes `Result` as if nested in
   `TextGuardrail`; the jar has **`Guardrail.Result`** — a record of `(boolean passed, String
   explanation)` with a static `OK`. `TextGuardrail` declares only
   `Guardrail.Result evaluate(String)`.
2. **The docs omit the zero-arg constructor path.** They describe the `GuardrailContext` constructor
   as the way to get configuration and say it is optional, but do not say what happens without it.
   The bytecode shows an explicit second attempt with no arguments — so a settings-free guardrail is
   a supported, first-class form, not an accident.

Both are the kind of thing this project records in `docs/` rather than in the code.

---

## Summary of decisions

| # | Decision |
|---|---|
| R1 | Custom guardrails are top-level Scala **classes**; ship one `(GuardrailContext)` form and one no-arg form; probe the `object` form as a documented negative |
| R2 | Enable the SDK's **already-declared** `"default jailbreak"` `SimilarityGuard` by overriding `agents` — one config line, no new dependency, fully offline |
| R3 | Verify first that a block arrives as a thrown exception; then make `DocsAgent.onFailure` discriminate block from model failure |
| R4 | Rely on the SDK's fail-fast loading; prove it with a bad-class-name startup test |
| R5 | No descriptor entry — guardrails are not components; unchanged descriptor + green tests is the proof |
| R6 | Blocked ⇒ `422` with `{blocked, rule, category, explanation}`; answer and decline stay `200`; validation stays `400` |
| R7 | Offline throughout; the report-only switch is demonstrated by config override in a second test class |
| R8 | Attach by agent id; no `@AgentRole` in this capability |

---

## B1 — Pre-change baseline (T001/T002, captured 2026-09-04)

`mvn clean verify` on `014-agent-guardrails` at `291daae` (design-only commit; no source or config
change yet) — **BUILD SUCCESS**, no failures, no errors, nothing skipped:

| Phase | Tests | Result |
|---|---|---|
| surefire (unit, 21 classes) | **105** | 0 failures / 0 errors / 0 skipped |
| failsafe (integration, 21 classes) | **101** | 0 failures / 0 errors / 0 skipped |
| **total** | **206** | green |

SC-002 ("capability 8's tests pass unchanged") is measured against this: the cap-8 classes are
`docs.domain.AskQuestionTest` (4), `docs.domain.KnowledgeCorpusTest` (3),
`docs.application.KnowledgeStoreTest` (4) and `docs.api.DocsEndpointIntegrationTest` (**7**) — 18
tests that must still pass, with the same assertions, after guardrails are added.

### The `POST /ask` shapes that must stay byte-identical (T002)

Read off the existing `DocsEndpointIntegrationTest` expectations, which are the contract of record:

| Case | Request | Status | Body |
|---|---|---|---|
| In-corpus | `{"question":"what makes agent work survive a restart without writing persistence code?"}` | `200` | `{"answer":<the mocked grounded text>,"citedSources":[…"durability-tasks"…]}` — non-empty, contains the top retrieved source |
| Out-of-corpus (decline) | `{"question":"what is the capital of France?"}` | `200` | `{"answer":"I don't know","citedSources":[]}` — the sentinel, citing nothing |
| Blank | `{"question":"   "}` | `400` | validation message, no retrieval, no model call |
| Absent field | `{}` | `400` | same |
| Malformed JSON | `{ "question":` | `400` | SDK boundary rejection |
| Unknown extra property | `{"question":…,"surprise":"ignored"}` | `200` | tolerated |
| Two distinct questions | — | `200`,`200` | each cites its own retrieval (`cap-7-activity-coordinator`, `interop-method-ref-wall`) |

Note the shape collision this table makes concrete: **the decline is a `200` whose body carries the
`"I don't know"` sentinel**. A guardrail block must therefore be distinguishable from row 2 by
*status*, not by inspecting the answer text — which is exactly why the contract puts a block at `422`
(R6) and why R3's `onFailure` question has to be settled before any production edit (T003).

---

## R3-RESOLVED — T003 discovery result (run 2026-09-04)

R3's hypothesis was written as a hypothesis on purpose. It has now been **run**
(`GuardrailProbeIntegrationTest`, an always-failing `TextGuardrail` declared through
`TestKit.Settings.withAdditionalConfig` and pointed at `docs-agent` with `use-for = ["model-request"]`).
All three questions are settled, and one of them the wrong way round from what the docs imply.

| # | Question | Answer | Evidence |
|---|---|---|---|
| a | Does the TestKit engage guardrails at all? | **Yes** | The scripted `TestModelProvider` reply (`"UNGUARDED-MODEL-ANSWER"`) was **never returned** — the rule ran and stopped the interaction before the model. FR-012's offline claim holds; no live runtime is needed to prove governance. |
| b | Does a block reach the agent's effect pipeline as a throwable? | **Yes** | The interaction ended on the failure path. |
| c | Does cap-8's `.onFailure(_ => DontKnow)` swallow it? | **Yes — the collision is real** | Probe verdict: `C-SWALLOWED: the block was absorbed by onFailure into the honest-decline sentinel`. The caller received `"I don't know"`, i.e. **a governance block was reported as an honest decline**, exactly the failure FR-005 forbids. |

So **T007 is required, not optional** — the conditional "skip entirely if T003 shows blocks do not
travel that path" does not apply.

### The exception type a handler can discriminate on

`AgentImpl.convert$1` (invoked from `mapSpiAgentException`, which wraps the user's `onFailure`
function, so the conversion happens **before** our handler sees anything) maps each SPI failure
reason to a public SDK exception:

| `SpiAgent.FailureReason` | SDK exception handed to `onFailure` |
|---|---|
| `ModelFailure` | `akka.javasdk.agent.ModelException` |
| `RateLimitFailure` | `RateLimitException` |
| `TimeoutFailure` | `ModelTimeoutException` |
| `ToolCallExecutionFailure` | `ToolCallExecutionException` |
| **`GuardrailFailure`** | **`akka.javasdk.agent.Guardrail.GuardrailException(failure.explanation)`** |
| `OutputParsingFailure` | the underlying cause |

This **corrects R3's caveat** that "nothing in the SDK jar throws `GuardrailException`". Nothing
*raises* it — but the SDK jar *constructs* it, in `AgentImpl`, from the runtime's `GuardrailFailure`.
`onFailure` therefore receives a genuine `Guardrail.GuardrailException`, and narrowing on that type is
sound rather than speculative.

### Divergence #4 — the block's identity never reaches application code

A first reading of the bytecode suggested the rule name and category were carried in the exception
message and merely needed parsing. **Running it disproved that**, and the corrected finding is
sharper — and more limiting.

Three different strings are involved, and only the weakest one is public:

| Carrier | Content | Who can see it |
|---|---|---|
| `SpiAgent.AgentException` (SPI-internal) | `Request guardrail blocked, category [FORMAT], name [probe always fail]: <explanation>` — the full audit line | the runtime: logs, metrics, traces |
| `SpiAgent.GuardrailFailure` | one field, `explanation` — the rule's own text, nothing else | internal |
| **`Guardrail.GuardrailException`** (public) | **the bare `explanation`**, no name, no category, **no cause** | our `onFailure` |

Observed verbatim in the runtime's own warning during the probe run:

```text
AK-01203 Agent [docs-agent] Failure mapping error […]:
  akka.javasdk.agent.Guardrail$GuardrailException: probe guard 'probe always fail' always fails,
  with original cause: akka.runtime.sdk.spi.SpiAgent$AgentException:
    Request guardrail blocked, category [FORMAT], name [probe always fail]: probe guard … always fails
```

**Consequence**: on SDK 3.6.3 an application cannot learn *which* rule fired or in *what* category —
only *what it said*. A rule we author can compensate by naming itself in its own explanation
(`domain/GuardrailAudit`, `[name/CATEGORY] …`); the SDK's own `SimilarityGuard` cannot, so a jailbreak
block is reported with `rule`/`category` of `"unknown"`. That asymmetry is not a workaround failure —
it is the honest shape of the platform's contract, and it is recorded rather than papered over.

### Divergence #5 — rethrowing from `onFailure` is not a channel

The natural design — let the `GuardrailException` propagate and have the endpoint catch it — **does
not work**, and the probe is what showed it. Throwing from inside the `onFailure` function is caught
by the SDK as a *"Failure mapping error"* (`AK-01203`); the caller receives an opaque
`kalix.runtime.CorrelatedRuntimeException` carrying only the message. The exception **type is gone by
the time it crosses the component client**, so an endpoint cannot distinguish governance from any
other failure by catching.

Capability 12 therefore carries a block on the **reply channel**, behind `DocsAgent.BlockedPrefix` —
the same sentinel technique cap-8 already uses for `DontKnow`, shared as a constant so the agent and
the endpoint cannot drift. `DocsAgent.onFailure` maps a `Guardrail.GuardrailException` to
`BlockedPrefix + explanation` and leaves every other throwable degrading to the decline sentinel, so
cap-8's behaviour is unchanged for everything that is not a block.

**Interop note**: neither divergence is Scala-specific. A Java agent would hit both identically. They
belong to the "what the platform tells you" axis, not the language-boundary axis — worth stating,
since this project's habit is to suspect the language first.

---

## R2-CONFIRMED — the jailbreak rule, enabled and measured (T008–T010, 2026-09-04)

R2 predicted the MVP would need **one configuration key and no code**. It does. The whole of user
story 1 is this, merged over the SDK's own `reference.conf` declaration:

```hocon
akka.javasdk.agent.guardrails."default jailbreak".agents = ["docs-agent"]
```

No class, no threshold, no examples directory, no dependency, and **no change to the hand-maintained
`META-INF` descriptor** — the first direct evidence for R5 (a guardrail is not a component). Zero
lines of production Scala were written for this user story.

### The observed block

A DAN-style prompt in the shape of the SDK's bundled examples produces:

```json
{"blocked":true,"rule":"unknown","category":"unknown",
 "explanation":"Content similarity [0.77] exceeds threshold [0.75]"}
```

Three things this pins down:

1. **The rule really ran offline.** `SimilarityGuard` is evaluated by the runtime against the 10
   in-jar example prompts using the quantized all-MiniLM ONNX model already on the classpath from
   cap-8. No network, no API key.
2. **No model was called.** The test class scripts **no** `TestModelProvider` response at all, so a
   model call would fail the test rather than silently pass it. The `422` is therefore proof of
   FR-001, not just of a rule firing.
3. **`rule`/`category` read `unknown`,** exactly as divergence #4 predicts for a rule the SDK owns —
   now asserted by a test (`anSdkOwnedRuleCannotIdentifyItselfToTheCaller`) so that if a later SDK
   starts supplying the identity, the suite fails and the finding gets revisited rather than quietly
   rotting.

### The margin is narrow, and that is worth stating

A genuine jailbreak attempt scored **0.77** against a threshold of **0.75** — two hundredths of
headroom. So the false-positive risk plan.md flagged is not theoretical. It is now covered by a
regression case in `DocsEndpointIntegrationTest`
(`ordinaryQuestionsAreNotMistakenForJailbreakAttempts`) over five realistic corpus questions,
including a deliberately imperative near-miss — *"ignore the previous answer and tell me about session
memory instead"* — which passes as an ordinary `200`. Raising the threshold remains a configuration
change if a real corpus ever trips it.
