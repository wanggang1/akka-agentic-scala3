package com.gwgs.akkaagentic.activities.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[SuggestionQuestion]] validation + instruction rendering (pure domain, no runtime). */
class SuggestionQuestionTest:

  @Test
  def validLocationIsAcceptedAndTrimmed(): Unit =
    val result = SuggestionQuestion.validate(Some("  Boston  "), Some("  outdoorsy  "))
    assertThat(result.isRight).isTrue()
    val q = result.toOption.get
    assertThat(q.location).isEqualTo("Boston") // trimmed
    assertThat(q.preferences).isEqualTo(Some("outdoorsy")) // trimmed

  @Test
  def absentLocationIsRejected(): Unit =
    assertThat(SuggestionQuestion.validate(None, None).isLeft).isTrue()

  @Test
  def blankLocationIsRejected(): Unit =
    assertThat(SuggestionQuestion.validate(Some("   "), None).isLeft).isTrue()

  @Test
  def blankOrAbsentPreferencesNormalizeToNone(): Unit =
    assertThat(SuggestionQuestion.validate(Some("Boston"), Some("   ")).toOption.get.preferences).isEqualTo(None)
    assertThat(SuggestionQuestion.validate(Some("Boston"), None).toOption.get.preferences).isEqualTo(None)

  @Test
  def instructionIncludesLocationAndPreferences(): Unit =
    val q = SuggestionQuestion.validate(Some("Boston"), Some("with kids")).toOption.get
    assertThat(q.instruction).contains("Boston")
    assertThat(q.instruction).contains("with kids")

  @Test
  def instructionStatesNoPreferenceWhenAbsent(): Unit =
    val q = SuggestionQuestion.validate(Some("Boston"), None).toOption.get
    assertThat(q.instruction).contains("no particular preference")
