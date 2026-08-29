# Phase 0 Research: To-do Read Model (cap-11)

**Feature**: `013-views-read-model` | **Date**: 2026-08-28 | **SDK**: `akka-javasdk` 3.6.3

All findings below are settled with **evidence** (JVM bytecode via `javap`, plus one compile spike),
following the method established in specs/005 R2, specs/007 R1, specs/009 D1 and specs/012 R1. No
item was carried into implementation as an assumption: R1–R5 were settled before coding, and R6 —
deliberately left open with a decided fallback rather than guessed — was settled empirically during
implementation (task T010; **Branch A** held, see below).

---

## R1 — Is the View query client Scala-callable? **NO. The project's bet is CONFIRMED.**

**Decision**: The **endpoint that queries the View must be Java.**

**Evidence** — `javap -cp akka-javasdk-3.6.3.jar akka.javasdk.client.ViewClient`:

```java
public interface akka.javasdk.client.ViewClient {
  <T,R>    ComponentInvokeOnlyMethodRef<R>      method(akka.japi.function.Function<T, View$QueryEffect<R>>);
  <T,A1,R> ComponentInvokeOnlyMethodRef1<A1,R>  method(akka.japi.function.Function2<T, A1, View$QueryEffect<R>>);
  <T,R>    ViewStreamMethodRef<R>               stream(akka.japi.function.Function<T, View$QueryStreamEffect<R>>);
  <T,A1,R> ViewStreamMethodRef1<A1,R>           stream(akka.japi.function.Function2<T, A1, View$QueryStreamEffect<R>>);
}
```

Four methods, **all** taking `akka.japi.function.Function*`. There is **no `dynamicCall`**, no
String-keyed overload, no query-name API. And the parameter type is the serializable-lambda type the
resolver needs:

```java
public interface akka.japi.function.Function<T,R> extends java.io.Serializable   // akka-actor_2.13-2.10.20.jar
```

A Scala lambda compiles to a synthetic `$anonfun$N`, so `MethodRefResolver` cannot recover the target
method name. This is **structurally identical to `KeyValueEntityClient`** (same two `method(Function…)`
overloads), i.e. the same wall as cap-2's Workflow (§4) and cap-6's entity (§8).

**The contrast that proves the wall is a client property**, `AgentClientInSession`:

```java
<A1,R> DynamicMethodRef<A1,R> dynamicCall(java.lang.String);   // present on the agent client
```

`dynamicCall` exists on `AgentClientInSession` and **on no other client** — a jar-wide sweep over
`AgentClient`, `AutonomousAgentClient`, `TaskClient`, `ViewClient`, `WorkflowClient`,
`EventSourcedEntityClient`, `KeyValueEntityClient` returns it only there.

**Alternatives considered**: none viable. There is no string/query-name escape hatch to fall back on,
and routing the query through another Scala component would just relocate the same client call.

---

## R2 — Can the `View` / `TableUpdater` itself be Scala? **YES — but only with the updater in the companion object.**

**Decision**: **The View component is Scala.** Its `TableUpdater` subclass is declared in the View's
**companion object**, not as an inner class of the View class.

This is the headline finding of cap-11 and it was *not* obvious: the SDK docs describe a Table Updater
as "a **static inner class** inside your View", and Scala's two nesting forms compile very differently.

**Evidence — the SDK's requirement.** `ViewDescriptorFactory$` discovers updaters reflectively:

```
// Method java/lang/Class.getDeclaredClasses      <- updaters must be *declared classes of the View class*
// Method java/lang/Class.isAssignableFrom        <- filtered to TableUpdater subtypes
```

and `ViewDescriptorFactory$UpdateHandlerImpl` instantiates the one it finds with a **zero-argument
constructor**:

```
4: iconst_0
5: anewarray  #133  // class java/lang/Class
8: invokevirtual   // java/lang/Class.getDeclaredConstructor:([Ljava/lang/Class;)…
   … java/lang/reflect/Constructor.newInstance
```

**Evidence — the compile spike.** Both Scala nesting forms were compiled in this project against the
real SDK and disassembled:

| | Variant A: `class View { class Updater }` | Variant B: `object View { class Updater }` |
|---|---|---|
| Found by `getDeclaredClasses()` of the View class | ✅ yes (`InnerClasses: UpdaterA … of class ProbeViewA`) | ✅ yes (`InnerClasses: **public static** UpdaterB … of class ProbeViewB`) |
| Static? | ❌ no — carries `private final ProbeViewA $outer` | ✅ **public static** |
| Zero-arg constructor? | ❌ **no** — only `UpdaterA(ProbeViewA)` | ✅ **yes** — `ProbeViewB$UpdaterB()` |
| Verdict | **would fail at runtime** on `getDeclaredConstructor()` | **matches the Java `static class` shape exactly** |

So a Scala *inner* class compiles to an outer-referencing, non-static class the SDK cannot construct,
while a class in the *companion object* compiles to a `public static` member class **of the companion
class** with a synthesized no-arg constructor — byte-for-byte the shape the SDK reflects on.

**Why this matters beyond cap-11**: every other Scala-clean result in this project came from an API
that took `Class`/`String` values. This one is different — the SDK's requirement is on the **bytecode
shape of a nested class**, and Scala satisfies it only under one of its two nesting forms. It is a new
*category* of interop hazard: not a wall, but a placement rule.

**Alternatives considered**: (a) inner class — rejected, proven to produce an unconstructable class;
(b) writing the whole View in Java — rejected, unnecessary once B was proven, and it would have made
the capability 100% Java and taught less.

---

## R3 — Must the view row type be Java-shaped? **YES — but NOT authored in Java.**

**Decision (revised during implementation)**: the row and the multi-row wrapper are **Jackson-annotated
Scala case classes** in `com.gwgs.akkaagentic.todos.application`. They were initially Java records, on a
belief about the build that turned out to be a defect — see reason 2 below.

**Two independent reasons, one confirmed by bytecode and one by the project's own rules:**

1. **The two-mapper boundary (README §3).** View rows are serialized by the SDK's *internal*
   serializer, not the public `JsonSupport` mapper where `Bootstrap` registers `DefaultScalaModule`.
   `ViewDescriptorFactory$UpdateHandlerImpl` calls:

   ```
   // Method akka/javasdk/impl/serialization/Serializer.toBytesAsJson
   // Method akka/javasdk/impl/serialization/Serializer.fromBytes
   // Method akka/javasdk/impl/serialization/Serializer.registerTypeHints
   ```

   Same path as entity state and agent payloads. So the row must be Java-*shaped* — exactly like
   `GreetingAgent.Result`, `HelpAnswer`, and `TodoList`.

2. **The build could not compile Java→Scala at all — a latent defect cap-11 exposed and forced to be
   fixed.** Reason 1 alone would permit a Scala row: the project already writes Java-shaped types *in
   Scala* (`HelpAnswer`, `GreetingAgent.Result` — Jackson-annotated case classes on this same internal
   serializer path). The obstacle was the **build order**.

   **Verified by experiment during implementation (2026-08-29).** The row was rewritten as a
   Jackson-annotated Scala case class, everything else left untouched. It did not compile:

   ```
   [ERROR] .../todos/api/TodoSummaryEndpoint.java:[9,46] cannot find symbol
   [ERROR]   symbol:   class TodoSummaryEntry
   ```

   Cause, from the build log — **javac ran before scalac**, because `maven-compiler-plugin` is declared
   by the **parent** POM (`akka-javasdk-parent`) and `scala-maven-plugin` by ours, and within the
   `compile` phase parent-declared plugins run first.

   **The conclusion first drawn from this was wrong, and the error was caught in PR review.** The
   original write-up concluded the constraint was inherent and "not freely reversible", and treated the
   row's Java authorship as forced. But cap-11's Java endpoint *must* name the Scala `TodoSummaryView`
   to hold its `ViewClient` method reference — the same Java→Scala direction. So the capability **did
   not build from clean at all**; `mvn clean compile` failed with `cannot find symbol: class
   TodoSummaryView`. It went unnoticed because every `mvn verify` during development was incremental
   and reused a `target/classes` that already contained the Scala output. The IDE flagged it correctly
   and the build did not, because the build was never run clean.

   **Fix (pom.xml).** Bind `scala-maven-plugin`'s `compile` to **`process-resources`** and its
   `testCompile` to **`process-test-resources`**, and set **`sendJavaToScalac=true`**. scalac then runs
   first, reading the Java sources for signatures (so Scala→Java still resolves), and javac compiles the
   Java last against scalac's output (so Java→Scala resolves too). Critically, `-parameters` **survives**
   under this order — it was lost before precisely because scalac ran *second* and overwrote javac's
   class files; now javac writes them last. Verified via the `MethodParameters` attribute on both a Java
   endpoint (`request`, `componentClient`) and a Scala one (`username`), and by a green
   `mvn clean verify` (105 unit + 101 integration). The hand-maintained descriptor is not regenerated
   (`-proc:none` still applies; `target/classes/META-INF` holds exactly our one file).

   **Mirror-image bug, also fixed.** `sendJavaToScalac=true` makes scalac joint-compile the Java sources
   and emit their `.class` files. On a *clean* build javac then recompiles and overwrites them; on an
   *incremental* build javac sees scalac's fresh output as up to date, skips, and scalac's classes ship —
   **without `-parameters`**, so HTTP path binding fails at startup with *"the parameter [username] …
   does not match the method parameter name [arg0]"*. This is the failure README §4 originally blamed on
   `sendJavaToScalac=true`: real, but caused by scalac's javac not receiving `-parameters`, not by the
   setting. Fixed with `scala-maven-plugin` `<javacArgs>-parameters</javacArgs>`, so whichever compiler
   writes the Java class files last, they carry parameter names. **Both `mvn clean verify` and
   `mvn verify` are green** (105 unit + 101 integration), verified separately.

   **Consequence — the rows moved to Scala, and README §8's "never Java→Scala" is repealed as a hard
   rule.** With both directions compiling, the reason for Java-authored rows evaporated, so
   `TodoSummaryEntry`/`TodoSummaryEntries` are now Jackson-annotated **Scala** case classes (the
   `HelpAnswer`/`GreetingAgent.Result` shape). The Java quarantine is therefore **exactly one class** —
   `TodoSummaryEndpoint`, the only holder of a `ViewClient` method reference — which is what makes R1's
   claim literal rather than approximate. The language-of-consumer rule survives as **ergonomics
   guidance**, which is what it claimed to be before this feature briefly promoted it to a mechanical law.

**SETTLED — the internal mapper does accept a Jackson-annotated Scala case class as a view row.** The
first attempt failed at compile time before ever reaching the serializer; after the build fix the
experiment was re-run to completion. All 13 cap-11 integration tests pass with Scala rows, and they
genuinely exercise the serializer end to end (the updater writes a row → it is stored → queried back →
mapped to HTTP). Requirements: explicit `@JsonCreator`/`@JsonProperty`, and `java.util.List` rather than
a Scala `List` for the multi-row wrapper — i.e. exactly the Java-*shaped* rules cap-3's `HelpAnswer`
already follows. So "view rows must be Java-shaped" holds; "view rows must be Java-authored" does not.

**Consequence — a deliberate, documented deviation from AGENTS.md.** AGENTS.md says a View's query
parameter/reply should be an inner record of the View. Here they are **top-level Scala case classes
beside** the Scala View: they must be Java-*shaped* for the internal serializer (so `@JsonCreator` /
`@JsonProperty`, `java.util.List`), and a Scala View cannot nest them as Java-shaped inner records the
way a Java View would. Recorded in plan.md's Complexity Tracking.

---

## R4 — Descriptor key. **`view`.**

**Decision**: add the View under a new `view` key in the hand-maintained descriptor; add the endpoint
to the existing `http-endpoint` list.

**Evidence** — string constant pool of `akka.javasdk.impl.ComponentType$`:

```
#61 = String  // view
#33 = String  // key-value-entity
#65 = String  // agent
#69 = String  // autonomous-agent
#49 = String  // mcp-endpoint
```

This matters because the annotation processor is disabled (`-proc:none`, README §4/§1), so **nothing
discovers a component that is not listed by hand**.

---

## R5 — How to drive the projection offline. **Publish entity state directly through the TestKit.**

**Decision**: tests publish `TodoList` states straight at the view's source, with no entity command,
no agent, and no model.

**Evidence** — `TestKit$Settings` and `EventingTestKit$IncomingMessages`:

```java
TestKit.Settings withKeyValueEntityIncomingMessages(Class<? extends KeyValueEntity<?>>);   // Class-keyed → Scala-fine
<T> void publish(T message, String subject);   // publish(todoList, username)
void publishDelete(String subject);            // exercises deleteRow, if we ever need it
```

`withKeyValueEntityIncomingMessages` is `Class`-keyed, so **the test settings are callable from
Scala**; only a test that queries the View through `componentClient.forView()` is forced into Java
by R1. This is what lets the test suite split the same way the production code does.

---

## R6 — SETTLED (Branch A): a keyed single-row query returns `Optional`, empty on no match

**Status**: **RESOLVED empirically at task T010** by the first run of
`TodoSummaryViewIntegrationTest` (2026-08-29). It was left open on purpose rather than guessed.

`View$QueryEffect<T>` is a marker interface and the client returns a bare `R`, so a no-match single-row
query either returns `null` or throws. The docs in `akka-context/` say nothing about it, and
`java.util.Optional` *does* appear in `ViewDescriptorFactory$`, suggesting `QueryEffect<Optional<T>>`
may be supported — but "appears in the constant pool" is not proof, and this project does not ship
guesses.

**Plan (as written before the experiment)**: implement the keyed query returning
`Optional<TodoSummaryEntry>` and let the first run of the view integration test confirm it. **If
unsupported, fall back** to giving the keyed query the same wrapper shape as the multi-row query
(`SELECT * AS entries … WHERE username = :username`) and treating an empty list as not-found. The
fallback is deterministic regardless of SDK behavior, so **FR-004 is satisfiable either way** and no
requirement is at risk.

### Outcome: **Branch A**, no fallback needed

`QueryEffect[Optional[TodoSummaryEntry]]` is fully supported on SDK 3.6.3 — the runtime accepted the
query at component discovery, and a lookup for a username the projection has never seen returns
**`Optional.empty`**: not `null`, not a thrown exception, and not a zero-filled row. So a keyed view
query has a first-class "no such row" answer, which the endpoint maps straight to `404`
(`Optional.empty` &rarr; `HttpResponses.notFound()`), while an *emptied* list is a real row of
`(0, 0, 0)` &rarr; `200`. The two are cleanly distinguishable, which is exactly what FR-004 needs.

Shipped signature (`TodoSummaryView.scala`):

```scala
@Query("SELECT * FROM todo_summaries WHERE username = :username")
def getByUsername(username: String): View.QueryEffect[Optional[TodoSummaryEntry]] =
  queryResult()
```

Proof: `TodoSummaryViewIntegrationTest.anUnknownUsernameYieldsNotFound` asserts `isEmpty()` on an
unknown username *after* awaiting a different user's row, so the assertion is about genuine absence
rather than the projection not having caught up. The wrapper-shape fallback was never used, and the
multi-row query keeps the `SELECT * AS entries` form only because it genuinely selects many rows.

---

## Resulting language split (one sentence, as required)

> **The View is Scala — with its `TableUpdater` in the companion object so it compiles to the static,
> zero-arg nested class the SDK reflects on — and only the querying endpoint is Java, because
> `ViewClient` is method-reference-only; the row records are Java so the Java endpoint never depends
> on Scala.**

**Why this is a new data point rather than a repeat of cap-2/cap-6**: in every previous encounter the
wall pulled the **component itself** into Java. Here the component stays **Scala** and the wall claims
only its **caller**. Cap-11 is the first capability split *across* the component/caller boundary,
which sharpens the through-line from "some components are Java" to the more precise
"**the wall is a property of the client, and it travels no further than the class that holds it**."
