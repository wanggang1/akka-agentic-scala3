# Phase 0 Research: To-do Read Model (cap-11)

**Feature**: `013-views-read-model` | **Date**: 2026-08-28 | **SDK**: `akka-javasdk` 3.6.3

All findings below are settled with **evidence** (JVM bytecode via `javap`, plus one compile spike),
following the method established in specs/005 R2, specs/007 R1, specs/009 D1 and specs/012 R1. No
item is carried into implementation as an assumption except R6, which is explicitly flagged with a
decided fallback.

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

2. **The language-of-consumer rule (README §8) settles *authored in Java*, not merely Java-shaped.**
   The project already writes Java-shaped types *in Scala* (Jackson-annotated case classes), so
   reason 1 alone would permit a Scala row. But the row has **two** consumers: the Scala updater
   (which builds it) and the **Java endpoint** (which receives it from the query). §8 forbids a
   Java→Scala dependency. A Java record satisfies both consumers with no annotations at all.

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

## R6 — OPEN (with a decided fallback): what a keyed single-row query returns on no match

**Status**: not settled from bytecode or docs; **resolved by the first integration test**, not by
guesswork.

`View$QueryEffect<T>` is a marker interface and the client returns a bare `R`, so a no-match single-row
query either returns `null` or throws. The docs in `akka-context/` say nothing about it, and
`java.util.Optional` *does* appear in `ViewDescriptorFactory$`, suggesting `QueryEffect<Optional<T>>`
may be supported — but "appears in the constant pool" is not proof, and this project does not ship
guesses.

**Plan**: implement the keyed query returning `Optional<TodoSummaryEntry>` and let the first run of
the view integration test confirm it. **If unsupported, fall back** to giving the keyed query the same
wrapper shape as the multi-row query (`SELECT * AS entries … WHERE username = :username`) and treating
an empty list as not-found. The fallback is deterministic regardless of SDK behavior, so **FR-004 is
satisfiable either way** and no requirement is at risk. Whichever holds gets recorded in the README.

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
