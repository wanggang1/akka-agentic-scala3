package com.gwgs.akkaagentic.activities.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[WeatherData]] — canned, case-insensitive, total (pure domain, no runtime). */
class WeatherDataTest:

  @Test
  def knownLocationReturnsItsConditions(): Unit =
    assertThat(WeatherData.forLocation("Boston")).isEqualTo("clear skies, around 20°C")

  @Test
  def lookupIsCaseInsensitiveAndTrimmed(): Unit =
    assertThat(WeatherData.forLocation("  bOsToN  ")).isEqualTo(WeatherData.forLocation("Boston"))

  @Test
  def unknownLocationFallsBackToDefaultNeverThrows(): Unit =
    val summary = WeatherData.forLocation("Atlantis")
    assertThat(summary).isNotEmpty()
    assertThat(WeatherData.knownLocations.contains("atlantis")).isFalse()

  @Test
  def knownLocationsAreExposed(): Unit =
    assertThat(WeatherData.knownLocations.contains("boston")).isTrue()
