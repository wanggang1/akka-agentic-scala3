package com.gwgs.akkaagentic.docs.domain

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[CitationMarkers]] — the incremental `[n]` stripper (pure domain, no runtime).
  *
  * The decisive property (and the offline oracle for the whole capability): for *any* chunking of an
  * answer, the concatenated emits equal the answer with every numeric `[n]` removed, and the
  * concatenated numbers equal the markers' numbers in order. The regex `\[\d+\]` is that oracle — it is
  * exactly the stripper's grammar, so a single-pass regex over the whole answer is what the chunked
  * stripper must reproduce no matter where the chunk boundaries fall (research R3).
  */
class CitationMarkersTest:

  /** Drive a chunk sequence through `step`…`step` then `finish`; return the full emitted text and all
    * extracted numbers in order — exactly what the streaming endpoint reconstructs. */
  private def run(chunks: List[String]): (String, List[Int]) =
    val (finalState, emits, nums) =
      chunks.foldLeft((CitationMarkers.empty, Vector.empty[String], Vector.empty[Int])) {
        case ((state, emits, nums), chunk) =>
          val r = CitationMarkers.step(state, chunk)
          (r.carry, emits :+ r.emit, nums ++ r.numbers.asScala.map(_.intValue))
      }
    val f = CitationMarkers.finish(finalState)
    ((emits :+ f.emit).mkString, (nums ++ f.numbers.asScala.map(_.intValue)).toList)

  // The oracle: numeric-marker grammar applied in one pass over the whole string.
  private def strippedOracle(answer: String): String = answer.replaceAll("\\[\\d+\\]", "")
  private def numbersOracle(answer: String): List[Int] =
    "\\[(\\d+)\\]".r.findAllMatchIn(answer).map(_.group(1).toInt).toList

  @Test
  def noMarkerTextPassesThroughUnchanged(): Unit =
    val (emit, numbers) = run(List("agent work survives a restart because tasks are durable"))
    assertThat(emit).isEqualTo("agent work survives a restart because tasks are durable")
    assertThat(numbers.asJava).isEmpty()

  @Test
  def aSingleMarkerIsExtractedAndRemoved(): Unit =
    val (emit, numbers) = run(List("see the durability tasks [1] for details"))
    assertThat(emit).isEqualTo("see the durability tasks  for details") // marker gone, spaces kept
    assertThat(numbers.asJava).containsExactly(1)

  @Test
  def multipleMarkersAreExtractedInOrder(): Unit =
    val answer = "tasks are durable [1] and survive a restart [2] via snapshots [3]"
    val (emit, numbers) = run(List(answer))
    assertThat(emit).isEqualTo(strippedOracle(answer))
    assertThat(numbers.asJava).containsExactly(1, 2, 3)

  @Test
  def aMarkerSplitAtEveryBoundaryNeverLeaksAndIsExtractedOnce(): Unit =
    // The one that matters: split this answer at EVERY position into two chunks and assert the chunked
    // stripper reproduces the single-pass oracle — text stripped identically, numbers identical.
    val answer = "survives a restart [12] because tasks [3] are durable and replayed [456]"
    val expectedText = strippedOracle(answer)
    val expectedNums = numbersOracle(answer)
    for k <- 0 to answer.length do
      val (emit, numbers) = run(List(answer.substring(0, k), answer.substring(k)))
      // Scala `assert` here (not assertj) so the split index `k` is named on failure; a chunked run that
      // diverges from the single-pass oracle at any boundary is the bug this test exists to catch.
      assert(emit == expectedText, s"text wrong when split at $k: got [$emit]")
      assert(numbers == expectedNums, s"numbers wrong when split at $k: got $numbers")
      assert(!emit.contains("["), s"'[' fragment leaked when split at $k: [$emit]")

  @Test
  def theTaskExampleSplitMidMarkerYields12WithNoFragmentLeak(): Unit =
    val (emit, numbers) = run(List("…restart [1", "2] because done"))
    assertThat(numbers.asJava).containsExactly(12)
    assertThat(emit).isEqualTo("…restart  because done")
    assertThat(emit).doesNotContain("[1")

  @Test
  def aTrailingOpenBracketIsHeldThenFlushedAsLiteral(): Unit =
    // A dangling '[' or '[digits' that never closes is not a marker — finish emits it as literal text.
    assertThat(run(List("ends with a bracket ["))._1).isEqualTo("ends with a bracket [")
    assertThat(run(List("ends with digits [12"))._1).isEqualTo("ends with digits [12")
    val (emit, numbers) = run(List("ends with digits [12"))
    assertThat(numbers.asJava).isEmpty()

  @Test
  def nonNumericBracketsAreLeftLiteral(): Unit =
    // Only `[` + digits + `]` is the signal. `[note]`, `[]`, and `[12x` stay in the prose verbatim.
    val answer = "a footnote [note] and empty [] and partial [12x end"
    val (emit, numbers) = run(List(answer))
    assertThat(emit).isEqualTo(answer)
    assertThat(numbers.asJava).isEmpty()

  @Test
  def declineTextPassesThroughUnchanged(): Unit =
    // The honest-decline sentinel (mirrors DocsAgent.DontKnow) has no brackets — unchanged, no numbers.
    val (emit, numbers) = run(List("I don't know"))
    assertThat(emit).isEqualTo("I don't know")
    assertThat(numbers.asJava).isEmpty()
