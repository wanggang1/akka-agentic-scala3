package com.gwgs.akkaagentic.docs.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[AnswerRules]] — the pure predicates behind capability 12's two response-side
  * guardrails. No Akka runtime, no model, no configuration: the rule *logic* is separable from the
  * `TextGuardrail` adapters that carry it, which is why it lives in `domain` (Constitution II).
  */
class AnswerRulesTest:

  private val Markers = List("http", "www.", "see also:")

  // ---- firstExternalReferenceMarker ----

  @Test
  def detectsAnExternalLink(): Unit =
    val found = AnswerRules.firstExternalReferenceMarker("Read more at https://akka.io/docs", Markers)
    assertThat(found).isEqualTo(Some("http"))

  @Test
  def detectionIsCaseInsensitive(): Unit =
    assertThat(AnswerRules.firstExternalReferenceMarker("See Also: the Akka manual", Markers))
      .isEqualTo(Some("see also:"))

  @Test
  def returnsTheFirstMarkerInDeclarationOrder(): Unit =
    // Both "http" and "www." are present; the answer names the first configured marker that matched,
    // so the guardrail's explanation is deterministic rather than dependent on string position.
    assertThat(AnswerRules.firstExternalReferenceMarker("http://www.example.com", Markers))
      .isEqualTo(Some("http"))

  @Test
  def anOrdinaryGroundedAnswerHasNoMarker(): Unit =
    val answer = "The runtime persists the task as the loop runs, so the work survives a restart."
    assertThat(AnswerRules.firstExternalReferenceMarker(answer, Markers)).isEqualTo(None)

  /** SC-003: an honest decline must never be treated as a violation. */
  @Test
  def theDeclineSentinelHasNoMarker(): Unit =
    assertThat(AnswerRules.firstExternalReferenceMarker("I don't know", Markers)).isEqualTo(None)

  @Test
  def emptyAndBlankTextHaveNoMarker(): Unit =
    assertThat(AnswerRules.firstExternalReferenceMarker("", Markers)).isEqualTo(None)
    assertThat(AnswerRules.firstExternalReferenceMarker("   \t ", Markers)).isEqualTo(None)

  @Test
  def noConfiguredMarkersMeansNothingIsFlagged(): Unit =
    // An empty marker list disables the rule by configuration alone (FR-007) — it must not throw
    // and must not match everything.
    assertThat(AnswerRules.firstExternalReferenceMarker("https://akka.io", Nil)).isEqualTo(None)

  @Test
  def aBlankMarkerIsIgnoredRatherThanMatchingEverything(): Unit =
    assertThat(AnswerRules.firstExternalReferenceMarker("a perfectly ordinary answer", List("", "  ")))
      .isEqualTo(None)

  // ---- sentenceCount ----

  @Test
  def countsSentencesByTerminator(): Unit =
    assertThat(AnswerRules.sentenceCount("One. Two! Three?")).isEqualTo(3)

  @Test
  def textWithoutAnyTerminatorIsOneSentence(): Unit =
    assertThat(AnswerRules.sentenceCount("a single unterminated clause")).isEqualTo(1)

  @Test
  def aTrailingTerminatorDoesNotAddAnEmptySentence(): Unit =
    assertThat(AnswerRules.sentenceCount("One. Two.")).isEqualTo(2)

  @Test
  def repeatedTerminatorsCountOnce(): Unit =
    assertThat(AnswerRules.sentenceCount("Really?! Yes...")).isEqualTo(2)

  @Test
  def blankTextHasNoSentences(): Unit =
    assertThat(AnswerRules.sentenceCount("")).isEqualTo(0)
    assertThat(AnswerRules.sentenceCount("   \n ")).isEqualTo(0)

  /** Under, at, and over a limit of 2 — the three cases the record-only guard discriminates. */
  @Test
  def underAtAndOverALimit(): Unit =
    assertThat(AnswerRules.sentenceCount("One.")).isEqualTo(1)              // under
    assertThat(AnswerRules.sentenceCount("One. Two.")).isEqualTo(2)         // at
    assertThat(AnswerRules.sentenceCount("One. Two. Three.")).isEqualTo(3)  // over

  /** Known imprecision, asserted so it is a documented property rather than a lurking surprise: a
    * decimal point reads as a terminator. Acceptable because the only rule using this count is
    * record-only (US3) — it never blocks anything on the strength of this number. */
  @Test
  def aDecimalNumberIsMiscountedAndThatIsAcceptedHere(): Unit =
    assertThat(AnswerRules.sentenceCount("The threshold is 0.75 by default.")).isEqualTo(2)
