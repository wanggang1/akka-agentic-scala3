# Research: Multi-agent greeting Workflow

Phase 0 findings. Every unknown from the spec/Technical Context is resolved below. R1 and R2 are the
load-bearing findings that shaped the whole feature; R6 is the meta-finding that frames the roadmap.

## R1 — The Workflow API is Java-method-reference-only; a pure-Scala workflow is impossible (THE CRUX)

**Decision**: Implement **all of capability 2 in Java**. Do **not** attempt a Scala workflow, nor a
Scala/Java hybrid.

**Rationale (proven, not assumed)**. Inspecting the SDK 3.6.0 bytecode and running compile spikes:

1. **How the SDK resolves a step/command reference.**
   `akka.javasdk.impl.client.MethodRefResolver.resolveMethodRef(obj)`:
   - requires `obj` to be `Serializable`, calls its `writeReplace`, expects a
     `java.lang.invoke.SerializedLambda`, then looks up
     `implClass.getDeclaredMethod(implMethodName, argClasses)`.
   - i.e. it finds the target purely by the lambda's **`implMethodName`** on **`implClass`**.
   `akka.japi.function.Function`/`Function2` both `extends java.io.Serializable`, so a *serializable
   SAM lambda* is what it expects.

2. **Every step-referencing Workflow API takes such a `Function`.** From `javap`:
   - `Effect.Builder.transitionTo(Function<W, StepEffect>)` / `transitionTo(Function2<W, I, StepEffect>)`
   - `StepEffect.Builder.thenTransitionTo(Function<W, StepEffect>)` (+ `Function2`)
   - `WorkflowSettingsBuilder.stepTimeout(Function…, Duration)`, `stepRecovery(Function…, RecoverStrategy)`,
     `timeout(Duration, Function…)`
   - `RecoverStrategy.failoverTo(Function<W, StepEffect>)` / `MaxRetries.failoverTo(…)`
   - `WorkflowClient.method(Function<T, Effect<R>>)` / `method(Function2<T, A1, Effect<R>>)`
   **There is no string/step-name overload anywhere** (the `@StepName` name is only used *internally*
   after the ref is resolved).

3. **`WorkflowClient` has no `dynamicCall`.** `dynamicCall(String)` exists **only** on
   `AgentClientInSession` (that is why cap-1 could invoke the agent from Scala by id). Entity/View/
   **Workflow** clients only expose `method(...)`.

4. **Spike results** (compiled against the project + SDK):
   - Java-authored `GreetingAgent::greet` (an unbound reference to a **Scala** component method) →
     `resolveMethodRef` returns `greet` on `GreetingAgent`. ✅
   - Scala lambdas `(a, r) => a.greet(r)` and `_.greet(_)` → resolve to
     `ScalaLambdas$.$init$$$anonfun$1` / `$anonfun$2` — the mangled synthetic names, **not** `greet`. ❌
   Scala has no `Type::instanceMethod` syntax for an unbound instance method with an argument, so a
   Scala step/command reference is always a lambda → always mangled → never resolves.

**Consequence**: a Scala workflow cannot wire `transitionTo`/`stepRecovery`/… to its own steps, and a
Scala endpoint cannot call `WorkflowClient.method(...)`. Both would need Java-authored method-ref
constants. Rather than sprinkle a Java "ref table" through a Scala workflow (and make steps public,
and manage Scala↔Java wire types and mixed-compilation ordering), the least-friction path — chosen by
the user after seeing the trade-off — is to write the **entire** workflow-centric module in Java.

**Alternatives considered**:
- *Scala workflow + Java method-ref table* — viable but requires public steps and threads a Java
  pointer table through Scala; more moving parts than a plain Java workflow.
- *Java workflow + Scala agents/endpoint* — reintroduces a Scala→Java→Scala reference chain, forcing
  joint/ordered mixed compilation and Scala↔Java wire records. Strictly more complex than all-Java.
- *Pure Scala* — impossible (point 4).

## R2 — Adding Java sources would clobber the hand-maintained descriptor

**Decision**: compile cap-2's Java with **`-proc:none`** (disable annotation processing) and keep the
single **hand-maintained** `akka-javasdk-components_*.conf` authoritative for **all** components.

**Rationale**: `akka-javasdk-parent` (3.6.0) configures `maven-compiler-plugin` with
`ComponentAnnotationProcessor` + `ComponentValidationProcessor` on the annotation-processor path.
Today the project has no Java sources, so javac never runs and the processor is dormant — which is
exactly why the Scala components are declared by hand. The moment cap-2 adds Java sources, javac runs
the processor, which **generates its own** `akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`
into `target/classes/META-INF/…`. That path is the same file our hand-maintained resource is copied
to, and the processor runs in the `compile` phase *after* `process-resources`, so the generated file
would **overwrite** ours — listing only the Java (cap-2) components and dropping the Scala (cap-1)
entries **and** the top-level `service-setup`. cap-1 would silently stop being discovered.

`-proc:none` keeps the processor off, so the hand-maintained descriptor remains the single source of
truth. We add the cap-2 Java FQCNs (and a new `workflow = [...]` key) to it by hand — identical to how
every Scala component is already registered.

**Alternatives considered**:
- *Let the processor generate a Java descriptor + a separately-named hand file* — rejected: same
  filename (groupId_artifactId) ⇒ collision; the `ComponentLocator` glob + overwrite semantics make
  two files fragile.
- *Keep the processor for compile-time validation* — nice-to-have, but not worth losing the single
  authoritative descriptor; the project has always hand-maintained it.

## R3 — All-Java cap-2 is fully decoupled from the Scala sources

**Decision**: cap-2 references **no** Scala and is referenced by **no** Scala; it carries its own
`TimeOfDay`/`Tone`. Add `maven-compiler-plugin` (compile + testCompile); leave `scala-maven-plugin` as
is.

**Rationale**: with zero cross-language references, `scalac` (cap-1) and `javac` (cap-2) each compile
in one pass and their order is irrelevant — no joint compilation, no ordering config. This is the
whole payoff of the all-Java choice. As a bonus, Java records are **Java-shaped by construction**
(explicit component-serializable types), so the feature-003 two-mapper problem never arises for cap-2
and no `Bootstrap`/`DefaultScalaModule` is needed here (that stays a cap-1 concern).

**Cost (accepted)**: `TimeOfDay` label logic is duplicated (~15 lines) rather than reused across the
language boundary. Deliberate — see Complexity Tracking in plan.md.

## R4 — Gemini: tools vs. native structured output (carried from feature 002)

**Decision**: `GreetingComposerAgent` uses `responseAs(GreetingResult.class)` + `.onFailure(...)`, not
`responseConformsTo`. `ToneAgent` has no tool and returns a plain label via `thenReply()`.

**Rationale**: Google Gemini rejects combining function calling with a JSON response mime type
(`INVALID_ARGUMENT: "Function calling with a response mime type: 'application/json' is unsupported"`).
The composer both exposes a `@FunctionTool` (current time of day) and returns a structured result, so
it must instruct the model to emit JSON in the system prompt and parse the reply text, degrading to a
safe fallback result on a parse/model failure. `ToneAgent`, having no tool, is unconstrained; a plain
one-word label reply keeps it trivial and the workflow normalizes it (`Tone.normalize`).

## R5 — Async exposure and offline testing

**Decision**: `POST /greetings` starts the workflow and returns immediately with the workflow id +
`Location`; `GET /greetings/{id}` returns the composed greeting once `getResult` reports COMPLETED,
otherwise a not-ready response. Tests mock both agents with `TestModelProvider` and poll with
Awaitility.

**Rationale**: a Workflow runs steps asynchronously; there is no synchronous "run to completion" for
the caller (verified against the Workflow API — `start` returns after `transitionTo`, steps run
after). `getResult` is a `ReadOnlyEffect<GreetingResult>` that errors until the state is COMPLETED (or
when the id is unknown / not started); the endpoint catches that and maps it to a not-ready HTTP
status, so a premature or unknown-id read never fabricates a greeting (FR-003). `TestKitSupport`
starts the service so `TestModelProvider` fixed responses drive both agents deterministically offline;
Awaitility bridges the async gap in the endpoint/workflow integration tests. **Durability (FR-006)**:
`toneStep` has `stepRecovery(maxRetries(2).failoverTo(toneFallbackStep))` — a terminal tone failure
still yields a greeting with a neutral tone; the composer's own `onFailure` covers compose failures.

## R6 — There is no native Scala path for the Akka SDK (meta-finding, context)

**Finding**: nothing in the Akka toolchain scaffolds a Scala project. `akka code init` only *"clones
one of the official Akka sample projects"* (all Java); `akka specify init` scaffolds an **empty Java**
Akka project plus SDD markdown templates and a constitution. There is no `--lang`, no Scala template,
no Scala sample. A Scala-on-the-SDK project is therefore **AI-reconstructed** from a Scala-specifying
prompt (this repo began with `/akka.specify "Create a baseline Scala 3 project structure…"`), and
because there is no first-party Scala baseline encoding the interop, the gaps surface **one capability
at a time**: hand-maintained descriptor (001), `dynamicCall` for agents (001), the two-mapper
`Bootstrap` (003), and now the **workflow method-reference wall** (004, this feature). Lightbend's
"easily used in Scala" holds for straightforward components but not for workflow-centric or
serialization-heavy code — which is the through-line this roadmap is documenting.
