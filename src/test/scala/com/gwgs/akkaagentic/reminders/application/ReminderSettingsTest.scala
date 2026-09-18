package com.gwgs.akkaagentic.reminders.application

import scala.util.{Failure, Try}

import com.typesafe.config.ConfigFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** "No value of this key makes retrying unbounded" — pinned rather than promised in a comment. */
class ReminderSettingsTest:

  private def withMaxRetries(n: Int) = ConfigFactory.parseString(s"reminders.max-retries = $n")

  @Test
  def theShippedDefaultIsInRange(): Unit =
    assertThat(ReminderSettings.maxRetries(ConfigFactory.load())).isBetween(1, ReminderSettings.MaxRetriesCeiling)

  @Test
  def bothEndsOfTheRangeAreAccepted(): Unit =
    assertThat(ReminderSettings.maxRetries(withMaxRetries(1))).isEqualTo(1)
    assertThat(ReminderSettings.maxRetries(withMaxRetries(10))).isEqualTo(10)

  @Test
  def valuesOutsideTheRangeAreRefusedNotClamped(): Unit =
    // Zero and negatives are where "unbounded" or "never" conventions hide; refuse them outright.
    // (Asserted via Try rather than assertThatThrownBy: AssertJ's chained methods return SELF, which
    // Scala cannot resolve past the first call — the same edge as describedAs.)
    for n <- List(0, -1, 11) do
      Try(ReminderSettings.maxRetries(withMaxRetries(n))) match
        case Failure(e: IllegalArgumentException) =>
          assertThat(e.getMessage).contains(ReminderSettings.MaxRetriesKey)
        case other => throw AssertionError(s"max-retries = $n should be refused, got $other")
