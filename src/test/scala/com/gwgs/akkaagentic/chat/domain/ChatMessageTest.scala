package com.gwgs.akkaagentic.chat.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for message validation (parse-don't-validate). No Akka runtime needed. */
class ChatMessageTest:

  @Test
  def validMessageReturnsRightTrimmed(): Unit =
    assertThat(ChatMessage.validate(Some("  what is my name? ")))
      .isEqualTo(Right(ChatMessage("what is my name?")))

  @Test
  def blankMessageReturnsLeft(): Unit =
    assertThat(ChatMessage.validate(Some(""))).isEqualTo(Left("message must not be blank"))
    assertThat(ChatMessage.validate(Some("   "))).isEqualTo(Left("message must not be blank"))

  // `None` is what an absent `message` JSON property becomes after the endpoint boundary
  // converts Jackson's `null` to `None`.
  @Test
  def absentMessageReturnsLeft(): Unit =
    assertThat(ChatMessage.validate(None)).isEqualTo(Left("message must not be blank"))
