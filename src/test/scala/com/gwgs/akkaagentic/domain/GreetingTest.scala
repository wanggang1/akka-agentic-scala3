package com.gwgs.akkaagentic.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for the domain validation rules. No Akka runtime needed. */
class GreetingTest:

  @Test
  def validRequestReturnsRight(): Unit =
    val request = GreetingRequest(Some("Ada"), Some("hello there"))
    assertThat(request.validate).isEqualTo(Right(ValidGreeting("Ada", "hello there")))

  @Test
  def blankUserReturnsLeft(): Unit =
    assertThat(GreetingRequest(Some(""), Some("hi")).validate)
      .isEqualTo(Left("user must not be blank"))
    assertThat(GreetingRequest(Some("   "), Some("hi")).validate)
      .isEqualTo(Left("user must not be blank"))

  // `None` is what an absent `user` JSON property becomes after the boundary
  // converts Jackson's `null` to `None` (see GreetingEndpoint).
  @Test
  def absentUserReturnsLeft(): Unit =
    assertThat(GreetingRequest(None, Some("hi")).validate)
      .isEqualTo(Left("user must not be blank"))

  @Test
  def blankTextReturnsLeft(): Unit =
    assertThat(GreetingRequest(Some("Ada"), Some("")).validate)
      .isEqualTo(Left("text must not be blank"))
    assertThat(GreetingRequest(Some("Ada"), Some("   ")).validate)
      .isEqualTo(Left("text must not be blank"))

  @Test
  def absentTextReturnsLeft(): Unit =
    assertThat(GreetingRequest(Some("Ada"), None).validate)
      .isEqualTo(Left("text must not be blank"))
