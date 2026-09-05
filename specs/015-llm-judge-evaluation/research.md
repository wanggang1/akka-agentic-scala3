# Research: LLM-as-judge evaluation (cap-13)

**Feature**: `015-llm-judge-evaluation` · **Date**: 2026-09-05
**SDK under test**: `akka-javasdk` 3.6.3, `akka-runtime-core_2.13` 1.6.15, `akka-sdk-spi_2.13` 1.6.15
**Method**: every finding below is read from the shipped artifacts (`javap` over the jars in
`~/.m2/repository`), not from documentation. Where documentation and bytecode disagree, the bytecode
is recorded and the divergence is called out. This is the discipline capability 12 settled on after
two of its design assumptions were disproved by running rather than reading.

---

## Baseline

The evaluation surface of the SDK is three classes plus one interface:

```
akka/javasdk/agent/EvaluationResult.class
akka/javasdk/agent/evaluator/LlmAsJudge.class            (package-private, abstract)
akka/javasdk/agent/evaluator/ToxicityEvaluator.class
akka/javasdk/agent/evaluator/SummarizationEvaluator.class
akka/javasdk/agent/evaluator/HallucinationEvaluator.class
```

```
public interface akka.javasdk.agent.EvaluationResult {
  public abstract java.lang.String explanation();
  public abstract boolean passed();
}

abstract class akka.javasdk.agent.evaluator.LlmAsJudge extends akka.javasdk.agent.Agent { ... }

public class akka.javasdk.agent.evaluator.HallucinationEvaluator
    extends akka.javasdk.agent.evaluator.LlmAsJudge {
  public HallucinationEvaluator(akka.javasdk.client.ComponentClient, com.typesafe.config.Config);
  public Agent$Effect<HallucinationEvaluator$Result> evaluate(HallucinationEvaluator$EvaluationRequest);
}
```

with

```
record HallucinationEvaluator$EvaluationRequest(String query, String referenceText, String answer)
record HallucinationEvaluator$Result(String explanation, boolean passed) implements EvaluationResult
record SummarizationEvaluator$EvaluationRequest(String document, String summary)
        ToxicityEvaluator.evaluate(java.lang.String)      // a bare String, not a record
```

**The single most consequential line in the baseline is `LlmAsJudge extends Agent`.** An evaluator is
not a new component family, not a config-registered callback, and not an SPI hook. It is an ordinary
request-based **Agent**. Everything below follows from that.

> **Immediate consequence — cap-13 sits on the *first* interop axis, not cap-12's second one.**
> Capability 12's guardrails were declared in configuration by class-name string, constructed
> reflectively, and took **no** descriptor entry — cap-11's bytecode-shape axis. Capability 13 is the
> exact opposite in every respect: agents, `@Component` ids, the component client, and (for the one
> we author) a descriptor line. The governing question is the **method-reference wall** again — the
> project's oldest finding, asked in a place it has never been asked.

---

## R1 — Can a Scala caller invoke an SDK-owned evaluator agent? **RESOLVED: yes, via `dynamicCall`, and the evaluators are built-in components**

### R1a — The documented call form is the wall

`akka-context/sdk/agents/llm_eval.html.md` calls a built-in judge as:

```java
componentClient.forAgent().inSession(taskId).method(ToxicityEvaluator::evaluate).invoke(...)
```

`AgentClientInSession` confirms the shape:

```
public interface akka.javasdk.client.AgentClientInSession {
  <T, R>      AgentMethodRef<R>            method(akka.japi.function.Function<T, Agent$Effect<R>>);
  <T, A1, R>  AgentMethodRef1<A1, R>       method(akka.japi.function.Function2<T, A1, Agent$Effect<R>>);
  <T>         ComponentStreamMethodRef<String> tokenStream(...);
  <A1, R>     DynamicMethodRef<A1, R>      dynamicCall(java.lang.String);
}
```

`akka.japi.function.Function extends java.io.Serializable`, so `method(...)` resolves through
`SerializedLambda` — the wall. A Scala lambda compiles to a synthetic `$anonfun` and never resolves.
**The form the documentation shows is unusable from Scala**, exactly as for `WorkflowClient`,
`KeyValueEntityClient` and `ViewClient`.

### R1b — But `dynamicCall` resolves by component id, off a `Class` map

`AgentClientImpl.dynamicCall(String)`:

```
 0: agentClassById()                                     // Map[String, Class[Agent]]
 5: InvokeDynamic  ...                                   // the not-found thunk
11: Map.getOrElse(id, <thunk>)                           // -> Class
27: Class.getMethods()
41: ArrayOps.find$extension(<predicate>)                 // the single command handler
73: StringOps.capitalize$extension(method.getName())     // -> "Evaluate"
80: Reflect$.getReturnType(clazz, method)                // declared return type
85: new ComponentMethodRefImpl(...)
```

and the not-found path is

```
public static final scala.runtime.Nothing$ $anonfun$dynamicCall$1(java.lang.String);
   0: new java/lang/IllegalArgumentException
   5: InvokeDynamic makeConcatWithConstants
  13: athrow
```

Two things matter here:

1. **No lambda, no `SerializedLambda`, no method reference.** The target method is found by
   *reflection on the class the id maps to*. There is nothing for Scala to fail at.
2. **The deserialization target is the method's *declared* return type**
   (`Reflect$.getReturnType(clazz, method)`), not the type argument the caller writes. So
   `dynamicCall[Req, Res](id)` gets `HallucinationEvaluator$Result` materialised by the internal
   serializer and then cast — the type parameters are the caller's assertion about a type the
   runtime already knows.

The only precondition is therefore: **is the evaluator's id in `agentClassById`?**

### R1c — The built-in evaluators are *provided components*, registered like `SessionMemoryEntity`

`ComponentLocator$`'s static initialiser builds two hardcoded lists:

```
agentProvidedComponents          = [ PromptTemplate, SessionMemoryEntity,
                                     ToxicityEvaluator, SummarizationEvaluator,
                                     HallucinationEvaluator ]
autonomousAgentProvidedComponents = [ TaskEntity, BacklogEntity ]
providedComponents               = agentProvidedComponents ++ autonomousAgentProvidedComponents
```

So the three evaluators are **built-in components of every Akka service**, in the same list as the
session-memory entity capability 4 met and the task entity capability 3 met. They carry real
`@Component` annotations, read straight from the constant pool:

| Class | `@Component(id = …)` | `@AgentRole` |
|---|---|---|
| `HallucinationEvaluator` | `hallucination-evaluator` | `evaluator` |
| `ToxicityEvaluator` | `toxicity-evaluator` | `evaluator` |
| `SummarizationEvaluator` | `summarization-evaluator` | `evaluator` |

(The ids are also `static final String COMPONENT_ID` on each class, and appear as `ldc` constants in
each constructor.)

**R1 verdict — the SDK's own evaluator library IS callable from Scala.** Not by the form the
documentation shows, but by the one escape hatch this project has relied on since capability 1:

```scala
componentClient.forAgent()
  .inSession(sessionId)
  .dynamicCall[HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result](
    "hallucination-evaluator")
  .invoke(HallucinationEvaluator.EvaluationRequest(question, referenceText, answer))
```

> **This extends `dynamicCall` past a boundary the project had never tested.** Every previous use
> targeted an agent **we** declared with our own `@Component` and listed in our own descriptor.
> `agentClassById` is populated from *all* registered agents — ours and the runtime's — so the
> escape hatch reaches **SDK-owned agents too**. Capabilities 4, 6 and 11 each hit the wall on a
> runtime-owned component (`SessionMemoryEntity`, `TodoEntity`, the View) and had to quarantine
> Java. Here the runtime-owned component happens to be an **agent**, and the agent client is the one
> client with a string-keyed API. The wall is not merely "a client property" — it is a property of
> *which* client, and the agent client is on the right side of it even for components we do not own.

**Two-mapper boundary (README §3): satisfied without effort.** `EvaluationRequest` and `Result` are
**Java records**. They cross the SDK's *internal* serializer, which is exactly the mapper that cannot
read idiomatic Scala — and they are the SDK's own types, so there is nothing for us to shape. Our own
judge's request/result must be Java-*shaped* Scala (the `HelpAnswer` / `TodoSummaryEntry` pattern).

---

## R2 — Is a custom evaluator just an ordinary Agent? **RESOLVED: yes — and the *return type* is what makes it an evaluator**

`Reflect$` carries a dedicated predicate:

```
public boolean isEvaluatorAgent(java.lang.Class<?>);
```

whose body reduces to (bytecode, `Reflect$.class`):

```
 ... getReturnClass(clazz, method)
 70: ldc_w  class akka/javasdk/agent/EvaluationResult
 75: Class.isAssignableFrom(returnClass)
```

i.e. *find the command handler, take its return class, ask whether it implements
`EvaluationResult`.* There is **no annotation** involved — no `@Evaluator`, and `@AgentRole` is not
consulted for this.

`Sdk` then folds the answer into the agent's descriptor:

```
Reflect$.readComponentDescription(clazz)
Reflect$.isEvaluatorAgent(clazz)          // <- boolean field on the descriptor
this.isProvided(clazz)                    // <- boolean field on the descriptor
new akka.runtime.sdk.spi.AgentDescriptor(String, String, Function1, Option, Option, Z, Z)
```

**R2 verdict, three parts:**

1. **A custom evaluator is an ordinary `Agent`.** Nothing else. It gets `@Component(id = …)` like any
   agent, and it is invoked through the component client like any agent — so from Scala, via
   `dynamicCall`, exactly as capabilities 1 and 6 already do.
2. **The trigger for evaluation semantics is the command handler's return type.** Make the reply type
   implement `akka.javasdk.agent.EvaluationResult` and the runtime marks the agent as an evaluator in
   its descriptor; that flag is what routes verdicts into metrics and traces (**FR-011 satisfied by
   construction, with no logging code of ours**). Miss the interface and it is silently an ordinary
   agent whose verdicts go nowhere — a quiet failure mode worth a test.
3. **It needs a descriptor entry, under the existing `agent` key.** `ComponentType$`'s constant pool
   holds exactly: `agent`, `autonomous-agent`, `consumer`, `event-sourced-entity`, `grpc-endpoint`,
   `http-endpoint`, `key-value-entity`, `mcp-endpoint`, `timed-action`, `view`. **There is no
   `evaluator` key.** The built-ins are exempt because they are `providedComponents` (R1c); ours is
   not.

> **A deliberate contrast with capability 12 worth recording as the headline of this pair.** Cap-12's
> governance cost **zero** descriptor lines because a guardrail is not a component. Cap-13's
> evaluation costs **one**, because a judge is. Two mechanisms that both "wrap" an agent's behaviour,
> registered in two completely different ways. The distinction is not stylistic: it decides whether
> a Scala author's obstacle is the descriptor (cap-1's finding) or the bytecode shape (cap-11's).

**`@AgentRole("evaluator")`** is unrelated to the descriptor flag but is not decoration: `AgentRegistry`
exposes `agentsWithRole(String)` and `AgentInfo(id, name, description, role)`. Annotating our judge
makes it discoverable alongside the SDK's three. Cheap, and it keeps ours indistinguishable from the
built-ins to a consumer of the registry (**SC-007**).

---

## R3 — Is evaluation testable offline? **RESOLVED: YES — and this is the finding the capability's design hinged on**

This was the spec's declared risk: a judge *is* generative, and the built-in judges are classes this
project does not own. A negative answer would have forced the built-in half of the capability to be
live-only.

### R3a — `withModelProvider` is keyed by component id, derived from the `Class`

```
public TestKit$Settings withModelProvider(java.lang.Class<?>, ModelProvider);
   0: aload_1
   1: invokestatic  TestKit.getComponentId:(Ljava/lang/Class;)Ljava/lang/String;
   4: astore_3
   ...
  21: HashMap.put(id, provider)          // -> field modelProvidersByAgentId : Map<String, ModelProvider>
```

The field name says it: providers are stored **by agent id**, and the `Class` is only the means of
looking that id up (from `@Component`). The built-in evaluators **have** `@Component` ids (R1c), so
`withModelProvider(classOf[HallucinationEvaluator], testProvider)` is a legal, meaningful call that
registers `"hallucination-evaluator" -> testProvider`.

### R3b — …and the override beats an explicitly set model, which is the part that was in doubt

The doubt was real. `LlmAsJudge` does **not** use the default model — it sets one explicitly:

```
protected ModelProvider modelProvider();
   0: config
   8: InvokeDynamic makeConcatWithConstants        // "akka.javasdk.agent.evaluators." + componentId + ".model-provider"
  13: Config.getString(...)
  18: ModelProvider.fromConfig(...)
```

and `HallucinationEvaluator.evaluate` calls it:

```
 39: effects()
 43: modelProvider()
 46: Agent$Effect$Builder.model(ModelProvider)     // <- explicit per-request model
 60: systemMessage(...)
 68: memory(MemoryProvider.none())
 74: userMessage(...)
 81: responseConformsTo(ModelResult.class)
 91: map(ModelResult::toEvaluationResult)
 96: thenReply()
```

If an explicit `.model(...)` won, a test provider registered for that agent would be ignored and the
judge would call a real model in every test. It does not win. `AgentImpl.handleCommand`:

```
255: overrideModelProvider
260: componentId
263: OverrideModelProvider.getModelProviderForAgent(componentId)   // -> Option[ModelProvider]
268: InvokeDynamic  ... (requestModel)                             // the fallback thunk
273: Option.getOrElse(<requestModel.modelProvider>)
284: toSpiModelProvider(...)
```

**The override is consulted first and the request's own model provider is only the `getOrElse`
fallback.** `TestKit` populates that override at startup:

```
192: settings
195: TestKit$Settings.modelProvidersByAgentId
200: InvokeDynamic accept:(Sdk$StartupContext)   // -> overrideModelProvider.setModelProviderForAgent
205: Map.forEach(...)
```

**R3 verdict — the built-in judge is fully scriptable offline.** Registering a `TestModelProvider`
for `HallucinationEvaluator` replaces the model it would otherwise build from
`akka.javasdk.agent.evaluators.hallucination-evaluator.model-provider`. The judge's prompt is still
the SDK's real prompt, the response is parsed by the SDK's real `responseConformsTo(ModelResult)` and
mapped by the SDK's real `toEvaluationResult`, and only the model itself is scripted — which is
precisely the right seam.

The script must satisfy the SDK's own parser: a JSON object `{"explanation": "...", "label": "..."}`
where `label` ∈ {`factual`, `hallucinated`} (from the bundled default system message, read out of the
constant pool). An unknown label makes `toEvaluationResult` throw
`IllegalArgumentException("Unknown evaluation label [...]")` — which is exactly how the **errored**
outcome (FR-005, SC-004) is provoked deterministically, with no need to break anything.

> **This is a strictly better position than capability 7 or capability 6 ended in.** Cap-7's
> request-based delegation was *not* faithfully mockable (D9) and cap-6's recall was live-only, so
> both shipped with a live-only proof at the centre. Cap-13's centre is offline. What remains
> live-only here is only the *quality* of a real judge's opinion — which no test should assert
> anyway, because a model verdict is not deterministic (the spec makes that a rule, not a caveat).

---

## R4 — Is there an interaction-completion hook for asynchronous evaluation? **RESOLVED: no**

The documentation's asynchronous pattern is a `Consumer` over the runtime's task entity:

```java
@Consume.FromEventSourcedEntity(TaskEntity.class)
public class EvaluationConsumer extends Consumer { ... }
```

The available sources are exactly:

```
akka/javasdk/annotations/Consume$FromEventSourcedEntity.class
akka/javasdk/annotations/Consume$FromKeyValueEntity.class
akka/javasdk/annotations/Consume$FromServiceStream.class
akka/javasdk/annotations/Consume$FromTopic.class
akka/javasdk/annotations/Consume$FromWorkflow.class
```

**There is no `Consume.FromAgent`, and no agent-interaction stream.** A request-based agent produces
nothing consumable. The documented pattern is available only to an `AutonomousAgent`, because what it
actually consumes is `TaskEntity` — a task's completion events, not an agent's.

Capability 8 is a **request-based** agent with no task and no entity of its own. So the asynchronous
shape is unreachable without first inventing durable state capability 8 does not have — an entity or
an autonomous agent — which would (a) change capability 8, breaking SC-003, (b) duplicate what
capability 11 already explored, and (c) bury this capability's interop question under an unrelated
one.

**R4 verdict — the spec's default stands, and is now confirmed rather than merely preferred:
evaluation gets its own surface.** The consequence is favourable and worth stating plainly: because
evaluation runs over the same pipeline from its own entry point, **capability 8's and capability 12's
sources need not be touched at all**, so SC-003 ("byte-identical") is provable by `git diff` rather
than by argument. Capability 12 earned one line of change in `DocsAgent`; capability 13 earns none.

---

## R5 — How much of a verdict reaches application code? **RESOLVED: all of it — the opposite of cap-12**

Capability 12's exact analogue was a limitation: a guardrail's name and category never reach
application code, because the composed audit line lives on an SPI-internal exception exported only to
traces and metrics; the public `Guardrail.GuardrailException` carries the bare explanation. Cap-12
worked around it by having each rule name itself inside its own explanation.

Evaluation has no such gap, for a structural reason: **a verdict is a command handler's return value,
not an exception.** `evaluate` returns `Agent$Effect<Result>`, and `Result(explanation, passed)` is
delivered to the caller by the ordinary component-client reply path. Nothing is erased, nothing is
internal-only, and there is no need for a self-tagging convention like `GuardrailAudit`.

**Which judge produced a verdict is likewise ours by construction**, since we choose which id to call
— so the `unknown`/`unknown` attribution that capability 12 had to ship for the SDK's own
`SimilarityGuard` has no counterpart here. **FR-005 and SC-007 are satisfiable in full for both the
platform's judge and ours.**

> The two capabilities together make a sharper general point than either alone: **a mechanism the
> platform invokes on your behalf (a guardrail) tells you less than a mechanism you invoke yourself
> (an evaluator)**, even when both produce the same shape of finding. If attribution matters, prefer
> the one you call.

---

## R6 — Refusals and empty reference text **RESOLVED: no SDK support; handle at our layer**

`HallucinationEvaluator.evaluate` does no validation. Its user message is a plain `String.formatted`
of the bundled template:

```
[Query]\n************\n%s\n************\n[Reference text]\n************\n%s\n************\n[Answer]\n************\n%s\n************\n
```

An empty `referenceText` produces a well-formed prompt with an empty section, and the model will
return *some* label for it. Likewise, capability 12's block sentinel (`DocsAgent.BlockedPrefix`) is
just a string as far as a judge is concerned: feeding a refusal to a grounding judge yields a
confident, meaningless verdict.

**R6 verdict — applicability is our responsibility and belongs in the domain layer** (FR-012, SC-006):
a pure predicate over `(answer, passages)` that yields `NotApplicable` for a refused interaction or
absent reference material, evaluated **before** any judge is called. This also saves two model calls
on exactly the inputs where they would be worthless.

Note the reachability honestly: with capability 8's canned corpus, retrieval always returns top-3, so
**empty reference material is not reachable through `POST /evaluate`** and is covered by a domain unit
test. The **refusal** path *is* reachable end to end, because capability 12's jailbreak rule guards
`docs-agent` — which makes cap-12 and cap-13 interact in a genuinely useful way: a hostile question
sent to the evaluation surface is blocked upstream, and both judges correctly report
not-applicable rather than judging a refusal string.

---

## Design decisions falling out of R1–R6

| # | Decision | Rationale | Alternative rejected |
|---|---|---|---|
| D1 | New package `com.gwgs.akkaagentic.eval.*` | Keeps cap-8's `docs.*` untouchable (SC-003) | Extending `docs.*` — would edit cap-8 |
| D2 | Own HTTP surface `POST /evaluate`; `/ask` untouched | R4: no completion hook exists | A background hook — not available (R4) |
| D3 | Built-in judge reached by `dynamicCall("hallucination-evaluator")` | R1: the documented `.method(…)` form is the wall | Java quarantine class holding the method ref — unnecessary, and would hide the finding |
| D4 | One authored judge, `decline-judge`, result implements `EvaluationResult` | R2: the return type is the trigger; proves the authoring path | An annotation — none exists |
| D5 | Applicability decided in a **pure domain predicate**, before any judge call | R6 + Constitution II (no Akka in domain) | Letting the judge decide — yields meaningless verdicts and spends two model calls |
| D6 | Sentinels (`DontKnow`, `BlockedPrefix`) passed **into** the domain as parameters | Domain must not depend on `application` | Importing `DocsAgent` into domain — inverts the layer direction |
| D7 | Orchestration in a plain `AnswerEvaluator` class the endpoint constructs | Only components/endpoints get injection; keeps the endpoint thin | Orchestrating in the endpoint — violates Constitution II's single responsibility |
| D8 | `eval.enabled` config flag read via injected `Config` | FR-009 with one key and one mechanism | `ServiceSetup.disabledComponents` — disables the *agent* but cannot disable the *endpoint* that calls it, so the flag would be needed anyway; two mechanisms for one switch fails Constitution IV |
| D9 | A parity test: `/evaluate`'s answer + citations equal `/ask`'s, and its reference text equals an independent `KnowledgeStore.retrieve` | Converts the deliberate ~5-line pipeline duplication into a checked invariant | Extracting a shared pipeline — would edit cap-8 (SC-003) |

**Deliberate duplication, declared.** D2 means the evaluation surface re-runs capability 8's
retrieve → ask sequence rather than sharing code with it. That is a knowing trade: SC-003
(cap-8/cap-12 byte-identical) is worth more here than DRY across a five-line sequence, and D9 turns
the risk of drift into a failing test. This is recorded so it is read as a decision, not an oversight.

**One test-fixture hazard, from cap-12.** `linked answer guard` is enforcing on `docs-agent`'s
responses and blocks any answer containing `http`, `www.` or `see also:`. Scripted answers in this
capability's tests must avoid those markers unless the block is the thing under test.

---

## What is offline-provable, and what is not (FR-015, SC-008)

**Offline (the whole functional surface):** the built-in judge (R3), the authored judge, the
applicability rules, the errored outcome (via an unknown label, R3), the parity invariant (D9), the
descriptor requirement (R2), attribution (R5), the enabled/disabled flag (D8), and the refusal path
end to end (R6 + cap-12).

**Live-only:** exactly one thing — **whether a real judge's opinion is any good.** No test asserts
the *value* of a verdict from a live model, by rule. A live smoke test is a supplement that
demonstrates the loop against Ollama and reports what it saw, including anything unflattering.
