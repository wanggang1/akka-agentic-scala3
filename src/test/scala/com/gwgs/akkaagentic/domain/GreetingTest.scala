package com.gwgs.akkaagentic.domain

import java.time.{LocalDate, LocalTime, ZoneId, ZoneOffset}

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

  // --- TimeOfDay (T007, US2) ---

  private val Utc: ZoneId = ZoneOffset.UTC
  private val ValidLabels = Set("morning", "afternoon", "evening", "night")

  /** The instant at which the given local `hour` is observed in UTC. */
  private def atHour(hour: Int) =
    LocalDate.of(2026, 7, 3).atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC)

  @Test
  def morningHoursMapToMorning(): Unit =
    for h <- 5 to 11 do assertThat(TimeOfDay.of(atHour(h), Utc)).isEqualTo("morning")

  @Test
  def afternoonHoursMapToAfternoon(): Unit =
    for h <- 12 to 16 do assertThat(TimeOfDay.of(atHour(h), Utc)).isEqualTo("afternoon")

  @Test
  def eveningHoursMapToEvening(): Unit =
    for h <- 17 to 20 do assertThat(TimeOfDay.of(atHour(h), Utc)).isEqualTo("evening")

  @Test
  def nightHoursMapToNight(): Unit =
    for h <- Seq(21, 22, 23, 0, 1, 2, 3, 4) do
      assertThat(TimeOfDay.of(atHour(h), Utc)).isEqualTo("night")

  @Test
  def everyHourMapsToALabelNeverThrows(): Unit =
    for h <- 0 to 23 do assertThat(TimeOfDay.of(atHour(h), Utc)).isIn(ValidLabels.toArray*)

  @Test
  def blankTimezoneFallsBackToUtc(): Unit =
    // Blank/absent input must not throw; it resolves to UTC and yields a valid label.
    for tz <- Seq(None, Some(""), Some("   ")) do
      assertThat(TimeOfDay.now(tz)).isIn(ValidLabels.toArray*)

  @Test
  def invalidTimezoneFallsBackToUtc(): Unit =
    // An unrecognized IANA id must not throw; it resolves to UTC (via Try(...).toOption).
    for tz <- Seq(Some("Not/AZone"), Some("Europe/Nowhere"), Some("12345")) do
      assertThat(TimeOfDay.now(tz)).isIn(ValidLabels.toArray*)

  @Test
  def validTimezoneNeverThrows(): Unit =
    assertThat(TimeOfDay.now(Some("America/New_York"))).isIn(ValidLabels.toArray*)
