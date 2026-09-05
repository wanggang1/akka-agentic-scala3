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

---

## Baseline (T001/T002)

Measured on `8ecb309`, before any capability 13 production code:

```
mvn clean verify  ->  BUILD SUCCESS
  unit (surefire):        127
  integration (failsafe): 121
```

Blob hashes recorded for the SC-003 byte-identity check (T031 re-checks these):

```
a04913e2  src/main/scala/com/gwgs/akkaagentic/docs/api/DocsEndpoint.scala
e54d1827  src/main/scala/com/gwgs/akkaagentic/docs/application/DocsAgent.scala
ff34379e  .../docs/application/AnswerLengthGuard.scala
d3548d87  .../docs/application/LinkedAnswerGuard.scala
4d7767c6  .../docs/application/KnowledgeStore.scala
bd404fe1  .../docs/domain/AnswerRules.scala
366ac674  .../docs/domain/AskQuestion.scala
35e2bc11  .../docs/domain/GuardrailAudit.scala
04625016  .../docs/domain/KnowledgeCorpus.scala
0f9ea42e  .../docs/domain/Passage.scala
```

**Scope of the SC-003 claim, stated precisely so it is not overclaimed.** The whole
`src/main/scala/com/gwgs/akkaagentic/docs/` tree must end byte-identical — that is the real
assertion, and `git diff` proves it. `application.conf` and the component descriptor are *shared*
project files that every capability appends to; capability 13 adds `eval.enabled` and two descriptor
lines to them. What must hold there is that capability 12's `guardrails` block and the existing
descriptor entries are **unchanged** — additive only, never edited.

---

## R1/R3 — MEASURED (T003), and T004's negative control

Everything above in R1 and R3 was read from bytecode. This section is what happened when it was
**run** — `BuiltInJudgeProbeIntegrationTest`, 4 tests, all green, fully offline.

### R1 — CONFIRMED: `dynamicCall` reaches an SDK-owned agent

```
R1/R3 MEASURED: dynamicCall reached the SDK-owned judge AND the test model override won.
                passed=true explanation=The answer restates the reference text.
```

`componentClient.forAgent().inSession(id).dynamicCall[EvaluationRequest, Result]("hallucination-evaluator")`
returned a real `HallucinationEvaluator.Result`. The evaluators are in `agentClassById` because
`ComponentLocator$` provides them, exactly as the bytecode said.

**The project's oldest finding gains a clause.** From capability 2 onward the rule was *"the
method-reference wall is a property of the client"*. Capabilities 4, 6 and 11 each needed a
**runtime-owned** component (`SessionMemoryEntity`, `TodoEntity` behind `TodoTools`, the View) and each
had to quarantine Java to reach it. Capability 13 needs a runtime-owned component too — and does not,
because that component is an **agent**, and the agent client is the one client with `dynamicCall`. The
sharper statement: *the wall is a property of **which** client, and the agent client is on the right
side of it **even for components we do not own**.*

### R3 — CONFIRMED: the TestKit override beats the evaluator's explicit `.model(...)`

This was the real risk, and the naive reading lost. `LlmAsJudge` sets its model explicitly from
`akka.javasdk.agent.evaluators.hallucination-evaluator.model-provider`, yet the scripted response came
back — no network, no API key, no model server. `AgentImpl`'s
`overrideModelProvider(id).getOrElse(requestModel.modelProvider)` is decisive in practice, not only in
bytecode.

Label mapping, both directions, confirmed against the SDK's own parser and `toEvaluationResult`:

| scripted `label` | `Result.passed` |
|---|---|
| `factual` | `true` |
| `hallucinated` | `false` |

**So the SDK's own judge is fully scriptable offline** — the SDK's real prompt, real
`responseConformsTo(ModelResult)` and real result mapping all still run; only the model is replaced.
This is a better position than capability 6 (recall live-only) or capability 7 (delegation not
faithfully mockable, D9) ended in.

### R3(d) — CONFIRMED: an unrecognised label fails, and the failure is usable

```
R3(d) MEASURED: unknown label ->
  kalix.runtime.CorrelatedRuntimeException:
    [e6a8340e-...] Response mapping error: Unknown evaluation label [maybe]
WARN  AK-01203 Agent [hallucination-evaluator] Response mapping error [e6a8340e-...]:
      java.lang.IllegalArgumentException: Unknown evaluation label [maybe]
```

The `errored` outcome therefore has a deterministic trigger that comes from **the SDK**, not from us
breaking something: script a judge to answer with a label outside its vocabulary.

**One carry-over from capability 12, confirmed rather than assumed.** The original
`IllegalArgumentException` is **type-erased at the component-client boundary** into
`kalix.runtime.CorrelatedRuntimeException` — precisely what capability 12 measured when it tried to
rethrow a `GuardrailException`. The consequence for the design is small but must be respected:
`AnswerEvaluator` must classify a judge failure by catching **any** `Throwable`, never by matching
`IllegalArgumentException`. The *message* survives intact (`Unknown evaluation label [maybe]`), so the
`errored` verdict's explanation is still informative — which is why FR-005 is satisfiable even though
the type is gone.

### T004 — the negative control, and it is more interesting than expected

The prediction was a `SerializedLambda` resolution failure complaining about a synthetic `$anonfun`.
That is **not** what a Scala developer sees. The attempt **compiles cleanly**, and at invocation
produces:

```
java.lang.IllegalArgumentException:
  class com.gwgs.akkaagentic.eval.application.BuiltInJudgeProbeIntegrationTest
  is not a subclass of class akka.javasdk.agent.Agent
```

`MethodRefResolver` reads the `SerializedLambda`'s **`implClass`**, which for a Scala lambda is the
*enclosing* class — here the caller — not the agent the lambda mentions. So the SDK reports that
**the developer's own class is not an Agent**.

**This is a materially worse developer experience than the wall the project has documented so far, and
worth recording as such.** Every previous encounter produced an error that at least pointed at the
right area. This one names a class that is not the problem, says nothing about lambdas or Scala, and
sends a reader off to check whether their endpoint should extend `Agent` — which it must not. A Scala
developer following `llm_eval.html.md` gets a compiling program and a misdirecting runtime error.
Recorded for `docs/sdk-3.6.0-limitations.md` (T030); the fix is one line — use `dynamicCall(id)`.


## R6 — measured (T021/T022)

Both not-applicable causes behave as the design predicted, but their **reachability differs**, and the
difference is recorded rather than papered over.

**A refusal is reachable end to end, and was driven as such.** Capability 13 configures no rule of its
own; the path exists purely because capability 12's `default jailbreak` guards `docs-agent` and
`/evaluate` calls the same agent. Sending capability 12's DAN fixture to `POST /evaluate` returns
`200` with an empty answer, no citations, and both verdicts `not-applicable`.

The test proves the judges were **never invoked**, and proves it by omission rather than by claim: no
model is scripted for either judge, so had one been called `TestModelProvider` would have failed it and
the verdict would read `errored`. `not-applicable` is therefore only reachable if the applicability
rule short-circuited before any model call — the saving R6 predicted, made observable.

**Empty reference material is *not* reachable through the endpoint.** Capability 8's corpus is canned
and retrieval always returns top-3, so `referenceText` is never blank in practice. The rule still has
to be right, and is covered where it is genuinely reachable — a domain unit test
(`EvaluationApplicabilityTest.emptyReferenceMaterialIsNotApplicable`) — rather than by staging an
artificial path to it through HTTP. Stated here so the coverage gap is a decision on the record and not
an omission someone discovers later.

**Ordering matters and is pinned**: a refusal against empty reference material reports the *refusal*,
the more specific and more useful explanation.

---

## Suite after US3 (T023) and final (T036)

```
after US3 (T023)              final (T036)
  unit:        148   (+21)      unit:        148   (+21 over the 127 baseline)
  integration: 142   (+21)      integration: 147   (+26 over the 121 baseline)
BUILD SUCCESS in both cases.
```

Capability 8's and capability 12's suites pass **unmodified** — no existing test file was touched.


## R5/FR-011 — measured (T026/T027): full attribution, but telemetry is not observable offline

**Attribution: confirmed, and it is the clean inverse of capability 12.** Every verdict names its
judge, for the SDK's judge exactly as for ours. Capability 12 had to ship
`rule: "unknown", category: "unknown"` for the SDK's own `SimilarityGuard`, because the composed audit
line lives on an SPI-internal exception and never reaches application code. A verdict is a **return
value**, not an exception, so nothing is erased. `EvaluationEndpointIntegrationTest` asserts the
absence of `unknown` explicitly, so a regression to capability 12's situation would fail rather than
pass quietly.

The general point the pair makes: **a mechanism the platform invokes on your behalf tells you less
than one you invoke yourself**, even when both produce the same shape of finding.

**Telemetry: a TestKit limitation, recorded rather than worked around.** The intent for T027 was to
observe the correlation directly through `TelemetryReader.getAgents(sessionId)` — the TestKit's own
span reader, reached from `TestKitSupport.telemetryReader`. It returns an **empty list**. Two
configurations were tried through `TestKit.Settings.withAdditionalConfig` and neither produced spans:

```
akka.runtime.telemetry.tracing.enabled = true
akka.runtime.telemetry.tracing.override-setup =
  "kalix.runtime.telemetry.tracing.TracingSetup$DevModeInMemoryTracingSetup"
```

`TestKit.getInMemorySpanExporter` does **not** throw (its "No in-memory span exporter configured"
message is not reached), so an exporter exists — there are simply no spans in it, i.e. tracing is off
and the SDK 3.6.3 TestKit offers no documented switch to turn it on from `Settings`.

**Consequence, stated rather than hidden.** FR-011 is verified **by mechanism, not by observation**:
`Reflect$.isEvaluatorAgent` marks the agent from its return type, `Sdk` folds that flag into the
`AgentDescriptor`, and that is what routes verdicts into metrics and traces —
`EvaluatorDescriptorTest` pins both halves. **Metrics have no TestKit reader at all.** This is
capability 13's one honest gap in an otherwise fully-offline capability, and it is the SDK's, not the
design's. Recorded for `docs/sdk-3.6.0-limitations.md`.

What *is* asserted at the endpoint is the correlation handle itself: one `evaluationId` per
evaluation, used as the session for the assistant turn and both judges, and distinct between
evaluations. Running all three under one session is also better design independently — it is what the
SDK's own documented `EvaluationConsumer` does, keying every evaluator call on the task id.

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

## The consolidated interop finding (T029)

**Question.** Can a Scala caller use the Akka SDK's own LLM-as-judge evaluators, and author one of its
own?

**Answer: yes to both — but not by the route the documentation shows, and the two halves are
registered in opposite ways.**

### 1. Calling the SDK's judges — `dynamicCall` reaches components we do not own

`akka-context/sdk/agents/llm_eval.html.md` calls a built-in judge as
`.method(ToxicityEvaluator::evaluate)`. That is a Java method reference resolved from a
`SerializedLambda`, and it is the wall this project has hit since capability 2. **From Scala the
nearest equivalent compiles and then fails at invocation with a message that names the wrong class**
(see "T004" above).

The working route is the escape hatch capability 1 established:

```scala
componentClient.forAgent().inSession(sessionId)
  .dynamicCall[HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result](
    "hallucination-evaluator")
  .invoke(new HallucinationEvaluator.EvaluationRequest(question, referenceText, answer))
```

It works because `AgentClientImpl.dynamicCall` resolves the target off `agentClassById` — a
`Map[String, Class[Agent]]` — and finds the command handler by reflection. No lambda is involved.
And `agentClassById` holds **every registered agent, the runtime's as well as ours**:
`ComponentLocator$` provides `HallucinationEvaluator`, `ToxicityEvaluator` and `SummarizationEvaluator`
in the same hardcoded list as `SessionMemoryEntity`, `PromptTemplate` and `TaskEntity`.

**This sharpens the project's oldest finding by one clause.** The rule was *"the method-reference wall
is a property of the client"*. Capabilities 4, 6 and 11 each needed a **runtime-owned** component —
`SessionMemoryEntity`, `TodoEntity`, the `View` — and each had to quarantine Java to reach it, which
made the wall look like a property of *ownership*. It is not. The precise statement is:

> **The wall is a property of *which* client — and the agent client, alone in having
> `dynamicCall(String)`, is on the right side of it *even for components the SDK owns*.**

Capability 13 needed a runtime-owned component too, and needed **zero** Java, purely because that
component happens to be an agent. A jar-wide sweep in capability 11 already established that
`dynamicCall(String)` exists on `AgentClientInSession` only; capability 13 shows what that
asymmetry is worth when the component you need is on the lucky side of it.

### 2. Authoring a judge — an ordinary agent, and the *return type* is the switch

A custom evaluator is an `Agent` with one command handler. There is no `@Evaluator` annotation and
`@AgentRole` is not consulted for it. `Reflect$.isEvaluatorAgent` takes the handler's return class and
asks `EvaluationResult.class.isAssignableFrom(...)`; `Sdk` folds that boolean into the
`AgentDescriptor`, and **that flag is what routes verdicts into metrics and traces**.

The failure mode is quiet: drop `extends EvaluationResult` and you have a compiling, working, silently
un-instrumented agent. `EvaluatorDescriptorTest` pins it for that reason.

### 3. The descriptor asymmetry — the sharpest contrast with capability 12

| | capability 12 (guardrails) | capability 13 (evaluators) |
|---|---|---|
| Registered by | configuration: `akka.javasdk.agent.guardrails.<name>.class` | being a component |
| Constructed by | the runtime, reflectively, from a class-name string | the runtime, as an agent |
| Descriptor lines **we** add | **0** | **1** (under `agent`) |
| SDK's own instances | `"default jailbreak"`, inert until `agents = [...]` | three provided components, always present, never listed |
| Interop axis | bytecode **shape** (cap-11's axis) | the **method-reference wall** (cap-2's axis) |
| Verdict identity reaching our code | **none** — name and category are SPI-internal | **all of it** — a verdict is a return value |

Two mechanisms that both wrap an agent's behaviour, registered in two entirely different ways, sitting
on two different interop axes, with opposite attribution properties. Neither generalises to the other,
and a project that met only one of them would draw the wrong general rule from it.

### 4. What this cost in Java

Nothing. Capability 13 contains **no Java at all** — not in production, not in tests. Compare
capability 2 (a whole capability), capability 6 (an entity plus its caller), capability 4 (one test),
capability 11 (one endpoint). The reason is entirely the R1 clause above.


---

## SC-003 — asserted mechanically (T031)

The claim "capability 8 and capability 12 are untouched" is worth nothing unless it is shown, so here
is the showing rather than the saying.

```
$ git diff --stat main -- src/main/scala/com/gwgs/akkaagentic/docs/
(no output)
```

Every blob hash matches the T002 baseline exactly:

```
a04913e2  docs/api/DocsEndpoint.scala              ff34379e  docs/application/AnswerLengthGuard.scala
e54d1827  docs/application/DocsAgent.scala          d3548d87  docs/application/LinkedAnswerGuard.scala
4d7767c6  docs/application/KnowledgeStore.scala     bd404fe1  docs/domain/AnswerRules.scala
366ac674  docs/domain/AskQuestion.scala             35e2bc11  docs/domain/GuardrailAudit.scala
04625016  docs/domain/KnowledgeCorpus.scala         0f9ea42e  docs/domain/Passage.scala
```

`git diff main -- src/test/scala/com/gwgs/akkaagentic/docs/ src/test/java/` is likewise empty: no
existing test was modified either, which is the other half of SC-003.

The two **shared** files are additive only. `git diff main -- application.conf` contains `+` lines and
no `-` lines: capability 12's entire `guardrails` block is byte-for-byte intact and capability 13
appends a top-level `eval` block. The descriptor gains exactly two entries and edits none
(`EvaluatorDescriptorTest.capability13AddsExactlyTwoDescriptorEntries` pins the count, so an accidental
third or an edit to an existing line fails the suite).

**Why this was achievable at all**, restated because it is a research result and not discipline:
research R4 found no `Consume.From*` source for a request-based agent, so evaluation *could not* have
been attached to `POST /ask` as a background hook even had that been preferred. The separate surface
was the only available shape, and it happens to make SC-003 provable with `git diff` instead of by
argument. Capability 12 earned one line of change in `DocsAgent`; capability 13 earned none.


---

## What is offline-provable, and what is not (FR-015, SC-008)

**Offline (the whole functional surface):** the built-in judge (R3), the authored judge, the
applicability rules, the errored outcome (via an unknown label, R3), the parity invariant (D9), the
descriptor requirement (R2), attribution (R5), the enabled/disabled flag (D8), and the refusal path
end to end (R6 + cap-12).

**Live-only:** exactly one thing — **whether a real judge's opinion is any good.** No test asserts
the *value* of a verdict from a live model, by rule. A live smoke test is a supplement that
demonstrates the loop against Ollama and reports what it saw, including anything unflattering.

---

## Live verification (T037) — Ollama `qwen3:8b`

All four flows end to end against a real model, with the judges' verdicts produced by a real model
too. Zero `ERROR` lines in the service log across the whole session.

**1. An in-corpus answer, judged.** Both judges passed, and both explanations quote the reference
passages rather than restating the answer — evidence the reference text genuinely reached them:

> `hallucination-evaluator` / passed — *"The answer correctly identifies two reasons from the reference
> text: (1) the 'method-reference wall' (reference [1]) … and (2) the Scala-unaware mapper for
> component-to-component payloads … These claims are explicitly supported by the reference text without
> adding unverified information."*

The answer itself reconstructed the corpus's own method-ref-wall and two-mapper findings, which is
un-hallucinatable — so retrieval and grounding genuinely ran, not just the judges.

**2. A decline, judged — the case capability 8 never checked.** `"I don't know"` for *"what is the
capital of France?"*, cited nothing, and:

> `decline-judge` / passed — *"The reference text contains no information about the capital of France.
> The three sections describe different agents (help-desk, greeting, activity coordinator) but none
> provide factual knowledge about geographical capitals. The assistant correctly declined."*

Note the judge names the three retrieved passages by their subject. It was reasoning about the actual
reference text, not producing a generic approval.

**3. A refused interaction — nothing judged, and no judge called.** The DAN prompt returned `200` with
an empty answer and both verdicts `not-applicable`, with

```
WARN DocsAgent - docs-agent interaction blocked by a guardrail:
     Content similarity [0.77] exceeds threshold [0.75]
```

in the log — capability 12's rule firing on a surface capability 12 never knew about, exactly as
designed, with capability 13 configuring nothing.

**4. Validation** — a blank question returned `400 question must not be blank` before anything ran.

### What the live run could *not* show, reported because it is a limit of the smoke test

**No `failed` verdict was provoked.** Several attempts to make the assistant over-claim — including
*"how many total capabilities does this project have, and what does capability 20 cover?"*, chosen
because the corpus has no capability 20 — produced an honest `"I don't know"` and two `passed`
verdicts instead. Capability 8's grounding instruction held every time.

Two honest readings, and the second is the important one:

1. Encouraging for capability 8 — its soft grounding constraint was not observed to fail here.
2. **It means the live run exercised only one half of each judge.** The `failed` and `errored` paths
   are proven **offline**, on scripted input, where they can be produced deterministically. A smoke
   test against a well-behaved model cannot demonstrate a judge disagreeing, and it would be a
   mistake to read four green live verdicts as evidence that the judges *can* fail. The offline suite
   is the proof; the live run only shows the loop is real.

This is also the reason the design forbids acting on a verdict (FR-008): a judge's opinion is
non-deterministic, and nothing here has established a base rate for it.
