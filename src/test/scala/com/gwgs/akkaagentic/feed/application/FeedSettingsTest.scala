package com.gwgs.akkaagentic.feed.application

import scala.util.{Failure, Try}

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T009 — the bounds are real bounds, and a bad one is the server's fault, not the caller's. */
class FeedSettingsTest:

  private def config(attempts: Int) = ConfigFactory.parseString(s"feed.max-attempts = $attempts")

  @Test
  def theShippedDefaultsAreInRange(): Unit =
    val loaded = ConfigFactory.load()
    assertThat(FeedSettings.maxAttempts(loaded)).isBetween(1, 10)
    val limits = FeedSettings.limits(loaded)
    assertThat(limits.maxEntries).isBetween(1, 10000)
    assertThat(limits.maxSetAsides).isBetween(1, 1000)
    assertThat(limits.maxUsers).isBetween(1, 100000)

  @Test
  def bothEndsOfTheAttemptRangeAreAccepted(): Unit =
    assertThat(FeedSettings.maxAttempts(config(1))).isEqualTo(1)
    assertThat(FeedSettings.maxAttempts(config(10))).isEqualTo(10)

  @Test
  def outOfRangeValuesAreRefusedAsAConfigError(): Unit =
    // Not IllegalArgumentException: the SDK turns that into the caller's 400 when a component fails to
    // construct, blaming whoever sent the request for the server's configuration (limitations §7d).
    for n <- List(0, -1, 11) do
      Try(FeedSettings.maxAttempts(config(n))) match
        case Failure(e: ConfigException.BadValue) => assertThat(e.getMessage).contains(FeedSettings.MaxAttemptsKey)
        case other => throw AssertionError(s"max-attempts = $n should be refused, got $other")
