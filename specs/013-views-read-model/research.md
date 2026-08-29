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

## R3 — Must the view row type be Java-shaped? **YES — and it must also be *authored* in Java.**

**Decision**: The row record and the multi-row wrapper are **Java records** in
`com.gwgs.akkaagentic.todos.application`.

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

2. **The build's compile order makes a Java→Scala dependency *impossible*, not merely discouraged.**
   Reason 1 alone would permit a Scala row: the project already writes Java-shaped types *in Scala*
   (`HelpAnswer`, `GreetingAgent.Result` — Jackson-annotated case classes on this same internal
   serializer path). What actually forces Java authorship is the **consumer**: R1 puts the querying
   endpoint in Java, and a Java class cannot reference a Scala one in this build.

   **Verified by experiment during implementation (2026-08-29).** The row was rewritten as a
   Jackson-annotated Scala case class, everything else left untouched. It does not compile:

   ```
   [ERROR] .../todos/api/TodoSummaryEndpoint.java:[9,46] cannot find symbol
   [ERROR]   symbol:   class TodoSummaryEntry
   [ERROR]   location: package com.gwgs.akkaagentic.todos.application
   ```

   Cause, from the build log — **javac runs before scalac**:

   ```
   [INFO] --- compiler:3.13.0:compile (default-compile) ---
   [INFO] Compiling 15 source files with javac [debug release 21] to target/classes
   [INFO] --- scala:4.9.2:compile (scala-compile) ---
   ```

   `maven-compiler-plugin` is declared by the **parent** POM (`akka-javasdk-parent`) and
   `scala-maven-plugin` by ours; within the `compile` phase, parent-declared plugins run first. So
   when javac runs, no Scala class file exists yet. Scala→Java works (the Java classes are already
   on disk); **Java→Scala cannot work at all**.

   This is a **correction to how README §8 has been stated**. §8 was written as a *style* rule
   ("depend Scala→Java, never Java→Scala") justified by ergonomics — `MODULE$`, `Option` interop.
   It is in fact a **mechanical constraint of the build**, and the ergonomic argument is a
   side-issue. Nor is it freely fixable: binding `scala-maven-plugin` to an earlier phase would
   reverse the constraint (breaking the Scala→Java direction this project depends on everywhere),
   and `sendJavaToScalac=true` — the joint-compilation escape — is already ruled out because it
   drops `-parameters` and breaks HTTP path binding (README §4). One direction is available, and
   the project picked the right one.

**Note on what the experiment did *not* settle.** Because it failed at compile time, it never reached
the serializer, so whether the internal mapper accepts a Jackson-annotated Scala case class *as a view
row* remains untested. It does not matter here: R1 forces the endpoint to Java, which forces the row's
consumer to Java, which — by the compile order above — forces the row itself to Java. The two findings
compose into a genuine constraint, just not the one originally claimed.

**Consequence — a deliberate, documented deviation from AGENTS.md.** AGENTS.md says a View's query
parameter/reply should be an inner record of the View. Here they are **top-level Java records beside**
the Scala View, because a Java endpoint cannot depend on types nested in a Scala class without
violating §8. Recorded in plan.md's Complexity Tracking.

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
