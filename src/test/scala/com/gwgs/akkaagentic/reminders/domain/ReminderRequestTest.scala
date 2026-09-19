package com.gwgs.akkaagentic.reminders.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T005 — the validation rule, proven with no runtime and no model (FR-007, SC-005).
  *
  * The rejection messages are asserted **verbatim** rather than by "starts with" or "is non-empty":
  * they are the body of a `400`, so they are part of the HTTP contract, and a test that only checks
  * that *something* was rejected would let the contract drift silently.
  */
class ReminderRequestTest:

  private def rejection(note: Option[String], delay: Option[Int]): String =
    ReminderRequest.validate(note, delay) match
      case Left(message) => message
      case Right(valid)  => fail(s"expected a rejection but got $valid")

  private def accepted(note: Option[String], delay: Option[Int]): ReminderRequest =
    ReminderRequest.validate(note, delay) match
      case Right(valid)  => valid
      case Left(message) => fail(s"expected acceptance but was rejected with: $message")

  private def fail(message: String): Nothing = throw AssertionError(message)

  @Test
  def acceptsANoteAndADelay(): Unit =
    val request = accepted(Some("stand up and stretch"), Some(60))
    assertThat(request.note).isEqualTo("stand up and stretch")
    assertThat(request.delay.toSeconds).isEqualTo(60L)

  @Test
  def trimsTheEndsButKeepsInnerSpacing(): Unit =
    // Inner spacing is the caller's content; only the ends are noise.
    assertThat(accepted(Some("  call  the   dentist  "), Some(60)).note)
      .isEqualTo("call  the   dentist")

  @Test
  def rejectsAnAbsentOrBlankNote(): Unit =
    assertThat(rejection(None, Some(60))).isEqualTo(ReminderRequest.BlankNoteMessage)
    assertThat(rejection(Some(""), Some(60))).isEqualTo(ReminderRequest.BlankNoteMessage)
    assertThat(rejection(Some("   "), Some(60))).isEqualTo(ReminderRequest.BlankNoteMessage)
    assertThat(rejection(Some("\t\n "), Some(60))).isEqualTo(ReminderRequest.BlankNoteMessage)

  @Test
  def rejectsAnOverLongNoteAtTheBoundary(): Unit =
    val longest = "x" * ReminderRequest.MaxNoteLength
    assertThat(accepted(Some(longest), Some(60)).note.length).isEqualTo(ReminderRequest.MaxNoteLength)
    assertThat(rejection(Some(longest + "x"), Some(60))).isEqualTo(ReminderRequest.NoteTooLongMessage)

  @Test
  def rejectsADelayOutsideTheSupportedRange(): Unit =
    // Zero and negative are invalid input, NOT "fire immediately" (spec Edge Cases).
    assertThat(rejection(Some("ok"), Some(0))).isEqualTo(ReminderRequest.DelayOutOfRangeMessage)
    assertThat(rejection(Some("ok"), Some(-1))).isEqualTo(ReminderRequest.DelayOutOfRangeMessage)
    assertThat(rejection(Some("ok"), None)).isEqualTo(ReminderRequest.DelayOutOfRangeMessage)
    assertThat(rejection(Some("ok"), Some(86401))).isEqualTo(ReminderRequest.DelayOutOfRangeMessage)

  @Test
  def acceptsBothEndsOfTheSupportedRange(): Unit =
    assertThat(accepted(Some("ok"), Some(1)).delay.toSeconds).isEqualTo(1L)
    assertThat(accepted(Some("ok"), Some(86400)).delay.toSeconds).isEqualTo(86400L)

  @Test
  def reportsTheNoteFirstWhenBothFieldsAreBad(): Unit =
    // Fixed order, so a caller sending two bad fields always gets the same message.
    assertThat(rejection(Some("  "), Some(0))).isEqualTo(ReminderRequest.BlankNoteMessage)
