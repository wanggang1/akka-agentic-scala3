package com.gwgs.akkaagentic.capturecheck.domain

import language.experimental.captureChecking
import scala.caps.Capability

/** Capture-checking spike (Scala 3.7) — a LANGUAGE-FEATURE demonstration on the pure
  * `domain` layer, NOT an agent-safety feature.
  *
  * Why not agent safety: the agents in this project are tool-calling agents that the Akka
  * SDK dispatches REFLECTIVELY over JAVA types (`effects()`, `ComponentClient`, Jackson).
  * There is no agent-generated Scala source to capture-check, and the SDK's reflective/Java
  * core is exactly the kind of code capture checking's "safe subset" disables. Odersky's
  * "Tracked Capabilities for Safer Agents" targets CODE-GENERATING agents; ours are not that.
  * Full reasoning: `docs/capture-checking-evaluation.md`.
  *
  * What this DOES show: on a pure value (a retrieved RAG `Passage`, tying back to caps 8/21/22),
  * capture checking can prove at COMPILE TIME that a labelling transform has no side effects —
  * that it cannot smuggle the passage out through a logger, a network call, or a
  * `ComponentClient`. The proof is the function arrow: `->` (pure, empty capture set) versus
  * `=>` (impure, may capture capabilities). See `CaptureCheckingTest` for the compile-time
  * accept/reject evidence, and `docs/capture-checking-spike.md` for where the tracking dies at
  * the SDK edge.
  */

/** A retrieved passage — the sensitive value we do not want leaking out of the pure core. */
final case class Passage(id: String, text: String)

/** A citation label derived purely from a passage (an index + the source id it cites). */
final case class Label(index: Int, sourceId: String)

/** A side-effecting capability: the stand-in for the SDK edge — logging, a network call, a
  * `ComponentClient` invocation, anything that could carry a passage OUT of the pure core.
  * Extending `scala.caps.Capability` makes a value of this type TRACKED in capture sets, so
  * any closure that closes over one is typed impure (`=>{sink}`), never pure (`->`). */
trait Sink extends Capability:
  def emit(line: String): Unit

/** Pure derivations from a passage. */
object CitationLabels:
  /** PURE: a passage to a citation label, capturing nothing. The `->` arrow is the whole
    * point — the compiler proves this cannot touch a `Sink`. */
  val label: Passage -> Label =
    p => Label(p.id.length, p.id)

  /** IMPURE, and it compiles — but ONLY because it is typed with the impure arrow `=>`. The
    * body closes over the `sink` capability, so its inferred type is `Passage ->{sink} Label`.
    * Retype the result as the pure `Passage -> Label` and the compiler rejects it with:
    *
    *   Found:    (p: Passage^?) ->{sink} Label^?
    *   Required: Passage -> Label
    *
    * That rejection is the load-bearing witness of the spike (reproduced in
    * `docs/capture-checking-spike.md`); the arrow is the only thing that changes. `transform`
    * and `use` on `Classified` demand `->`, so no `sink`-capturing closure can be passed to
    * them — a passage cannot be smuggled out of the pure core. */
  def auditedLabel(sink: Sink): Passage => Label =
    p => { sink.emit(s"labelled ${p.id}"); label(p) }

/** Holds a passage so it can only be observed through a PURE function. The `->` in `transform`
  * and `use` forbids the supplied function from capturing any capability, so the wrapped
  * passage cannot be exfiltrated into a `Sink`. An impure closure simply does not typecheck
  * where a `->` is required (proven in the test). */
final class Classified[T](private val value: T):

  /** Map the wrapped value with a PURE function, staying inside the classified boundary. */
  def transform[U](f: T -> U): Classified[U] = Classified(f(value))

  /** Declassify to a derived value with a PURE function. The result (e.g. a `Label`) is what
    * leaves the boundary — never the raw passage, and never via a captured capability. */
  def use[U](f: T -> U): U = f(value)
