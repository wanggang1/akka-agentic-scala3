# Scala 3.7 capture-checking spike

A **language-feature spike**, not a capability and not an agent-safety feature. It demonstrates
Scala 3.7 capture checking on the project's pure `domain` layer and records where the tracking
stops at the Akka SDK edge. Run as a lightweight throwaway on branch
`spike/capture-checking-37-probe`.

**Read `docs/capture-checking-evaluation.md` first** for why capture checking cannot make *these*
agents safer: they are tool-calling agents dispatched reflectively by the SDK over Java types, so
there is no agent-generated source to check, and the SDK's reflective/Java core is exactly what the
feature's safe subset disables. Odersky's "Tracked Capabilities for Safer Agents" targets
code-generating agents; ours are not that. If anyone reframes this spike as agent safety, correct it.

## Artifacts

- `src/main/scala/com/gwgs/akkaagentic/capturecheck/domain/CaptureChecking.scala` — `Passage`,
  `Label`, a `Sink` capability (the SDK-edge stand-in), a pure `CitationLabels.label: Passage -> Label`,
  a compiling impure witness `CitationLabels.auditedLabel(sink): Passage => Label`, and `Classified[T]`
  whose `transform`/`use` demand a pure `->` function.
- `src/test/scala/.../CaptureCheckingTest.scala` — pure path runs; `Classified` lets only a derived
  value out; and a locked-in witness that the standard compile-time tools are capture-blind.
- **Off the component descriptor and off the agent path.** Compile-time only, no runtime component.

## The core demonstration

Tie-in to the RAG work (caps 8/21/22): a retrieved `Passage` is the sensitive value; a citation
`Label` is the only thing allowed to leave the pure core.

The pure transform compiles and is the sole way through `Classified`:

```scala
val label: Passage -> Label = p => Label(p.id.length, p.id)     // compiles
Classified(Passage("src-7", "...")).transform(label).use(_.sourceId)  // "src-7"
```

The impure body compiles **only** when typed with the impure arrow `=>`:

```scala
def auditedLabel(sink: Sink): Passage => Label =                // compiles
  p => { sink.emit(s"labelled ${p.id}"); label(p) }
```

Retype that same body's result as the pure `Passage -> Label` and it is **rejected**. This is the
load-bearing negative. Reproduce it exactly:

```
# on branch spike/capture-checking-37-probe, append to CaptureChecking.scala:
#   object NegProbe:
#     def leak(sink: Sink): Passage -> Label = p => { sink.emit(p.text); Label(0, p.id) }
mvn compile
```

Compiler error (Scala 3.7.3):

```
Found:    (p: Passage^?) ->{sink} Label^?
Required: Passage -> Label
```

The `->{sink}` is the capture set made visible: the closure captures the `sink` capability, so it is
not the empty-capture pure function `transform`/`use` require. A `Sink`-capturing closure therefore
cannot be passed to `Classified`, so a passage cannot be smuggled out of the pure core.

## Findings

1. **Build compatibility (Phase 0 probe) — PASS.** `scala-maven-plugin` 4.9.2 drives a Scala **3.7.3**
   compiler on this mixed Java/Scala project with **akka-javasdk pinned at 3.6.3**; `mvn clean verify`
   is green (252 integration tests). The only Scala-3-version-sensitive dependency is
   `scala3-library_3` (bumped 3.3.8 → 3.7.3); the `scala-library:2.13.16` underneath is the Akka
   runtime's 2.13 stdlib, on an independent axis our Scala 3 code never TASTy-links. No pom change
   beyond the one-line `scala.version` bump — the `-proc:none` / `-parameters` / `sendJavaToScalac`
   mechanics all survived the compiler bump. Confirms the 2026-10-30 dependency-tree reasoning: a
   Scala 3.7 compiler and the free-tier akka-javasdk 3.6.3 are two different "3.7"s on separate axes;
   no paid plan needed (that ceiling is the *SDK* version, not the Scala version).

2. **Capture checking tracks capability VALUES, not `def`-returned capabilities.** A closure that
   calls `def sink: Sink = ???` captures nothing (the method, not a value), so the impure-as-pure
   assignment wrongly compiles. The capability must be a `val` or a parameter (`sink: Sink`) for the
   closure to capture it and the rejection to fire. Easy to get wrong when writing a demo.

3. **The standard compile-time testing utilities are capture-blind.**
   `scala.compiletime.testing.typeChecks` / `typeCheckErrors` compile their snippet in a context
   **without** the `captureChecking` language feature: it is a top-level-only import
   (`this language import is only allowed at the toplevel`) that does not propagate into the macro
   snippet, and the snippet may not re-import it. So the impure-as-pure snippet type-checks as valid
   inside `typeChecks`. The test keeps this as a deliberate `isTrue` witness that flips (and fails
   loudly) if a future release changes the behaviour.

4. **`<:<` evidence is capture-blind too.** `summon[NotGiven[(Passage => Label) <:< (Passage -> Label)]]`
   does **not** compile — the compiler synthesizes the `<:<` instance regardless of capture sets.
   Capture enforcement happens during assignment/adaptation checking, not in the implicit / `<:<`
   layer that `typeChecks` and `<:<` probe. Consequence: there is **no green, automated** way to
   assert a capture-checking negative with the lightweight in-build tools; a true automated negative
   would need the Scala 3 compiler as a test dependency to compile a fixture programmatically (not
   taken, to keep the spike lightweight). The negative here is instead recorded-and-reproducible
   (above), and proven by hand.

## Where the tracking dies at the SDK edge

Capture checking is a property of **Scala source the compiler sees**. It stops exactly where this
project's other interop findings stop — at the framework boundary (cf. `FINDINGS.md` and
`docs/http-endpoint-sdk-boundary.md`):

- **`effects()` / `ComponentClient` / the agent & task clients** are the SDK's own Java-shaped API.
  A capture set cannot cross into them: once a value is handed to `effects().reply(...)` or a
  `ComponentClient` call, it leaves the typed Scala world for reflective dispatch. The pure core can
  be capture-checked; the call that publishes its result cannot.
- **Jackson (de)serialization** reconstructs component payloads reflectively from Java-shaped wire
  types (see the Scala-Jackson-module finding). A value that re-enters Scala from the wire arrives
  with no capture information — the tracking does not survive a round trip through the journal, a
  topic, or an HTTP body.
- **The model → tool path** is reflective and Java-typed end to end, which is why this is a domain
  demo and not agent safety.

Net: capture checking is usable and genuinely enforcing **within** the pure domain layer (as shown),
and that is the whole of what it can offer here. It adds nothing at the SDK edge, where this
project's value and its hazards actually live.

## Exit status

Met: one file type-checks the pure transform and the impure one is rejected (recorded + reproducible),
plus this boundary write-up. Branch is a throwaway; see the final commit for whether it is kept,
merged, or dropped.
