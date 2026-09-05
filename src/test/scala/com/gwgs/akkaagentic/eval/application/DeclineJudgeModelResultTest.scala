package com.gwgs.akkaagentic.eval.application

import scala.util.Try

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T018 (unit half) — the label translation, tested with no runtime and no model.
  *
  * This is the whole of the judge that *can* be tested deterministically: whether a real model judges
  * well is not a property any test may assert. What must be exact is the translation, and especially
  * its **intolerance** — a lenient mapping that quietly coerced an unrecognised label to `false` would
  * report a broken judge as an unfavourable verdict, which is the difference between `errored` and
  * `failed` that FR-005 exists to preserve.
  */
class DeclineJudgeModelResultTest:

  @Test
  def appropriatePasses(): Unit =
    val result = DeclineJudge.ModelResult("the reference text does not cover it", "appropriate").toResult
    assertThat(result.passed).isTrue()
    assertThat(result.explanation).isEqualTo("the reference text does not cover it")

  @Test
  def inappropriateFails(): Unit =
    assertThat(DeclineJudge.ModelResult("passage [2] covered it", "inappropriate").toResult.passed).isFalse()

  /** Models are inconsistent about casing and whitespace is not; casing is tolerated because it is not
    * a semantic difference, unrecognised words are not because they are. */
  @Test
  def labelCasingIsTolerated(): Unit =
    assertThat(DeclineJudge.ModelResult("e", "APPROPRIATE").toResult.passed).isTrue()
    assertThat(DeclineJudge.ModelResult("e", "Inappropriate").toResult.passed).isFalse()

  @Test
  def anUnrecognisedLabelThrowsRatherThanBeingCoercedToAVerdict(): Unit =
    val thrown = Try(DeclineJudge.ModelResult("I am not sure", "maybe").toResult).failed.get
    assertThat(thrown).isInstanceOf(classOf[IllegalArgumentException])
    assertThat(thrown.getMessage).isEqualTo("Unknown evaluation label [maybe]")

  @Test
  def aMissingLabelThrows(): Unit =
    val thrown = Try(DeclineJudge.ModelResult("no label at all", null).toResult).failed.get
    assertThat(thrown).isInstanceOf(classOf[IllegalArgumentException])
    assertThat(thrown.getMessage).contains("must include label")
