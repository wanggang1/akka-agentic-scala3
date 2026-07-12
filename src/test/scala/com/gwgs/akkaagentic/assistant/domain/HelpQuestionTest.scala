package com.gwgs.akkaagentic.assistant.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for question validation (parse-don't-validate). No Akka runtime needed. */
class HelpQuestionTest:

  @Test
  def validQuestionReturnsRightTrimmed(): Unit =
    assertThat(HelpQuestion.validate(Some("  How do I reset my password? ")))
      .isEqualTo(Right(HelpQuestion("How do I reset my password?")))

  @Test
  def blankQuestionReturnsLeft(): Unit =
    assertThat(HelpQuestion.validate(Some(""))).isEqualTo(Left("question must not be blank"))
    assertThat(HelpQuestion.validate(Some("   "))).isEqualTo(Left("question must not be blank"))

  // `None` is what an absent `question` JSON property becomes after the endpoint boundary
  // converts Jackson's `null` to `None`.
  @Test
  def absentQuestionReturnsLeft(): Unit =
    assertThat(HelpQuestion.validate(None)).isEqualTo(Left("question must not be blank"))
