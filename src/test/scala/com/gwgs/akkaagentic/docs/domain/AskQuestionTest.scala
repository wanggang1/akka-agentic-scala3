package com.gwgs.akkaagentic.docs.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[AskQuestion]] validation (pure domain, no runtime). */
class AskQuestionTest:

  @Test
  def presentQuestionIsAcceptedAndTrimmed(): Unit =
    val result = AskQuestion.validate(Some("  how does session memory work?  "))
    assertThat(result.isRight).isTrue()
    assertThat(result.toOption.get.question).isEqualTo("how does session memory work?") // trimmed

  @Test
  def absentQuestionIsRejected(): Unit =
    val result = AskQuestion.validate(None)
    assertThat(result.isLeft).isTrue()
    assertThat(result.left.toOption.get).isEqualTo("question must not be blank")

  @Test
  def blankQuestionIsRejected(): Unit =
    assertThat(AskQuestion.validate(Some("")).isLeft).isTrue()

  @Test
  def whitespaceOnlyQuestionIsRejected(): Unit =
    assertThat(AskQuestion.validate(Some("   \t  ")).isLeft).isTrue()
