package com.gwgs.akkaagentic.capturecheck.domain

import language.experimental.captureChecking
import scala.compiletime.testing.{typeChecks, typeCheckErrors}
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/** Compile-time + runtime evidence for the capture-checking spike (Scala 3.7). Pure-logic only
  * (no Akka runtime); runs under `-Pquick`.
  *
  * What IS asserted here (green):
  *   - the pure `Passage -> Label` transform compiles and runs, and `Classified` only lets a
  *     derived value out (never the raw passage);
  *   - a LOCKED-IN finding: the standard compile-time testing utilities are *capture-blind*.
  *
  * What is NOT asserted here, and why: the capture-checking REJECTION (an impure closure may not
  * be typed `Passage -> Label`) is real and authoritative, but it cannot be turned into a green
  * assertion with the lightweight in-build tools, because both of them ignore capture sets:
  *   - `scala.compiletime.testing.typeChecks` compiles its snippet in a context WITHOUT the
  *     `captureChecking` language feature (it is a top-level-only import and does not propagate
  *     into the macro snippet, which also may not re-import it), so the impure snippet type-checks;
  *   - `<:<` evidence between `=>` and `->` is synthesized regardless of captures (see the
  *     `NotGiven` note below).
  * The rejection is instead proven by a recorded, reproducible compile in
  * `docs/capture-checking-spike.md` (exact diff + the `->{sink}` error + the one-line command).
  * The compiling impure witness `CitationLabels.auditedLabel` (typed `=>`) is the in-code
  * counterpart: same body, accepted only because of the arrow.
  */
class CaptureCheckingTest:

  // --- positive: the pure path compiles AND runs, raw passage never escapes ---

  @Test def pureTransformRunsAndStaysClassified(): Unit =
    val classified = Classified(Passage("src-7", "the sky is blue"))
    val labelled: Classified[Label] = classified.transform(CitationLabels.label)
    // `use` with a pure projection is the only way out — a Label, never the raw passage.
    assertThat(labelled.use(_.sourceId)).isEqualTo("src-7")
    assertThat(labelled.use(_.index)).isEqualTo(5) // "src-7".length

  // --- positive: a pure `Passage -> Label` is a legal argument to `transform` ---

  @Test def pureFunctionTypeChecks(): Unit =
    val code =
      """
      val pure: Passage -> Label = p => Label(p.id.length, p.id)
      Classified(Passage("p1", "hi")).transform(pure)
      """
    assertThat(typeCheckErrors(code).map(_.message).mkString("; ")).isEqualTo("")
    assertThat(typeChecks(code)).isTrue

  // --- locked-in finding: `typeChecks` is capture-blind (does NOT propagate `captureChecking`) ---

  @Test def standardTypeCheckUtilitiesAreCaptureBlind(): Unit =
    // This closure captures the `sink` capability; under capture checking it may NOT be typed
    // `Passage -> Label` (proven in docs/capture-checking-spike.md). Yet `typeChecks` reports it
    // as valid, because its snippet is compiled WITHOUT the feature. If a future Scala release
    // makes the feature propagate here, this flips to `false` and this assertion fails — which is
    // exactly the signal we want, so the finding stays honest.
    val impureSnippet =
      """
      def leak(sink: Sink): Passage -> Label = p => { sink.emit(p.text); Label(0, p.id) }
      """
    // (true == capture-blind: the impure-as-pure snippet is wrongly accepted)
    assertThat(typeChecks(impureSnippet)).isTrue
