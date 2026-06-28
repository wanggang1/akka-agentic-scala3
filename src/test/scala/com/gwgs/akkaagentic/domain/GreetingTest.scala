package com.gwgs.akkaagentic.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for the domain validation rules. No Akka runtime needed. */
class GreetingTest:

  @Test
  def validRequestReturnsRight(): Unit =
    val request = GreetingRequest("Ada", "hello there")
    assertThat(request.validate).isEqualTo(Right(request))

  @Test
  def blankUserReturnsLeft(): Unit =
    assertThat(GreetingRequest("", "hi").validate)
      .isEqualTo(Left("user must not be blank"))
    assertThat(GreetingRequest("   ", "hi").validate)
      .isEqualTo(Left("user must not be blank"))

  @Test
  def nullUserReturnsLeft(): Unit =
    assertThat(GreetingRequest(null, "hi").validate)
      .isEqualTo(Left("user must not be blank"))

  @Test
  def blankTextReturnsLeft(): Unit =
    assertThat(GreetingRequest("Ada", "").validate)
      .isEqualTo(Left("text must not be blank"))
    assertThat(GreetingRequest("Ada", "   ").validate)
      .isEqualTo(Left("text must not be blank"))

  @Test
  def nullTextReturnsLeft(): Unit =
    assertThat(GreetingRequest("Ada", null).validate)
      .isEqualTo(Left("text must not be blank"))
