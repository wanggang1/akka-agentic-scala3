package com.gwgs.akkaagentic.approvals.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for question validation (parse-don't-validate). No Akka runtime needed. */
class ApprovalQuestionTest:

  @Test
  def validQuestionReturnsRight(): Unit =
    assertThat(ApprovalQuestion.validate(Some("How do I get a refund?")))
      .isEqualTo(Right(ApprovalQuestion("How do I get a refund?")))

  @Test
  def paddedQuestionIsTrimmed(): Unit =
    assertThat(ApprovalQuestion.validate(Some("  How do I get a refund?  ")))
      .isEqualTo(Right(ApprovalQuestion("How do I get a refund?")))

  @Test
  def blankQuestionReturnsLeft(): Unit =
    assertThat(ApprovalQuestion.validate(Some(""))).isEqualTo(Left("question must not be blank"))
    assertThat(ApprovalQuestion.validate(Some("   "))).isEqualTo(Left("question must not be blank"))

  // `None` is what an absent `question` JSON property becomes after the endpoint boundary
  // converts Jackson's `null` to `None`.
  @Test
  def absentQuestionReturnsLeft(): Unit =
    assertThat(ApprovalQuestion.validate(None)).isEqualTo(Left("question must not be blank"))
